package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 4-state handle detection machine for Just Lift auto-start/auto-stop.
 *
 * Extracted from KableBleRepository (Phase 9).
 * Pure state machine: processes WorkoutMetric -> HandleState transitions.
 *
 * States: WaitingForRest -> Released -> Grabbed -> Moving
 *
 * Features preserved from KableBleRepository:
 * - Issue #176: Overhead pulley baseline tracking (relative grab/release detection)
 * - Task 14: Hysteresis dwell timers (200ms sustained before state transition)
 * - iOS autostart fix: WaitingForRest timeout escape (3s)
 * - Issue #96: Lower velocity threshold for auto-start mode
 * - Single-handle exercise support via activeHandlesMask
 *
 * @param timeProvider Injectable time source (default: currentTimeMillis() for production)
 */
class HandleStateDetector(
    private val timeProvider: () -> Long = { currentTimeMillis() }
) {
    private val log = Logger.withTag("HandleStateDetector")

    companion object {
        /** Position threshold (mm) for simple left/right handle detection booleans */
        private const val SIMPLE_DETECTION_THRESHOLD = 50.0f
    }

    // State flows
    private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
    val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _handleDetection = MutableStateFlow(HandleDetection())
    val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

    // Configuration
    var isEnabled: Boolean = false
        private set
    var isAutoStartMode: Boolean = false
        private set

    // Hysteresis timers (Task 14)
    private var pendingGrabbedStartTime: Long? = null
    private var pendingReleasedStartTime: Long? = null

    // Active handle tracking for single-cable release detection
    // 0 = none, 1 = A only, 2 = B only, 3 = both
    private var activeHandlesMask: Int = 0

    // WaitingForRest timeout (iOS autostart fix)
    private var waitingForRestStartTime: Long? = null

    // Issue #176: Overhead pulley baseline tracking
    private var restBaselinePosA: Double? = null
    private var restBaselinePosB: Double? = null

    // Position range diagnostics
    var minPositionSeen: Double = Double.MAX_VALUE
        private set
    var maxPositionSeen: Double = Double.MIN_VALUE
        private set

    // Periodic logging counter
    private var logCounter = 0L

    /**
     * Process a workout metric and update handle state.
     * Called from parseMonitorData and parseRxMetrics in KableBleRepository.
     */
    fun processMetric(metric: WorkoutMetric) {
        if (!isEnabled) return

        // Simple handle detection (left/right boolean)
        val leftDetected = metric.positionA > SIMPLE_DETECTION_THRESHOLD
        val rightDetected = metric.positionB > SIMPLE_DETECTION_THRESHOLD
        val currentDetection = _handleDetection.value
        if (currentDetection.leftDetected != leftDetected || currentDetection.rightDetected != rightDetected) {
            _handleDetection.value = HandleDetection(leftDetected, rightDetected)
        }

        // 4-state machine
        val newState = analyzeHandleState(metric)
        if (newState != _handleState.value) {
            log.d { "Handle state: ${_handleState.value} -> $newState" }
            _handleState.value = newState
        }
    }

    /**
     * Enable handle detection state machine.
     * Resets all state for a fresh detection cycle.
     *
     * @param autoStart If true, uses lower velocity threshold (Issue #96)
     */
    fun enable(autoStart: Boolean) {
        isEnabled = true
        isAutoStartMode = autoStart
        _handleState.value = HandleState.WaitingForRest
        logCounter = 0L
        resetInternalState()
        log.i { "Handle detection enabled (autoStart=$autoStart)" }
    }

    /**
     * Disable handle detection.
     * Clears all state including baseline tracking.
     */
    fun disable() {
        isEnabled = false
        isAutoStartMode = false
        // Issue #176: Clear baseline when detection disabled
        restBaselinePosA = null
        restBaselinePosB = null
        log.i { "Handle detection disabled" }
    }

    /**
     * Reset state machine to WaitingForRest.
     * Preserves enabled/autoStart state.
     */
    fun reset() {
        log.d { "Resetting handle state to WaitingForRest" }
        _handleState.value = HandleState.WaitingForRest
        resetInternalState()
    }

    /**
     * Enable Just Lift waiting mode.
     * Resets state and enables autoStart for next set detection.
     */
    fun enableJustLiftWaiting() {
        log.i { "Enabling Just Lift waiting mode - ready for next set" }
        isEnabled = true
        isAutoStartMode = true
        _handleState.value = HandleState.WaitingForRest
        logCounter = 0L
        resetInternalState()
    }

    /**
     * Reset all internal state variables (timers, baselines, diagnostics).
     */
    private fun resetInternalState() {
        minPositionSeen = Double.MAX_VALUE
        maxPositionSeen = Double.MIN_VALUE
        pendingGrabbedStartTime = null
        pendingReleasedStartTime = null
        activeHandlesMask = 0
        waitingForRestStartTime = null
        restBaselinePosA = null
        restBaselinePosB = null
    }

    /**
     * 4-state handle activity machine matching parent repo v0.5.1-beta:
     *
     * State transitions:
     * - WaitingForRest -> Released: When both handles < 5mm (armed)
     * - Released/Moving -> Grabbed: When position > threshold AND velocity > threshold (GRAB DETECTED)
     * - Released/Moving -> Moving: When position > threshold but no velocity (intermediate)
     * - Released/Moving -> Released: When position <= threshold (back to rest)
     * - Grabbed -> Released: When handles return to rest (RELEASE DETECTED)
     *
     * Verbatim logic from KableBleRepository.analyzeHandleState() with adaptations:
     * - currentTimeMillis() -> timeProvider()
     * - _handleState.value reads -> currentState local variable
     */
    private fun analyzeHandleState(metric: WorkoutMetric): HandleState {
        val posA = metric.positionA.toDouble()
        val posB = metric.positionB.toDouble()
        val velocityA = metric.velocityA
        val velocityB = metric.velocityB

        // Track position range for post-workout tuning diagnostics
        minPositionSeen = minOf(minPositionSeen, minOf(posA, posB))
        maxPositionSeen = maxOf(maxPositionSeen, maxOf(posA, posB))

        val currentState = _handleState.value

        // Check handles - support single-handle exercises
        // NOTE: Use abs(velocity) since velocity is now signed (Issue #204 fix)
        // Issue #96: Use lower velocity threshold for auto-start grab detection
        val velocityThreshold = if (isAutoStartMode) BleConstants.Thresholds.AUTO_START_VELOCITY_THRESHOLD else BleConstants.Thresholds.VELOCITY_THRESHOLD

        // Issue #176: Use relative position change when baseline is set (for overhead pulley setups)
        // When cables can't reach absolute rest position, detect grabs via delta from baseline
        val handleAGrabbed = isAboveThreshold(posA, restBaselinePosA,
            BleConstants.Thresholds.GRAB_DELTA_THRESHOLD, BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD)
        val handleBGrabbed = isAboveThreshold(posB, restBaselinePosB,
            BleConstants.Thresholds.GRAB_DELTA_THRESHOLD, BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD)
        val handleAMoving = kotlin.math.abs(velocityA) > velocityThreshold
        val handleBMoving = kotlin.math.abs(velocityB) > velocityThreshold

        // Periodic diagnostic logging (every 200 samples at high poll rate)
        logCounter++
        if (logCounter % 200 == 0L) {
            log.i { "HANDLE STATE: $currentState | posA=${posA.format(1)}mm posB=${posB.format(1)}mm | velA=${velocityA.format(0)} velB=${velocityB.format(0)} | thresholds: rest<${BleConstants.Thresholds.HANDLE_REST_THRESHOLD} grab>${BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD} vel>$velocityThreshold${if (isAutoStartMode) " (auto-start)" else ""}" }
        }

        return when (currentState) {
            HandleState.WaitingForRest -> {
                // MUST see handles at rest before arming grab detection
                // This prevents immediate auto-start if cables already have tension
                if (posA < BleConstants.Thresholds.HANDLE_REST_THRESHOLD && posB < BleConstants.Thresholds.HANDLE_REST_THRESHOLD) {
                    log.i { "Handles at REST (posA=$posA, posB=$posB < ${BleConstants.Thresholds.HANDLE_REST_THRESHOLD}) - auto-start now ARMED" }
                    waitingForRestStartTime = null
                    // Issue #176: Capture baseline position (will be ~0 for normal setups)
                    restBaselinePosA = posA
                    restBaselinePosB = posB
                    HandleState.Released  // SetComplete = "Released/Armed" state
                } else {
                    // iOS autostart fix: Add timeout to escape WaitingForRest trap
                    // If user holds handles before screen loads (pre-tensioned cables),
                    // the state machine would be stuck forever. After timeout, arm anyway.
                    val currentTime = timeProvider()
                    if (waitingForRestStartTime == null) {
                        // Start timeout timer
                        waitingForRestStartTime = currentTime
                        HandleState.WaitingForRest
                    } else if (currentTime - waitingForRestStartTime!! > BleConstants.Timing.WAITING_FOR_REST_TIMEOUT_MS) {
                        // Issue #176: When timeout fires, check if handles are already grabbed
                        // If user is already holding handles (position > threshold), use virtual
                        // baseline of 0 so grab detection triggers immediately when they move.
                        // Otherwise, use current position as baseline for elevated rest setups.
                        val alreadyGrabbed = posA > BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD || posB > BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD
                        if (alreadyGrabbed) {
                            log.w { "WaitingForRest TIMEOUT - handles already grabbed (posA=$posA, posB=$posB > ${BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD}) - using virtual baseline=0 for immediate grab detection" }
                            restBaselinePosA = 0.0
                            restBaselinePosB = 0.0
                        } else {
                            log.w { "WaitingForRest TIMEOUT (${BleConstants.Timing.WAITING_FOR_REST_TIMEOUT_MS}ms) - capturing baseline posA=$posA, posB=$posB for relative grab detection" }
                            restBaselinePosA = posA
                            restBaselinePosB = posB
                        }
                        waitingForRestStartTime = null
                        HandleState.Released  // Force arm after timeout
                    } else {
                        // Still waiting for timeout
                        HandleState.WaitingForRest
                    }
                }
            }

            HandleState.Released, HandleState.Moving -> {
                // Check if EITHER handle is grabbed AND moving (for single-handle exercises)
                val aActive = handleAGrabbed && handleAMoving
                val bActive = handleBGrabbed && handleBMoving

                when {
                    aActive || bActive -> {
                        // Task 14: Handle state hysteresis - require 200ms sustained before transition
                        val currentTime = timeProvider()
                        if (pendingGrabbedStartTime == null) {
                            // Start dwell timer
                            pendingGrabbedStartTime = currentTime
                            currentState  // Stay in current state
                        } else if (currentTime - pendingGrabbedStartTime!! >= BleConstants.Timing.STATE_TRANSITION_DWELL_MS) {
                            // GRAB CONFIRMED - position AND velocity thresholds met for 200ms
                            val activeHandle = when {
                                aActive && bActive -> "both"
                                aActive -> "A"
                                else -> "B"
                            }
                            // Store which handle(s) are active for release detection
                            activeHandlesMask = (if (aActive) 1 else 0) or (if (bActive) 2 else 0)
                            log.i { "GRAB CONFIRMED: handle=$activeHandle mask=$activeHandlesMask (posA=${posA.format(1)}, posB=${posB.format(1)}, velA=${velocityA.format(0)}, velB=${velocityB.format(0)}) after ${BleConstants.Timing.STATE_TRANSITION_DWELL_MS}ms dwell" }
                            pendingGrabbedStartTime = null
                            HandleState.Grabbed
                        } else {
                            currentState  // Still dwelling
                        }
                    }
                    handleAGrabbed || handleBGrabbed -> {
                        // Position extended but no significant movement yet
                        pendingGrabbedStartTime = null  // Reset grab timer
                        HandleState.Moving
                    }
                    else -> {
                        // Back to rest position
                        pendingGrabbedStartTime = null  // Reset grab timer
                        HandleState.Released
                    }
                }
            }

            HandleState.Grabbed -> {
                // Release detection: only check handles that were actually grabbed
                // Issue #176: Use baseline-relative release detection for overhead pulley setups
                val aReleased = isBelowThreshold(posA, restBaselinePosA,
                    BleConstants.Thresholds.RELEASE_DELTA_THRESHOLD, BleConstants.Thresholds.HANDLE_REST_THRESHOLD)
                val bReleased = isBelowThreshold(posB, restBaselinePosB,
                    BleConstants.Thresholds.RELEASE_DELTA_THRESHOLD, BleConstants.Thresholds.HANDLE_REST_THRESHOLD)

                // Only check release on the handle(s) that were actually grabbed.
                // This prevents premature release detection when unused cable is at rest.
                val isReleased = when (activeHandlesMask) {
                    1 -> aReleased           // Only A was active - check A only
                    2 -> bReleased           // Only B was active - check B only
                    3 -> aReleased && bReleased  // Both active - both must release
                    else -> aReleased || bReleased  // Fallback (shouldn't happen)
                }

                if (isReleased) {
                    // Task 14: Handle state hysteresis - require 200ms sustained before release
                    val currentTime = timeProvider()
                    if (pendingReleasedStartTime == null) {
                        // Start dwell timer
                        pendingReleasedStartTime = currentTime
                        HandleState.Grabbed  // Stay grabbed
                    } else if (currentTime - pendingReleasedStartTime!! >= BleConstants.Timing.STATE_TRANSITION_DWELL_MS) {
                        log.d { "RELEASE DETECTED (mask=$activeHandlesMask): posA=$posA (baseline=${restBaselinePosA ?: "none"}), posB=$posB (baseline=${restBaselinePosB ?: "none"}) after ${BleConstants.Timing.STATE_TRANSITION_DWELL_MS}ms dwell" }
                        pendingReleasedStartTime = null
                        activeHandlesMask = 0  // Reset for next grab
                        HandleState.Released
                    } else {
                        HandleState.Grabbed  // Still dwelling
                    }
                } else {
                    pendingReleasedStartTime = null  // Reset release timer if handles move away from rest
                    HandleState.Grabbed
                }
            }
        }
    }

    /**
     * Check if a handle position exceeds a threshold relative to its baseline.
     * Falls back to an absolute threshold when no baseline is set.
     *
     * @param position Current handle position in mm
     * @param baseline Rest baseline position (null if not yet captured)
     * @param deltaThreshold Threshold for baseline-relative detection
     * @param absoluteThreshold Fallback threshold for absolute detection
     * @return true if position exceeds threshold
     */
    private fun isAboveThreshold(
        position: Double,
        baseline: Double?,
        deltaThreshold: Double,
        absoluteThreshold: Double
    ): Boolean = if (baseline != null) {
        (position - baseline) > deltaThreshold
    } else {
        position > absoluteThreshold
    }

    /**
     * Check if a handle position is below a threshold relative to its baseline.
     * Falls back to an absolute threshold when no baseline is set.
     */
    private fun isBelowThreshold(
        position: Double,
        baseline: Double?,
        deltaThreshold: Double,
        absoluteThreshold: Double
    ): Boolean = if (baseline != null) {
        (position - baseline) < deltaThreshold
    } else {
        position < absoluteThreshold
    }

    /**
     * Format a Double to a specific number of decimal places.
     * KMP-compatible (no String.format).
     */
    private fun Double.format(decimals: Int): String {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return ((this * factor).toLong() / factor).toString()
    }
}
