@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, com.juul.kable.ExperimentalApi::class)

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.Identifier
import com.juul.kable.ManufacturerData
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription

/** Kable API double, not a simulated radio, CoreBluetooth stack, or trainer firmware. */
internal class CompositionAdvertisement(
    id: String,
    override val name: String? = "Vee_Composition",
    override val rssi: Int = -45,
) : Advertisement {
    // Identifier is Uuid on Apple and String on Android. Validate UUIDs on both,
    // but do not call Android's MAC validator: this is not a radio advertisement.
    private val uuid: Any = Uuid.parse(id)
    @Suppress("USELESS_CAST")
    override val identifier: Identifier = if (uuid is Identifier) uuid else id as Identifier
    override val peripheralName: String? = name
    override val isConnectable: Boolean = true
    override val txPower: Int? = null
    override val uuids: List<Uuid> = listOf(BleConstants.NUS_SERVICE_UUID)
    override val manufacturerData: ManufacturerData? = null
    override fun serviceData(uuid: Uuid): ByteArray? = null
    override fun manufacturerData(companyIdentifierCode: Int): ByteArray? = null
}

internal class CompositionGattFake(
    advertisement: Advertisement,
    dispatcher: CoroutineDispatcher,
) : Peripheral {
    override val scope = CoroutineScope(SupervisorJob() + dispatcher)
    override val identifier: Identifier = advertisement.identifier
    override val name: String? = advertisement.name
    override val state = MutableStateFlow<State>(State.Disconnected())
    override val services = MutableStateFlow<List<DiscoveredService>?>(null)
    private var connectionScope: CoroutineScope? = null
    private val dispatcher = dispatcher

    var connectGate: CompletableDeferred<Unit>? = null
    var cancelConnectGateOnDisconnect = true
    var afterRepsSubscription: (() -> Unit)? = null
    // Optional pre-physical-disconnect barrier for exact call(s).
    var disconnectGateOnCalls: Set<Int> = emptySet()
    var disconnectGate: CompletableDeferred<Unit>? = null
    // Optional first-call return barrier: physical teardown has already completed.
    var disconnectReturnGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var writeFailure: Exception? = null
    var connectCalls = 0
    var disconnectCalls = 0
    var activeObservations = 0
    var activeOperations = 0
    var maxConcurrentOperations = 0
    val reads = mutableListOf<Characteristic>()
    val observations = mutableListOf<Characteristic>()
    val writes = mutableListOf<Write>()
    private val notifications = mutableMapOf<Uuid, MutableSharedFlow<ByteArray>>()

    data class Write(val characteristic: Characteristic, val bytes: ByteArray, val type: WriteType)

    private class GattCharacteristic(
        source: Characteristic,
        flags: Int,
    ) : DiscoveredCharacteristic {
        override val serviceUuid = source.serviceUuid
        override val characteristicUuid = source.characteristicUuid
        override val properties = Characteristic.Properties(flags)
        override val descriptors = emptyList<DiscoveredDescriptor>()
    }

    private val profile = listOf(
        GattCharacteristic(BleConstants.txCharacteristic, 0x08),
        GattCharacteristic(BleConstants.monitorCharacteristic, 0x02),
        GattCharacteristic(BleConstants.diagnosticCharacteristic, 0x02),
        GattCharacteristic(BleConstants.heuristicCharacteristic, 0x02),
        GattCharacteristic(BleConstants.repsCharacteristic, 0x10),
        GattCharacteristic(BleConstants.versionCharacteristic, 0x12),
        GattCharacteristic(BleConstants.modeCharacteristic, 0x10),
        GattCharacteristic(BleConstants.firmwareRevisionCharacteristic, 0x02),
    ).groupBy { it.serviceUuid }.map { (uuid, chars) ->
        object : DiscoveredService {
            override val serviceUuid = uuid
            override val characteristics: List<DiscoveredCharacteristic> = chars
        }
    }

    override suspend fun connect(): CoroutineScope {
        connectCalls++
        state.value = State.Connecting.Bluetooth
        connectGate?.await()
        val connected = CoroutineScope(SupervisorJob() + dispatcher)
        connectionScope = connected
        services.value = profile
        state.value = State.Connected(connected)
        return connected
    }

    override suspend fun disconnect() {
        disconnectCalls++
        if (disconnectCalls in disconnectGateOnCalls) disconnectGate?.await()
        if (cancelConnectGateOnDisconnect) connectGate?.cancel()
        dropConnection()
        if (disconnectCalls == 1) disconnectReturnGate?.await()
    }

    fun dropConnection() {
        connectionScope?.cancel()
        connectionScope = null
        services.value = null
        state.value = State.Disconnected()
    }

    override fun close() {
        dropConnection()
        scope.cancel()
    }

    override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int = 512
    override suspend fun rssi(): Int = -45

    private fun requireCharacteristic(characteristic: Characteristic, flag: Int) {
        check(state.value is State.Connected) { "Not connected" }
        val found = services.value.orEmpty().flatMap { it.characteristics }.any {
            it.serviceUuid == characteristic.serviceUuid &&
                it.characteristicUuid == characteristic.characteristicUuid &&
                it.properties.value and flag != 0
        }
        check(found) { "Characteristic or required property not discovered: $characteristic" }
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        activeOperations++
        maxConcurrentOperations = maxOf(maxConcurrentOperations, activeOperations)
        return try {
            // Successful monitor reads have no production delay; emulate asynchronous GATT I/O.
            delay(10)
            block()
        } finally {
            activeOperations--
        }
    }

    override suspend fun read(characteristic: Characteristic): ByteArray {
        requireCharacteristic(characteristic, 0x02)
        reads += characteristic
        return operation {
            when (characteristic.characteristicUuid) {
                BleConstants.MONITOR_UUID -> {
                    readGate?.await()
                    ByteArray(16)
                }
                BleConstants.DIAGNOSTIC_UUID -> ByteArray(18).also {
                    it[0] = 42
                    it[12] = 25
                }
                BleConstants.FIRMWARE_REVISION_UUID -> "composition-fixture".encodeToByteArray()
                else -> byteArrayOf()
            }
        }
    }

    override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
        requireCharacteristic(characteristic, if (writeType == WriteType.WithResponse) 0x08 else 0x04)
        operation {
            writeFailure?.let { throw it }
            writes += Write(characteristic, data.copyOf(), writeType)
        }
    }

    override fun observe(characteristic: Characteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> = flow {
        requireCharacteristic(characteristic, 0x10)
        observations += characteristic
        activeObservations++
        try {
            // Kable observe flows survive disconnect and wait for a later reconnect.
            emitAll(
                notifications.getOrPut(characteristic.characteristicUuid) { MutableSharedFlow() }
                    .onSubscription {
                        onSubscription()
                        if (characteristic.characteristicUuid == BleConstants.REPS_UUID) {
                            afterRepsSubscription?.invoke()
                        }
                    }
                    .filter { state.value is State.Connected },
            )
        } finally {
            activeObservations--
        }
    }

    suspend fun notifyReps(bytes: ByteArray) {
        notifications.getValue(BleConstants.REPS_UUID).emit(bytes)
    }

    override suspend fun read(descriptor: Descriptor): ByteArray = error("Unexpected descriptor read")
    override suspend fun write(descriptor: Descriptor, data: ByteArray): Unit = error("Unexpected descriptor write")
}
