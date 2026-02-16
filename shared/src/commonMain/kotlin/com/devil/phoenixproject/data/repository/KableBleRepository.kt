package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.util.BlePacketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger
import kotlin.time.Clock

import com.devil.phoenixproject.data.ble.DiscoMode
import com.devil.phoenixproject.data.ble.HandleStateDetector
import com.devil.phoenixproject.data.ble.MonitorDataProcessor
import com.devil.phoenixproject.data.ble.BleOperationQueue
import com.devil.phoenixproject.data.ble.MetricPollingEngine
import com.devil.phoenixproject.data.ble.KableBleConnectionManager
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.data.ble.getUInt16BE
import com.devil.phoenixproject.data.ble.toVitruvianHex

import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutParameters

/**
 * Thin facade delegating to 6 extracted modules (BleOperationQueue, DiscoMode,
 * HandleStateDetector, MonitorDataProcessor, MetricPollingEngine, KableBleConnectionManager).
 */
class KableBleRepository : BleRepository {

    private val log = Logger.withTag("KableBleRepository")
    private val logRepo = ConnectionLogRepository.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== State flows =====
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()
    private val handleDetector = HandleStateDetector()
    override val handleDetection: StateFlow<HandleDetection> = handleDetector.handleDetection
    override val handleState: StateFlow<HandleState> = handleDetector.handleState
    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()
    private val _repEvents = MutableSharedFlow<RepNotification>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()
    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()
    enum class RomViolationType { OUTSIDE_HIGH, OUTSIDE_LOW }
    private val _romViolationEvents = MutableSharedFlow<RomViolationType>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val romViolationEvents: Flow<RomViolationType> = _romViolationEvents.asSharedFlow()
    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()
    private val _heuristicData = MutableStateFlow<HeuristicStatistics?>(null)
    override val heuristicData: StateFlow<HeuristicStatistics?> = _heuristicData.asStateFlow()
    override val discoModeActive: StateFlow<Boolean> get() = discoMode.isActive

    // ===== Extracted modules (6 modules) =====
    private val bleQueue = BleOperationQueue()

    private val monitorProcessor = MonitorDataProcessor(
        onDeloadOccurred = { scope.launch { _deloadOccurredEvents.emit(Unit) } },
        onRomViolation = { type ->
            scope.launch {
                when (type) {
                    MonitorDataProcessor.RomViolationType.OUTSIDE_HIGH ->
                        _romViolationEvents.emit(RomViolationType.OUTSIDE_HIGH)
                    MonitorDataProcessor.RomViolationType.OUTSIDE_LOW ->
                        _romViolationEvents.emit(RomViolationType.OUTSIDE_LOW)
                }
            }
        }
    )

    private val discoMode = DiscoMode(
        scope = scope,
        sendCommand = { command -> connectionManager.sendWorkoutCommand(command) }
    )

    private val pollingEngine = MetricPollingEngine(
        scope = scope,
        bleQueue = bleQueue,
        monitorProcessor = monitorProcessor,
        handleDetector = handleDetector,
        onMetricEmit = { metric ->
            val emitted = _metricsFlow.tryEmit(metric)
            if (!emitted && monitorProcessor.notificationCount % 100 == 0L) {
                log.w { "Failed to emit metric - buffer full? Count: ${monitorProcessor.notificationCount}" }
            }
            emitted
        },
        onHeuristicData = { stats -> _heuristicData.value = stats },
        onConnectionLost = { connectionManager.disconnect() }
    )

    // ConnectionManager declared LAST (depends on all above modules for init-order safety)
    private lateinit var connectionManager: KableBleConnectionManager
    init {
        connectionManager = KableBleConnectionManager(
            scope = scope,
            logRepo = logRepo,
            bleQueue = bleQueue,
            pollingEngine = pollingEngine,
            discoMode = discoMode,
            handleDetector = handleDetector,
            onConnectionStateChanged = { state -> _connectionState.value = state },
            onScannedDevicesChanged = { devices -> _scannedDevices.value = devices },
            onReconnectionRequested = { request -> _reconnectionRequested.emit(request) },
            onCommandResponse = { _ -> /* no external consumer currently */ },
            onRepEventFromCharacteristic = { data -> parseRepsCharacteristicData(data) },
            onRepEventFromRx = { data -> parseRepNotification(data) },
            onMetricFromRx = { data -> parseMetricsPacket(data) },
        )
    }

