package com.devil.phoenixproject.presentation.manager

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Events emitted by [MotionStartDetector] as the user holds
 * the cable handles under load.
 */
sealed class MotionStartEvent {
    /** Periodic progress tick while load is sustained. [remainingMs] counts down toward zero. */
    data class CountdownTick(val remainingMs: Long) : MotionStartEvent()
    /** Emitted once when the hold duration has been satisfied -- the set should begin. */
    data object Started : MotionStartEvent()
    /** Emitted when load drops below threshold before the hold completes, resetting the countdown. */
    data object Cancelled : MotionStartEvent()
}

/**
 * Reusable cable-hold detection utility.
 *
 * Watches a stream of metric samples (load + timestamp) and emits
 * [MotionStartEvent]s when the user grabs the handles and sustains
 * load above [loadThresholdKg] for [holdDurationMs] milliseconds.
 *
 * This is a pure state-machine driven by [onMetricReceived] calls --
 * it owns no coroutine scope and does not use `delay()`, making it
 * trivially testable with virtual time.
 *
 * Extracted from the inline auto-start logic in [ActiveSessionEngine]
 * so it can be reused for Routine and Single Exercise flows (Task 4).
 */
class MotionStartDetector(
    private val loadThresholdKg: Float = 5f,
    private val holdDurationMs: Long = 1500L
) {
    private val _events = MutableSharedFlow<MotionStartEvent>()
    val events: SharedFlow<MotionStartEvent> = _events

    private var holdStartMs: Long? = null
    private var hasTriggered: Boolean = false

    /**
     * Feed a metric sample into the detector.
     *
     * @param load current cable load in kg (per-cable)
     * @param timestampMs monotonic timestamp of the sample in milliseconds
     */
    suspend fun onMetricReceived(load: Float, timestampMs: Long) {
        if (hasTriggered) return // Already fired Started; ignore until reset

        if (load >= loadThresholdKg) {
            val start = holdStartMs ?: run {
                holdStartMs = timestampMs
                timestampMs
            }
            val elapsed = timestampMs - start
            if (elapsed >= holdDurationMs) {
                hasTriggered = true
                _events.emit(MotionStartEvent.Started)
                holdStartMs = null
            } else if (elapsed > 0) {
                _events.emit(MotionStartEvent.CountdownTick(holdDurationMs - elapsed))
            }
        } else {
            if (holdStartMs != null) {
                _events.emit(MotionStartEvent.Cancelled)
                holdStartMs = null
            }
        }
    }

    /** Reset all internal state so the detector can be reused for the next set. */
    fun reset() {
        holdStartMs = null
        hasTriggered = false
    }
}
