package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.ControllableClock
import com.devil.phoenixproject.testutil.FakeSyncTriggerHost
import com.devil.phoenixproject.testutil.productionSyncTriggerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Exponential backoff schedule 5 → 15 → 30 → 60 minutes on the production
 * [SyncTriggerManager], with an injected clock.
 */
class SyncBackoffTest {

    private fun nextRetryDelayMinutes(trigger: SyncTriggerManager): Int? =
        trigger.retryState.value.nextRetryDelayMinutes

    @Test
    fun backoffScheduleMatchesFiveFifteenThirtySixty() {
        assertEquals(
            listOf(5, 15, 30, 60),
            SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES,
            "Documented schedule is 5 → 15 → 30 → 60 minutes",
        )
    }

    @Test
    fun consecutiveFailuresStepThroughScheduleExactly() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("boom", null, 500))

        trigger.onWorkoutCompleted()
        assertEquals(5, nextRetryDelayMinutes(trigger), "Step 1 = 5 min")

        trigger.onWorkoutCompleted()
        assertEquals(15, nextRetryDelayMinutes(trigger), "Step 2 = 15 min")

        trigger.onWorkoutCompleted()
        assertEquals(30, nextRetryDelayMinutes(trigger), "Step 3 = 30 min")

        trigger.onWorkoutCompleted()
        assertEquals(60, nextRetryDelayMinutes(trigger), "Step 4 = 60 min")
    }

    @Test
    fun backoffScheduleCapsAtSixtyMinutes() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("boom", null, 500))

        repeat(6) { trigger.onWorkoutCompleted() }

        assertEquals(
            60,
            nextRetryDelayMinutes(trigger),
            "After 5+ transient failures the delay caps at the last scheduled value (60)",
        )
    }

    @Test
    fun successfulSyncResetsBackoffCounterToZero() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(30, nextRetryDelayMinutes(trigger), "pre-condition: backoff has ratcheted up")

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()

        assertEquals(0, trigger.retryState.value.retryCount, "Success resets failure counter")
        assertNull(nextRetryDelayMinutes(trigger), "No next-retry delay after a clean success")
    }

    @Test
    fun manualWorkoutCompleteBypassesBackoffWindow() = runTest {
        val host = FakeSyncTriggerHost()
        val clock = ControllableClock(1_000_000L)
        val trigger = productionSyncTriggerManager(host, clock = clock)

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()
        val afterFirst = host.syncCallCount

        clock.advance(30_000L)
        trigger.onWorkoutCompleted()

        assertEquals(
            afterFirst + 1,
            host.syncCallCount,
            "onWorkoutCompleted must bypass the throttle/backoff window",
        )
    }

    @Test
    fun foregroundTriggerIsSuppressedInsideBackoffWindow() = runTest {
        val host = FakeSyncTriggerHost()
        val clock = ControllableClock(1_000_000L)
        val trigger = productionSyncTriggerManager(host, clock = clock)

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onAppForeground()
        val firstCount = host.syncCallCount
        assertEquals(1, firstCount, "First foreground sync should run")

        clock.advance(60_000L)
        trigger.onAppForeground()
        assertEquals(
            firstCount,
            host.syncCallCount,
            "Foreground trigger inside throttle window must not call sync",
        )

        clock.advance(5 * 60 * 1000L + 1)
        trigger.onAppForeground()
        assertTrue(
            host.syncCallCount > firstCount,
            "Foreground trigger after throttle window should call sync again",
        )
    }

    @Test
    fun foregroundTriggerRespectsEscalatedBackoffAfterFailures() = runTest {
        val host = FakeSyncTriggerHost()
        val clock = ControllableClock(1_000_000L)
        val trigger = productionSyncTriggerManager(host, clock = clock)

        host.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(15, nextRetryDelayMinutes(trigger), "After 2 failures backoff = 15 min")
        val callsAfterFailures = host.syncCallCount

        clock.advance(10 * 60 * 1000L)
        trigger.onAppForeground()
        assertEquals(
            callsAfterFailures,
            host.syncCallCount,
            "Foreground trigger at t=10min (inside 15-min backoff) must be suppressed",
        )

        clock.advance(11 * 60 * 1000L)
        trigger.onAppForeground()
        assertTrue(
            host.syncCallCount > callsAfterFailures,
            "Foreground trigger after the escalated backoff window must run",
        )
    }
}
