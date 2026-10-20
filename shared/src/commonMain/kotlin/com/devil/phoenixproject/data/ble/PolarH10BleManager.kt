package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.PolarAccFilter
import com.devil.phoenixproject.util.PolarTelemetryUtils
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.russhwolf.settings.Settings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Summary metrics for a session recorded by Polar H10.
 */
data class PolarSessionSummary(
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val avgRrIntervalMs: Float? = null,
    val rmssdMs: Float? = null,
    val hrr60Bpm: Int? = null,
    val hrr300Bpm: Int? = null,
    val maxTorsoPitchDeg: Float? = null,
    val maxTorsoRollDeg: Float? = null,
)

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
    private val settings: Settings? = null,
    private val database: PhoenixDatabase? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timeSource: () -> Long = ::currentTimeMillis,
) {
    private val log = Logger.withTag("PolarH10BleManager")

    companion object {
        const val KEY_POLAR_REMEMBERED_ADDRESS = "polar_h10_remembered_address"
        const val KEY_POLAR_REMEMBERED_NAME = "polar_h10_remembered_name"
        const val KEY_POLAR_AUTO_RECONNECT = "polar_h10_auto_reconnect_enabled"
    }

    // ===== Remembered Device & Auto-Reconnect =====
    private val _rememberedAddress = MutableStateFlow<String?>(settings?.getStringOrNull(KEY_POLAR_REMEMBERED_ADDRESS))
    val rememberedAddress: StateFlow<String?> = _rememberedAddress.asStateFlow()

    private val _rememberedName = MutableStateFlow<String?>(settings?.getStringOrNull(KEY_POLAR_REMEMBERED_NAME))
    val rememberedName: StateFlow<String?> = _rememberedName.asStateFlow()

    private val _isAutoReconnectEnabled = MutableStateFlow<Boolean>(
        settings?.getBoolean(KEY_POLAR_AUTO_RECONNECT, defaultValue = true) ?: true,
    )
    val isAutoReconnectEnabled: StateFlow<Boolean> = _isAutoReconnectEnabled.asStateFlow()

    fun setAutoReconnectEnabled(enabled: Boolean) {
        settings?.putBoolean(KEY_POLAR_AUTO_RECONNECT, enabled)
        _isAutoReconnectEnabled.value = enabled
    }

    fun forgetRememberedDevice() {
        settings?.remove(KEY_POLAR_REMEMBERED_ADDRESS)
        settings?.remove(KEY_POLAR_REMEMBERED_NAME)
        _rememberedAddress.value = null
        _rememberedName.value = null
    }

    // ===== Torso Biomechanics Posture & Filtering =====
    private val accFilter = PolarAccFilter()

    private val _torsoPitchDeg = MutableStateFlow<Double?>(null)
    val torsoPitchDeg: StateFlow<Double?> = _torsoPitchDeg.asStateFlow()

    private val _torsoRollDeg = MutableStateFlow<Double?>(null)
    val torsoRollDeg: StateFlow<Double?> = _torsoRollDeg.asStateFlow()

    private val _dynamicAccMg = MutableStateFlow<Int?>(null)
    val dynamicAccMg: StateFlow<Int?> = _dynamicAccMg.asStateFlow()

    fun tareCurrentPosture() {
        accFilter.tareCurrentPosture()
    }

    fun resetPostureTare() {
        accFilter.tarePitchDeg = 0.0
        accFilter.tareRollDeg = 0.0
    }

    // ===== Session Recording & Recovery State =====
    private var activeSessionId: String? = null
    private var currentPhase: String = "WARMUP"
    private var cooldownJob: Job? = null
    private val sessionHrs = mutableListOf<Int>()
    private val sessionRrs = mutableListOf<Double>()
    private val recentRrs = ArrayDeque<Double>()
    private var maxTorsoPitch: Double? = null
    private var maxTorsoRoll: Double? = null
    private var endHeartRate: Int? = null
    private var hrr60: Int? = null
    private var hrr300: Int? = null

    val currentRecordingSessionId: String? get() = activeSessionId

    fun startSessionRecording(sessionId: String, phase: String = "WARMUP") {
        cooldownJob?.cancel()
        cooldownJob = null
        activeSessionId = sessionId
        currentPhase = phase
        sessionHrs.clear()
        sessionRrs.clear()
        recentRrs.clear()
        maxTorsoPitch = null
        maxTorsoRoll = null
        endHeartRate = null
        hrr60 = null
        hrr300 = null

        if (_connectionState.value is ConnectionState.Connected) {
            if (!isAccStreaming) scope.launch { startAccStream() }
            if (!isEcgStreaming) scope.launch { startEcgStream() }
        }
    }

    fun updateSessionPhase(phase: String) {
        currentPhase = phase
    }

    fun endSessionRecording(sessionId: String? = null) {
        val targetSessionId = sessionId ?: activeSessionId ?: return
        if (activeSessionId == null) {
            activeSessionId = targetSessionId
        }
        endHeartRate = _hrMeasurement.value?.hr ?: sessionHrs.lastOrNull()
        currentPhase = "POST_WORKOUT_COOLDOWN"

        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            val cooldownStartMs = timeSource()
            var hrr60Persisted = false
            var hrr300Persisted = false
            while (isActive && (timeSource() - cooldownStartMs) < 300_000L) {
                delay(1000L)
                val elapsedMs = timeSource() - cooldownStartMs
                val curHr = _hrMeasurement.value?.hr
                val endHr = endHeartRate
                if (elapsedMs >= 60_000L && !hrr60Persisted && curHr != null && endHr != null) {
                    hrr60 = (endHr - curHr).coerceAtLeast(0)
                    hrr60Persisted = true
                    persistPolarSummary(targetSessionId)
                }
                if (elapsedMs >= 300_000L && !hrr300Persisted && curHr != null && endHr != null) {
                    hrr300 = (endHr - curHr).coerceAtLeast(0)
                    hrr300Persisted = true
                    persistPolarSummary(targetSessionId)
                }
            }
            val finalHr = _hrMeasurement.value?.hr
            if (!hrr300Persisted && finalHr != null && endHeartRate != null) {
                hrr300 = (endHeartRate!! - finalHr).coerceAtLeast(0)
                persistPolarSummary(targetSessionId)
            }
            if (activeSessionId == targetSessionId) {
                activeSessionId = null
            }
        }
    }

    fun getSessionSummary(sessionId: String): PolarSessionSummary? {
        if (activeSessionId != null && activeSessionId != sessionId) return null
        val avgHr = if (sessionHrs.isNotEmpty()) sessionHrs.average().roundToInt() else null
        val maxHr = sessionHrs.maxOrNull()
        val minHr = sessionHrs.minOrNull()
        val avgRr = if (sessionRrs.isNotEmpty()) sessionRrs.average().toFloat() else null
        val rmssd = PolarTelemetryUtils.calculateRmssdMs(sessionRrs)?.toFloat()
        return PolarSessionSummary(
            avgHeartRateBpm = avgHr,
            maxHeartRateBpm = maxHr,
            minHeartRateBpm = minHr,
            avgRrIntervalMs = avgRr,
            rmssdMs = rmssd,
            hrr60Bpm = hrr60,
            hrr300Bpm = hrr300,
            maxTorsoPitchDeg = maxTorsoPitch?.toFloat(),
            maxTorsoRollDeg = maxTorsoRoll?.toFloat(),
        )
    }

    private fun persistPolarSummary(sessionId: String) {
        val summary = getSessionSummary(sessionId) ?: return
        try {
            database?.phoenixDatabaseQueries?.updateSessionPolarSummary(
                id = sessionId,
                avgHeartRateBpm = summary.avgHeartRateBpm?.toLong(),
                maxHeartRateBpm = summary.maxHeartRateBpm?.toLong(),
                minHeartRateBpm = summary.minHeartRateBpm?.toLong(),
                avgRrIntervalMs = summary.avgRrIntervalMs?.toDouble(),
                rmssdMs = summary.rmssdMs?.toDouble(),
                hrr60Bpm = summary.hrr60Bpm?.toLong(),
                hrr300Bpm = summary.hrr300Bpm?.toLong(),
                maxTorsoPitchDeg = summary.maxTorsoPitchDeg?.toDouble(),
                maxTorsoRollDeg = summary.maxTorsoRollDeg?.toDouble(),
            )
        } catch (e: Exception) {
            log.w { "Failed to update session polar summary: ${e.message}" }
        }
    }

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

    private val _latestEcg = MutableStateFlow<PolarPmdMeasurement.Ecg?>(null)
    val latestEcg: StateFlow<PolarPmdMeasurement.Ecg?> = _latestEcg.asStateFlow()

    private val _latestAcc = MutableStateFlow<PolarPmdMeasurement.Acc?>(null)
    val latestAcc: StateFlow<PolarPmdMeasurement.Acc?> = _latestAcc.asStateFlow()

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
    private val pmdControlPointMutex = Mutex()
    private val pmdCpResponses = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var pmdControlPointJob: Job? = null
    private var pmdDataJob: Job? = null
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

    private class AutoReconnectMatchFound(val device: ScannedDevice) : CancellationException()

    /**
     * Auto-reconnect to the remembered Polar H10 if one was previously connected
     * and auto-reconnect is enabled.
     */
    suspend fun autoReconnectIfRemembered(timeoutMs: Long = 10000L): Result<Unit> {
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }
        if (!_isAutoReconnectEnabled.value) {
            return Result.success(Unit)
        }
        val address = _rememberedAddress.value ?: return Result.success(Unit)
        val name = _rememberedName.value ?: "Polar H10"
        log.i { "Attempting auto-reconnect to remembered Polar H10: $name ($address)" }

        val cachedAdv = discoveredAdvertisements[address]
            ?: discoveredAdvertisements.values.firstOrNull { it.identifier.toString() == address }
        if (cachedAdv != null) {
            return connect(ScannedDevice(name = name, address = address))
        }

        return try {
            val matchedDevice = withTimeoutOrNull(timeoutMs) {
                var found: ScannedDevice? = null
                try {
                    Scanner {}
                        .advertisements
                        .filter { isPolarH10Advertisement(it) }
                        .collect { adv ->
                            val id = adv.identifier.toString()
                            discoveredAdvertisements[id] = adv
                            if (id.equals(address, ignoreCase = true) || (adv.name != null && adv.name == name)) {
                                found = ScannedDevice(name = adv.name ?: name, address = id, rssi = adv.rssi)
                                throw AutoReconnectMatchFound(found!!)
                            }
                        }
                } catch (e: AutoReconnectMatchFound) {
                    found = e.device
                }
                found
            }

            if (matchedDevice != null) {
                connect(matchedDevice)
            } else {
                Result.failure(IllegalStateException("Timed out waiting for remembered Polar H10"))
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Auto-reconnect to Polar H10 failed: ${e.message}" }
            Result.failure(e)
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
                            if (_isAutoReconnectEnabled.value) {
                                settings?.putString(KEY_POLAR_REMEMBERED_ADDRESS, device.address)
                                settings?.putString(KEY_POLAR_REMEMBERED_NAME, device.name)
                                _rememberedAddress.value = device.address
                                _rememberedName.value = device.name
                            }
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

        // 4. Pre-subscribe to PMD Control Point & Data so observers are active and shared
        try {
            ensurePmdSubscribed(p)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Pre-subscribing PMD non-fatal (will retry on stream start): ${e.message}" }
        }
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

            val currentSid = activeSessionId
            if (currentSid != null) {
                sessionHrs.add(beat.hr)
                sessionRrs.add(beat.rrIntervalMs)
                recentRrs.addLast(beat.rrIntervalMs)
                if (recentRrs.size > 30) recentRrs.removeFirst()
                val currentRmssd = PolarTelemetryUtils.calculateRmssdMs(recentRrs.toList())
                try {
                    database?.phoenixDatabaseQueries?.insertPolarBeatSample(
                        sessionId = currentSid,
                        timestampMs = beat.timestampMs,
                        phase = currentPhase,
                        hrBpm = beat.hr.toLong(),
                        rrMs = beat.rrIntervalMs,
                        rmssdMs = currentRmssd,
                        torsoPitchDeg = _torsoPitchDeg.value,
                        torsoRollDeg = _torsoRollDeg.value,
                        dynamicAccMg = _dynamicAccMg.value?.toLong(),
                    )
                } catch (e: Exception) {
                    log.w { "Failed to insert PolarBeatSample: ${e.message}" }
                }
            }
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

        val cpSubscribed = CompletableDeferred<Unit>()
        val dataSubscribed = CompletableDeferred<Unit>()

        // 1. Subscribe to PMD Control Point (FB005C81)
        pmdControlPointJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdControlPointCharacteristic, onSubscription = {
                    log.i { "PMD Control Point (FB005C81) subscribed" }
                    cpSubscribed.complete(Unit)
                })
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.w { "PMD CP observe error: ${e.message}" }
                    }
                    .collect { cpData ->
                        log.d { "PMD CP response: ${cpData.size} bytes: ${cpData.joinToString { (it.toInt() and 0xFF).toString(16) }}" }
                        pmdCpResponses.tryEmit(cpData)
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "PMD CP observer failed: ${e.message}" }
                if (!cpSubscribed.isCompleted) {
                    cpSubscribed.completeExceptionally(e)
                }
            }
        }

        // 2. Subscribe to PMD Data (FB005C82)
        pmdDataJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdDataCharacteristic, onSubscription = {
                    log.i { "PMD Data (FB005C82) subscribed" }
                    dataSubscribed.complete(Unit)
                })
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.w { "PMD Data observe error: ${e.message}" }
                    }
                    .collect { data ->
                        val measurement = parsePolarPmdData(data) ?: return@collect
                        _pmdMeasurement.tryEmit(measurement)
                        when (measurement) {
                            is PolarPmdMeasurement.Ecg -> {
                                _latestEcg.value = measurement
                                _ecgStream.tryEmit(measurement)
                                 val currentSid = activeSessionId
                                if (currentSid != null && database != null) {
                                    try {
                                        val nowMs = timeSource()
                                        val csv = measurement.samplesMicrovolts.joinToString(",")
                                        database.phoenixDatabaseQueries.insertPolarEcgSegment(
                                            sessionId = currentSid,
                                            sensorTimestampNs = measurement.sensorTimestampNs,
                                            wallClockTimestampMs = nowMs,
                                            phase = currentPhase,
                                            samplesUvCsv = csv,
                                        )
                                    } catch (e: Exception) {
                                        log.w { "Failed to insert PolarEcgSegment: ${e.message}" }
                                    }
                                }
                            }
                            is PolarPmdMeasurement.Acc -> {
                                _latestAcc.value = measurement
                                _accStream.tryEmit(measurement)
                                for (s in measurement.samples) {
                                    val bio = accFilter.processSample(s.xMg, s.yMg, s.zMg)
                                    _torsoPitchDeg.value = bio.pitchDeg
                                    _torsoRollDeg.value = bio.rollDeg
                                    _dynamicAccMg.value = bio.dynamicAccMg
                                    if (activeSessionId != null) {
                                        if (maxTorsoPitch == null || bio.pitchDeg > maxTorsoPitch!!) {
                                            maxTorsoPitch = bio.pitchDeg
                                        }
                                        if (maxTorsoRoll == null || abs(bio.rollDeg) > abs(maxTorsoRoll!!)) {
                                            maxTorsoRoll = bio.rollDeg
                                        }
                                    }
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "PMD Data observer failed: ${e.message}" }
                if (!dataSubscribed.isCompleted) {
                    dataSubscribed.completeExceptionally(e)
                }
            }
        }

        withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
            cpSubscribed.await()
            dataSubscribed.await()
        }
        true
    }

    private suspend fun sendPmdControlCommand(
        p: Peripheral,
        command: ByteArray,
        opCode: Byte,
        measurementType: Byte,
        timeoutMs: Long = 2000L,
    ): Result<Int> = pmdControlPointMutex.withLock {
        ensurePmdSubscribed(p)
        // Dwell 50ms before writing to ensure Android GATT queue is clean
        delay(50L)

        val responseDeferred = CompletableDeferred<Int>()
        val responseListener = scope.launch {
            pmdCpResponses
                .filter { resp ->
                    resp.size >= 4 &&
                    (resp[0].toInt() and 0xFF) == 0xF0 &&
                    resp[1] == opCode &&
                    resp[2] == measurementType
                }
                .first()
                .let { resp ->
                    val status = resp[3].toInt() and 0xFF
                    responseDeferred.complete(status)
                }
        }

        try {
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = command,
                writeType = WriteType.WithResponse,
            )
            val status = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            } ?: 0 // If timeout waiting for indication, assume write succeeded
            Result.success(status)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Result.failure(e)
        } finally {
            responseListener.cancel()
        }
    }

    /**
     * Start the 130 Hz 14-bit ECG stream.
     * Ensures PMD Control Point and Data notifications are active before sending command.
     */
    suspend fun startEcgStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ECG stream" }
            val status = sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_START_ECG_CMD,
                opCode = 0x02,
                measurementType = 0x00,
            ).getOrThrow()
            if (status != 0) {
                log.w { "Polar H10 returned non-zero status $status starting ECG stream" }
            }
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
            sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_STOP_ECG_CMD,
                opCode = 0x03,
                measurementType = 0x00,
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
     * Automatically retries with ascending TLV order or 25Hz/2G fallback if range-first TLV is rejected.
     */
    suspend fun startAccStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ACC stream" }
            // Try 1: standard command (range-first)
            var status = sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_START_ACC_CMD,
                opCode = 0x02,
                measurementType = 0x02,
            ).getOrThrow()

            if (status != 0) {
                log.w { "Starting ACC with range-first TLV returned status $status. Retrying with ascending TLV (50Hz, 16-bit, 8G)..." }
                status = sendPmdControlCommand(
                    p = p,
                    command = BleConstants.Polar.PMD_START_ACC_ASCENDING_CMD,
                    opCode = 0x02,
                    measurementType = 0x02,
                ).getOrThrow()
            }

            if (status != 0) {
                log.w { "Starting ACC with ascending TLV returned status $status. Retrying with 25Hz/2G fallback..." }
                status = sendPmdControlCommand(
                    p = p,
                    command = BleConstants.Polar.PMD_START_ACC_FALLBACK_25HZ_CMD,
                    opCode = 0x02,
                    measurementType = 0x02,
                ).getOrThrow()
            }

            if (status != 0) {
                log.e { "Polar H10 rejected ACC start command with status $status" }
                return Result.failure(IllegalStateException("Polar H10 rejected ACC start command: status $status"))
            }

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
            sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_STOP_ACC_CMD,
                opCode = 0x03,
                measurementType = 0x02,
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
        _latestEcg.value = null
        _latestAcc.value = null

        cooldownJob?.cancel()
        cooldownJob = null
        accFilter.reset()
        _torsoPitchDeg.value = null
        _torsoRollDeg.value = null
        _dynamicAccMg.value = null
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
