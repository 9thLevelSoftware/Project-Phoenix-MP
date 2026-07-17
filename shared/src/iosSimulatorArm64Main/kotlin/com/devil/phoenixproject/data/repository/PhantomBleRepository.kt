package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.data.ble.MonitorDataProcessor
import com.devil.phoenixproject.data.ble.parseDiagnosticPacket
import com.devil.phoenixproject.data.ble.parseHeuristicPacket
import com.devil.phoenixproject.data.ble.parseMonitorPacket
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.data.ble.toVitruvianHex
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicPhaseStatistics
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

enum class PhantomRawPacketKind {
    MONITOR,
    REP,
    DIAGNOSTIC,
    HEURISTIC,
}

data class PhantomBleConfig(
    val loadScale: Float = 1f,
    val velocityScale: Double = 1.0,
    val positionScale: Float = 1f,
    val repDelayMs: Long = 750L,
    val autoCompleteFixedRepSets: Boolean = true,
) {
    init {
        require(loadScale > 0f) { "loadScale must be > 0" }
        require(velocityScale > 0.0) { "velocityScale must be > 0" }
        require(positionScale > 0f) { "positionScale must be > 0" }
        require(repDelayMs >= 100L) { "repDelayMs must be >= 100" }
    }

    companion object {
        val Default = PhantomBleConfig()
    }
}

/**
 * Emulator-safe phantom Vitruvian machine.
 *
 * This implementation exercises the same app-level [BleRepository] surface as the real
 * Kable-backed machine without touching Bluetooth hardware. It is intended for debug
 * sandboxes, emulator bug reproduction, UI flow testing, and connection-log capture.
 */
