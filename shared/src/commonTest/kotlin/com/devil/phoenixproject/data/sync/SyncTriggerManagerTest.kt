package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.ControllableClock
import com.devil.phoenixproject.testutil.FakeSyncTriggerHost
import com.devil.phoenixproject.testutil.productionSyncTriggerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the production [SyncTriggerManager].
 *
 * Clock and connectivity are injected; backoff/throttle logic is not copied.
 */
class SyncTriggerManagerTest {

    private fun delayMinutes(trigger: SyncTriggerManager): Int? =
        trigger.retryState.value.nextRetryDelayMinutes

    private fun retryCount(trigger: SyncTriggerManager): Int =
        trigger.retryState.value.retryCount

    private fun lastCategory(trigger: SyncTriggerManager): SyncErrorCategory? =
        trigger.retryState.value.lastErrorCategory

    // ==================== Production trigger contract ====================

    @Test
    fun onWorkoutCompletedCallsSyncBypassingThrottle() = runTest {
        val host = FakeSyncTriggerHost()
        val clock = ControllableClock(1_000_000L)
        val trigger = productionSyncTriggerManager(host, clock = clock)

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()
        assertEquals(1, host.syncCallCount)

        clock.advance(30_000L)
        trigger.onWorkoutCompleted()
        assertEquals(
            2,
            host.syncCallCount,
            "onWorkoutCompleted must call sync even inside the throttle window",
        )
    }

    @Test
    fun notPremiumSkipsSyncAfterFirstSync() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.setPremium(false)
        host.setLastSyncTime(1000L)

        trigger.onWorkoutCompleted()

