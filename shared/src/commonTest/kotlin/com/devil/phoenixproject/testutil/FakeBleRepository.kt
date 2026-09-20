package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.CommandLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Fake BLE repository for testing.
 * Provides controllable state and response simulation without real hardware.
 *
 * KD-9 / F-050: every frame handed to [sendWorkoutCommand] is decoded and checked against
 * [CommandLimits] for [model]. A command above this trainer's per-cable ceiling, or with a
 * per-rep progression beyond the bound, fails the test that produced it. That turns every
 * engine test into a machine-safety invariant check, so a start path that bypasses the
 * command-resolution clamp cannot pass the suite.
 */
class FakeBleRepository : BleRepository {

    /**
     * Trainer this fake pretends to be. Defaults to the widest hardware so existing tests
     * keep their headroom; tests that exercise the V-Form ceiling set it explicitly.
     */
    var model: PhoenixModel = PhoenixModel.TrainerPlus

    sealed interface Event {
        data object StopWorkoutEntered : Event
        data object StopWorkoutCompleted : Event
        data object Disconnected : Event
        data class WorkoutCommand(val bytes: ByteArray) : Event {
            override fun equals(other: Any?): Boolean = other is WorkoutCommand && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    // Controllable state flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _metricsFlow = MutableSharedFlow<WorkoutMetric>(replay = 0)
    override val metricsFlow: Flow<WorkoutMetric> = _metricsFlow.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _handleDetection = MutableStateFlow(HandleDetection())
    override val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

    private val _repEvents = MutableSharedFlow<RepNotification>(replay = 0)
    override val repEvents: Flow<RepNotification> = _repEvents.asSharedFlow()

    private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
    override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _deloadOccurredEvents = MutableSharedFlow<Unit>(replay = 0)
    override val deloadOccurredEvents: Flow<Unit> = _deloadOccurredEvents.asSharedFlow()

    private val _reconnectionRequested = MutableSharedFlow<ReconnectionRequest>(replay = 0)
    override val reconnectionRequested: Flow<ReconnectionRequest> = _reconnectionRequested.asSharedFlow()

    private val _heuristicData = MutableStateFlow<HeuristicStatistics?>(null)
    override val heuristicData: StateFlow<HeuristicStatistics?> = _heuristicData.asStateFlow()

    private val _diagnostics = MutableStateFlow<DiagnosticPacket?>(null)
    override val diagnostics: StateFlow<DiagnosticPacket?> = _diagnostics.asStateFlow()

    private val _discoModeActive = MutableStateFlow(false)
    override val discoModeActive: StateFlow<Boolean> = _discoModeActive.asStateFlow()

    /** A decoded 96-byte activation/program frame. */
    data class ProgramCommand(
        val weightPerCableKg: Float,
        val progressionKg: Float,
        val forceMaxKg: Float,
        val repsByte: Int,
    )

    /** A decoded 32-byte Echo control frame. */
    data class EchoCommand(val warmupRepsByte: Int, val targetRepsByte: Int)

    // Track commands received for verification in tests
    val commandsReceived = mutableListOf<ByteArray>()
    val programCommands = mutableListOf<ProgramCommand>()
    val echoCommands = mutableListOf<EchoCommand>()
    val colorSchemeCommands = mutableListOf<Int>()
    val events = mutableListOf<Event>()

    // Configurable behavior
    var scanResult: Result<Unit> = Result.success(Unit)
    var connectResult: Result<Unit> = Result.success(Unit)
    var workoutCommandResult: Result<Unit> = Result.success(Unit)
    var afterWorkoutCommand: suspend (ByteArray) -> Unit = {}
    var shouldFailConnect = false
    var connectDelay: Long = 0L
    var stopWorkoutBlock: suspend () -> Result<Unit> = { Result.success(Unit) }
    var stopWorkoutCallCount = 0
    var stopPacketCallCount = 0
    var stopPollingCallCount = 0
    var restartPollingCallCount = 0
    var monitorPollingActive = false
        private set

    // Opt-in peripheral stimulus: unlike setHandleState, cannot deliver without polling.
    fun emitPolledHandleState(state: HandleState): Boolean {
        if (!monitorPollingActive) return false
        setHandleState(state)
        return true
    }
    var disconnectCallCount = 0
    var reconnectCallCount = 0

    // ========== Test control methods ==========

    private fun setConnectionState(state: ConnectionState) {
        if (state !is ConnectionState.Connected) {
            _diagnostics.value = null
        }
        _connectionState.value = state
    }

