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
 * Handle detection state (left/right cable detection)
 */
data class HandleDetection(
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
 * Handle state for auto-start/auto-stop logic.
 * Tracks the workout phase based on handle position.
 *
 * 4-state machine (matches parent repo):
 * - WaitingForRest: Initial state, requires handles at rest before arming
 * - Released: Handles at rest, armed for grab detection
 * - Grabbed: Handles grabbed with velocity - workout active
 * - Moving: Handles in motion
 */
enum class HandleState {
    /** Initial state - waiting for handles to be at rest before arming grab detection */
    WaitingForRest,
    /** Handles at rest - armed for grab detection */
    Released,
    /** Handles grabbed - force > 3kg sustained */
    Grabbed,
    /** Handles in motion */
    Moving
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
    val rawData: ByteArray,
    val timestamp: Long = 0L,
    val isLegacyFormat: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RepNotification

        if (topCounter != other.topCounter) return false
        if (completeCounter != other.completeCounter) return false
        if (repsRomCount != other.repsRomCount) return false
        if (repsSetCount != other.repsSetCount) return false
        if (rangeTop != other.rangeTop) return false
        if (rangeBottom != other.rangeBottom) return false
        if (!rawData.contentEquals(other.rawData)) return false
        if (timestamp != other.timestamp) return false
        if (isLegacyFormat != other.isLegacyFormat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = topCounter
        result = 31 * result + completeCounter
        result = 31 * result + repsRomCount
        result = 31 * result + repsSetCount
        result = 31 * result + rangeTop.hashCode()
        result = 31 * result + rangeBottom.hashCode()
        result = 31 * result + rawData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isLegacyFormat.hashCode()
        return result
    }
}

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
 * Implementation: KableBleRepository (commonMain) - Kable-based implementation for Android/iOS
 */
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val metricsFlow: Flow<WorkoutMetric>
    val scannedDevices: StateFlow<List<ScannedDevice>>
    val handleDetection: StateFlow<HandleDetection>
    val repEvents: Flow<RepNotification>

    // Handle state (4-state machine for Just Lift auto-start)
    val handleState: StateFlow<HandleState>

    // Deload safety event (for Just Lift mode safety recovery)
    val deloadOccurredEvents: Flow<Unit>

    // Reconnection request (for auto-recovery on connection loss)
    val reconnectionRequested: Flow<ReconnectionRequest>

    // Heuristic/phase statistics from machine (for Echo mode force feedback)
    val heuristicData: StateFlow<com.devil.phoenixproject.domain.model.HeuristicStatistics?>

    suspend fun startScanning(): Result<Unit>
    suspend fun stopScanning()
    suspend fun connect(device: ScannedDevice): Result<Unit>
    suspend fun cancelConnection()  // Cancel an in-progress connection attempt
    suspend fun disconnect()

    /**
     * Scan for first Vitruvian device and connect to it immediately.
     * Matches parent repo behavior - no manual device selection needed.
     * @param timeoutMs Maximum time to scan before giving up (default 30 seconds)
     * @return Result.success if connected, Result.failure if timeout or error
     */
    suspend fun scanAndConnect(timeoutMs: Long = 30000L): Result<Unit>
    suspend fun setColorScheme(schemeIndex: Int): Result<Unit>
    suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit>

    // High-level workout control (parity with parent repo)
    suspend fun sendInitSequence(): Result<Unit>
    suspend fun startWorkout(params: com.devil.phoenixproject.domain.model.WorkoutParameters): Result<Unit>
    suspend fun stopWorkout(): Result<Unit>

    /**
     * Send stop command to machine WITHOUT stopping polling.
     * Use this for Just Lift mode where we need continuous polling for auto-start detection.
     */
    suspend fun sendStopCommand(): Result<Unit>

    // Handle detection for auto-start (arms the state machine in WaitingForRest)
    fun enableHandleDetection(enabled: Boolean)

    // Reset handle state machine to initial state (for re-arming Just Lift)
    fun resetHandleState()

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

    // ========== Disco Mode (Easter Egg) ==========

    /**
     * Whether disco mode is currently active.
     */
    val discoModeActive: StateFlow<Boolean>

    /**
     * Start disco mode - rapidly cycles through LED color schemes.
     * Only works when not in an active workout to avoid interfering with position polling.
     * Automatically pauses if a workout starts.
     */
    fun startDiscoMode()

    /**
     * Stop disco mode and restore the last selected color scheme.
     */
    fun stopDiscoMode()
}
