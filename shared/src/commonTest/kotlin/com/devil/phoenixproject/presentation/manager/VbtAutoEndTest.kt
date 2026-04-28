package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-local class mirroring the velocity threshold checking logic that
 * ActiveSessionEngine uses (or will use after Plan 43-02 wiring).
 *
 * Isolates the auto-end decision flow from the 17+ dependency ActiveSessionEngine
 * so we can verify state machine behavior independently.
 */
private class VelocityThresholdTracker(
    private val autoEndEnabled: Boolean,
    private val onAlert: () -> Unit,
    private val onAutoEnd: () -> Unit,
) {
    var alertEmitted = false
        private set
    var consecutiveThresholdReps = 0
        private set

    fun onRepCompleted(shouldStopSet: Boolean) {
        if (shouldStopSet) {
            consecutiveThresholdReps++
            if (!alertEmitted) {
                alertEmitted = true
                onAlert()
            }
            if (consecutiveThresholdReps >= 2 && autoEndEnabled) {
                onAutoEnd()
            }
        } else {
            consecutiveThresholdReps = 0
        }
    }

    fun reset() {
        alertEmitted = false
        consecutiveThresholdReps = 0
    }
}

class VbtAutoEndTest {

    @Test
    fun `alert emitted once per set when threshold reached`() {
        var alertCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = { alertCount++ },
            onAutoEnd = {},
        )

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, alertCount, "First threshold rep emits alert")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, alertCount, "Second threshold rep does not re-emit alert")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, alertCount, "Third threshold rep does not re-emit alert")
    }

    @Test
    fun `no alert when threshold not reached`() {
        var alertCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = { alertCount++ },
            onAutoEnd = {},
        )

        tracker.onRepCompleted(shouldStopSet = false)
        tracker.onRepCompleted(shouldStopSet = false)
        tracker.onRepCompleted(shouldStopSet = false)

        assertEquals(0, alertCount, "No alert when threshold never reached")
    }

    @Test
    fun `grace period - single threshold rep does not auto-end`() {
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = {},
            onAutoEnd = { autoEndCount++ },
        )

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(0, autoEndCount, "Single threshold rep should not trigger auto-end (grace period)")
    }

    @Test
    fun `auto-end triggers on second consecutive threshold rep`() {
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = {},
            onAutoEnd = { autoEndCount++ },
        )

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(0, autoEndCount, "First threshold rep: no auto-end")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, autoEndCount, "Second consecutive threshold rep: auto-end triggered")
    }

    @Test
    fun `recovery rep resets consecutive counter`() {
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = {},
            onAutoEnd = { autoEndCount++ },
        )

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = false)
        assertEquals(0, tracker.consecutiveThresholdReps, "Recovery rep resets counter")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(0, autoEndCount, "After recovery, single threshold rep does not auto-end")
    }

    @Test
    fun `auto-end disabled - threshold reps do not end set`() {
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = false,
            onAlert = {},
            onAutoEnd = { autoEndCount++ },
        )

        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)

        assertEquals(0, autoEndCount, "Auto-end disabled: never triggers regardless of consecutive reps")
    }

    @Test
    fun `state resets between sets`() {
        var alertCount = 0
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = { alertCount++ },
            onAutoEnd = { autoEndCount++ },
        )

        // Set 1: trigger alert and auto-end
        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, alertCount)
        assertEquals(1, autoEndCount)
        assertTrue(tracker.alertEmitted)
        assertEquals(2, tracker.consecutiveThresholdReps)

        // Reset for new set
        tracker.reset()
        assertFalse(tracker.alertEmitted, "alertEmitted cleared after reset")
        assertEquals(0, tracker.consecutiveThresholdReps, "consecutiveThresholdReps cleared after reset")

        // Set 2: can trigger fresh alert and auto-end
        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(2, alertCount, "New alert emitted in set 2")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(2, autoEndCount, "New auto-end triggered in set 2")
    }

    @Test
    fun `auto-end calls onAutoEnd exactly once per trigger`() {
        var autoEndCount = 0
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = {},
            onAutoEnd = { autoEndCount++ },
        )

        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, autoEndCount, "Auto-end triggered once at 2 consecutive reps")

        // Third consecutive rep still passes the >= 2 check
        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(2, autoEndCount, "Third consecutive rep also triggers (>=2)")
    }

    @Test
    fun `consecutive counter tracks correctly across mixed reps`() {
        val tracker = VelocityThresholdTracker(
            autoEndEnabled = true,
            onAlert = {},
            onAutoEnd = {},
        )

        // Sequence: false, true, true, false, true, false, true, true
        tracker.onRepCompleted(shouldStopSet = false)
        assertEquals(0, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(2, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = false)
        assertEquals(0, tracker.consecutiveThresholdReps, "Recovery resets counter")

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = false)
        assertEquals(0, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(1, tracker.consecutiveThresholdReps)

        tracker.onRepCompleted(shouldStopSet = true)
        assertEquals(2, tracker.consecutiveThresholdReps)
    }
}
