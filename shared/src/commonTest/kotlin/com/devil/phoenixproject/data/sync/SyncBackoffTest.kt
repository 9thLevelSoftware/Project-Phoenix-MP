package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeSyncTriggerTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for the documented exponential backoff schedule 5 → 15 → 30 → 60 minutes in the
 * production [SyncTriggerManager], with its clock injected so throttle windows can be
 * crossed without real waits (#869: this used to test a hand-copied mirror).
 *
 * Scope covered:
 *  - After N consecutive transient failures, the next-retry delay matches the schedule.
 *  - Schedule caps at 60 min (no growth beyond 4 failures).
 *  - Successful sync resets the counter to 0 (next delay goes back to null / 5-min throttle).
 *  - Manual trigger (onWorkoutCompleted) bypasses throttle/backoff and still calls sync.
 *  - Throttled trigger (onAppForeground) respects backoff and is suppressed within window.
 */
class SyncBackoffTest {

    private class Fixture {
        val target = FakeSyncTriggerTarget()
        var now = 1_000_000L
        val trigger = SyncTriggerManager(syncManager = target, isOnline = { true }, nowMillis = { now })

        fun nextRetryDelayMinutes(): Int? = trigger.retryState.value.nextRetryDelayMinutes

        fun advanceTime(millis: Long) {
            now += millis
        }
    }

    @Test
    fun backoffScheduleMatchesFiveFifteenThirtySixty() {
        assertEquals(listOf(5, 15, 30, 60), SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES)
    }

    @Test
    fun consecutiveFailuresStepThroughScheduleExactly() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))

        // onWorkoutCompleted bypasses throttle so each attempt actually runs.
        f.trigger.onWorkoutCompleted()
        assertEquals(5, f.nextRetryDelayMinutes(), "Step 1 = 5 min")
        f.trigger.onWorkoutCompleted()
        assertEquals(15, f.nextRetryDelayMinutes(), "Step 2 = 15 min")
        f.trigger.onWorkoutCompleted()
        assertEquals(30, f.nextRetryDelayMinutes(), "Step 3 = 30 min")
        f.trigger.onWorkoutCompleted()
        assertEquals(60, f.nextRetryDelayMinutes(), "Step 4 = 60 min")
    }

    @Test
    fun backoffScheduleCapsAtSixtyMinutes() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))

        repeat(6) { f.trigger.onWorkoutCompleted() }

        assertEquals(60, f.nextRetryDelayMinutes(), "After 5+ transient failures the delay caps at 60")
        assertEquals(6, f.trigger.retryState.value.retryCount)
    }

    @Test
    fun nextRetryAtIsMeasuredFromTheLastAttempt() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))

        f.trigger.onWorkoutCompleted()

        assertEquals(f.now + 5 * 60 * 1000L, f.trigger.retryState.value.nextRetryAtMillis)
    }

    @Test
    fun successfulSyncResetsBackoffCounterToZero() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))
        repeat(3) { f.trigger.onWorkoutCompleted() }
        assertEquals(30, f.nextRetryDelayMinutes(), "pre-condition: backoff has ratcheted up")

        f.target.syncResult = Result.success(2L)
        f.trigger.onWorkoutCompleted()

        assertEquals(0, f.trigger.retryState.value.retryCount, "Success resets failure counter")
        assertNull(f.nextRetryDelayMinutes(), "No next-retry delay after a clean success")
    }

    @Test
    fun manualWorkoutCompleteBypassesBackoffWindow() = runTest {
        val f = Fixture()
        f.trigger.onWorkoutCompleted()
        val afterFirst = f.target.syncCallCount

        // Still within the 5-minute default throttle: a workout-complete trigger MUST still sync.
        f.advanceTime(30_000L)
        f.trigger.onWorkoutCompleted()

        assertEquals(afterFirst + 1, f.target.syncCallCount, "onWorkoutCompleted must bypass the throttle")
    }

    @Test
    fun foregroundTriggerIsSuppressedInsideBackoffWindow() = runTest {
        val f = Fixture()
        f.trigger.onAppForeground()
        assertEquals(1, f.target.syncCallCount, "First foreground sync should run")

        f.advanceTime(60_000L)
        f.trigger.onAppForeground()
        assertEquals(1, f.target.syncCallCount, "Foreground trigger inside throttle window must not call sync")

        f.advanceTime(5 * 60 * 1000L + 1)
        f.trigger.onAppForeground()
        assertEquals(2, f.target.syncCallCount, "Foreground trigger after throttle window should sync again")
    }

    @Test
    fun foregroundTriggerRespectsEscalatedBackoffAfterFailures() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))
        f.trigger.onWorkoutCompleted()
        f.trigger.onWorkoutCompleted()
        assertEquals(15, f.nextRetryDelayMinutes(), "After 2 failures backoff = 15 min")
        val callsAfterFailures = f.target.syncCallCount

        f.advanceTime(10 * 60 * 1000L)
        f.trigger.onAppForeground()
        assertEquals(callsAfterFailures, f.target.syncCallCount, "t=10min is inside the 15-min backoff")

        f.advanceTime(11 * 60 * 1000L)
        f.trigger.onAppForeground()
        assertTrue(f.target.syncCallCount > callsAfterFailures, "Past the escalated window the trigger must run")
    }
}
