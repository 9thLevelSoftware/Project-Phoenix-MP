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
 * Single source of truth is a @Volatile Long deadline — 0L means no defer. The
 * tracker mirrors that pattern: an `isDeferActive(nowMs)` predicate derived from
 * the deadline only, with the flag computed lazily inside the predicate.
 */
private const val VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS_TRACKER = 30_000L

private class VerbalEncouragementAutoStopDeferTracker(
    private val autoEndOnVelocityLoss: Boolean,
) {
    private var deferDeadlineMs: Long = 0L

    fun onRepCompleted(shouldStopSet: Boolean, nowMs: Long) {
        if (shouldStopSet) {
            if (deferDeadlineMs == 0L && !autoEndOnVelocityLoss) {
                // Verbal cue fires once per set when VBT auto-end is OFF.
                deferDeadlineMs = nowMs + VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS_TRACKER
            }
        }
    }

    fun onCompletedWorkingRep() {
        // A completed working rep proves the user is back in motion; let normal
        // AMRAP / stall auto-stop resume by zeroing the deadline.
        deferDeadlineMs = 0L
    }

    /** Returns true if the defer is still active at `nowMs`. */
    fun isDeferActive(nowMs: Long): Boolean {
        val deadline = deferDeadlineMs
        if (deadline == 0L) return false
        if (nowMs >= deadline) {
            deferDeadlineMs = 0L
            return false
        }
        return true
    }

    /** Mirrors resetAutoStopState(): every new-set reset clears the defer. */
    fun onResetAutoStopState() {
        deferDeadlineMs = 0L
    }

    /** Test seam: read the published deadline (mirrors the @Volatile field). */
    fun deferDeadlineSnapshot(): Long = deferDeadlineMs

    /** Test seam: true when no defer is armed (one-shot is fired unless VBT auto-end). */
    fun armEligible(): Boolean = deferDeadlineMs == 0L && !autoEndOnVelocityLoss
}

class VerbalEncouragementAutoStopDeferTest {

    @Test
    fun `autoEnd off - first velocity-threshold rep arms the defer with a future deadline`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertEquals(30_000L, tracker.deferDeadlineSnapshot())
        assertTrue(tracker.isDeferActive(nowMs = 0L))
        assertTrue(tracker.isDeferActive(nowMs = 29_999L))
    }

    @Test
    fun `autoEnd on - first velocity-threshold rep does NOT arm the defer`() {
        // With VBT auto-end ON, the existing two-consecutive-rep behavior decides set end;
        // the defer guard would only suppress that and must NOT be armed.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = true)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertEquals(0L, tracker.deferDeadlineSnapshot())
        assertFalse(tracker.isDeferActive(nowMs = 0L))
    }

    @Test
    fun `next completed working rep clears the defer regardless of deadline remaining`() {
        // Verbal cue armed at t=5s, user completes a working rep at t=10s.
        // The defer must clear immediately, even though the 30s window hasn't expired,
        // so AMRAP / stall auto-stop can fire normally on subsequent metrics.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 5_000L)
        assertTrue(tracker.isDeferActive(nowMs = 10_000L))

        tracker.onCompletedWorkingRep()

        assertEquals(0L, tracker.deferDeadlineSnapshot())
        assertFalse(
            tracker.isDeferActive(nowMs = 10_000L),
            "After a completed working rep the defer must clear so AMRAP/stall can fire again",
        )
        assertFalse(
            tracker.isDeferActive(nowMs = 29_999L),
            "Re-arm must require another velocity-threshold-triggered cue, not automatic re-derivation",
        )
    }

    @Test
    fun `autoEnd off - non-threshold reps do not arm the defer`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = false, nowMs = 0L)
        tracker.onRepCompleted(shouldStopSet = false, nowMs = 0L)

        assertEquals(0L, tracker.deferDeadlineSnapshot())
        assertFalse(tracker.isDeferActive(nowMs = 0L))
        assertTrue(tracker.armEligible(), "Cue hasn't fired yet, so arm remains eligible")
    }

    @Test
    fun `defer stays active within the cue window`() {
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 10_000L)

        assertTrue(tracker.isDeferActive(nowMs = 20_000L))
    }

    @Test
    fun `defer expires at the cue window deadline`() {
        // User rackets handles mid-set after the cue and never completes a working rep.
        // Once the deadline passes, the defer must release so AMRAP/stall auto-stop can fire.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 0L)

        assertTrue(tracker.isDeferActive(nowMs = 29_999L), "Just before deadline still defers")
        assertFalse(
            tracker.isDeferActive(nowMs = 30_001L),
            "Past the deadline the defer must clear so auto-stop can fire normally",
        )
        assertEquals(0L, tracker.deferDeadlineSnapshot(), "Expiry must zero the deadline")
    }

    @Test
    fun `resetAutoStopState clears the defer for the next set`() {
        // Verbal cue armed the defer mid-set. Before the 30s deadline the user
        // skips / restarts. The next active set must not inherit the defer —
        // otherwise checkAutoStop() would suppress AMRAP/stall auto-stop there too.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 10_000L)
        assertTrue(tracker.isDeferActive(nowMs = 20_000L))

        tracker.onResetAutoStopState()

        assertEquals(0L, tracker.deferDeadlineSnapshot())
        assertTrue(tracker.armEligible(), "Next set must allow a fresh cue to arm again")
        assertFalse(
            tracker.isDeferActive(nowMs = 20_000L),
            "After resetAutoStopState() the new set must allow normal auto-stop behavior",
        )
    }

    @Test
    fun `arm publishes future deadline that a metrics-thread reader sees immediately`() {
        // Single-source-of-truth guarantee: the deadline field is the gate.
        // Even a reader that observes the field the instant the cue fires sees
        // a future deadline, never the stale 0L value.
        val tracker = VerbalEncouragementAutoStopDeferTracker(autoEndOnVelocityLoss = false)
        tracker.onRepCompleted(shouldStopSet = true, nowMs = 5_000L)
        assertEquals(35_000L, tracker.deferDeadlineSnapshot())
        assertTrue(tracker.isDeferActive(nowMs = 5_000L))
    }
}