    fun simulateConnect(
        deviceName: String,
        deviceAddress: String = "AA:BB:CC:DD:EE:FF",
        hardwareModel: PhoenixModel = model,
    ) {
        model = hardwareModel
        monitorPollingActive = true
        setConnectionState(
            ConnectionState.Connected(
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                hardwareModel = hardwareModel,
            ),
        )
    }

    fun simulateDisconnect() {
        setConnectionState(ConnectionState.Disconnected)
    }

    fun simulateError(message: String, throwable: Throwable? = null) {
        setConnectionState(ConnectionState.Error(message, throwable))
    }

    fun simulateScanning() {
        setConnectionState(ConnectionState.Scanning)
    }

    fun simulateConnecting() {
        setConnectionState(ConnectionState.Connecting)
    }

    suspend fun emitMetric(metric: WorkoutMetric) {
        _metricsFlow.emit(metric)
    }

    suspend fun emitRepNotification(notification: RepNotification) {
        _repEvents.emit(notification)
    }

    suspend fun emitDeloadOccurred() {
        _deloadOccurredEvents.emit(Unit)
    }

    suspend fun emitReconnectionRequest(request: ReconnectionRequest) {
        _reconnectionRequested.emit(request)
    }

    fun setScannedDevices(devices: List<ScannedDevice>) {
        _scannedDevices.value = devices
    }

    fun setHandleDetection(detection: HandleDetection) {
        _handleDetection.value = detection
    }

    fun setHandleState(state: HandleState) {
        _handleState.value = state
    }

    fun setHeuristicData(data: HeuristicStatistics?) {
        _heuristicData.value = data
    }

    fun setDiagnostics(data: DiagnosticPacket?) {
        _diagnostics.value = data
    }

    fun setDiscoModeActive(active: Boolean) {
        _discoModeActive.value = active
    }

    fun reset() {
        setConnectionState(ConnectionState.Disconnected)
        _scannedDevices.value = emptyList()
        _handleDetection.value = HandleDetection()
        _handleState.value = HandleState.WaitingForRest
        _heuristicData.value = null
        _diagnostics.value = null
        _discoModeActive.value = false
        commandsReceived.clear()
        programCommands.clear()
        echoCommands.clear()
        model = PhoenixModel.TrainerPlus
        colorSchemeCommands.clear()
        events.clear()
        scanResult = Result.success(Unit)
        connectResult = Result.success(Unit)
        workoutCommandResult = Result.success(Unit)
        afterWorkoutCommand = {}
        shouldFailConnect = false
        connectDelay = 0L
        stopWorkoutBlock = { Result.success(Unit) }
        stopWorkoutCallCount = 0
        stopPacketCallCount = 0
        stopPollingCallCount = 0
        restartPollingCallCount = 0
        monitorPollingActive = false
        disconnectCallCount = 0
        reconnectCallCount = 0
    }

    // ========== BleRepository interface implementation ==========

    override suspend fun startScanning(): Result<Unit> {
        if (scanResult.isSuccess) {
            setConnectionState(ConnectionState.Scanning)
        }
        return scanResult
    }

