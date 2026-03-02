package com.devil.phoenixproject.data.repository.simulator

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicPhaseStatistics
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.VitruvianModel
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.currentTimeMillis
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simulated BLE Repository for development and testing without hardware.
 *
 * This implementation simulates the Vitruvian machine's BLE behavior using
 * [WorkoutSimulationEngine] for physics-based position/load calculations.
 * It allows testing the full workout flow without a physical machine.
 *
 * Usage:
 * - Enable via settings or feature flag to switch from [KableBleRepository]
 * - Simulates scanning, connection, workout metrics, and rep detection
 * - Supports Just Lift mode with handle detection simulation
 *
 * @property config Simulation parameters for timing, device name, etc.
 */
class SimulatorBleRepository(
    private val config: SimulatorConfig = SimulatorConfig()
) : BleRepository {

    private val log = Logger.withTag("SimulatorBleRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = WorkoutSimulationEngine(config)

    // Virtual device for scanning
    private val simulatorDevice = ScannedDevice(
        name = config.deviceName,
        address = "SIM:00:00:00:00:01",
        rssi = -50
    )

    // Current workout parameters
    private var currentParams: WorkoutParameters? = null
    private var simulationJob: Job? = null
    private var handleDetectionJob: Job? = null
    private var handleDetectionEnabled = false

    // Rep tracking
    private var totalTopCounter = 0
    private var totalCompleteCounter = 0
    private var currentWarmupReps = 0
    private var currentWorkingReps = 0

    // ========== State Flows ==========

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _handleDetection = MutableStateFlow(HandleDetection())
    override val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

    private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
    override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _heuristicData = MutableStateFlow<HeuristicStatistics?>(null)
    override val heuristicData: StateFlow<HeuristicStatistics?> = _heuristicData.asStateFlow()

    private val _discoModeActive = MutableStateFlow(false)
    override val discoModeActive: StateFlow<Boolean> = _discoModeActive.asStateFlow()

    // ========== Shared Flows ==========

    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    private val _repEvents = MutableSharedFlow<RepNotification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()

    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()

    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()

    // ========== Scanning & Connection ==========

    override suspend fun startScanning(): Result<Unit> {
        log.i { "Starting simulated BLE scan" }
        _connectionState.value = ConnectionState.Scanning
        _scannedDevices.value = emptyList()

        // Simulate scan delay
        delay(500)

        // "Find" the simulator device
        _scannedDevices.value = listOf(simulatorDevice)
        log.i { "Found simulated device: ${simulatorDevice.name}" }

        return Result.success(Unit)
    }

    override suspend fun stopScanning() {
        log.i { "Stopping simulated BLE scan" }
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        log.i { "Connecting to simulated device: ${device.name}" }
        _connectionState.value = ConnectionState.Connecting

        // Simulate connection handshake delay
        delay(1000)

        _connectionState.value = ConnectionState.Connected(
            deviceName = device.name,
            deviceAddress = device.address,
            hardwareModel = VitruvianModel.TrainerPlus
        )
        log.i { "Connected to simulated device" }

        return Result.success(Unit)
    }

    override suspend fun cancelConnection() {
        log.i { "Cancelling simulated connection" }
        simulationJob?.cancel()
        simulationJob = null
        handleDetectionJob?.cancel()
        handleDetectionJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun disconnect() {
        log.i { "Disconnecting from simulated device" }
        stopWorkoutInternal()
        _connectionState.value = ConnectionState.Disconnected
        _scannedDevices.value = emptyList()
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        log.i { "Scan and connect (timeout: ${timeoutMs}ms)" }

        val scanResult = startScanning()
        if (scanResult.isFailure) {
            return scanResult
        }

        // Wait for device to appear
        delay(800)

        val devices = _scannedDevices.value
        if (devices.isEmpty()) {
            _connectionState.value = ConnectionState.Error("No simulator device found")
            return Result.failure(Exception("No simulator device found"))
        }

        return connect(devices.first())
    }

    // ========== Workout Control ==========

    override suspend fun sendInitSequence(): Result<Unit> {
        log.i { "Sending simulated init sequence" }
        // No-op for simulator - just log
        return Result.success(Unit)
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        log.i { "Starting simulated workout: mode=${params.programMode}, weight=${params.weightPerCableKg}kg, reps=${params.reps}" }

        currentParams = params
        totalTopCounter = 0
        totalCompleteCounter = 0
        currentWarmupReps = 0
        currentWorkingReps = 0

        // Start the physics engine
        engine.startWorkout(params.weightPerCableKg)

        // Start emitting metrics
        startMetricsEmission()

        // Set handle state to active since workout is running
        _handleState.value = HandleState.Grabbed

        return Result.success(Unit)
    }

    override suspend fun stopWorkout(): Result<Unit> {
        log.i { "Stopping simulated workout" }
        stopWorkoutInternal()
        return Result.success(Unit)
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        log.i { "Sending simulated stop command (keeping polling active)" }
        // Stop workout but don't stop polling - for Just Lift mode
        engine.stopWorkout()
        simulationJob?.cancel()
        simulationJob = null
        return Result.success(Unit)
    }

    private fun stopWorkoutInternal() {
        engine.stopWorkout()
        simulationJob?.cancel()
        simulationJob = null
        handleDetectionJob?.cancel()
        handleDetectionJob = null
        currentParams = null
        _handleState.value = HandleState.WaitingForRest
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        log.d { "Simulated command: ${command.joinToString(" ") { it.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase() }}" }
        // No-op for simulator - just log the command
        return Result.success(Unit)
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        log.i { "Setting simulated color scheme: $schemeIndex" }
        // No-op for simulator
        return Result.success(Unit)
    }

    // ========== Handle Detection ==========

    override fun enableHandleDetection(enabled: Boolean) {
        log.i { "Handle detection enabled: $enabled" }
        handleDetectionEnabled = enabled

        if (enabled) {
            // Start handle detection simulation
            handleDetectionJob?.cancel()
            handleDetectionJob = scope.launch {
                simulateHandleGrab()
            }
        } else {
            handleDetectionJob?.cancel()
            handleDetectionJob = null
            _handleState.value = HandleState.WaitingForRest
        }
    }

    private suspend fun simulateHandleGrab() {
        // Reset to waiting state
        _handleState.value = HandleState.WaitingForRest

        // Brief delay then transition to Released (handles at rest)
        delay(200)
        _handleState.value = HandleState.Released
        log.d { "Handle state: Released (armed for grab detection)" }

        // Wait for configured grab delay then simulate grab
        delay(config.grabDelayMs)

        if (handleDetectionEnabled) {
            _handleState.value = HandleState.Grabbed
            log.i { "Handle state: Grabbed (simulated)" }
            _handleDetection.value = HandleDetection(leftDetected = true, rightDetected = true)
        }
    }

    override fun resetHandleState() {
        log.d { "Resetting handle state" }
        _handleState.value = HandleState.WaitingForRest
        _handleDetection.value = HandleDetection()
    }

    override fun enableJustLiftWaitingMode() {
        log.i { "Enabling Just Lift waiting mode" }
        resetHandleState()
        // Re-arm for next set if handle detection is enabled
        if (handleDetectionEnabled) {
            handleDetectionJob?.cancel()
            handleDetectionJob = scope.launch {
                simulateHandleGrab()
            }
        }
    }

    // ========== Polling Control ==========

    override fun restartMonitorPolling() {
        log.d { "Restarting monitor polling (simulated)" }
        // No-op for simulator - metrics emission handles this
    }

    override fun startActiveWorkoutPolling() {
        log.d { "Starting active workout polling (simulated)" }
        _handleState.value = HandleState.Grabbed
    }

    override fun stopPolling() {
        log.d { "Stopping polling (simulated)" }
        simulationJob?.cancel()
        simulationJob = null
    }

    override fun stopMonitorPollingOnly() {
        log.d { "Stopping monitor polling only (simulated) - diagnostic polling + heartbeat continue" }
        // Issue #222: In real BLE, diagnostic polling at 500ms keeps the link warm during bodyweight.
        // In simulator, we just stop the simulation job (no actual BLE to keep warm).
        simulationJob?.cancel()
        simulationJob = null
    }

    override fun restartDiagnosticPolling() {
        log.d { "Restarting diagnostic polling (simulated) - Issue #222 v10" }
        // In simulator, nothing to restart - no actual BLE
    }

    // ========== Disco Mode ==========

    override fun startDiscoMode() {
        log.i { "Starting disco mode (simulated)" }
        _discoModeActive.value = true
        // In real implementation this cycles LED colors
    }

    override fun stopDiscoMode() {
        log.i { "Stopping disco mode (simulated)" }
        _discoModeActive.value = false
    }

    override fun setLastColorSchemeIndex(index: Int) {
        // No-op in simulator - no physical LEDs
    }

    // ========== Metrics Emission ==========

    private fun startMetricsEmission() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            log.i { "Starting metrics emission loop" }
            val params = currentParams ?: return@launch

            while (isActive) {
                val now = currentTimeMillis()

                // Calculate position using physics engine
                val (posA, posB) = engine.calculatePosition(now)
                val (loadA, loadB) = engine.calculateLoad()
                val velocity = engine.calculateVelocity(posA, now)

                // Emit metric with weight added to base load
                val metric = WorkoutMetric(
                    timestamp = now,
                    positionA = posA,
                    positionB = posB,
                    loadA = loadA,
                    loadB = loadB,
                    velocityA = velocity,
                    velocityB = velocity,
                    status = 0
                )
                _metricsFlow.emit(metric)

                // Check for rep completion
                engine.checkRepCompletion(posA)?.let { completion ->
                    when (completion) {
                        RepCompletionType.TOP_REACHED -> {
                            totalTopCounter++
                            log.d { "Top reached: topCounter=$totalTopCounter" }
                        }
                        RepCompletionType.BOTTOM_REACHED -> {
                            totalCompleteCounter++
                            log.d { "Bottom reached: completeCounter=$totalCompleteCounter" }
                            emitRepNotification(params)
                        }
                    }
                }

                // Emit heuristic data occasionally (every 250ms = 4Hz)
                if (now % 250 < 20) {
                    emitHeuristicData(velocity.toFloat(), loadA)
                }

                // ~50Hz update rate
                delay(20)
            }
        }
    }

    private suspend fun emitRepNotification(params: WorkoutParameters) {
        val (topCounter, completeCounter) = engine.getRepCounts()

        // Track warmup vs working reps
        val warmupTarget = params.warmupReps
        if (completeCounter <= warmupTarget) {
            currentWarmupReps = completeCounter
            currentWorkingReps = 0
        } else {
            currentWarmupReps = warmupTarget
            currentWorkingReps = completeCounter - warmupTarget
        }

        val notification = RepNotification(
            topCounter = topCounter,
            completeCounter = completeCounter,
            repsRomCount = currentWarmupReps,
            repsRomTotal = warmupTarget,  // Issue #210: Machine's warmup target
            repsSetCount = currentWorkingReps,
            repsSetTotal = params.reps,   // Issue #210: Machine's working target
            rangeTop = config.positionRange.endInclusive,
            rangeBottom = config.positionRange.start,
            rawData = byteArrayOf(), // No raw BLE data in simulator
            timestamp = currentTimeMillis(),
            isLegacyFormat = false
        )

        _repEvents.emit(notification)
        log.i { "Rep notification: warmup=$currentWarmupReps, working=$currentWorkingReps" }
    }

    private fun emitHeuristicData(velocity: Float, load: Float) {
        val phaseStats = HeuristicPhaseStatistics(
            kgAvg = load,
            kgMax = load * 1.1f,
            velAvg = velocity,
            velMax = velocity * 1.2f,
            wattAvg = load * kotlin.math.abs(velocity) / 1000f,
            wattMax = load * kotlin.math.abs(velocity) * 1.2f / 1000f
        )

        _heuristicData.value = HeuristicStatistics(
            concentric = phaseStats,
            eccentric = phaseStats.copy(velAvg = -velocity, velMax = -velocity * 1.2f),
            timestamp = currentTimeMillis()
        )
    }
}
