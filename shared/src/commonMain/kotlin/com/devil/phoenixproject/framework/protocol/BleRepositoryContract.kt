package com.devil.phoenixproject.framework.protocol

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Scanned BLE device. */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0
)

/** Handle detection state (left/right cable detection). */
data class HandleDetection(
    val leftDetected: Boolean = false,
    val rightDetected: Boolean = false
)

/** Auto-stop UI state for Just Lift mode. */
data class AutoStopUiState(
    val isActive: Boolean = false,
    val secondsRemaining: Int = 0,
    val progress: Float = 0f
)

/** Handle state for auto-start/auto-stop logic. */
enum class HandleState {
    WaitingForRest,
    Released,
    Grabbed,
    Moving
}

/** Rep notification from machine packet streams. */
data class RepNotification(
    val topCounter: Int,
    val completeCounter: Int,
    val repsRomCount: Int,
    val repsRomTotal: Int,
    val repsSetCount: Int,
    val repsSetTotal: Int,
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

        return topCounter == other.topCounter &&
            completeCounter == other.completeCounter &&
            repsRomCount == other.repsRomCount &&
            repsRomTotal == other.repsRomTotal &&
            repsSetCount == other.repsSetCount &&
            repsSetTotal == other.repsSetTotal &&
            rangeTop == other.rangeTop &&
            rangeBottom == other.rangeBottom &&
            rawData.contentEquals(other.rawData) &&
            timestamp == other.timestamp &&
            isLegacyFormat == other.isLegacyFormat
    }

    override fun hashCode(): Int {
        var result = topCounter
        result = 31 * result + completeCounter
        result = 31 * result + repsRomCount
        result = 31 * result + repsRomTotal
        result = 31 * result + repsSetCount
        result = 31 * result + repsSetTotal
        result = 31 * result + rangeTop.hashCode()
        result = 31 * result + rangeBottom.hashCode()
        result = 31 * result + rawData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isLegacyFormat.hashCode()
        return result
    }
}

data class ReconnectionRequest(
    val deviceName: String?,
    val deviceAddress: String,
    val reason: String,
    val timestamp: Long
)

/** Vendor-neutral BLE adapter contract for Phoenix-compatible apps. */
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val metricsFlow: Flow<WorkoutMetric>
    val scannedDevices: StateFlow<List<ScannedDevice>>
    val handleDetection: StateFlow<HandleDetection>
    val repEvents: Flow<RepNotification>
    val handleState: StateFlow<HandleState>
    val deloadOccurredEvents: Flow<Unit>
    val reconnectionRequested: Flow<ReconnectionRequest>
    val heuristicData: StateFlow<HeuristicStatistics?>

    suspend fun startScanning(): Result<Unit>
    suspend fun stopScanning()
    suspend fun connect(device: ScannedDevice): Result<Unit>
    suspend fun cancelConnection()
    suspend fun disconnect()
    suspend fun scanAndConnect(timeoutMs: Long = 30000L): Result<Unit>
    suspend fun setColorScheme(schemeIndex: Int): Result<Unit>
    suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit>
    suspend fun sendInitSequence(): Result<Unit>
    suspend fun startWorkout(params: WorkoutParameters): Result<Unit>
    suspend fun stopWorkout(): Result<Unit>
    suspend fun sendStopCommand(): Result<Unit>

    fun enableHandleDetection(enabled: Boolean)
    fun resetHandleState()
    fun enableJustLiftWaitingMode()
    fun restartMonitorPolling()
    fun startActiveWorkoutPolling()
    fun stopPolling()
    fun stopMonitorPollingOnly()
    fun restartDiagnosticPolling()

    val discoModeActive: StateFlow<Boolean>
    fun startDiscoMode()
    fun stopDiscoMode()
    fun setLastColorSchemeIndex(index: Int) {}
}
