package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake BLE repository for testing.
 * Provides controllable state and response simulation without real hardware.
 */
class FakeBleRepository : BleRepository {

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

    private val _discoModeActive = MutableStateFlow(false)
    override val discoModeActive: StateFlow<Boolean> = _discoModeActive.asStateFlow()

    // Track commands received for verification in tests
    val commandsReceived = mutableListOf<ByteArray>()
    val workoutParameters = mutableListOf<WorkoutParameters>()

    // Configurable behavior
    var scanResult: Result<Unit> = Result.success(Unit)
    var connectResult: Result<Unit> = Result.success(Unit)
    var workoutCommandResult: Result<Unit> = Result.success(Unit)
    var shouldFailConnect = false
    var connectDelay: Long = 0L

    // ========== Test control methods ==========

    fun simulateConnect(deviceName: String, deviceAddress: String = "AA:BB:CC:DD:EE:FF") {
        _connectionState.value = ConnectionState.Connected(
            deviceName = deviceName,
            deviceAddress = deviceAddress
        )
    }

    fun simulateDisconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    fun simulateError(message: String, throwable: Throwable? = null) {
        _connectionState.value = ConnectionState.Error(message, throwable)
    }

    fun simulateScanning() {
        _connectionState.value = ConnectionState.Scanning
    }

    fun simulateConnecting() {
        _connectionState.value = ConnectionState.Connecting
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

    fun reset() {
        _connectionState.value = ConnectionState.Disconnected
        _scannedDevices.value = emptyList()
        _handleDetection.value = HandleDetection()
        _handleState.value = HandleState.WaitingForRest
        _heuristicData.value = null
        _discoModeActive.value = false
        commandsReceived.clear()
        workoutParameters.clear()
        scanResult = Result.success(Unit)
        connectResult = Result.success(Unit)
        workoutCommandResult = Result.success(Unit)
        shouldFailConnect = false
        connectDelay = 0L
    }

    // ========== BleRepository interface implementation ==========

    override suspend fun startScanning(): Result<Unit> {
        if (scanResult.isSuccess) {
            _connectionState.value = ConnectionState.Scanning
        }
        return scanResult
    }

    override suspend fun stopScanning() {
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun connect(device: ScannedDevice): Result<Unit> {
        if (shouldFailConnect) {
            _connectionState.value = ConnectionState.Error("Connection failed")
            return Result.failure(Exception("Connection failed"))
        }

        _connectionState.value = ConnectionState.Connecting

        if (connectDelay > 0) {
            kotlinx.coroutines.delay(connectDelay)
        }

        return if (connectResult.isSuccess) {
            _connectionState.value = ConnectionState.Connected(
                deviceName = device.name,
                deviceAddress = device.address
            )
            Result.success(Unit)
        } else {
            _connectionState.value = ConnectionState.Error("Connection failed")
            connectResult
        }
    }

    override suspend fun cancelConnection() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> {
        _connectionState.value = ConnectionState.Scanning

        val devices = _scannedDevices.value
        return if (devices.isNotEmpty()) {
            connect(devices.first())
        } else if (shouldFailConnect) {
            _connectionState.value = ConnectionState.Error("No devices found")
            Result.failure(Exception("No devices found"))
        } else {
            // Auto-add a fake device and connect
            val fakeDevice = ScannedDevice("Vee_Test", "AA:BB:CC:DD:EE:FF", -50)
            connect(fakeDevice)
        }
    }

    override suspend fun setColorScheme(schemeIndex: Int): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
        commandsReceived.add(command)
        return workoutCommandResult
    }

    override suspend fun sendInitSequence(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun startWorkout(params: WorkoutParameters): Result<Unit> {
        workoutParameters.add(params)
        return Result.success(Unit)
    }

    override suspend fun stopWorkout(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sendStopCommand(): Result<Unit> {
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
        // No-op in fake
    }

    override fun startActiveWorkoutPolling() {
        _handleState.value = HandleState.Grabbed
    }

    override fun stopPolling() {
        // No-op in fake
    }

    override fun startDiscoMode() {
        _discoModeActive.value = true
    }

    override fun stopDiscoMode() {
        _discoModeActive.value = false
    }
}
