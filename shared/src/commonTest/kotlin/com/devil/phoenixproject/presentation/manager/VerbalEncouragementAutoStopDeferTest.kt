package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #649: while a verbal VBT cue is playing and the user has VBT auto-end OFF,
 * neither the AMRAP position-based nor the velocity-stall auto-stop may end the set.
 *
 * Mirrors the velocity-threshold state machine from VbtAutoEndTest / VerbalEncouragementGateTest.
 * Keeps the logic testable without spinning up the 17+ dependency ActiveSessionEngine.
 */
private class VerbalEncouragementAutoStopDeferTracker(
    private val autoEndOnVelocityLoss: Boolean,
) {
    var alertEmitted = false
        private set
    var deferAutoStop = false
        private set

    fun onRepCompleted(shouldStopSet: Boolean) {
        if (shouldStopSet) {
            if (!alertEmitted) {
                alertEmitted = true
                // Verbal cue fired -> defer position/stall auto-stop until the next
                // completed working rep, but only if VBT auto-end is OFF.
                if (!autoEndOnVelocityLoss) {
                    deferAutoStop = true
                }
            }
        }
    }

    fun onCompletedWorkingRep() {
        // A completed working rep proves the user is back in motion;
        // let normal AMRAP / stall auto-stop resume.
        deferAutoStop = false
    }
}

class VerbalEncouragementAutoStopDeferTest {

    @Test
    fun `autoEnd off - first velocity-threshold rep defers auto-stop`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true)

        assertTrue(tracker.alertEmitted, "Velocity threshold alert is one-shot per set")
        assertTrue(tracker.deferAutoStop, "Defer must be active while verbal cue is in flight")
    }

    @Test
    fun `autoEnd on - first velocity-threshold rep does NOT defer auto-stop`() {
        // With VBT auto-end ON, the existing two-consecutive-rep behavior decides set end;
        // the defer guard would only suppress that and must NOT be armed.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = true)
        tracker.onRepCompleted(shouldStopSet = true)

        assertTrue(tracker.alertEmitted)
        assertFalse(
            tracker.deferAutoStop,
            "Defer must NOT arm when VBT auto-end is on — that branch handles set end itself",
        )
    }

    @Test
    fun `autoEnd off - next completed working rep clears the defer`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true)
        assertTrue(tracker.deferAutoStop)

        tracker.onCompletedWorkingRep()

        assertFalse(
            tracker.deferAutoStop,
            "After a completed working rep the defer must clear so AMRAP/stall can fire again",
        )
    }

    @Test
    fun `autoEnd off - non-threshold reps do not arm the defer`() {
        // ShouldStopSet=false never reaches the alert branch, so the defer never sets.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = false)
        tracker.onRepCompleted(shouldStopSet = false)

        assertFalse(tracker.alertEmitted)
        assertFalse(tracker.deferAutoStop)
    }
}
