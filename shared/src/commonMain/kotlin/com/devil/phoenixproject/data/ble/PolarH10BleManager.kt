package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multiplatform BLE Manager for Polar H10 heart rate sensors.
 *
 * Implements independent connection lifecycle, GATT service discovery,
 * standard Heart Rate (0x180D) and Battery (0x180F) telemetry, and proprietary
 * Polar Measurement Data (PMD) streaming for ECG and Accelerometer (ACC).
 *
 * Designed to operate simultaneously alongside [KableBleConnectionManager]
 * without cross-peripheral interference.
 */
class PolarH10BleManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timeSource: () -> Long = ::currentTimeMillis,
) {
    private val log = Logger.withTag("PolarH10BleManager")

    // ===== Connection & Discovery State =====
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // ===== Telemetry State & Streams =====
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _hrMeasurement = MutableStateFlow<PolarHrMeasurement?>(null)
    val hrMeasurement: StateFlow<PolarHrMeasurement?> = _hrMeasurement.asStateFlow()

    private val _hrMeasurements = MutableSharedFlow<PolarHrMeasurement>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val hrMeasurements: SharedFlow<PolarHrMeasurement> = _hrMeasurements.asSharedFlow()

    private val _pmdMeasurement = MutableSharedFlow<PolarPmdMeasurement>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pmdMeasurement: SharedFlow<PolarPmdMeasurement> = _pmdMeasurement.asSharedFlow()

    private val _ecgStream = MutableSharedFlow<PolarPmdMeasurement.Ecg>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ecgStream: SharedFlow<PolarPmdMeasurement.Ecg> = _ecgStream.asSharedFlow()

    private val _accStream = MutableSharedFlow<PolarPmdMeasurement.Acc>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accStream: SharedFlow<PolarPmdMeasurement.Acc> = _accStream.asSharedFlow()

    private val _rrBeats = MutableSharedFlow<PolarRrBeat>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rrBeats: SharedFlow<PolarRrBeat> = _rrBeats.asSharedFlow()

    private val _rrClockTimestampMs = MutableStateFlow<Long?>(null)
    val rrClockTimestampMs: StateFlow<Long?> = _rrClockTimestampMs.asStateFlow()

    // ===== Internal Connection References =====
    private var peripheral: Peripheral? = null
    val currentPeripheral: Peripheral? get() = peripheral

    private var scanJob: Job? = null
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()

    private var stateObserverJob: Job? = null
    private var hrObserveJob: Job? = null
    private var batteryObserveJob: Job? = null

    // ===== PMD Stream Control =====
    private val pmdMutex = Mutex()
    private var pmdControlPointJob: Job? = null
    private var pmdDataJob: Job? = null
    private var pmdCpSubscribed = CompletableDeferred<Unit>()
    private var pmdDataSubscribed = CompletableDeferred<Unit>()
    private var isEcgStreaming = false
    private var isAccStreaming = false

    val isEcgActive: Boolean get() = isEcgStreaming
    val isAccActive: Boolean get() = isAccStreaming

    // ===== Chained RR Clock State =====
    private var lastBeatTimestampMs: Long? = null

    /**
     * Start scanning for Polar H10 devices (by name prefix or Heart Rate 0x180D UUID).
     * Automatically stops when [BleConstants.SCAN_TIMEOUT_MS] elapses.
     */
    suspend fun startScanning(): Result<Unit> {
        return try {
            log.i { "Starting BLE scan for Polar H10 devices" }
            scanJob?.cancel()
            scanJob = null
            _scannedDevices.value = emptyList()
            discoveredAdvertisements.clear()

            _connectionState.value = ConnectionState.Scanning

            var currentDevices = emptyList<ScannedDevice>()

            scanJob = scope.launch {
                try {
                    withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS) {
                        Scanner {}
                            .advertisements
                            .filter { adv -> isPolarH10Advertisement(adv) }
                            .collect { adv ->
                                val identifier = adv.identifier.toString()
                                discoveredAdvertisements[identifier] = adv
                                val name = adv.name ?: "Polar H10"
                                val scanned = ScannedDevice(name = name, address = identifier, rssi = adv.rssi)

                                val updated = currentDevices.toMutableList()
                                val existingIndex = updated.indexOfFirst { it.address == identifier }
                                if (existingIndex >= 0) {
                                    updated[existingIndex] = scanned
                                } else {
                                    updated.add(scanned)
                                }
                                val sorted = updated.sortedByDescending { it.rssi }
                                currentDevices = sorted
                                _scannedDevices.value = sorted
                            }
                    }
                    if (_connectionState.value is ConnectionState.Scanning) {
                        log.i { "Polar scan timeout reached (${BleConstants.SCAN_TIMEOUT_MS}ms)" }
                        _connectionState.value = ConnectionState.Disconnected
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(e) { "Scan error: ${e.message}" }
                    if (_connectionState.value is ConnectionState.Scanning) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start Polar scan: ${e.message}" }
            Result.failure(e)
        }
    }

    suspend fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Connect to the selected Polar H10 device.
     */
    suspend fun connect(device: ScannedDevice): Result<Unit> {
        log.i { "Connecting to Polar H10: ${device.name} (${device.address})" }
        stopScanning()

        val advertisement = discoveredAdvertisements[device.address]
            ?: discoveredAdvertisements.values.firstOrNull { it.identifier.toString() == device.address }
            ?: return Result.failure(IllegalStateException("Polar device ${device.address} not found in scanned cache"))

        return try {
            cleanupExistingConnection()
            _connectionState.value = ConnectionState.Connecting

            val p = Peripheral(advertisement)
            peripheral = p

            stateObserverJob = scope.launch {
                p.state.collect { state ->
                    log.d { "Polar H10 state: $state" }
                    when (state) {
                        is State.Connecting -> {
                            _connectionState.value = ConnectionState.Connecting
                        }
                        is State.Connected -> {
                            _connectionState.value = ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address,
                            )
                            onDeviceConnected(p)
                        }
                        is State.Disconnecting -> {
                            // Transient disconnecting state
                        }
                        is State.Disconnected -> {
                            _connectionState.value = ConnectionState.Disconnected
                            cleanupSessionTelemetry()
                        }
                    }
                }
            }

            withTimeout(BleConstants.CONNECTION_TIMEOUT_MS) {
                p.connect()
                p.state.first { it is State.Connected }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to connect to Polar H10: ${e.message}" }
            cleanupExistingConnection()
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun onDeviceConnected(p: Peripheral) {
        log.i { "Configuring Polar H10 connection parameters and services" }

        // 1. Request MTU 232 if supported
        try {
            val mtu = p.requestMtuIfSupported(BleConstants.Polar.DESIRED_PMD_MTU)
            log.i { "Polar H10 MTU negotiated: $mtu (desired: ${BleConstants.Polar.DESIRED_PMD_MTU})" }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "MTU negotiation error: ${e.message}" }
        }

        // 2. Read initial battery level (0x2A19)
        try {
            val batteryBytes = p.read(BleConstants.Polar.batteryLevelCharacteristic)
            val level = parsePolarBatteryLevel(batteryBytes)
            _batteryLevel.value = level
            log.i { "Polar H10 battery level: $level%" }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Could not read Polar battery level: ${e.message}" }
        }

        batteryObserveJob?.cancel()
        batteryObserveJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.batteryLevelCharacteristic)
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.d { "Battery notification non-fatal: ${e.message}" }
                    }
                    .collect { bytes ->
                        parsePolarBatteryLevel(bytes)?.let { _batteryLevel.value = it }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
            }
        }

        // 3. Subscribe to HR/RR notifications (0x2A37)
        startHrObservation(p)
    }

    private fun startHrObservation(p: Peripheral) {
        hrObserveJob?.cancel()
        hrObserveJob = scope.launch {
            try {
                log.i { "Subscribing to Polar HR & RR notifications (0x2A37)" }
                p.observe(BleConstants.Polar.hrMeasurementCharacteristic)
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.e { "Polar HR observation error: ${e.message}" }
                    }
                    .collect { data ->
                        val measurement = parsePolarHrMeasurement(data) ?: return@collect
                        _hrMeasurement.value = measurement
                        _hrMeasurements.emit(measurement)

                        if (measurement.rrIntervalsMs.isNotEmpty()) {
                            processRrIntervals(measurement)
                        }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "Failed to maintain Polar HR observation: ${e.message}" }
            }
        }
    }

    /**
     * Reconstructs accurate R-wave beat timestamps by chaining RR intervals together,
     * eliminating packet jitter across connection intervals. Automatically re-anchors
     * if gaps or drift exceed 5 seconds.
     */
    internal fun processRrIntervals(measurement: PolarHrMeasurement) {
        val rrs = measurement.rrIntervalsMs
        if (rrs.isEmpty()) return

        val nowMs = timeSource()
        val previousBeat = lastBeatTimestampMs

        val startingTs: Long = if (previousBeat == null || (nowMs - previousBeat) > 5000L || previousBeat > nowMs + 5000L) {
            val totalRrDurationMs = rrs.sum().toLong()
            nowMs - totalRrDurationMs
        } else {
            previousBeat
        }

        var runningTs = startingTs
        for (rr in rrs) {
            runningTs += rr.toLong()
            val beat = PolarRrBeat(
                rrIntervalMs = rr,
                timestampMs = runningTs,
                hr = measurement.hr,
            )
            _rrBeats.tryEmit(beat)
        }

        lastBeatTimestampMs = runningTs
        _rrClockTimestampMs.value = runningTs
    }

    private suspend fun ensurePmdSubscribed(p: Peripheral): Boolean = pmdMutex.withLock {
        if (pmdControlPointJob?.isActive == true && pmdDataJob?.isActive == true) {
            return true
        }

        pmdControlPointJob?.cancel()
        pmdDataJob?.cancel()

        pmdCpSubscribed = CompletableDeferred()
        pmdDataSubscribed = CompletableDeferred()

        // 1. Subscribe to PMD Control Point (FB005C81)
        pmdControlPointJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdControlPointCharacteristic, onSubscription = {
                    log.i { "PMD Control Point (FB005C81) subscribed" }
                    pmdCpSubscribed.complete(Unit)
                })
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.w { "PMD CP observe error: ${e.message}" }
                    }
                    .collect { cpData ->
                        log.d { "PMD CP response: ${cpData.size} bytes" }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "PMD CP observer failed: ${e.message}" }
                if (!pmdCpSubscribed.isCompleted) {
                    pmdCpSubscribed.completeExceptionally(e)
                }
            }
        }

        // 2. Subscribe to PMD Data (FB005C82)
        pmdDataJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdDataCharacteristic, onSubscription = {
                    log.i { "PMD Data (FB005C82) subscribed" }
                    pmdDataSubscribed.complete(Unit)
                })
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.w { "PMD Data observe error: ${e.message}" }
                    }
                    .collect { data ->
                        val measurement = parsePolarPmdData(data) ?: return@collect
                        _pmdMeasurement.tryEmit(measurement)
                        when (measurement) {
                            is PolarPmdMeasurement.Ecg -> _ecgStream.tryEmit(measurement)
                            is PolarPmdMeasurement.Acc -> _accStream.tryEmit(measurement)
                        }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "PMD Data observer failed: ${e.message}" }
                if (!pmdDataSubscribed.isCompleted) {
                    pmdDataSubscribed.completeExceptionally(e)
                }
            }
        }

        withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
            pmdCpSubscribed.await()
            pmdDataSubscribed.await()
        }
        true
    }

    /**
     * Start the 130 Hz 14-bit ECG stream.
     * Ensures PMD Control Point and Data notifications are active before sending command.
     */
    suspend fun startEcgStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ECG stream" }
            ensurePmdSubscribed(p)
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = BleConstants.Polar.PMD_START_ECG_CMD,
                writeType = WriteType.WithResponse,
            )
            isEcgStreaming = true
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start ECG stream: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Stop the ECG stream.
     */
    suspend fun stopEcgStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Stopping Polar H10 ECG stream" }
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = BleConstants.Polar.PMD_STOP_ECG_CMD,
                writeType = WriteType.WithResponse,
            )
            isEcgStreaming = false
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to stop ECG stream: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Start the 50 Hz 16-bit ACC stream (±8G range).
     * Ensures PMD Control Point and Data notifications are active before sending command.
     */
    suspend fun startAccStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ACC stream" }
            ensurePmdSubscribed(p)
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = BleConstants.Polar.PMD_START_ACC_CMD,
                writeType = WriteType.WithResponse,
            )
            isAccStreaming = true
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start ACC stream: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Stop the ACC stream.
     */
    suspend fun stopAccStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Stopping Polar H10 ACC stream" }
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = BleConstants.Polar.PMD_STOP_ACC_CMD,
                writeType = WriteType.WithResponse,
            )
            isAccStreaming = false
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to stop ACC stream: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Disconnect from the Polar H10 device and clean up all active telemetry streams.
     */
    suspend fun disconnect() {
        val p = peripheral
        if (p != null) {
            log.i { "Disconnecting Polar H10" }
            try {
                if (isEcgStreaming) {
                    try { stopEcgStream() } catch (_: Exception) {}
                }
                if (isAccStreaming) {
                    try { stopAccStream() } catch (_: Exception) {}
                }
                p.disconnect()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Error during Polar disconnect: ${e.message}" }
            }
        }
        cleanupExistingConnection()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanupSessionTelemetry() {
        hrObserveJob?.cancel()
        hrObserveJob = null
        batteryObserveJob?.cancel()
        batteryObserveJob = null

        pmdControlPointJob?.cancel()
        pmdControlPointJob = null
        pmdDataJob?.cancel()
        pmdDataJob = null

        isEcgStreaming = false
        isAccStreaming = false

        lastBeatTimestampMs = null
        _batteryLevel.value = null
        _hrMeasurement.value = null
        _rrClockTimestampMs.value = null
    }

    private fun cleanupExistingConnection() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        cleanupSessionTelemetry()
        peripheral = null
    }

    fun shutdown() {
        scanJob?.cancel()
        scanJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null
        cleanupSessionTelemetry()
        val p = peripheral
        peripheral = null
        if (p != null) {
            scope.launch {
                try { p.disconnect() } catch (_: Exception) {}
            }
        }
    }

    internal fun isPolarH10Advertisement(adv: Advertisement): Boolean {
        val name = adv.name
        if (name != null && name.startsWith("Polar H10", ignoreCase = true)) {
            return true
        }
        val hrUuidPrefix = "0000180d"
        return adv.uuids.any { uuid ->
            val s = uuid.toString().lowercase()
            s.startsWith(hrUuidPrefix) || s == BleConstants.Polar.HR_SERVICE_UUID_STRING
        }
    }
}
