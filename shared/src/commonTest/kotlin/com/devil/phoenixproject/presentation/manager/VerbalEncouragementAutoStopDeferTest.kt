package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #649: while a verbal VBT cue is playing and the user has VBT auto-end OFF,
 * neither the AMRAP position-based nor the velocity-stall auto-stop may end the set.
 *
 * Mirrors the velocity-threshold state machine from VbtAutoEndTest / VerbalEncouragementGateTest.
 * Keeps the logic testable without spinning up the 17+ dependency ActiveSessionEngine.
 *
 * Cross-thread note: the production field is @Volatile. The tracker here doesn't
 * model the threading — it only mirrors the predicate (defer armed? deadline
 * passed?). The companion object constant is mirrored inline; production uses
 * ActiveSessionEngine.VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS.
 */
private const val VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS_TRACKER = 30_000L

private class VerbalEncouragementAutoStopDeferTracker(
    private val autoEndOnVelocityLoss: Boolean,
) {
    var alertEmitted = false
        private set
    var deferAutoStop = false
        private set
    private var deferDeadlineMs: Long = 0L

    fun onRepCompleted(shouldStopSet: Boolean, nowMs: Long) {
        if (shouldStopSet) {
            if (!alertEmitted) {
                alertEmitted = true
                // Verbal cue fired -> defer position/stall auto-stop until the next
                // completed working rep or the cue-window deadline, but only if
                // VBT auto-end is OFF.
                if (!autoEndOnVelocityLoss) {
                    deferAutoStop = true
                    deferDeadlineMs = nowMs + VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS_TRACKER
                }
            }
        }
    }

    fun onCompletedWorkingRep() {
        // A completed working rep proves the user is back in motion;
        // let normal AMRAP / stall auto-stop resume.
        deferAutoStop = false
        deferDeadlineMs = 0L
    }

    /** Returns true if the defer is still active at `nowMs`. */
    fun isDeferActive(nowMs: Long): Boolean {
        val deadline = deferDeadlineMs
        if (deadline == 0L) return false
        if (nowMs >= deadline) {
            deferAutoStop = false
            deferDeadlineMs = 0L
            return false
        }
        deferAutoStop = true
        return true
    }

    /** Mirrors resetAutoStopState(): every new-set reset clears the defer. */
    fun onResetAutoStopState() {
        deferAutoStop = false
        deferDeadlineMs = 0L
    }

    /** Test seam: read the published deadline (mirrors the @Volatile field). */
    fun deferDeadlineSnapshot(): Long = deferDeadlineMs
}

class VerbalEncouragementAutoStopDeferTest {

    @Test
    fun `autoEnd off - first velocity-threshold rep defers auto-stop`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertTrue(tracker.alertEmitted, "Velocity threshold alert is one-shot per set")
        assertTrue(tracker.deferAutoStop, "Defer must be active while verbal cue is in flight")
    }

    @Test
    fun `autoEnd on - first velocity-threshold rep does NOT defer auto-stop`() {
        // With VBT auto-end ON, the existing two-consecutive-rep behavior decides set end;
        // the defer guard would only suppress that and must NOT be armed.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = true)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertTrue(tracker.alertEmitted)
        assertFalse(
            tracker.deferAutoStop,
            "Defer must NOT arm when VBT auto-end is on — that branch handles set end itself",
        )
    }

    @Test
    fun `autoEnd off - next completed working rep clears the defer`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)
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
        tracker.onRepCompleted(shouldStopSet = false, nowMs = 0L)
        tracker.onRepCompleted(shouldStopSet = false, nowMs = 0L)

        assertFalse(tracker.alertEmitted)
        assertFalse(tracker.deferAutoStop)
    }

    @Test
    fun `autoEnd off - defer stays active within the cue window`() {
        // Cue fires, user keeps exercising without a completed working rep, but the
        // window hasn't elapsed — defer must stay armed so position/stall can't end the set.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 10_000L)

        assertTrue(tracker.isDeferActive(nowMs = 20_000L))
        assertTrue(
            tracker.deferAutoStop,
            "Within the cue window defer must remain active even without a completed rep",
        )
    }

    @Test
    fun `autoEnd off - defer expires at the cue window deadline`() {
        // User rackets handles mid-set after the cue and never completes a working rep.
        // Once the deadline passes, the defer must release so AMRAP/stall auto-stop can fire.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertTrue(tracker.isDeferActive(nowMs = 29_999L), "Just before deadline still defers")
        assertFalse(
            tracker.isDeferActive(nowMs = 30_001L),
            "Past the deadline the defer must clear so auto-stop can fire normally",
        )
        assertFalse(tracker.deferAutoStop, "Expired defer must clear the flag")
    }

    @Test
    fun `autoEnd off - resetAutoStopState clears the defer for the next set`() {
        // Verbal cue armed the defer mid-set. Before the 30s deadline the user
        // skips / restarts. The next active set must not inherit the defer —
        // otherwise checkAutoStop() would suppress AMRAP/stall auto-stop there too.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 10_000L)
        assertTrue(tracker.isDeferActive(nowMs = 20_000L))

        tracker.onResetAutoStopState()

        assertFalse(tracker.deferAutoStop)
        assertEquals(
            0L, tracker.deferDeadlineSnapshot(),
            "resetAutoStopState must zero the deadline so the source-of-truth predicate clears",
        )
        assertFalse(
            tracker.isDeferActive(nowMs = 20_000L),
            "After resetAutoStopState() the new set must allow normal auto-stop behavior",
        )
    }

    @Test
    fun `arm publishes deadline before flag is observable to readers`() {
        // Mirrors the production publish order: deadline first, flag second.
        // The tracker mirrors the production single-source-of-truth pattern
        // (deadline != 0L is the gate); this test pins the deadline value at
        // arming time so a reader that arrives the instant the cue fires sees
        // a future deadline and never the stale 0L value.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 5_000L)
        assertEquals(35_000L, tracker.deferDeadlineSnapshot())
        assertTrue(tracker.isDeferActive(nowMs = 5_000L))
    }
}
