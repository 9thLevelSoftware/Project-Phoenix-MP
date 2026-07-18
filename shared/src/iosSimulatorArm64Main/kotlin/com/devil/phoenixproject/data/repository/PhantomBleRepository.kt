package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicPhaseStatistics
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/** Configuration for deterministic simulator telemetry. */
data class PhantomBleConfig(
    val loadScale: Float = 1f,
    val velocityScale: Double = 1.0,
    val positionScale: Float = 1f,
    val repDelayMs: Long = 750L,
    val autoCompleteFixedRepSets: Boolean = true,
    val defaultEchoLoadKg: Float = 20f,
) {
    init {
        require(loadScale > 0f) { "loadScale must be > 0" }
        require(velocityScale > 0.0) { "velocityScale must be > 0" }
        require(positionScale > 0f) { "positionScale must be > 0" }
        require(repDelayMs >= 100L) { "repDelayMs must be >= 100" }
        require(defaultEchoLoadKg > 0f) { "defaultEchoLoadKg must be > 0" }
    }

    companion object {
        val Default = PhantomBleConfig()
    }
}

internal data class PhantomWorkoutProgram(
    val warmupReps: Int,
    val workingReps: Int?,
    val weightPerCableKg: Float,
)

/**
 * Simulator-only BLE implementation. It intentionally contains no Kable or CoreBluetooth
 * references: the iOS simulator binding can exercise the real app repository contract without
 * requesting hardware permissions or touching a persisted BLE session.
 */
