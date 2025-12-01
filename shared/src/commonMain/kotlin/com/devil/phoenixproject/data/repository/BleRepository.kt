package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scanned BLE device
 */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0
)

/**
 * Handle detection state
 */
data class HandleState(
    val leftDetected: Boolean = false,
    val rightDetected: Boolean = false
)

/**
 * Auto-stop UI state for Just Lift mode
 */
data class AutoStopUiState(
    val isActive: Boolean = false,
    val secondsRemaining: Int = 0,
    val progress: Float = 0f
)

/**
 * Handle activity state for auto-start/auto-stop logic.
 * Tracks the workout phase based on handle position.
 *
 * 4-state machine (matches parent repo v0.5.1-beta):
 * - WaitingForRest: Initial state, requires handles at rest before arming
 * - Released (SetComplete): Handles at rest, armed for grab detection
 * - Moving: Handles extended but no significant velocity yet (intermediate state)
 * - Grabbed (Active): Handles grabbed with velocity - workout active
 */
enum class HandleActivityState {
    /** Waiting for user to pick up handles (at rest position) */
    WaitingForRest,
    /** Handles extended (position > threshold) but no significant velocity yet */
    Moving,
    /** Handles are lifted with velocity - user is actively working (maps to "Grabbed" in parent) */
    Active,
    /** Set completed / handles released, armed for next grab (maps to "Released" in parent) */
    SetComplete
}

/**
 * Rep notification from the Vitruvian machine.
 *
 * Supports TWO packet formats for backwards compatibility (Issue #187):
 *
 * LEGACY FORMAT (6+ bytes, used in Beta 4, Samsung devices):
 * - Bytes 0-1: topCounter (u16) - concentric completions
 * - Bytes 2-3: (unused)
 * - Bytes 4-5: completeCounter (u16) - eccentric completions
 * - isLegacyFormat = true
 * - Uses topCounter increments for rep counting (Beta 4 method)
 *
 * OFFICIAL APP FORMAT (24 bytes):
 * - topCounter (u32): Concentric/up phase completions
 * - completeCounter (u32): Eccentric/down phase completions
 * - rangeTop (float): Maximum ROM boundary
 * - rangeBottom (float): Minimum ROM boundary
 * - repsRomCount (u16): Warmup reps with proper ROM - USE FOR WARMUP DISPLAY
 * - repsRomTotal (u16): Total reps regardless of ROM
 * - repsSetCount (u16): Working set rep count - USE FOR WORKING REPS DISPLAY
 * - repsSetTotal (u16): Total reps in set
 * - isLegacyFormat = false
 */
data class RepNotification(
    val topCounter: Int,
    val completeCounter: Int,
    val repsRomCount: Int,
    val repsSetCount: Int,
    val rangeTop: Float = 0f,
    val rangeBottom: Float = 0f,
    val isLegacyFormat: Boolean = false,
    val timestamp: Long = 0L
)

/**
 * Reconnection request data.
 * Emitted when connection is lost but auto-reconnect should be attempted.
 */
data class ReconnectionRequest(
    val deviceName: String?,
    val deviceAddress: String,
    val reason: String,
    val timestamp: Long
)

/**
 * BLE Repository interface for Vitruvian machine communication.
 *
 * Implementations:
 * - KableBleRepository (commonMain): Kable-based implementation for Android/iOS
 * - StubBleRepository (commonMain): No-op stub for platforms without BLE (Desktop)
 */
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val metricsFlow: Flow<WorkoutMetric>
    val scannedDevices: StateFlow<List<ScannedDevice>>
    val handleState: StateFlow<HandleState>
    val repEvents: Flow<RepNotification>

    // Handle activity state (4-state machine for Just Lift auto-start)
    val handleActivityState: StateFlow<HandleActivityState>

    // Deload safety event (for Just Lift mode safety recovery)
    val deloadOccurredEvents: Flow<Unit>

    // Reconnection request (for auto-recovery on connection loss)
    val reconnectionRequested: Flow<ReconnectionRequest>

    suspend fun startScanning()
    suspend fun stopScanning()
    suspend fun connect(device: ScannedDevice)
    suspend fun disconnect()
    suspend fun setColorScheme(schemeIndex: Int)
    suspend fun sendWorkoutCommand(command: ByteArray)

    // Handle detection for auto-start (arms the state machine in WaitingForRest)
    fun enableHandleDetection(enabled: Boolean)

    // Reset handle state machine to initial state (for re-arming Just Lift)
    fun resetHandleActivityState()

    /**
     * Enable Just Lift waiting mode after set completion.
     * Resets position tracking and state machine to WaitingForRest,
     * ready to detect when user grabs handles for next set.
     * This is called BETWEEN sets to re-arm the auto-start detection.
     */
    fun enableJustLiftWaitingMode()

    /**
     * Restart monitor polling to clear machine fault state (red lights).
     * Unlike enableHandleDetection(), this does NOT arm auto-start -
     * it just ensures polling continues to clear danger zone alarms.
     * Use after AMRAP completion or when machine needs fault clearing.
     */
    fun restartMonitorPolling()

    /**
     * Start monitor polling for active workout (not for auto-start).
     * Sets handle state to Active since workout is already running.
     * Use when starting a workout that doesn't need handle detection.
     */
    fun startActiveWorkoutPolling()

    /**
     * Stop all polling (Monitor, Diagnostic, Heartbeat).
     * Logs workout analysis (min/max positions) before stopping.
     * Does NOT disconnect the device.
     */
    fun stopPolling()
}

/**
 * Stub BLE Repository for compilation - does nothing
 */
class StubBleRepository : BleRepository {
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val metricsFlow: Flow<WorkoutMetric> = MutableStateFlow(
        WorkoutMetric(loadA = 0f, loadB = 0f, positionA = 0f, positionB = 0f)
    )
    override val scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val handleState = MutableStateFlow(HandleState())
    override val repEvents: Flow<RepNotification> = kotlinx.coroutines.flow.emptyFlow()
    override val handleActivityState = MutableStateFlow(HandleActivityState.WaitingForRest)
    override val deloadOccurredEvents: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
    override val reconnectionRequested: Flow<ReconnectionRequest> = kotlinx.coroutines.flow.emptyFlow()

    override suspend fun startScanning() {}
    override suspend fun stopScanning() {}
    override suspend fun connect(device: ScannedDevice) {}
    override suspend fun disconnect() {}
    override suspend fun setColorScheme(schemeIndex: Int) {}
    override suspend fun sendWorkoutCommand(command: ByteArray) {}
    override fun enableHandleDetection(enabled: Boolean) {}
    override fun resetHandleActivityState() {}
    override fun enableJustLiftWaitingMode() {}
    override fun restartMonitorPolling() {}
    override fun startActiveWorkoutPolling() {}
    override fun stopPolling() {}
}
