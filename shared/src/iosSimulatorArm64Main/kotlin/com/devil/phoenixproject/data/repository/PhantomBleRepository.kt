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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
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
import kotlinx.coroutines.currentCoroutineContext
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
 *
 * Lifecycle ownership is deliberately token based. A token is never reused: an operation, normal
 * cleanup, and terminal cleanup can therefore invalidate one another without predicting or
 * comparing generation values across public calls.
 */
class PhantomBleRepository(
    private val logRepo: ConnectionLogRepository = ConnectionLogRepository.instance,
    initialConfig: PhantomBleConfig = PhantomBleConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BleRepository {
    private enum class LifecyclePhase {
        ACTIVE,
        CLEANING,
        TERMINAL,
    }

    /** Identity-only token. Equality must remain reference equality. */
    private class LifecycleToken

    private val lifecycleLock = reentrantLock()
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + dispatcher)
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

    /* All fields below are read and written while lifecycleLock is held. */
    private var config = initialConfig
    private var lifecyclePhase = LifecyclePhase.ACTIVE
    private var lifecycleOwner: LifecycleToken? = null
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
    private var repDeliveryLosses = 0L
    private var repNoSubscriberLosses = 0L
    private var repOverflowLosses = 0L

    override suspend fun startScanning(): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        val owner = lifecycleLock.withLock { reserveOperationLocked() }
            ?: return lifecycleFailure()
        return try {
            scanPhase(owner, callerJob)
        } catch (cancellation: CancellationException) {
            cleanupOperationIfOwner(owner, reason = "scan_cancelled")
            throw cancellation
        }
    }

    override suspend fun stopScanning() {
        val cleanup = lifecycleLock.withLock {
            if (!isOperationOwnerLocked() ||
                (_connectionState.value != ConnectionState.Scanning &&
                    _connectionState.value != ConnectionState.Connecting)
            ) {
                null
            } else {
                claimNormalCleanupLocked()
            }
        } ?: return
        lifecycleLock.withLock {
            finishCleanupLocked(cleanup, reason = "scan_stopped")
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        val owner = lifecycleLock.withLock { reserveOperationLocked() }
            ?: return lifecycleFailure()
        return try {
            connectPhase(owner, device, callerJob)
        } catch (cancellation: CancellationException) {
            cleanupOperationIfOwner(owner, reason = "connect_cancelled")
            throw cancellation
        }
    }

    override suspend fun cancelConnection() {
        val cleanup = lifecycleLock.withLock {
            if (!isOperationOwnerLocked() || _connectionState.value != ConnectionState.Connecting) {
                null
            } else {
                claimNormalCleanupLocked()
            }
        } ?: return
        lifecycleLock.withLock {
            finishCleanupLocked(cleanup, reason = "connection_cancelled")
        }
    }

    override suspend fun disconnect() {
        val cleanup = lifecycleLock.withLock { claimNormalCleanupLocked() } ?: return
        lifecycleLock.withLock {
            finishCleanupLocked(cleanup, reason = "disconnect")
        }
    }

    override suspend fun shutdown() {
        val terminalOwner = lifecycleLock.withLock { claimTerminalCleanupLocked() } ?: return
        lifecycleLock.withLock {
            finishCleanupLocked(terminalOwner, reason = "shutdown", terminal = true)
        }
        repositoryJob.cancel()
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        if (timeoutMs <= 0L) return Result.failure(IllegalArgumentException("timeoutMs must be > 0"))
        val callerJob = currentCoroutineContext()[Job]
        val owner = lifecycleLock.withLock { reserveOperationLocked() }
            ?: return lifecycleFailure()
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                val scanResult = scanPhase(owner, callerJob)
                if (scanResult.isFailure) {
                    scanResult
                } else {
                    val scannedDevice = lifecycleLock.withLock {
                        if (ownsOperationLocked(owner) && _connectionState.value == ConnectionState.Scanning) {
                            _scannedDevices.value.firstOrNull()
                        } else {
                            null
                        }
                    }
                    if (scannedDevice == null) {
                        lifecycleFailure()
                    } else {
                        connectPhase(owner, scannedDevice, callerJob)
                    }
                }
            }
            if (result != null) return result

            lifecycleLock.withLock {
                ensureCallerActiveLocked(owner, callerJob)
                if (!ownsOperationLocked(owner)) {
                    lifecycleFailureLocked()
                } else {
                    val cleanup = claimNormalCleanupLocked()
                    if (cleanup == null) {
                        lifecycleFailureLocked()
                    } else {
                        val cleanupResult = finishCleanupLocked(
                            cleanup,
                            reason = "connection_timeout",
                            reconnectionReason = "connection_timeout",
                            timeoutMs = timeoutMs,
                            callerJob = callerJob,
                        )
                        if (cleanupResult.isFailure) {
                            cleanupResult
                        } else {
                            throwIfCallerCancelled(callerJob)
                            Result.failure(IllegalStateException("Phantom scan and connect timed out after ${timeoutMs}ms"))
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            cleanupOperationIfOwner(owner, reason = "scan_connect_cancelled")
            throw cancellation
        }
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            lastColorSchemeIndex = schemeIndex
            acceptCommandLocked(owner, "Phantom color scheme set", "scheme=$schemeIndex", callerJob)
        }
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            if (_connectionState.value !is ConnectionState.Connected) {
                return@withLock Result.failure(IllegalStateException("Phantom workout command requires an active connection"))
            }
            decodeProgram(command)?.let { currentProgram = it }
            acceptCommandLocked(
                owner,
                "Phantom received raw workout command",
                command.joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') },
                callerJob,
            )
        }
    }

    override suspend fun sendInitSequence(): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            acceptCommandLocked(owner, "Phantom init sequence accepted", callerJob = callerJob)
        }
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _discoModeActive.value = false }) {
                return@withLock lifecycleFailureLocked()
            }
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
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _handleState.value = HandleState.Grabbed }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!startMetricsLocked(activeWorkout = true, owner)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!startHeuristicLocked(activeWorkout = true, owner, callerJob)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!startRepSimulationLocked(owner)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!logIfOwnedLocked(owner, callerJob = callerJob) {
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
    }

    override suspend fun stopWorkout(): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            cancelPollingLocked()
            workoutParams = null
            currentProgram = null
            fixedSetCompleted = false
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _handleState.value = HandleState.Released }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!logIfOwnedLocked(owner, callerJob = callerJob) {
                    logRepo.info(LogEventType.COMMAND_SENT, "Phantom workout stopped", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                }
            ) return@withLock lifecycleFailureLocked()
            Result.success(Unit)
        }
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        val callerJob = currentCoroutineContext()[Job]
        return lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock lifecycleFailureLocked()
            acceptCommandLocked(owner, "Phantom stop command accepted", callerJob = callerJob)
        }
    }

    override fun enableHandleDetection(enabled: Boolean) {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            if (!publishIfOwnedLocked(owner) { _handleDetection.value = HandleDetection(enabled, enabled) }) return@withLock
            publishIfOwnedLocked(owner) {
                _handleState.value = if (enabled) HandleState.WaitingForRest else HandleState.Released
            }
        }
    }

    override fun resetHandleState() {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            publishIfOwnedLocked(owner) { _handleState.value = HandleState.WaitingForRest }
        }
    }

    override fun enableJustLiftWaitingMode() {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            publishIfOwnedLocked(owner) { _handleState.value = HandleState.WaitingForRest }
        }
    }

    override fun restartMonitorPolling() {
        lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock
            startMetricsLocked(workoutParams != null, owner)
        }
    }

    override fun startActiveWorkoutPolling() {
        lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock
            if (fixedSetCompleted) return@withLock
            if (!publishIfOwnedLocked(owner) { _handleState.value = HandleState.Grabbed }) return@withLock
            if (!startMetricsLocked(activeWorkout = true, owner)) return@withLock
            if (!startHeuristicLocked(activeWorkout = true, owner)) return@withLock
            if (workoutParams != null) startRepSimulationLocked(owner)
        }
    }

    override fun stopPolling() {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            cancelPollingLocked()
            logIfOwnedLocked(owner) {
                logRepo.info(LogEventType.HEARTBEAT, "Phantom polling stopped")
            }
        }
    }

    override fun stopMonitorPollingOnly() {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            metricGeneration += 1
            metricsJob?.cancel()
            metricsJob = null
            logIfOwnedLocked(owner) {
                logRepo.info(LogEventType.HEARTBEAT, "Phantom monitor polling stopped; diagnostics kept warm")
            }
        }
    }

    override fun restartDiagnosticPolling() {
        lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock
            if (!startDiagnosticsLocked(owner)) return@withLock
            startHeartbeatLocked(owner)
        }
    }

    override fun startDiscoMode() {
        lifecycleLock.withLock {
            val owner = connectedOwnerLocked() ?: return@withLock
            if (workoutParams != null) return@withLock
            if (!publishIfOwnedLocked(owner) { _discoModeActive.value = true }) return@withLock
            logIfOwnedLocked(owner) {
                logRepo.info(LogEventType.COMMAND_SENT, "Phantom disco mode started", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
            }
        }
    }

    override fun stopDiscoMode() {
        lifecycleLock.withLock {
            val owner = activeOwnerLocked() ?: return@withLock
            if (!_discoModeActive.value) return@withLock
            if (!publishIfOwnedLocked(owner) { _discoModeActive.value = false }) return@withLock
            logIfOwnedLocked(owner) {
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
            val owner = activeOwnerLocked() ?: return@withLock
            if (ownsOperationLocked(owner)) lastColorSchemeIndex = index
        }
    }

    private suspend fun scanPhase(owner: LifecycleToken, callerJob: Job?): Result<Unit> {
        val prepared = lifecycleLock.withLock {
            if (!ownsOperationLocked(owner)) return@withLock false
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _connectionState.value = ConnectionState.Scanning }) return@withLock false
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _scannedDevices.value = emptyList() }) return@withLock false
            logIfOwnedLocked(owner, callerJob = callerJob) {
                logRepo.info(LogEventType.SCAN_START, "Starting phantom Vitruvian scan")
            }
        }
        if (!prepared) return lifecycleFailure()

        delay(SCAN_DELAY_MS)
        return lifecycleLock.withLock {
            if (!ownsOperationLocked(owner) || _connectionState.value != ConnectionState.Scanning) {
                return@withLock lifecycleFailureLocked()
            }
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _scannedDevices.value = listOf(device) }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!logIfOwnedLocked(owner, callerJob = callerJob) {
                    logRepo.info(
                        LogEventType.DEVICE_FOUND,
                        "Found phantom Vitruvian device",
                        device.name,
                        device.address,
                        "RSSI ${device.rssi}; no Bluetooth hardware used",
                    )
                }
            ) return@withLock lifecycleFailureLocked()
            Result.success(Unit)
        }
    }

    private suspend fun connectPhase(owner: LifecycleToken, device: ScannedDevice, callerJob: Job?): Result<Unit> {
        val prepared = lifecycleLock.withLock {
            if (!ownsOperationLocked(owner)) return@withLock false
            cancelPollingLocked()
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _connectionState.value = ConnectionState.Connecting }) return@withLock false
            logIfOwnedLocked(owner, callerJob = callerJob) {
                logRepo.info(LogEventType.CONNECT_START, "Connecting to phantom Vitruvian", device.name, device.address)
            }
        }
        if (!prepared) return lifecycleFailure()

        delay(CONNECT_DELAY_MS)
        return lifecycleLock.withLock {
            if (!ownsOperationLocked(owner) || _connectionState.value != ConnectionState.Connecting) {
                return@withLock lifecycleFailureLocked()
            }
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _connectionState.value = ConnectionState.Connected(device.name, device.address) }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _handleDetection.value = HandleDetection(leftDetected = true, rightDetected = true) }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!publishIfOwnedLocked(owner, callerJob = callerJob) { _handleState.value = HandleState.Released }) {
                return@withLock lifecycleFailureLocked()
            }
            if (!publishDiagnosticsLocked(owner, callerJob)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!startMetricsLocked(activeWorkout = false, owner)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!startHeuristicLocked(activeWorkout = false, owner, callerJob)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!startHeartbeatLocked(owner)) return@withLock lifecycleFailureLocked()
            ensureCallerActiveLocked(owner, callerJob)
            if (!logIfOwnedLocked(owner, callerJob = callerJob) {
                    logRepo.info(LogEventType.SERVICE_DISCOVERED, "Phantom service map ready", device.name, device.address)
                }
            ) return@withLock lifecycleFailureLocked()
            if (!logIfOwnedLocked(owner, callerJob = callerJob) {
                    logRepo.info(LogEventType.CONNECT_SUCCESS, "Connected to phantom Vitruvian", device.name, device.address)
                }
            ) return@withLock lifecycleFailureLocked()
            Result.success(Unit)
        }
    }

    private fun reserveOperationLocked(): LifecycleToken? {
        if (lifecyclePhase != LifecyclePhase.ACTIVE) return null
        val owner = LifecycleToken()
        lifecycleOwner = owner
        cancelPollingLocked()
        return owner
    }

    private fun activeOwnerLocked(): LifecycleToken? = lifecycleOwner?.takeIf { ownsOperationLocked(it) }

    private fun connectedOwnerLocked(): LifecycleToken? = activeOwnerLocked()?.takeIf {
        _connectionState.value is ConnectionState.Connected
    }

    private fun ownsOperationLocked(owner: LifecycleToken): Boolean =
        lifecyclePhase == LifecyclePhase.ACTIVE && lifecycleOwner === owner

    private fun isOperationOwnerLocked(): Boolean =
        lifecyclePhase == LifecyclePhase.ACTIVE && lifecycleOwner != null

    private fun ownsCleanupLocked(owner: LifecycleToken): Boolean =
        lifecyclePhase == LifecyclePhase.CLEANING && lifecycleOwner === owner

    private fun ownsTerminalLocked(owner: LifecycleToken): Boolean =
        lifecyclePhase == LifecyclePhase.TERMINAL && lifecycleOwner === owner

    private fun claimNormalCleanupLocked(): LifecycleToken? {
        if (lifecyclePhase == LifecyclePhase.TERMINAL || lifecyclePhase == LifecyclePhase.CLEANING) return null
        val cleanup = LifecycleToken()
        lifecyclePhase = LifecyclePhase.CLEANING
        lifecycleOwner = cleanup
        cancelPollingLocked()
        workoutParams = null
        currentProgram = null
        repCount = 0
        topCounter = 0
        completeCounter = 0
        fixedSetCompleted = false
        return cleanup
    }

    private fun claimTerminalCleanupLocked(): LifecycleToken? {
        if (lifecyclePhase == LifecyclePhase.TERMINAL) return null
        val terminalOwner = LifecycleToken()
        lifecyclePhase = LifecyclePhase.TERMINAL
        lifecycleOwner = terminalOwner
        cancelPollingLocked()
        workoutParams = null
        currentProgram = null
        repCount = 0
        topCounter = 0
        completeCounter = 0
        fixedSetCompleted = false
        return terminalOwner
    }

    private fun finishCleanupLocked(
        owner: LifecycleToken,
        reason: String,
        terminal: Boolean = false,
        reconnectionReason: String? = null,
        timeoutMs: Long? = null,
        callerJob: Job? = null,
    ): Result<Unit> {
        val phase = if (terminal) LifecyclePhase.TERMINAL else LifecyclePhase.CLEANING
        if (!ownsLocked(owner, phase)) return lifecycleFailureLocked()
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _handleDetection.value = HandleDetection() }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _handleState.value = HandleState.WaitingForRest }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _heuristicData.value = null }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _diagnostics.value = null }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _scannedDevices.value = emptyList() }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _discoModeActive.value = false }) {
            return lifecycleFailureLocked()
        }
        if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _connectionState.value = ConnectionState.Disconnected }) {
            return lifecycleFailureLocked()
        }
        if (reconnectionReason != null) {
            val request = ReconnectionRequest(
                deviceName = PHANTOM_DEVICE_NAME,
                deviceAddress = PHANTOM_DEVICE_ADDRESS,
                reason = reconnectionReason,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
            if (!publishIfOwnedLocked(owner, phase, callerJob = callerJob) { _reconnectionRequested.tryEmit(request) }) {
                return lifecycleFailureLocked()
            }
            if (!logIfOwnedLocked(owner, phase, callerJob = callerJob) {
                    logRepo.error(
                        LogEventType.ERROR,
                        "Phantom scan and connect timed out",
                        PHANTOM_DEVICE_NAME,
                        PHANTOM_DEVICE_ADDRESS,
                        "timeoutMs=$timeoutMs",
                    )
                }
            ) return lifecycleFailureLocked()
        }
        if (!logIfOwnedLocked(owner, phase, callerJob = callerJob) {
                logRepo.info(
                    LogEventType.DISCONNECT,
                    if (terminal) "Shutting down phantom Vitruvian" else "Disconnected phantom Vitruvian",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                    reason,
                )
            }
        ) return lifecycleFailureLocked()

        if (terminal) {
            if (!ownsTerminalLocked(owner)) return lifecycleFailureLocked()
        } else {
            if (!ownsCleanupLocked(owner)) return lifecycleFailureLocked()
            lifecyclePhase = LifecyclePhase.ACTIVE
            lifecycleOwner = null
        }
        return Result.success(Unit)
    }

    private fun cleanupOperationIfOwner(owner: LifecycleToken, reason: String): Boolean = lifecycleLock.withLock {
        if (!ownsOperationLocked(owner)) return@withLock false
        val cleanup = claimNormalCleanupLocked() ?: return@withLock false
        finishCleanupLocked(cleanup, reason)
        true
    }

    private fun acceptCommandLocked(
        owner: LifecycleToken,
        message: String,
        details: String? = null,
        callerJob: Job? = null,
    ): Result<Unit> {
        if (!logIfOwnedLocked(owner, callerJob = callerJob) {
                logRepo.info(LogEventType.COMMAND_SENT, message, PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS, details)
            }
        ) return lifecycleFailureLocked()
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

    private fun startMetricsLocked(activeWorkout: Boolean, owner: LifecycleToken): Boolean {
        if (!isConnectedLocked(owner)) return false
        metricGeneration += 1
        val expectedGeneration = metricGeneration
        metricsJob?.cancel()
        val configuredLoad = workoutParams?.weightPerCableKg ?: currentProgram?.weightPerCableKg ?: 7.5f
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
                    if (!isConnectedLocked(owner) || metricGeneration != expectedGeneration) {
                        false
                    } else {
                        var emitted = false
                        if (!publishIfOwnedLocked(owner) { emitted = _metricsFlow.tryEmit(metric) }) false
                        else emitted && isConnectedLocked(owner) && metricGeneration == expectedGeneration
                    }
                }
                if (!published) break
                sample += 1
                delay(if (activeWorkout) ACTIVE_METRIC_DELAY_MS else IDLE_METRIC_DELAY_MS)
            }
        }
        if (!isConnectedLocked(owner) || metricGeneration != expectedGeneration) {
            job.cancel()
            return false
        }
        metricsJob = job
        return true
    }

    private fun startHeuristicLocked(activeWorkout: Boolean, owner: LifecycleToken, callerJob: Job? = null): Boolean {
        if (!isConnectedLocked(owner)) return false
        heuristicGeneration += 1
        val expectedGeneration = heuristicGeneration
        heuristicJob?.cancel()
        if (!publishHeuristicLocked(activeWorkout, owner, callerJob)) return false
        ensureCallerActiveLocked(owner, callerJob)
        if (!isConnectedLocked(owner) || heuristicGeneration != expectedGeneration) return false
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(if (activeWorkout) ACTIVE_HEURISTIC_DELAY_MS else IDLE_HEURISTIC_DELAY_MS)
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(owner) || heuristicGeneration != expectedGeneration) {
                        false
                    } else {
                        publishHeuristicLocked(activeWorkout, owner) &&
                            isConnectedLocked(owner) && heuristicGeneration == expectedGeneration
                    }
                }
                if (!published) break
            }
        }
        if (!isConnectedLocked(owner) || heuristicGeneration != expectedGeneration) {
            job.cancel()
            return false
        }
        heuristicJob = job
        return true
    }

    private fun publishHeuristicLocked(activeWorkout: Boolean, owner: LifecycleToken, callerJob: Job? = null): Boolean {
        val load = (if (activeWorkout) {
            (workoutParams?.weightPerCableKg ?: currentProgram?.weightPerCableKg ?: 7.5f).coerceAtLeast(2f)
        } else 1.5f) * config.loadScale
        return publishIfOwnedLocked(owner, callerJob = callerJob) {
            _heuristicData.value = HeuristicStatistics(
                concentric = HeuristicPhaseStatistics(load, load + 1.5f, 0.42f, 0.70f, 85f, 130f),
                eccentric = HeuristicPhaseStatistics(load * 0.9f, load + 1f, 0.38f, 0.62f, 72f, 110f),
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    private fun startRepSimulationLocked(owner: LifecycleToken): Boolean {
        val program = currentProgram ?: return false
        if (!isConnectedLocked(owner) || fixedSetCompleted) return false
        repGeneration += 1
        val expectedGeneration = repGeneration
        repJob?.cancel()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(config.repDelayMs)
                val shouldContinue = lifecycleLock.withLock {
                    if (!isConnectedLocked(owner) || repGeneration != expectedGeneration || workoutParams == null) {
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
                        val notification = RepNotification(
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
                        )
                        if (!emitRepAndLogLocked(owner, notification, repCount, warmupDone, boundedWorking)) {
                            false
                        } else {
                            !reached
                        }
                    }
                }
                if (!shouldContinue) break
            }
        }
        if (!isConnectedLocked(owner) || repGeneration != expectedGeneration) {
            job.cancel()
            return false
        }
        repJob = job
        return true
    }

    private fun emitRepAndLogLocked(
        owner: LifecycleToken,
        notification: RepNotification,
        repNumber: Int,
        warmupDone: Int,
        workingDone: Int,
    ): Boolean {
        if (!isConnectedLocked(owner)) return false
        val hadSubscriber = _repEvents.subscriptionCount.value > 0
        var delivered = false
        if (!publishIfOwnedLocked(owner) { delivered = _repEvents.tryEmit(notification) }) return false
        if (!hadSubscriber || !delivered) {
            repDeliveryLosses += 1
            if (!hadSubscriber) repNoSubscriberLosses += 1 else repOverflowLosses += 1
            return logIfOwnedLocked(owner) {
                logRepo.warning(
                    LogEventType.REP_RECEIVED,
                    "Phantom rep delivery lost",
                    PHANTOM_DEVICE_NAME,
                    PHANTOM_DEVICE_ADDRESS,
                    "reason=${if (hadSubscriber) "overflow" else "no_subscriber"}; " +
                        "losses=$repDeliveryLosses; rep=$repNumber; warmup=$warmupDone; working=$workingDone",
                )
            }
        }
        return logIfOwnedLocked(owner) {
            logRepo.info(
                LogEventType.REP_RECEIVED,
                PHANTOM_REP,
                PHANTOM_DEVICE_NAME,
                PHANTOM_DEVICE_ADDRESS,
                "rep=$repNumber; warmup=$warmupDone/${currentProgram?.warmupReps ?: 0}; working=$workingDone/${currentProgram?.workingReps ?: 0}",
            )
        }
    }

    private fun publishDiagnosticsLocked(owner: LifecycleToken, callerJob: Job? = null): Boolean {
        if (!isConnectedLocked(owner)) return false
        if (!publishIfOwnedLocked(owner, callerJob = callerJob) {
                _diagnostics.value = DiagnosticPacket(
                    runtimeSeconds = 0,
                    faultWords = listOf(0, 0, 0, 0),
                    temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                    hasFaults = false,
                    receivedAtMillis = Clock.System.now().toEpochMilliseconds(),
                )
            }
        ) return false
        ensureCallerActiveLocked(owner, callerJob)
        return startDiagnosticsLocked(owner)
    }

    private fun startDiagnosticsLocked(owner: LifecycleToken): Boolean {
        if (!isConnectedLocked(owner)) return false
        diagnosticGeneration += 1
        val expectedGeneration = diagnosticGeneration
        diagnosticJob?.cancel()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var runtimeSeconds = 0L
            while (isActive) {
                delay(DIAGNOSTIC_DELAY_MS)
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(owner) || diagnosticGeneration != expectedGeneration) {
                        false
                    } else {
                        runtimeSeconds += DIAGNOSTIC_DELAY_MS / 1000L
                        if (!publishIfOwnedLocked(owner) {
                                _diagnostics.value = DiagnosticPacket(
                                    runtimeSeconds = runtimeSeconds,
                                    faultWords = listOf(0, 0, 0, 0),
                                    temperatures = listOf(34, 35, 34, 35, 36, 36, 35, 34),
                                    hasFaults = false,
                                    receivedAtMillis = Clock.System.now().toEpochMilliseconds(),
                                )
                            }
                        ) false else isConnectedLocked(owner) && diagnosticGeneration == expectedGeneration
                    }
                }
                if (!published) break
            }
        }
        if (!isConnectedLocked(owner) || diagnosticGeneration != expectedGeneration) {
            job.cancel()
            return false
        }
        diagnosticJob = job
        return true
    }

    private fun startHeartbeatLocked(owner: LifecycleToken): Boolean {
        if (!isConnectedLocked(owner)) return false
        heartbeatGeneration += 1
        val expectedGeneration = heartbeatGeneration
        heartbeatJob?.cancel()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                val published = lifecycleLock.withLock {
                    if (!isConnectedLocked(owner) || heartbeatGeneration != expectedGeneration) {
                        false
                    } else {
                        logIfOwnedLocked(owner) {
                            logRepo.info(LogEventType.HEARTBEAT, "Phantom heartbeat", PHANTOM_DEVICE_NAME, PHANTOM_DEVICE_ADDRESS)
                        } && isConnectedLocked(owner) && heartbeatGeneration == expectedGeneration
                    }
                }
                if (!published) break
                delay(HEARTBEAT_DELAY_MS)
            }
        }
        if (!isConnectedLocked(owner) || heartbeatGeneration != expectedGeneration) {
            job.cancel()
            return false
        }
        heartbeatJob = job
        return true
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

    private inline fun ownsLocked(owner: LifecycleToken, phase: LifecyclePhase): Boolean =
        lifecyclePhase == phase && lifecycleOwner === owner

    /** Every StateFlow/SharedFlow publication calls this pre/post ownership gate. */
    private inline fun publishIfOwnedLocked(
        owner: LifecycleToken,
        phase: LifecyclePhase = LifecyclePhase.ACTIVE,
        callerJob: Job? = null,
        callback: () -> Unit,
    ): Boolean {
        if (!ownsLocked(owner, phase)) return false
        if (callerJob?.isActive == false) abortCallerCancellationLocked(owner, callerJob)
        callback()
        if (callerJob?.isActive == false) abortCallerCancellationLocked(owner, callerJob)
        return ownsLocked(owner, phase)
    }

    private inline fun logIfOwnedLocked(
        owner: LifecycleToken,
        phase: LifecyclePhase = LifecyclePhase.ACTIVE,
        callerJob: Job? = null,
        log: () -> Unit,
    ): Boolean = publishIfOwnedLocked(owner, phase, callerJob, log)

    private fun ensureCallerActiveLocked(owner: LifecycleToken, callerJob: Job?) {
        if (callerJob?.isActive == false) abortCallerCancellationLocked(owner, callerJob)
    }

    private fun throwIfCallerCancelled(callerJob: Job?) {
        if (callerJob?.isActive == false) throw callerCancellationException(callerJob)
    }

    /**
     * A synchronous collector can cancel the suspend caller from inside a publication callback.
     * The callback has already run, so clean up only while this exact active owner still owns the
     * repository, then propagate cancellation instead of returning a successful Result.
     */
    private fun abortCallerCancellationLocked(owner: LifecycleToken, callerJob: Job?): Nothing {
        when {
            ownsOperationLocked(owner) -> {
                val cleanup = claimNormalCleanupLocked()
                if (cleanup != null) forceCleanupAfterCallerCancellationLocked(cleanup)
            }
            ownsCleanupLocked(owner) -> forceCleanupAfterCallerCancellationLocked(owner)
        }
        throw callerCancellationException(callerJob)
    }

    /** Restore the disconnected invariant without publishing timeout/reconnection events. */
    private fun forceCleanupAfterCallerCancellationLocked(owner: LifecycleToken) {
        if (!ownsCleanupLocked(owner)) return
        cancelPollingLocked()
        workoutParams = null
        currentProgram = null
        repCount = 0
        topCounter = 0
        completeCounter = 0
        fixedSetCompleted = false
        _handleDetection.value = HandleDetection()
        _handleState.value = HandleState.WaitingForRest
        _heuristicData.value = null
        _diagnostics.value = null
        _scannedDevices.value = emptyList()
        _discoModeActive.value = false
        _connectionState.value = ConnectionState.Disconnected
        if (ownsCleanupLocked(owner)) {
            lifecyclePhase = LifecyclePhase.ACTIVE
            lifecycleOwner = null
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun callerCancellationException(callerJob: Job?): CancellationException =
        callerJob?.getCancellationException()
            ?: CancellationException("Phantom caller operation cancelled")

    private fun isConnectedLocked(owner: LifecycleToken): Boolean =
        ownsOperationLocked(owner) && _connectionState.value is ConnectionState.Connected

    private fun lifecycleFailure(): Result<Unit> = lifecycleLock.withLock { lifecycleFailureLocked() }

    private fun lifecycleFailureLocked(): Result<Unit> =
        Result.failure(if (lifecyclePhase == LifecyclePhase.TERMINAL) shutdownError() else invalidatedError())

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