    // ===== Connection lifecycle delegations =====
    override suspend fun startScanning(): Result<Unit> = connectionManager.startScanning()
    override suspend fun stopScanning(): Unit = connectionManager.stopScanning()
    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> = connectionManager.scanAndConnect(timeoutMs)
    override suspend fun connect(device: ScannedDevice): Result<Unit> = connectionManager.connect(device)
    override suspend fun disconnect(): Unit = connectionManager.disconnect()
    override suspend fun cancelConnection(): Unit = connectionManager.cancelConnection()
    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> = connectionManager.sendWorkoutCommand(command)

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        log.d { "Setting color scheme: $schemeIndex" }
        return try {
            val command = BlePacketFactory.createColorSchemeCommand(schemeIndex)
            connectionManager.sendWorkoutCommand(command)
        } catch (e: Exception) {
            log.e { "Failed to set color scheme: ${e.message}" }
            Result.failure(e)
        }
    }

    // ===== High-level workout control =====
    override suspend fun sendInitSequence(): Result<Unit> {
        log.i { "Sending initialization sequence" }
        return try {
            val initCmd = byteArrayOf(0x01, 0x00, 0x00, 0x00)
            sendWorkoutCommand(initCmd)
        } catch (e: Exception) {
            log.e { "Failed to send init sequence: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        stopDiscoMode()

        log.i { "Starting workout with params: type=${params.programMode}, weight=${params.weightPerCableKg}kg" }
        return try {
            val modeCode = params.programMode.modeValue.toByte()
            val weightBytes = (params.weightPerCableKg * 100).toInt()
            val weightLow = (weightBytes and 0xFF).toByte()
            val weightHigh = ((weightBytes shr 8) and 0xFF).toByte()

            val startCmd = byteArrayOf(0x02, modeCode, weightLow, weightHigh)
            val result = sendWorkoutCommand(startCmd)

            if (result.isSuccess) {
                startActiveWorkoutPolling()
            }

            result
        } catch (e: Exception) {
            log.e { "Failed to start workout: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun stopWorkout(): Result<Unit> {
        log.i { "Stopping workout" }
        return try {
            val resetCmd = BlePacketFactory.createResetCommand()
            log.d { "Sending RESET command (0x0A)..." }
            sendWorkoutCommand(resetCmd)
            delay(50)

            log.d { "Stopping polling after RESET..." }
            stopPolling()

            Result.success(Unit)
        } catch (e: Exception) {
            log.e { "Failed to stop workout: ${e.message}" }
            Result.failure(e)
        }
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        log.i { "Sending stop command (polling continues)" }
        return try {
            val stopPacket = BlePacketFactory.createOfficialStopPacket()
            log.d { "Sending StopPacket (0x50)..." }
            sendWorkoutCommand(stopPacket)
        } catch (e: Exception) {
            log.e { "Failed to send stop command: ${e.message}" }
            Result.failure(e)
        }
    }

    // ===== Polling and handle detection delegations =====
    override fun enableHandleDetection(enabled: Boolean) {
        log.i { "Handle detection ${if (enabled) "ENABLED" else "DISABLED"}" }
        if (enabled) {
            val p = connectionManager.currentPeripheral
            if (p != null) {
                stopDiscoMode()
                pollingEngine.startMonitorPolling(p, forAutoStart = true)
            }
        } else {
            handleDetector.disable()
        }
    }

    override fun resetHandleState() = handleDetector.reset()

    override fun enableJustLiftWaitingMode() = handleDetector.enableJustLiftWaiting()

    override fun restartMonitorPolling() {
        log.i { "Restarting monitor polling to clear machine fault state" }
        val p = connectionManager.currentPeripheral ?: run {
            log.w { "Cannot restart monitor polling - peripheral is null" }
            return
        }
        stopDiscoMode()
        pollingEngine.startMonitorPolling(p, forAutoStart = false)
    }

    override fun startActiveWorkoutPolling() {
        log.i { "Starting active workout polling (no auto-start)" }
        val p = connectionManager.currentPeripheral ?: run {
            log.w { "Cannot start active workout polling - peripheral is null" }
            return
        }
        stopDiscoMode()
        pollingEngine.restartAll(p)
    }

    override fun stopPolling() = pollingEngine.stopAll()

    override fun stopMonitorPollingOnly() = pollingEngine.stopMonitorOnly()

    override fun restartDiagnosticPolling() {
        val p = connectionManager.currentPeripheral ?: run {
            log.w { "Cannot restart diagnostic polling - peripheral is null" }
            return
        }
        pollingEngine.restartDiagnosticAndHeartbeat(p)
    }

    // ===== Parsing methods (stay in facade) =====

    /** Parse metrics packet from RX notifications (0x01). Big-endian, position scaled to mm. */
    private fun parseMetricsPacket(data: ByteArray) {
        if (data.size < 16) return

        try {
            val positionARaw = getUInt16BE(data, 2)
            val positionBRaw = getUInt16BE(data, 4)
            val loadA = getUInt16BE(data, 6)
            val loadB = getUInt16BE(data, 8)
            val velocityA = getUInt16BE(data, 10)
            val velocityB = getUInt16BE(data, 12)

            val positionA = positionARaw / 10.0f
            val positionB = positionBRaw / 10.0f

            val currentTime = currentTimeMillis()
            val metric = WorkoutMetric(
                timestamp = currentTime,
                loadA = loadA / 10f,
                loadB = loadB / 10f,
                positionA = positionA,
                positionB = positionB,
                velocityA = (velocityA - 32768).toDouble(),
                velocityB = (velocityB - 32768).toDouble()
            )

            _metricsFlow.tryEmit(metric)
            handleDetector.processMetric(metric)
        } catch (e: Exception) {
            log.e { "Error parsing metrics: ${e.message}" }
        }
    }

    /** Parse rep notification from RX characteristic (with opcode 0x02 prefix). */
    private fun parseRepNotification(data: ByteArray) {
        try {
            val currentTime = currentTimeMillis()
            val notification = parseRepPacket(data, hasOpcodePrefix = true, timestamp = currentTime)

            if (notification == null) {
                log.w { "Rep notification too short: ${data.size} bytes (minimum 7)" }
                return
            }

            if (notification.isLegacyFormat) {
                log.w { "Rep notification (LEGACY 6-byte format - Issue #187 fallback):" }
                log.w { "  top=${notification.topCounter}, complete=${notification.completeCounter}" }
                log.w { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }
            } else {
                log.d { "Rep notification (24-byte format, RX):" }
                log.d { "  up=${notification.topCounter}, down=${notification.completeCounter}" }
                log.d { "  repsRomCount=${notification.repsRomCount} (warmup done), repsRomTotal=${notification.repsRomTotal} (warmup target)" }
                log.d { "  repsSetCount=${notification.repsSetCount} (working done), repsSetTotal=${notification.repsSetTotal} (working target)" }
                log.d { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }
            }

            val emitted = _repEvents.tryEmit(notification)
            log.d { "Emitted rep event (RX): success=$emitted, legacy=${notification.isLegacyFormat}" }

            logRepo.debug(
                LogEventType.REP_RECEIVED,
                if (notification.isLegacyFormat) "Legacy rep (6-byte)" else "Modern rep (24-byte)",
                details = "up=${notification.topCounter}, setCount=${notification.repsSetCount}, legacy=${notification.isLegacyFormat}"
            )
        } catch (e: Exception) {
            log.e { "Error parsing rep notification: ${e.message}" }
        }
    }

    /** Parse rep data from REPS characteristic notifications (NO opcode prefix). */
    private fun parseRepsCharacteristicData(data: ByteArray) {
        try {
            val currentTime = currentTimeMillis()
            val notification = parseRepPacket(data, hasOpcodePrefix = false, timestamp = currentTime)

            if (notification == null) {
                log.w { "REPS characteristic data too short: ${data.size} bytes (minimum 6)" }
                return
            }

            log.i { "REPS CHAR notification: ${data.size} bytes" }
            log.d { "  hex=${data.joinToString(" ") { it.toVitruvianHex() }}" }

            if (notification.isLegacyFormat) {
                log.w { "REPS (LEGACY 6-byte format):" }
                log.w { "  top=${notification.topCounter}, complete=${notification.completeCounter}" }
            } else {
                log.i { "REPS (24-byte official format):" }
                log.i { "  up=${notification.topCounter}, down=${notification.completeCounter}" }
                log.i { "  repsRomCount=${notification.repsRomCount} (warmup done), repsRomTotal=${notification.repsRomTotal} (warmup target)" }
                log.i { "  repsSetCount=${notification.repsSetCount} (working done), repsSetTotal=${notification.repsSetTotal} (working target)" }
                log.i { "  rangeTop=${notification.rangeTop}, rangeBottom=${notification.rangeBottom}" }
            }

            val emitted = _repEvents.tryEmit(notification)
            log.i { "Emitted rep event (REPS char): success=$emitted, legacy=${notification.isLegacyFormat}, repsSetCount=${notification.repsSetCount}" }

            logRepo.debug(
                LogEventType.REP_RECEIVED,
                if (notification.isLegacyFormat) "Legacy rep (6-byte)" else "Modern rep (24-byte)",
                details = "up=${notification.topCounter}, setCount=${notification.repsSetCount}, legacy=${notification.isLegacyFormat}"
            )
        } catch (e: Exception) {
            log.e { "Error parsing REPS characteristic data: ${e.message}" }
        }
    }

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    // ===== Disco Mode (Easter Egg) =====
    override fun startDiscoMode() {
        if (connectionManager.currentPeripheral == null) {
            log.w { "Cannot start disco mode - not connected" }
            return
        }
        discoMode.start()
    }

    override fun stopDiscoMode() = discoMode.stop()

    override fun setLastColorSchemeIndex(index: Int) = discoMode.setLastColorSchemeIndex(index)
}