class PhantomBleRepository(
    private val logRepo: ConnectionLogRepository = ConnectionLogRepository.instance,
    initialConfig: PhantomBleConfig = PhantomBleConfig(),
) : BleRepository {
    private val lifecycleLock = reentrantLock()
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
    private val _repEvents = MutableSharedFlow<RepNotification>(extraBufferCapacity = 64)
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

    private var config = initialConfig
    private var terminal = false
    private var generation = 0L
    private var metricGeneration = 0L
    private var heuristicGeneration = 0L
    private var repGeneration = 0L
    private var diagnosticGeneration = 0L
    private var heartbeatGeneration = 0L
    private var metricsJob: Job? = null
    private var heuristicJob: Job? = null
    private var repJob: Job? = null
    private var diagnosticJob: Job? = null
    private var heartbeatJob: Job? = null
    private var workoutParams: WorkoutParameters? = null
    private var currentProgram: PhantomWorkoutProgram? = null
    private var repCount = 0
    private var topCounter = 0
    private var completeCounter = 0
    private var fixedSetCompleted = false
    private var ticks = 0L
    private var lastColorSchemeIndex = 0

    override suspend fun startScanning(): Result<Unit> {
        val attempt = lifecycleLock.withLock {
            if (terminal) return@withLock null
            generation += 1
            val expectedGeneration = generation
            cancelPollingLocked()
            _connectionState.value = ConnectionState.Scanning
            if (!ownsStateLocked(expectedGeneration, ConnectionState.Scanning)) return@withLock null
            _scannedDevices.value = emptyList()
            if (!ownsStateLocked(expectedGeneration, ConnectionState.Scanning)) return@withLock null
            expectedGeneration
        }
        if (attempt == null) return lifecycleFailure()

        try {
            val started = lifecycleLock.withLock {
                logIfOwnedLocked(attempt) {
                    logRepo.info(LogEventType.SCAN_START, "Starting phantom Vitruvian scan")
                }
            }
            if (!started) return lifecycleFailure()
            delay(SCAN_DELAY_MS)
            return lifecycleLock.withLock {
                if (!ownsStateLocked(attempt, ConnectionState.Scanning)) {
                    return@withLock lifecycleFailureLocked()
                }
                _scannedDevices.value = listOf(device)
                if (!ownsStateLocked(attempt, ConnectionState.Scanning)) {
                    return@withLock lifecycleFailureLocked()
                }
                if (!logIfOwnedLocked(attempt) {
                        logRepo.info(
                            LogEventType.DEVICE_FOUND,
                            "Found phantom Vitruvian device",
                            device.name,
                            device.address,
                            "RSSI ${device.rssi}; no Bluetooth hardware used",
                        )
                    }
                ) {
                    return@withLock lifecycleFailureLocked()
                }
                Result.success(Unit)
            }
        } catch (cancellation: CancellationException) {
            cancelAttemptIfOwner(attempt)
            throw cancellation
        }
    }

    override suspend fun stopScanning() {
        lifecycleLock.withLock {
            if (terminal || (_connectionState.value != ConnectionState.Scanning &&
                    _connectionState.value != ConnectionState.Connecting)
            ) return@withLock
            generation += 1
            val expectedGeneration = generation
            cancelPollingLocked()
            _connectionState.value = ConnectionState.Disconnected
            if (!ownsGenerationLocked(expectedGeneration)) return@withLock
            _scannedDevices.value = emptyList()
            if (!ownsGenerationLocked(expectedGeneration)) return@withLock
            logIfGenerationOwnedLocked(expectedGeneration) {
                logRepo.info(LogEventType.SCAN_STOP, "Stopped phantom Vitruvian scan")
            }
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        val attempt = lifecycleLock.withLock {
            if (terminal) return@withLock null
            generation += 1
            val expectedGeneration = generation
            cancelPollingLocked()
            _connectionState.value = ConnectionState.Connecting
            if (!ownsStateLocked(expectedGeneration, ConnectionState.Connecting)) return@withLock null
            expectedGeneration
        }
        if (attempt == null) return lifecycleFailure()

        try {
            val started = lifecycleLock.withLock {
                logIfOwnedLocked(attempt) {
                    logRepo.info(LogEventType.CONNECT_START, "Connecting to phantom Vitruvian", device.name, device.address)
                }
            }
            if (!started) return lifecycleFailure()
            delay(CONNECT_DELAY_MS)
            return lifecycleLock.withLock {
                if (!ownsStateLocked(attempt, ConnectionState.Connecting)) {
                    return@withLock lifecycleFailureLocked()
                }
                _connectionState.value = ConnectionState.Connected(device.name, device.address)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                _handleDetection.value = HandleDetection(leftDetected = true, rightDetected = true)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                _handleState.value = HandleState.Released
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                publishDiagnosticsLocked(attempt)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                startMetricsLocked(activeWorkout = false, expectedGeneration = attempt)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                startHeuristicLocked(activeWorkout = false, expectedGeneration = attempt)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                startHeartbeatLocked(attempt)
                if (!isConnectedLocked(attempt)) return@withLock lifecycleFailureLocked()
                if (!logIfOwnedLocked(attempt) {
                        logRepo.info(LogEventType.SERVICE_DISCOVERED, "Phantom service map ready", device.name, device.address)
                    }
                ) return@withLock lifecycleFailureLocked()
                if (!logIfOwnedLocked(attempt) {
                        logRepo.info(LogEventType.CONNECT_SUCCESS, "Connected to phantom Vitruvian", device.name, device.address)
                    }
                ) return@withLock lifecycleFailureLocked()
                Result.success(Unit)
            }
        } catch (cancellation: CancellationException) {
            cancelAttemptIfOwner(attempt)
            throw cancellation
        }
    }

    override suspend fun cancelConnection() {
        lifecycleLock.withLock {
            if (terminal || _connectionState.value != ConnectionState.Connecting) return@withLock
            generation += 1
            val expectedGeneration = generation
            cancelPollingLocked()
            _connectionState.value = ConnectionState.Disconnected
            if (!ownsGenerationLocked(expectedGeneration)) return@withLock
            _scannedDevices.value = emptyList()
            if (!ownsGenerationLocked(expectedGeneration)) return@withLock
            logIfGenerationOwnedLocked(expectedGeneration) {
                logRepo.warning(LogEventType.DISCONNECT, "Cancelled phantom connection")
            }
        }
    }

    override suspend fun disconnect() {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            teardownLocked(markTerminal = false)
            if (!terminal && _connectionState.value == ConnectionState.Disconnected) {
                logRepo.info(LogEventType.DISCONNECT, "Disconnected phantom Vitruvian", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            }
        }
    }

    override suspend fun shutdown() {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            terminal = true
            teardownLocked(markTerminal = true)
        }
        repositoryJob.cancel()
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        if (timeoutMs <= 0L) return Result.failure(IllegalArgumentException("timeoutMs must be > 0"))
        val initialGeneration = lifecycleLock.withLock {
            if (terminal) null else generation
        } ?: return Result.failure(shutdownError())
        var attemptGeneration = initialGeneration + 1L
        val result: Result<Unit>? = withTimeoutOrNull(timeoutMs) {
            val scan = startScanning()
            if (scan.isFailure) {
                scan
            } else {
                attemptGeneration = lifecycleLock.withLock { generation + 1L }
                connect(device)
            }
        }
        if (result != null) return result

        return lifecycleLock.withLock {
            if (terminal) return@withLock Result.failure(shutdownError())
            val ownsTimedOutAttempt =
                (generation == attemptGeneration &&
                    (_connectionState.value == ConnectionState.Scanning ||
                        _connectionState.value == ConnectionState.Connecting)) ||
                    (generation == attemptGeneration + 1L &&
                        _connectionState.value == ConnectionState.Disconnected)
            if (!ownsTimedOutAttempt) return@withLock lifecycleFailureLocked()

            teardownLocked(markTerminal = false)
            val cleanupGeneration = generation
            if (!ownsGenerationLocked(cleanupGeneration) ||
                _connectionState.value != ConnectionState.Disconnected
            ) return@withLock lifecycleFailureLocked()
            val request = ReconnectionRequest(
                deviceName = PHANTOM_DEVICE_NAME,
                deviceAddress = PHANTOM_DEVICE_ADDRESS,
                reason = "connection_timeout",
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
            _reconnectionRequested.tryEmit(request)
            if (!ownsGenerationLocked(cleanupGeneration)) return@withLock lifecycleFailureLocked()
            logRepo.error(
                LogEventType.ERROR,
                "Phantom scan and connect timed out",
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "timeoutMs=$timeoutMs",
            )
            if (!ownsGenerationLocked(cleanupGeneration)) return@withLock lifecycleFailureLocked()
            Result.failure(IllegalStateException("Phantom scan and connect timed out after ${timeoutMs}ms"))
        }
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        lastColorSchemeIndex = schemeIndex
        acceptCommandLocked("Phantom color scheme set", "scheme=$schemeIndex")
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        if (_connectionState.value !is ConnectionState.Connected) {
            return@withLock Result.failure(IllegalStateException("Phantom workout command requires an active connection"))
        }
        decodeProgram(command)?.let { currentProgram = it }
        acceptCommandLocked(
            "Phantom received raw workout command",
            command.joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') },
        )
    }

    override suspend fun sendInitSequence(): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        acceptCommandLocked("Phantom init sequence accepted")
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        if (_connectionState.value !is ConnectionState.Connected) {
            return@withLock Result.failure(IllegalStateException("Phantom workout requires an active connection"))
        }
        val expected = generation
        _discoModeActive.value = false
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        workoutParams = params
        currentProgram = currentProgram ?: PhantomWorkoutProgram(
            warmupReps = params.warmupReps,
            workingReps = params.reps.takeUnless { params.isAMRAP },
            weightPerCableKg = params.weightPerCableKg,
        )
        repCount = 0
        topCounter = 0
        completeCounter = 0
        fixedSetCompleted = false
        _handleState.value = HandleState.Grabbed
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        startMetricsLocked(activeWorkout = true, expectedGeneration = expected)
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        startHeuristicLocked(activeWorkout = true, expectedGeneration = expected)
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        startRepSimulationLocked(expected)
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        if (!logIfOwnedLocked(expected) {
                logRepo.info(
                    LogEventType.COMMAND_SENT,
                    "Phantom workout started",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                    "mode=${params.programMode}; reps=${params.reps}; weightPerCableKg=${params.weightPerCableKg}",
                )
            }
        ) return@withLock lifecycleFailureLocked()
        Result.success(Unit)
    }

    override suspend fun stopWorkout(): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        if (_connectionState.value !is ConnectionState.Connected) {
            return@withLock Result.failure(IllegalStateException("Phantom workout requires an active connection"))
        }
        val expected = generation
        cancelPollingLocked()
        workoutParams = null
        currentProgram = null
        fixedSetCompleted = false
        _handleState.value = HandleState.Released
        if (!isConnectedLocked(expected)) return@withLock lifecycleFailureLocked()
        if (!logIfOwnedLocked(expected) {
                logRepo.info(LogEventType.COMMAND_SENT, "Phantom workout stopped", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            }
        ) return@withLock lifecycleFailureLocked()
        Result.success(Unit)
    }

    override suspend fun sendStopCommand(): Result<Unit> = lifecycleLock.withLock {
        if (terminal) return@withLock Result.failure(shutdownError())
        acceptCommandLocked("Phantom stop command accepted")
    }

    override fun enableHandleDetection(enabled: Boolean) {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            val expected = generation
            _handleDetection.value = HandleDetection(enabled, enabled)
            if (!ownsGenerationLocked(expected)) return@withLock
            _handleState.value = if (enabled) HandleState.WaitingForRest else HandleState.Released
            if (!ownsGenerationLocked(expected)) return@withLock
        }
    }

    override fun resetHandleState() {
        lifecycleLock.withLock {
            if (!terminal) _handleState.value = HandleState.WaitingForRest
        }
    }

    override fun enableJustLiftWaitingMode() {
        lifecycleLock.withLock {
            if (!terminal) _handleState.value = HandleState.WaitingForRest
        }
    }

    override fun restartMonitorPolling() {
        lifecycleLock.withLock {
            if (terminal || _connectionState.value !is ConnectionState.Connected) return@withLock
            startMetricsLocked(workoutParams != null, generation)
        }
    }

    override fun startActiveWorkoutPolling() {
        lifecycleLock.withLock {
            if (terminal || _connectionState.value !is ConnectionState.Connected || fixedSetCompleted) return@withLock
            val expected = generation
            _handleState.value = HandleState.Grabbed
            if (!isConnectedLocked(expected)) return@withLock
            startMetricsLocked(activeWorkout = true, expectedGeneration = expected)
            if (!isConnectedLocked(expected)) return@withLock
            startHeuristicLocked(activeWorkout = true, expectedGeneration = expected)
            if (!isConnectedLocked(expected)) return@withLock
            if (workoutParams != null) startRepSimulationLocked(expected)
        }
    }

    override fun stopPolling() {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            cancelPollingLocked()
            logRepo.info(LogEventType.HEARTBEAT, "Phantom polling stopped")
        }
    }

    override fun stopMonitorPollingOnly() {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            metricGeneration += 1
            metricsJob?.cancel()
            metricsJob = null
            logRepo.info(LogEventType.HEARTBEAT, "Phantom monitor polling stopped; diagnostics kept warm")
        }
    }

    override fun restartDiagnosticPolling() {
        lifecycleLock.withLock {
            if (terminal || _connectionState.value !is ConnectionState.Connected) return@withLock
            startDiagnosticsLocked(generation)
            startHeartbeatLocked(generation)
        }
    }

    override fun startDiscoMode() {
        lifecycleLock.withLock {
            if (terminal || _connectionState.value !is ConnectionState.Connected || workoutParams != null) return@withLock
            val expected = generation
            _discoModeActive.value = true
            if (!isConnectedLocked(expected)) return@withLock
            logRepo.info(LogEventType.COMMAND_SENT, "Phantom disco mode started", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
        }
    }

    override fun stopDiscoMode() {
        lifecycleLock.withLock {
            if (terminal) return@withLock
            if (_discoModeActive.value) {
                val expected = generation
                _discoModeActive.value = false
                if (!ownsGenerationLocked(expected)) return@withLock
                logRepo.info(
                    LogEventType.COMMAND_SENT,
                    "Phantom disco mode stopped",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                    "restoredScheme=$lastColorSchemeIndex",
                )
            }
        }
    }

    override fun setLastColorSchemeIndex(index: Int) {
        lifecycleLock.withLock {
            if (!terminal) lastColorSchemeIndex = index
        }
    }

    private fun acceptCommandLocked(message: String, details: String? = null): Result<Unit> {
        logRepo.info(LogEventType.COMMAND_SENT, message, PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS, details)
        return Result.success(Unit)
    }

    private fun decodeProgram(command: ByteArray): PhantomWorkoutProgram? {
        if (command.size >= REGULAR_PACKET_SIZE && readUInt32(command, 0) == REGULAR_OPCODE) {
            val totalReps = command[4].toInt() and 0xFF
            val warmupReps = command[5].toInt() and 0xFF
            return PhantomWorkoutProgram(
                warmupReps = warmupReps,
                workingReps = totalReps
                    .takeUnless { it == UNLIMITED_REPS }
                    ?.let { (it - warmupReps).coerceAtLeast(0) },
                weightPerCableKg = readFloat(command, REGULAR_WEIGHT_OFFSET),
            )
        }
        if (command.size >= ECHO_PACKET_SIZE && readUInt32(command, 0) == ECHO_OPCODE) {
            val warmupReps = command[4].toInt() and 0xFF
            val targetReps = command[5].toInt() and 0xFF
            return PhantomWorkoutProgram(
                warmupReps = warmupReps,
                workingReps = targetReps.takeUnless { it == UNLIMITED_REPS },
                weightPerCableKg = config.defaultEchoLoadKg,
            )
        }
        return null
    }

    private fun startMetricsLocked(activeWorkout: Boolean, expectedGeneration: Long) {
        if (!isConnectedLocked(expectedGeneration)) return
        metricGeneration += 1
        val expectedMetricGeneration = metricGeneration
        metricsJob?.cancel()
        val configuredLoad = workoutParams?.weightPerCableKg ?: currentProgram?.weightPerCableKg ?: 7.5f
        metricsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sample = 0
            while (isActive) {
                val phase = (sample % 40) / 40.0
                val wave = sin(phase * 2.0 * PI).toFloat()
                val metric = WorkoutMetric(
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    loadA = ((if (activeWorkout) configuredLoad.coerceAtLeast(2f) else 1.5f) + wave.coerceAtLeast(0f)) * config.loadScale,
                    loadB = ((if (activeWorkout) configuredLoad.coerceAtLeast(2f) else 1.5f) + (-wave).coerceAtLeast(0f)) * config.loadScale,
                    positionA = wave * 650f * config.positionScale,
                    positionB = wave * 640f * config.positionScale,
                    ticks = ticks++,
                    velocityA = wave * 250.0 * config.velocityScale,
                    velocityB = wave * 245.0 * config.velocityScale,
                )
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(expectedGeneration) || metricGeneration != expectedMetricGeneration) {
                        false
                    } else {
                        _metricsFlow.tryEmit(metric)
                        isConnectedLocked(expectedGeneration) && metricGeneration == expectedMetricGeneration
                    }
                }
                if (!published) break
                sample += 1
                delay(if (activeWorkout) ACTIVE_METRIC_DELAY_MS else IDLE_METRIC_DELAY_MS)
            }
        }
    }

    private fun startHeuristicLocked(activeWorkout: Boolean, expectedGeneration: Long) {
        if (!isConnectedLocked(expectedGeneration)) return
        heuristicGeneration += 1
        val expectedHeuristicGeneration = heuristicGeneration
        heuristicJob?.cancel()
        publishHeuristicLocked(activeWorkout)
        if (!isConnectedLocked(expectedGeneration) || heuristicGeneration != expectedHeuristicGeneration) return
        heuristicJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(if (activeWorkout) ACTIVE_HEURISTIC_DELAY_MS else IDLE_HEURISTIC_DELAY_MS)
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(expectedGeneration) || heuristicGeneration != expectedHeuristicGeneration) {
                        false
                    } else {
                        publishHeuristicLocked(activeWorkout)
                        isConnectedLocked(expectedGeneration) && heuristicGeneration == expectedHeuristicGeneration
                    }
                }
                if (!published) break
            }
        }
    }

    private fun publishHeuristicLocked(activeWorkout: Boolean) {
        val load = (if (activeWorkout) {
            (workoutParams?.weightPerCableKg ?: currentProgram?.weightPerCableKg ?: 7.5f).coerceAtLeast(2f)
        } else 1.5f) * config.loadScale
        _heuristicData.value = HeuristicStatistics(
            concentric = HeuristicPhaseStatistics(load, load + 1.5f, 0.42f, 0.70f, 85f, 130f),
            eccentric = HeuristicPhaseStatistics(load * 0.9f, load + 1f, 0.38f, 0.62f, 72f, 110f),
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun startRepSimulationLocked(expectedGeneration: Long) {
        val program = currentProgram ?: return
        if (!isConnectedLocked(expectedGeneration) || fixedSetCompleted) return
        repGeneration += 1
        val expectedRepGeneration = repGeneration
        repJob?.cancel()
        repJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(config.repDelayMs)
                val shouldContinue = lifecycleLock.withLock {
                    if (!isConnectedLocked(expectedGeneration) || repGeneration != expectedRepGeneration || workoutParams == null) {
                        false
                    } else {
                        repCount += 1
                        topCounter += 1
                        completeCounter += 1
                        val warmupDone = minOf(repCount, program.warmupReps)
                        val workingDone = (repCount - program.warmupReps).coerceAtLeast(0)
                        val boundedWorking = program.workingReps?.let(workingDone::coerceAtMost) ?: workingDone
                        val reached = config.autoCompleteFixedRepSets &&
                            program.workingReps != null && boundedWorking >= program.workingReps
                        if (reached) fixedSetCompleted = true
                        val rawData = ByteArray(24).also { bytes ->
                            writeUInt32(bytes, 0, topCounter)
                            writeUInt32(bytes, 4, completeCounter)
                            writeFloat(bytes, 8, RANGE_TOP)
                            writeFloat(bytes, 12, RANGE_BOTTOM)
                            writeUInt16(bytes, 16, warmupDone)
                            writeUInt16(bytes, 18, program.warmupReps)
                            writeUInt16(bytes, 20, boundedWorking)
                            writeUInt16(bytes, 22, program.workingReps ?: 0)
                        }
                        _repEvents.tryEmit(
                            RepNotification(
                                topCounter = topCounter,
                                completeCounter = completeCounter,
                                repsRomCount = warmupDone,
                                repsRomTotal = program.warmupReps,
                                repsSetCount = boundedWorking,
                                repsSetTotal = program.workingReps ?: 0,
                                rangeTop = RANGE_TOP,
                                rangeBottom = RANGE_BOTTOM,
                                rawData = rawData,
                                timestamp = Clock.System.now().toEpochMilliseconds(),
                            ),
                        )
                        if (!isConnectedLocked(expectedGeneration) ||
                            repGeneration != expectedRepGeneration ||
                            workoutParams == null
                        ) {
                            false
                        } else {
                            logRepo.info(
                                LogEventType.REP_RECEIVED,
                                PHANTOM_REP,
                                PHANTOM_DEVICE_NAME,
                                PHANTOM_DEVICE_ADDRESS,
                                "rep=$repCount; warmup=$warmupDone/${program.warmupReps}; working=$boundedWorking/${program.workingReps ?: 0}",
                            )
                            if (!isConnectedLocked(expectedGeneration) ||
                                repGeneration != expectedRepGeneration ||
                                workoutParams == null
                            ) {
                                false
                            } else {
                                !reached
                            }
                        }
                    }
                }
                if (!shouldContinue) break
            }
        }
    }

    private fun publishDiagnosticsLocked(expectedGeneration: Long) {
        if (isConnectedLocked(expectedGeneration)) {
            _diagnostics.value = DiagnosticPacket(
                runtimeSeconds = 0,
                faultWords = listOf(0, 0, 0, 0),
                temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                hasFaults = false,
                receivedAtMillis = Clock.System.now().toEpochMilliseconds(),
            )
            if (!isConnectedLocked(expectedGeneration)) return
            startDiagnosticsLocked(expectedGeneration)
        }
    }

    private fun startDiagnosticsLocked(expectedGeneration: Long) {
        if (!isConnectedLocked(expectedGeneration)) return
        diagnosticGeneration += 1
        val expectedDiagnosticGeneration = diagnosticGeneration
        diagnosticJob?.cancel()
        diagnosticJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var runtimeSeconds = 0L
            while (isActive) {
                delay(DIAGNOSTIC_DELAY_MS)
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(expectedGeneration) || diagnosticGeneration != expectedDiagnosticGeneration) {
                        false
                    } else {
                        runtimeSeconds += DIAGNOSTIC_DELAY_MS / 1000L
                        _diagnostics.value = DiagnosticPacket(
                            runtimeSeconds = runtimeSeconds,
                            faultWords = listOf(0, 0, 0, 0),
                            temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                            hasFaults = false,
                            receivedAtMillis = Clock.System.now().toEpochMilliseconds(),
                        )
                        isConnectedLocked(expectedGeneration) && diagnosticGeneration == expectedDiagnosticGeneration
                    }
                }
                if (!published) break
            }
        }
    }

    private fun startHeartbeatLocked(expectedGeneration: Long) {
        if (!isConnectedLocked(expectedGeneration)) return
        heartbeatGeneration += 1
        val expectedHeartbeatGeneration = heartbeatGeneration
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(expectedGeneration) || heartbeatGeneration != expectedHeartbeatGeneration) {
                        false
                    } else {
                        logRepo.info(LogEventType.HEARTBEAT, "Phantom heartbeat", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                        isConnectedLocked(expectedGeneration) && heartbeatGeneration == expectedHeartbeatGeneration
                    }
                }
                if (!published) break
                delay(HEARTBEAT_DELAY_MS)
            }
        }
    }

    private fun cancelPollingLocked() {
        metricGeneration += 1
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

    private fun teardownLocked(markTerminal: Boolean) {
        generation += 1
        val expectedGeneration = generation
        cancelPollingLocked()
        workoutParams = null
        currentProgram = null
        repCount = 0
        topCounter = 0
        completeCounter = 0
        fixedSetCompleted = false
        _handleDetection.value = HandleDetection()
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _handleState.value = HandleState.WaitingForRest
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _heuristicData.value = null
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _diagnostics.value = null
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _scannedDevices.value = emptyList()
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _discoModeActive.value = false
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        _connectionState.value = ConnectionState.Disconnected
        if (!ownsTeardownLocked(expectedGeneration, markTerminal)) return
        if (markTerminal) logRepo.info(LogEventType.DISCONNECT, "Shutting down phantom Vitruvian")
    }

    private fun ownsTeardownLocked(expectedGeneration: Long, markTerminal: Boolean): Boolean =
        generation == expectedGeneration && (if (markTerminal) terminal else !terminal)

    private fun cancelAttemptIfOwner(expectedGeneration: Long) {
        lifecycleLock.withLock {
            if (ownsGenerationLocked(expectedGeneration) &&
                (_connectionState.value == ConnectionState.Scanning ||
                    _connectionState.value == ConnectionState.Connecting)
            ) {
                teardownLocked(markTerminal = false)
            }
        }
    }

    private fun ownsStateLocked(expectedGeneration: Long, expectedState: ConnectionState): Boolean =
        !terminal && generation == expectedGeneration && _connectionState.value == expectedState

    private fun ownsGenerationLocked(expectedGeneration: Long): Boolean =
        !terminal && generation == expectedGeneration

    private inline fun logIfOwnedLocked(expectedGeneration: Long, log: () -> Unit): Boolean {
        if (!isCurrentLocked(expectedGeneration)) return false
        log()
        return isCurrentLocked(expectedGeneration)
    }

    private inline fun logIfGenerationOwnedLocked(expectedGeneration: Long, log: () -> Unit): Boolean {
        if (!ownsGenerationLocked(expectedGeneration)) return false
        log()
        return ownsGenerationLocked(expectedGeneration)
    }

    private fun lifecycleFailure(): Result<Unit> = lifecycleLock.withLock { lifecycleFailureLocked() }

    private fun lifecycleFailureLocked(): Result<Unit> =
        Result.failure(if (terminal) shutdownError() else invalidatedError())

    private fun isConnectedLocked(expectedGeneration: Long): Boolean =
        !terminal && generation == expectedGeneration && _connectionState.value is ConnectionState.Connected

    private fun isCurrentLocked(expectedGeneration: Long): Boolean =
        !terminal && generation == expectedGeneration &&
            (_connectionState.value == ConnectionState.Scanning ||
                _connectionState.value == ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Connected)

    private fun shutdownError() = IllegalStateException("Phantom repository is shut down")
    private fun invalidatedError() = IllegalStateException("Phantom lifecycle attempt invalidated")

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readFloat(bytes: ByteArray, offset: Int): Float = Float.fromBits(readUInt32(bytes, offset))

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeFloat(bytes: ByteArray, offset: Int, value: Float) = writeUInt32(bytes, offset, value.toBits())

    companion object {
        const val PHANTOM_DEVICE_NAME = "Vee_PhantomSimulator"
        const val PHANTOM_DEVICE_ADDRESS = "PH:AN:TO:MS:BX:01"
        const val PHANTOM_REP = "PHANTOM_REP"
        private const val REGULAR_OPCODE = 0x00000004
        private const val ECHO_OPCODE = 0x0000004E
        private const val REGULAR_PACKET_SIZE = 96
        private const val ECHO_PACKET_SIZE = 32
        private const val REGULAR_WEIGHT_OFFSET = 0x58
        private const val UNLIMITED_REPS = 0xFF
        private const val RANGE_TOP = 650f
        private const val RANGE_BOTTOM = 0f
        private const val SCAN_DELAY_MS = 150L
        private const val CONNECT_DELAY_MS = 250L
        private const val ACTIVE_METRIC_DELAY_MS = 250L
        private const val IDLE_METRIC_DELAY_MS = 750L
        private const val ACTIVE_HEURISTIC_DELAY_MS = 250L
        private const val IDLE_HEURISTIC_DELAY_MS = 750L
        private const val DIAGNOSTIC_DELAY_MS = 2_000L
        private const val HEARTBEAT_DELAY_MS = 2_000L
    }
}