        assertEquals(0, host.syncCallCount, "Sync should be skipped for non-premium after first sync")
    }

    @Test
    fun notPremiumAllowsFirstSync() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.setPremium(false)
        host.setLastSyncTime(0L)

        trigger.onWorkoutCompleted()

        assertEquals(1, host.syncCallCount, "First sync should be allowed for non-premium")
    }

    // ==================== Backoff Progression Tests ====================

    @Test
    fun backoffIndexProgressesOnTransientError() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        trigger.onWorkoutCompleted()
        assertEquals(5, delayMinutes(trigger), "Delay should be 5 minutes")

        trigger.onWorkoutCompleted()
        assertEquals(15, delayMinutes(trigger), "Delay should be 15 minutes")

        trigger.onWorkoutCompleted()
        assertEquals(30, delayMinutes(trigger), "Delay should be 30 minutes")

        trigger.onWorkoutCompleted()
        assertEquals(60, delayMinutes(trigger), "Delay should be 60 minutes")

        trigger.onWorkoutCompleted()
        assertEquals(60, delayMinutes(trigger), "Delay should stay at 60 minutes")
    }

    @Test
    fun backoffResetsOnSuccess() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(15, delayMinutes(trigger))
        assertEquals(2, retryCount(trigger))

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()

        assertNull(delayMinutes(trigger), "No retry delay needed")
        assertEquals(0, retryCount(trigger), "Failures should reset to 0")
        assertNull(lastCategory(trigger), "Error category should be null")
    }

    // ==================== Retry Storm Prevention Tests ====================

    @Test
    fun threeTransientFailuresDoNotLatchPersistentError() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "Should not have persistent error after 1 failure")

        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "Should not have persistent error after 2 failures")

        trigger.onWorkoutCompleted()
        assertFalse(
            trigger.hasPersistentError.value,
            "TRANSIENT storms must not latch persistent error (backoff only)",
        )
        assertEquals(3, retryCount(trigger))
    }

    @Test
    fun persistentErrorClearedOnManualReset() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Bad request", null, 400))
        trigger.onWorkoutCompleted()
        assertTrue(trigger.hasPersistentError.value, "PERMANENT should latch persistent error")

        trigger.clearError()

        assertFalse(trigger.hasPersistentError.value, "Persistent error should be cleared")
        assertNull(delayMinutes(trigger), "Backoff should be reset")
        assertEquals(0, retryCount(trigger), "Failures should be reset")
    }

    // ==================== Connectivity Tests ====================

    @Test
    fun connectivityRestoredTriggersSync() = runTest {
        val host = FakeSyncTriggerHost()
        var online = false
        val trigger = productionSyncTriggerManager(host, isOnline = { online })

        trigger.onAppForeground()

        assertTrue(
            trigger.retryState.value.isWaitingForConnectivity,
            "Should be waiting for connectivity when offline",
        )
        assertEquals(0, host.syncCallCount, "Sync should not be called when offline")

        online = true
        host.syncResult = Result.success(currentTimeMillis())
        trigger.onConnectivityRestored()

        assertFalse(
            trigger.retryState.value.isWaitingForConnectivity,
            "Should no longer be waiting for connectivity",
        )
        assertEquals(1, host.syncCallCount, "Sync should be called on connectivity restore")
    }

    @Test
    fun networkErrorSetsWaitingForConnectivity() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        class ConnectionException(message: String) : Exception(message)
        host.syncResult = Result.failure(ConnectionException("Connection refused"))

        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.NETWORK, lastCategory(trigger), "Should classify as NETWORK error")
        assertTrue(
            trigger.retryState.value.isWaitingForConnectivity,
            "Should be waiting for connectivity after network error",
        )
        assertNull(delayMinutes(trigger), "Backoff should not increase for network errors")
    }

    // ==================== Permanent Error Tests ====================

    @Test
    fun permanentErrorDoesNotTriggerBackoff() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Bad request", null, 400))

        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.PERMANENT, lastCategory(trigger), "Should classify as PERMANENT error")
        assertNull(delayMinutes(trigger), "Backoff should NOT increase for permanent errors")
        assertTrue(
            trigger.hasPersistentError.value,
            "Should set persistent error for permanent errors",
        )
    }

    @Test
    fun permanentErrorResetsExistingBackoff() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(15, delayMinutes(trigger), "Should have backoff from transient errors")

        host.syncResult = Result.failure(PortalApiException("Not found", null, 404))
        trigger.clearError()
        trigger.onWorkoutCompleted()

        assertNull(delayMinutes(trigger), "Permanent error should reset backoff to 0")
    }

    // ==================== Auth Error Tests ====================

    @Test
    fun authErrorClearsAndDoesNotRetry() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Unauthorized", null, 401))

        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.AUTH, lastCategory(trigger), "Should classify as AUTH error")
        assertNull(delayMinutes(trigger), "Auth errors should NOT increase backoff")
        assertTrue(
            trigger.hasPersistentError.value,
            "Auth errors should set persistent error (requires re-login)",
        )
        assertTrue(
            trigger.retryState.value.requiresReLogin,
            "Retry state should indicate re-login required",
        )
    }

    @Test
    fun authErrorResetsExistingBackoff() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        trigger.onWorkoutCompleted()
        assertEquals(5, delayMinutes(trigger))

        host.syncResult = Result.failure(PortalApiException("Token expired", null, 401))
        trigger.clearError()
        trigger.onWorkoutCompleted()

        assertNull(delayMinutes(trigger), "Auth error should reset backoff")
    }

    // ==================== Edge Cases ====================

    @Test
    fun partialSuccessTreatedAsTransientFailure() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.preserveSyncState = true
        host.syncResult = Result.success(currentTimeMillis())
        host.setSyncState(
            SyncState.PartialSuccess(
                pushSucceeded = true,
                pullSucceeded = false,
                lastSyncTime = currentTimeMillis(),
                pullError = "Network timeout on pull",
            ),
        )

        trigger.onWorkoutCompleted()

        assertEquals(5, delayMinutes(trigger), "Partial success should trigger backoff")
        assertEquals(1, retryCount(trigger), "Should count as a failure")
    }

    @Test
    fun backoffScheduleMatchesDocumentedValues() {
        assertEquals(
            listOf(5, 15, 30, 60),
            SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES,
            "Backoff schedule should be [5, 15, 30, 60] minutes",
        )
    }

    @Test
    fun notAuthenticatedSkipsSync() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.setAuthenticated(false)

        trigger.onWorkoutCompleted()

        assertEquals(0, host.syncCallCount, "Sync should not be called when not authenticated")
    }

    @Test
    fun rateLimitedErrorIsTransientAndRetryable() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("Rate limited", null, 429))

        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.TRANSIENT, lastCategory(trigger), "429 should be TRANSIENT")
        assertEquals(5, delayMinutes(trigger), "Should trigger backoff")
        assertFalse(
            trigger.hasPersistentError.value,
            "Single rate limit should not trigger persistent error",
        )
    }

    // ==================== Issue #566 Foreground Crash Containment Tests ====================

    @Test
    fun onAppForegroundRecordsFailureAndDoesNotPropagateWhenPremiumRefreshThrows() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        class SimulatedPremiumRefreshCrash(message: String) : Exception(message)
        host.refreshPremiumStatusThrows =
            SimulatedPremiumRefreshCrash("simulated premium refresh failure after wake")

        trigger.onAppForeground()

        assertEquals(1, host.refreshPremiumCallCount, "Premium refresh should have been attempted")
        assertEquals(0, host.syncCallCount, "attemptSync should not be reached after premium refresh threw")
        assertEquals(1, retryCount(trigger), "Foreground failure should be recorded")
        assertEquals(1, trigger.retryState.value.retryCount, "RetryState should reflect the foreground failure")
        assertNotNull(lastCategory(trigger), "Error category should be classified and recorded")
    }

    @Test
    fun onAppForegroundRethrowsCancellationException() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.refreshPremiumStatusThrows = CancellationException("lifecycle cancelled")

        assertFailsWith<CancellationException> {
            trigger.onAppForeground()
        }

        assertEquals(1, host.refreshPremiumCallCount, "Premium refresh should have been attempted")
        assertEquals(
            0,
            retryCount(trigger),
            "CancellationException must not be recorded as a sync failure",
        )
    }
}