class PhantomBleRepository(
    private val logRepo: ConnectionLogRepository = ConnectionLogRepository.instance,
    initialConfig: PhantomBleConfig = PhantomBleConfig.Default,
) : BleRepository {
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + Dispatchers.Default)

    private val device = ScannedDevice(PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS, -42)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(extraBufferCapacity = 64)
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _handleDetection = MutableStateFlow(HandleDetection())
    override val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

    private val _repEvents = MutableSharedFlow<RepNotification>(extraBufferCapacity = 16)
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()

    private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
    override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()

    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(extraBufferCapacity = 4)
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()

    private val _heuristicData = MutableStateFlow<HeuristicStatistics?>(null)
    override val heuristicData: StateFlow<HeuristicStatistics?> = _heuristicData.asStateFlow()

    private val _diagnostics = MutableStateFlow<DiagnosticPacket?>(null)
    override val diagnostics: StateFlow<DiagnosticPacket?> = _diagnostics.asStateFlow()

    private val _discoModeActive = MutableStateFlow(false)
    override val discoModeActive: StateFlow<Boolean> = _discoModeActive.asStateFlow()

    private val monitorProcessor = MonitorDataProcessor(
        onDeloadOccurred = {
            lifecycleLock.withLock {
                if (!terminal.value) {
                    _deloadOccurredEvents.tryEmit(Unit)
                }
            }
        },
        onRomViolation = { violation ->
            logRepo.warning(
                LogEventType.NOTIFICATION,
                "Phantom raw monitor packet reported ROM violation",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                violation.name,
            )
        },
    )

    private var metricsJob: Job? = null
    private var heuristicJob: Job? = null
    private var repJob: Job? = null
    private var diagnosticJob: Job? = null
    private var heartbeatJob: Job? = null
    private var workoutParams: WorkoutParameters? = null
    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<PhantomBleConfig> = _config.asStateFlow()
    private var ticks = 0L
    private var lastColorSchemeIndex = 0
    private val lifecycleLock = reentrantLock()
    private val terminal = atomic(false)
    private val connectionAttemptGeneration = atomic(0L)
    private var metricsGeneration = 0L
    private var heuristicGeneration = 0L
    private var repGeneration = 0L
    private var diagnosticGeneration = 0L
    private var heartbeatGeneration = 0L

    override suspend fun startScanning(): Result<Unit> {
        val attemptGeneration = beginConnectionAttempt()
            ?: return Result.failure(IllegalStateException("Phantom repository is shut down"))
        return startScanning(attemptGeneration)
    }

    private suspend fun startScanning(attemptGeneration: Long): Result<Unit> {
        if (!publishConnectionState(attemptGeneration, ConnectionState.Scanning) {
                logRepo.info(LogEventType.SCAN_START, "Starting phantom Vitruvian scan")
            }) {
            return Result.failure(IllegalStateException("Phantom scan attempt invalidated"))
        }
        try {
            delay(150)
        } catch (error: CancellationException) {
            invalidateCancelledConnectionAttempt(attemptGeneration, ConnectionState.Scanning)
            throw error
        }
        if (!publishScannedDevices(attemptGeneration, listOf(device))) {
            return Result.failure(IllegalStateException("Phantom scan attempt invalidated"))
        }
        return Result.success(Unit)
    }

    override suspend fun stopScanning() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            connectionAttemptGeneration.incrementAndGet()
            if (_connectionState.value == ConnectionState.Scanning || _connectionState.value == ConnectionState.Connecting) {
                _connectionState.value = ConnectionState.Disconnected
            }
            logRepo.info(LogEventType.SCAN_STOP, "Stopped phantom Vitruvian scan")
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        val attemptGeneration = beginConnectionAttempt()
            ?: return Result.failure(IllegalStateException("Phantom repository is shut down"))
        return connect(device, attemptGeneration)
    }

    private suspend fun connect(device: ScannedDevice, attemptGeneration: Long): Result<Unit> {
        if (!publishConnectionState(attemptGeneration, ConnectionState.Connecting) {
                logRepo.info(LogEventType.CONNECT_START, "Connecting to phantom Vitruvian", device.name, device.address)
            }) {
            return Result.failure(IllegalStateException("Phantom connection attempt invalidated"))
        }
        try {
            delay(250)
        } catch (error: CancellationException) {
            invalidateCancelledConnectionAttempt(attemptGeneration, ConnectionState.Connecting)
            throw error
        }
        if (!completeConnection(attemptGeneration, device)) {
            return Result.failure(IllegalStateException("Phantom connection attempt invalidated"))
        }
        return Result.success(Unit)
    }

    override suspend fun cancelConnection() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            if (_connectionState.value == ConnectionState.Connecting) {
                connectionAttemptGeneration.incrementAndGet()
                _connectionState.value = ConnectionState.Disconnected
                logRepo.warning(LogEventType.DISCONNECT, "Cancelled phantom connection")
            }
        }
    }

    override suspend fun disconnect() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            logRepo.info(LogEventType.DISCONNECT, "Disconnected phantom Vitruvian", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            teardownConnection()
        }
    }

    override suspend fun shutdown() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            logRepo.info(LogEventType.DISCONNECT, "Disconnected phantom Vitruvian", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            teardownConnection(markTerminal = true)
        }
        repositoryJob.cancel()
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        if (timeoutMs <= 0L) {
            return Result.failure(IllegalArgumentException("timeoutMs must be > 0"))
        }

        if (lifecycleLock.withLock { terminal.value }) {
            return Result.failure(IllegalStateException("Phantom repository is shut down"))
        }

        val attemptGeneration = beginConnectionAttempt()
            ?: return Result.failure(IllegalStateException("Phantom repository is shut down"))

        val completed = withTimeoutOrNull(timeoutMs) {
            val scanResult = startScanning(attemptGeneration)
            if (scanResult.isFailure) {
                return@withTimeoutOrNull scanResult
            }
            connect(device, attemptGeneration)
        }
        if (completed != null) {
            return completed
        }

        return lifecycleLock.withLock {
            if (terminal.value) {
                Result.failure(IllegalStateException("Phantom repository is shut down"))
            } else {
                teardownConnection()
                logRepo.error(
                    LogEventType.ERROR,
                    "Phantom scan and connect timed out",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                    "timeoutMs=$timeoutMs",
                )
                _reconnectionRequested.tryEmit(
                    ReconnectionRequest(
                        deviceName = PHANTOM_DEVICE_NAME,
                        deviceAddress = PHANTOM_DEVICE_ADDRESS,
                        reason = "connection_timeout",
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
                Result.failure(IllegalStateException("Phantom scan and connect timed out after ${timeoutMs}ms"))
            }
        }
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock Result.failure(IllegalStateException("Phantom repository is shut down"))
            }
            lastColorSchemeIndex = schemeIndex
            logRepo.info(LogEventType.COMMAND_SENT, "Phantom color scheme set", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS, "scheme=$schemeIndex")
            Result.success(Unit)
        }
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock Result.failure(IllegalStateException("Phantom repository is shut down"))
            }
            logRepo.info(
                LogEventType.COMMAND_SENT,
                "Phantom received raw workout command",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                command.joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') },
            )
            Result.success(Unit)
        }
    }

    override suspend fun sendInitSequence(): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock Result.failure(IllegalStateException("Phantom repository is shut down"))
            }
            logRepo.info(LogEventType.COMMAND_SENT, "Phantom init sequence accepted", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            Result.success(Unit)
        }
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock Result.failure(IllegalStateException("Phantom repository is shut down"))
            }
            if (_discoModeActive.value) {
                stopDiscoMode()
            }
            workoutParams = params
            _handleState.value = HandleState.Grabbed
            logRepo.info(
                LogEventType.COMMAND_SENT,
                "Phantom workout started",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "mode=${params.programMode}; reps=${params.reps}; weightPerCableKg=${params.weightPerCableKg}; justLift=${params.isJustLift}",
            )
            startMetrics(activeWorkout = true)
            startHeuristicGeneration(activeWorkout = true)
            startRepSimulation(params)
            Result.success(Unit)
        }
    }

    override suspend fun stopWorkout(): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock Result.failure(IllegalStateException("Phantom repository is shut down"))
            }
            logRepo.info(LogEventType.COMMAND_SENT, "Phantom workout stopped", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            stopJobs()
            workoutParams = null
            _handleState.value = HandleState.Released
            Result.success(Unit)
        }
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        return lifecycleLock.withLock {
            if (terminal.value) {
                Result.failure(IllegalStateException("Phantom repository is shut down"))
            } else {
                logRepo.info(LogEventType.COMMAND_SENT, "Phantom stop command accepted", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                Result.success(Unit)
            }
        }
    }

    override fun enableHandleDetection(enabled: Boolean) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            _handleDetection.value = HandleDetection(leftDetected = enabled, rightDetected = enabled)
            _handleState.value = if (enabled) HandleState.WaitingForRest else HandleState.Released
            logRepo.info(LogEventType.NOTIFICATION, "Phantom handle detection ${if (enabled) "enabled" else "disabled"}")
        }
    }

    override fun resetHandleState() {
        lifecycleLock.withLock {
            if (!terminal.value) {
                _handleState.value = HandleState.WaitingForRest
            }
        }
    }

    override fun enableJustLiftWaitingMode() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            _handleState.value = HandleState.WaitingForRest
            logRepo.info(LogEventType.NOTIFICATION, "Phantom Just Lift waiting mode armed")
        }
    }

    override fun restartMonitorPolling() {
        lifecycleLock.withLock {
            if (!terminal.value) {
                startMetrics(activeWorkout = workoutParams != null)
            }
        }
    }

    override fun startActiveWorkoutPolling() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            _handleState.value = HandleState.Grabbed
            startMetrics(activeWorkout = true)
            startHeuristicGeneration(activeWorkout = true)
        }
    }

    override fun stopPolling() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            metricsGeneration += 1
            heuristicGeneration += 1
            repGeneration += 1
            diagnosticGeneration += 1
            heartbeatGeneration += 1
            metricsJob?.cancel()
            heuristicJob?.cancel()
            repJob?.cancel()
            diagnosticJob?.cancel()
            heartbeatJob?.cancel()
            logRepo.info(LogEventType.HEARTBEAT, "Phantom polling stopped")
        }
    }

    override fun stopMonitorPollingOnly() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            metricsGeneration += 1
            metricsJob?.cancel()
            logRepo.info(LogEventType.HEARTBEAT, "Phantom monitor polling stopped; diagnostics kept warm")
        }
    }

    override fun restartDiagnosticPolling() {
        lifecycleLock.withLock {
            if (!terminal.value) {
                startDiagnostics()
                startHeartbeat()
            }
        }
    }

    override fun startDiscoMode() {
        lifecycleLock.withLock {
            if (terminal.value || _connectionState.value !is ConnectionState.Connected || workoutParams != null) {
                return@withLock
            }
            _discoModeActive.value = true
            logRepo.info(LogEventType.COMMAND_SENT, "Phantom disco mode started", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
        }
    }

    override fun stopDiscoMode() {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            _discoModeActive.value = false
            logRepo.info(
                LogEventType.COMMAND_SENT,
                "Phantom disco mode stopped",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "restoredScheme=$lastColorSchemeIndex",
            )
        }
    }

    override fun setLastColorSchemeIndex(index: Int) {
        lifecycleLock.withLock {
            if (!terminal.value) {
                lastColorSchemeIndex = index
            }
        }
    }

    /**
     * Inject raw Vitruvian characteristic bytes into the same protocol parsers used by real BLE.
     * This lets emulator-driven RCA reproduce packet-parser bugs without physical hardware.
     */
    suspend fun injectRawPacket(
        kind: PhantomRawPacketKind,
        data: ByteArray,
        hasOpcodePrefix: Boolean = false,
    ): Result<Unit> {
        if (lifecycleLock.withLock { terminal.value }) {
            return Result.failure(IllegalStateException("Phantom repository is shut down"))
        }

        return try {
            when (kind) {
                PhantomRawPacketKind.MONITOR -> injectMonitorPacket(data)
                PhantomRawPacketKind.REP -> injectRepPacket(data, hasOpcodePrefix)
                PhantomRawPacketKind.DIAGNOSTIC -> injectDiagnosticPacket(data)
                PhantomRawPacketKind.HEURISTIC -> injectHeuristicPacket(data)
            }
            if (lifecycleLock.withLock { terminal.value }) {
                Result.failure(IllegalStateException("Phantom repository is shut down"))
            } else {
                Result.success(Unit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lifecycleLock.withLock {
                if (!terminal.value) {
                    logRepo.error(
                        LogEventType.ERROR,
                        "Phantom raw ${kind.name.lowercase()} packet rejected",
                        PHANTOM_DEVICE_NAME,
                        PHANTOM_DEVICE_ADDRESS,
                        "${error.message}; hex=${data.joinToString(" ") { it.toVitruvianHex() }}",
                    )
                }
            }
            Result.failure(error)
        }
    }

    private suspend fun injectMonitorPacket(data: ByteArray) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            val packet = parseMonitorPacket(data)
                ?: error("monitor packet too short: ${data.size} bytes")
            val metric = monitorProcessor.process(packet)
                ?: error("monitor packet parsed but was rejected by validation")
            _metricsFlow.tryEmit(metric)
            logRepo.info(
                LogEventType.NOTIFICATION,
                "Phantom injected raw monitor packet",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "ticks=${metric.ticks}; load=${metric.totalLoad}; posA=${metric.positionA}; hex=${data.joinToString(" ") { it.toVitruvianHex() }}",
            )
        }
    }

    private suspend fun injectRepPacket(data: ByteArray, hasOpcodePrefix: Boolean) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val rep = parseRepPacket(data, hasOpcodePrefix, timestamp)
                ?: error("rep packet too short: ${data.size} bytes")
            _repEvents.tryEmit(rep)
            logRepo.info(
                LogEventType.REP_RECEIVED,
                "Phantom injected raw rep packet",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "top=${rep.topCounter}; complete=${rep.completeCounter}; legacy=${rep.isLegacyFormat}; hex=${data.joinToString(" ") { it.toVitruvianHex() }}",
            )
        }
    }

    private fun injectDiagnosticPacket(data: ByteArray) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val diagnostic = parseDiagnosticPacket(data)
                ?: error("diagnostic packet too short: ${data.size} bytes")
            _diagnostics.value = diagnostic.copy(receivedAtMillis = timestamp)
            logRepo.info(
                LogEventType.DIAGNOSTIC,
                "Phantom injected raw diagnostic packet",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "faults=${diagnostic.faultWords}; temps=${diagnostic.temperatures}; hex=${data.joinToString(" ") { it.toVitruvianHex() }}",
            )
        }
    }

    private fun injectHeuristicPacket(data: ByteArray) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val heuristic = parseHeuristicPacket(data, timestamp)
                ?: error("heuristic packet too short: ${data.size} bytes")
            _heuristicData.value = heuristic
            logRepo.info(
                LogEventType.NOTIFICATION,
                "Phantom injected raw heuristic packet",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "conKgAvg=${heuristic.concentric.kgAvg}; eccKgAvg=${heuristic.eccentric.kgAvg}; hex=${data.joinToString(" ") { it.toVitruvianHex() }}",
            )
        }
    }

    fun replaceConfig(config: PhantomBleConfig) {
        lifecycleLock.withLock {
            if (terminal.value) {
                return@withLock
            }
            _config.value = config
            logRepo.info(
                LogEventType.NOTIFICATION,
                "Phantom config updated",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "loadScale=${config.loadScale}; velocityScale=${config.velocityScale}; positionScale=${config.positionScale}; repDelayMs=${config.repDelayMs}; autoCompleteFixedRepSets=${config.autoCompleteFixedRepSets}",
            )
            if (_connectionState.value is ConnectionState.Connected) {
                startMetrics(activeWorkout = workoutParams != null)
                startHeuristicGeneration(activeWorkout = workoutParams != null)
                workoutParams?.takeIf { repJob?.isActive == true }?.let(::startRepSimulation)
            }
        }
    }

    private fun beginConnectionAttempt(): Long? = lifecycleLock.withLock {
        if (terminal.value) {
            null
        } else {
            connectionAttemptGeneration.incrementAndGet()
        }
    }

    private fun invalidateCancelledConnectionAttempt(
        attemptGeneration: Long,
        expectedState: ConnectionState,
    ) {
        lifecycleLock.withLock {
            if (
                !terminal.value &&
                connectionAttemptGeneration.value == attemptGeneration &&
                _connectionState.value == expectedState
            ) {
                connectionAttemptGeneration.incrementAndGet()
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private inline fun publishConnectionState(
        attemptGeneration: Long,
        state: ConnectionState,
        onPublished: () -> Unit,
    ): Boolean = lifecycleLock.withLock {
        if (terminal.value || connectionAttemptGeneration.value != attemptGeneration) {
            false
        } else {
            _connectionState.value = state
            onPublished()
            true
        }
    }

    private fun publishScannedDevices(attemptGeneration: Long, devices: List<ScannedDevice>): Boolean = lifecycleLock.withLock {
        if (terminal.value || connectionAttemptGeneration.value != attemptGeneration) {
            false
        } else {
            _scannedDevices.value = devices
            logRepo.info(
                LogEventType.DEVICE_FOUND,
                "Found phantom Vitruvian device",
                deviceName = device.name,
                deviceAddress = device.address,
                details = "RSSI ${device.rssi}; no Bluetooth hardware used",
            )
            true
        }
    }

    private fun completeConnection(attemptGeneration: Long, device: ScannedDevice): Boolean = lifecycleLock.withLock {
        if (terminal.value || connectionAttemptGeneration.value != attemptGeneration) {
            return@withLock false
        }
        _connectionState.value = ConnectionState.Connected(device.name, device.address)
        _handleDetection.value = HandleDetection(leftDetected = true, rightDetected = true)
        _handleState.value = HandleState.Released
        logRepo.info(LogEventType.SERVICE_DISCOVERED, "Phantom service map ready", device.name, device.address)
        logRepo.info(LogEventType.CONNECT_SUCCESS, "Connected to phantom Vitruvian", device.name, device.address)
        startDiagnostics()
        startMetrics(activeWorkout = false)
        startHeuristicGeneration(activeWorkout = false)
        startHeartbeat()
        true
    }

    private fun teardownConnection(markTerminal: Boolean = false) {
        lifecycleLock.withLock {
            if (markTerminal) {
                terminal.value = true
            }
            connectionAttemptGeneration.incrementAndGet()
            stopJobs()
            workoutParams = null
            _handleDetection.value = HandleDetection()
            _handleState.value = HandleState.WaitingForRest
            _diagnostics.value = null
            _heuristicData.value = null
            _scannedDevices.value = emptyList()
            _discoModeActive.value = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private inline fun publishIfConnected(
        expectedMetricsGeneration: Long? = null,
        expectedHeuristicGeneration: Long? = null,
        expectedRepGeneration: Long? = null,
        expectedDiagnosticGeneration: Long? = null,
        expectedHeartbeatGeneration: Long? = null,
        publish: () -> Unit,
    ): Boolean = lifecycleLock.withLock {
        if (
            terminal.value ||
            _connectionState.value !is ConnectionState.Connected ||
            (expectedMetricsGeneration != null && metricsGeneration != expectedMetricsGeneration) ||
            (expectedHeuristicGeneration != null && heuristicGeneration != expectedHeuristicGeneration) ||
            (expectedRepGeneration != null && repGeneration != expectedRepGeneration) ||
            (expectedDiagnosticGeneration != null && diagnosticGeneration != expectedDiagnosticGeneration) ||
            (expectedHeartbeatGeneration != null && heartbeatGeneration != expectedHeartbeatGeneration)
        ) {
            false
        } else {
            publish()
            true
        }
    }

    private fun startMetrics(activeWorkout: Boolean) {
        lifecycleLock.withLock {
            val workoutWeightPerCableKg = workoutParams?.weightPerCableKg
            metricsGeneration += 1
            val expectedGeneration = metricsGeneration
            metricsJob?.cancel()
            metricsJob = scope.launch {
                var sample = 0
                while (isActive && connectionState.value is ConnectionState.Connected) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    val phase = (sample % 40) / 40.0
                    val wave = sin(phase * 2.0 * PI).toFloat()
                    val config = _config.value
                    val configuredLoad = workoutWeightPerCableKg ?: 7.5f
                    val load = (if (activeWorkout) configuredLoad.coerceAtLeast(2f) else 1.5f) * config.loadScale
                    if (!publishIfConnected(expectedMetricsGeneration = expectedGeneration) {
                            val metric = WorkoutMetric(
                                timestamp = now,
                                loadA = load + wave.coerceAtLeast(0f) * config.loadScale,
                                loadB = load + (-wave).coerceAtLeast(0f) * config.loadScale,
                                positionA = wave * 650f * config.positionScale,
                                positionB = wave * 640f * config.positionScale,
                                ticks = ticks++,
                                velocityA = wave * 250.0 * config.velocityScale,
                                velocityB = wave * 245.0 * config.velocityScale,
                                status = 0,
                            )
                            _metricsFlow.tryEmit(metric)
                            logRepo.debug(
                                LogEventType.NOTIFICATION,
                                "Phantom monitor metric",
                                PHANTOM_DEVICE_NAME,
                                PHANTOM_DEVICE_ADDRESS,
                                "ticks=${metric.ticks}; load=${metric.totalLoad}; posA=${metric.positionA}",
                            )
                        }) {
                        break
                    }
                    sample++
                    delay(if (activeWorkout) 250 else 750)
                }
            }
        }
    }

    private fun startHeuristicGeneration(activeWorkout: Boolean) {
        lifecycleLock.withLock {
            val workoutWeightPerCableKg = workoutParams?.weightPerCableKg
            heuristicGeneration += 1
            val expectedGeneration = heuristicGeneration
            heuristicJob?.cancel()
            publishIfConnected(expectedHeuristicGeneration = expectedGeneration) {
                val config = _config.value
                val configuredLoad = workoutWeightPerCableKg ?: 7.5f
                val load = (if (activeWorkout) configuredLoad.coerceAtLeast(2f) else 1.5f) * config.loadScale
                _heuristicData.value = HeuristicStatistics(
                    concentric = HeuristicPhaseStatistics(load, load + 1.5f, 0.42f, 0.70f, 85f, 130f),
                    eccentric = HeuristicPhaseStatistics(load * 0.9f, load + 1f, 0.38f, 0.62f, 72f, 110f),
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
                logRepo.debug(
                    LogEventType.NOTIFICATION,
                    "Phantom heuristic update",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                )
            }
            heuristicJob = scope.launch {
                while (isActive && connectionState.value is ConnectionState.Connected) {
                    delay(if (activeWorkout) 250 else 750)
                    val config = _config.value
                    val configuredLoad = workoutWeightPerCableKg ?: 7.5f
                    val load = (if (activeWorkout) configuredLoad.coerceAtLeast(2f) else 1.5f) * config.loadScale
                    if (!publishIfConnected(expectedHeuristicGeneration = expectedGeneration) {
                            _heuristicData.value = HeuristicStatistics(
                                concentric = HeuristicPhaseStatistics(load, load + 1.5f, 0.42f, 0.70f, 85f, 130f),
                                eccentric = HeuristicPhaseStatistics(load * 0.9f, load + 1f, 0.38f, 0.62f, 72f, 110f),
                                timestamp = Clock.System.now().toEpochMilliseconds(),
                            )
                            logRepo.debug(
                                LogEventType.NOTIFICATION,
                                "Phantom heuristic update",
                                PHANTOM_DEVICE_NAME,
                                PHANTOM_DEVICE_ADDRESS,
                            )
                        }) {
                        break
                    }
                }
            }
        }
    }

    private fun startRepSimulation(params: WorkoutParameters) {
        repGeneration += 1
        val expectedGeneration = repGeneration
        repJob?.cancel()
        repJob = scope.launch {
            var rep = 0
            val target = params.reps.coerceAtLeast(1)
            while (isActive && connectionState.value is ConnectionState.Connected) {
                val config = _config.value
                delay(config.repDelayMs)
                rep += 1
                val timestamp = Clock.System.now().toEpochMilliseconds()
                val rawData = ByteArray(24).also { bytes ->
                    bytes[0] = rep.toByte()
                    bytes[4] = rep.toByte()
                    bytes[18] = params.warmupReps.toByte()
                    bytes[22] = target.toByte()
                }
                if (!publishIfConnected(expectedRepGeneration = expectedGeneration) {
                        _repEvents.tryEmit(
                            RepNotification(
                                topCounter = rep,
                                completeCounter = rep,
                                repsRomCount = params.warmupReps.coerceAtMost(rep),
                                repsRomTotal = params.warmupReps,
                                repsSetCount = rep.coerceAtMost(target),
                                repsSetTotal = target,
                                rangeTop = 650f,
                                rangeBottom = -650f,
                                rawData = rawData,
                                timestamp = timestamp,
                            ),
                        )
                        logRepo.info(
                            LogEventType.REP_RECEIVED,
                            "Phantom rep notification",
                            PHANTOM_DEVICE_NAME,
                            PHANTOM_DEVICE_ADDRESS,
                            "rep=$rep/$target; timestamp=$timestamp",
                        )
                        if (rep >= target && !params.isAMRAP && config.autoCompleteFixedRepSets) {
                            logRepo.info(LogEventType.COMMAND_RESPONSE, "Phantom target reps reached", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                        }
                    }) {
                    break
                }
                if (rep >= target && !params.isAMRAP && config.autoCompleteFixedRepSets) {
                    break
                }
            }
        }
    }

    private fun startDiagnostics() {
        diagnosticGeneration += 1
        val expectedGeneration = diagnosticGeneration
        diagnosticJob?.cancel()
        val connectedAt = Clock.System.now().toEpochMilliseconds()
        publishIfConnected(expectedDiagnosticGeneration = expectedGeneration) {
            val now = Clock.System.now().toEpochMilliseconds()
            _diagnostics.value = DiagnosticPacket(
                runtimeSeconds = (now - connectedAt) / 1000,
                faultWords = listOf(0, 0, 0, 0),
                temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                hasFaults = false,
                receivedAtMillis = now,
            )
            logRepo.debug(LogEventType.DIAGNOSTIC, "Phantom diagnostic heartbeat", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
        }
        diagnosticJob = scope.launch {
            while (isActive && connectionState.value is ConnectionState.Connected) {
                delay(2_000)
                val now = Clock.System.now().toEpochMilliseconds()
                if (!publishIfConnected(expectedDiagnosticGeneration = expectedGeneration) {
                        _diagnostics.value = DiagnosticPacket(
                            runtimeSeconds = (now - connectedAt) / 1000,
                            faultWords = listOf(0, 0, 0, 0),
                            temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                            hasFaults = false,
                            receivedAtMillis = now,
                        )
                        logRepo.debug(LogEventType.DIAGNOSTIC, "Phantom diagnostic heartbeat", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                    }) {
                    break
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatGeneration += 1
        val expectedGeneration = heartbeatGeneration
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connectionState.value is ConnectionState.Connected) {
                if (!publishIfConnected(expectedHeartbeatGeneration = expectedGeneration) {
                        logRepo.info(LogEventType.HEARTBEAT, "Phantom heartbeat", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                    }) {
                    break
                }
                delay(2_000)
            }
        }
    }

    private fun stopJobs() {
        metricsGeneration += 1
        heuristicGeneration += 1
        repGeneration += 1
        diagnosticGeneration += 1
        heartbeatGeneration += 1
        metricsJob?.cancel()
        heuristicJob?.cancel()
        repJob?.cancel()
        diagnosticJob?.cancel()
        heartbeatJob?.cancel()
        metricsJob = null
        heuristicJob = null
        repJob = null
        diagnosticJob = null
        heartbeatJob = null
    }

    companion object {
        const val PHANTOM_DEVICE_NAME = "Vee_PhantomSimulator"
        const val PHANTOM_DEVICE_ADDRESS = "PH:AN:TO:MS:BX:01"
    }
}
