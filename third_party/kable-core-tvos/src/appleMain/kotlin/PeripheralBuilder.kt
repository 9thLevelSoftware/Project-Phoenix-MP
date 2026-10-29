package com.juul.kable

import com.juul.kable.logs.Logging
import com.juul.kable.logs.LoggingBuilder
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

actual class ServicesDiscoveredPeripheral internal constructor(
    private val peripheral: CoreBluetoothPeripheral,
) {

    @Throws(CancellationException::class, IOException::class, NotConnectedException::class)
    actual suspend fun read(
        characteristic: Characteristic,
    ): ByteArray = peripheral.read(characteristic)

    @Throws(CancellationException::class, IOException::class, NotConnectedException::class)
    actual suspend fun read(
        descriptor: Descriptor,
    ): ByteArray = peripheral.read(descriptor)

    @Throws(CancellationException::class, IOException::class, NotConnectedException::class)
    actual suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    ) {
        peripheral.write(characteristic, data, writeType)
    }

    @Throws(CancellationException::class, IOException::class, NotConnectedException::class)
    actual suspend fun write(
        descriptor: Descriptor,
        data: ByteArray,
    ) {
        peripheral.write(descriptor, data)
    }
}

actual class PeripheralBuilder internal actual constructor() {

    internal var logging: Logging = Logging()
    actual fun logging(init: LoggingBuilder) {
        val logging = Logging()
        logging.init()
        this.logging = logging
    }

    internal var onServicesDiscovered: ServicesDiscoveredAction = {}
    actual fun onServicesDiscovered(action: ServicesDiscoveredAction) {
        onServicesDiscovered = action
    }

    internal var observationExceptionHandler: ObservationExceptionHandler = { cause -> throw cause }
    actual fun observationExceptionHandler(handler: ObservationExceptionHandler) {
        observationExceptionHandler = handler
    }

    actual var disconnectTimeout: Duration = defaultDisconnectTimeout

    actual var forceCharacteristicEqualityByUuid: Boolean = false
}