    override suspend fun stopScanning() {
        if (_connectionState.value == ConnectionState.Scanning) {
            setConnectionState(ConnectionState.Disconnected)
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        if (shouldFailConnect) {
            setConnectionState(ConnectionState.Error("Connection failed"))
            return Result.failure(Exception("Connection failed"))
        }

        setConnectionState(ConnectionState.Connecting)

        if (connectDelay > 0) {
            kotlinx.coroutines.delay(connectDelay)
        }

        return if (connectResult.isSuccess) {
            setConnectionState(
                ConnectionState.Connected(
                    deviceName = device.name,
                    deviceAddress = device.address,
                ),
            )
            Result.success(Unit)
        } else {
            setConnectionState(ConnectionState.Error("Connection failed"))
            connectResult
        }
    }

    override suspend fun cancelConnection() {
        setConnectionState(ConnectionState.Disconnected)
    }

    override suspend fun disconnect() {
        disconnectCallCount++
        events += Event.Disconnected
        setConnectionState(ConnectionState.Disconnected)
    }

    override suspend fun shutdown() {
        reset()
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        reconnectCallCount++
        setConnectionState(ConnectionState.Scanning)

        val devices = _scannedDevices.value
        return if (devices.isNotEmpty()) {
            connect(devices.first())
        } else if (shouldFailConnect) {
            setConnectionState(ConnectionState.Error("No devices found"))
            Result.failure(Exception("No devices found"))
        } else {
            // Auto-add a fake device and connect
            val fakeDevice = ScannedDevice("Vee_Test", "AA:BB:CC:DD:EE:FF", -50)
            connect(fakeDevice)
        }
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        colorSchemeCommands.add(schemeIndex)
        return Result.success(Unit)
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        val copy = command.copyOf()
        decodeAndAssertWithinLimits(copy)
        commandsReceived.add(copy)
        events += Event.WorkoutCommand(copy)
        afterWorkoutCommand(copy)
        return workoutCommandResult
    }

    /**
     * Decode the frames that carry load, and fail the calling test if the trainer would be
     * commanded outside [CommandLimits] for [model]. Control frames (init/start/stop/reset,
     * color scheme) carry no load and are recorded without a check.
     */
    private fun decodeAndAssertWithinLimits(command: ByteArray) {
        when {
            command.size == BleConstants.ActivationPacket.SIZE && command[0] == 0x04.toByte() -> {
                val decoded = ProgramCommand(
                    weightPerCableKg = readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT),
                    progressionKg = readFloatLE(command, BleConstants.ActivationPacket.OFFSET_PROGRESSION),
                    forceMaxKg = readFloatLE(command, BleConstants.ActivationPacket.OFFSET_FORCE_MAX),
                    repsByte = command[0x04].toInt() and 0xFF,
                )
                val ceiling = CommandLimits.maxWeightPerCableKg(model)
                if (!decoded.weightPerCableKg.isFinite() ||
                    decoded.weightPerCableKg < 0f ||
                    decoded.weightPerCableKg > ceiling + CommandLimits.WEIGHT_TOLERANCE_KG
                ) {
                    throw AssertionError(
                        "Program frame commands ${decoded.weightPerCableKg}kg/cable, outside " +
                            "0..${ceiling}kg for ${model.displayName}",
                    )
                }
                if (!decoded.progressionKg.isFinite() ||
                    abs(decoded.progressionKg) > CommandLimits.MAX_PROGRESSION_KG
                ) {
                    throw AssertionError(
                        "Program frame commands ${decoded.progressionKg}kg/rep progression, " +
                            "outside ±${CommandLimits.MAX_PROGRESSION_KG}kg",
                    )
                }
                programCommands.add(decoded)
            }

            command.size == 32 && readIntLE(command, 0) == 0x4E -> {
                echoCommands.add(
                    EchoCommand(
                        warmupRepsByte = command[0x04].toInt() and 0xFF,
                        targetRepsByte = command[0x05].toInt() and 0xFF,
                    ),
                )
            }
        }
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int = (buffer[offset].toInt() and 0xFF) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun readFloatLE(buffer: ByteArray, offset: Int): Float = Float.fromBits(readIntLE(buffer, offset))

    override suspend fun stopWorkout(): Result<Unit> {
        stopWorkoutCallCount++
        events += Event.StopWorkoutEntered
        return stopWorkoutBlock().also {
            events += Event.StopWorkoutCompleted
        }
    }

    override suspend fun sendStopCommand(): Result<Unit> {
        stopPacketCallCount++
        return Result.success(Unit)
    }

    override fun enableHandleDetection(enabled: Boolean) {
        if (enabled) {
            _handleState.value = HandleState.WaitingForRest
        }
    }

    override fun resetHandleState() {
        _handleState.value = HandleState.WaitingForRest
    }

    override fun enableJustLiftWaitingMode() {
        _handleState.value = HandleState.WaitingForRest
    }

    override fun restartMonitorPolling() {
        restartPollingCallCount++
        monitorPollingActive = true
    }

    override fun startActiveWorkoutPolling() {
        monitorPollingActive = true
        _handleState.value = HandleState.Grabbed
    }

    override fun stopPolling() {
        stopPollingCallCount++
        monitorPollingActive = false
    }

    override fun stopMonitorPollingOnly() {
        monitorPollingActive = false
    }

    override fun restartDiagnosticPolling() {
        // No-op in fake
    }

    override fun startDiscoMode() {
        _discoModeActive.value = true
    }

    override fun stopDiscoMode() {
        _discoModeActive.value = false
    }

    override fun setLastColorSchemeIndex(index: Int) {
        // No-op in fake - no color tracking needed
    }
}
