package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeSyncTriggerTarget
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
 * Unit tests for the production [SyncTriggerManager] (issue #869: these used to exercise a
 * hand-copied mirror that had drifted from the real class).
 *
 * Key behaviors tested:
 * - Exponential backoff: 5 -> 15 -> 30 -> 60 minutes on transient errors
 * - Auth errors: signal re-login, don't retry
 * - Permanent errors: Don't retry, reset backoff
 * - Network errors: Wait for connectivity restoration
 * - Success: Reset all backoff state
 * - Account-switch/ownership holds and sync() throwing
 */
class SyncTriggerManagerTest {

    private class Fixture {
        val target = FakeSyncTriggerTarget()
        var online = true
        var now = 1_000_000L
        val trigger = SyncTriggerManager(
            syncManager = target,
            isOnline = { online },
            nowMillis = { now },
        )
        val retry: RetryState get() = trigger.retryState.value
    }

    // ==================== Backoff Progression Tests ====================

    @Test
    fun backoffIndexProgressesOnTransientError() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        listOf(5, 15, 30, 60, 60).forEachIndexed { attempt, expectedDelay ->
            f.trigger.onWorkoutCompleted()
            assertEquals(expectedDelay, f.retry.nextRetryDelayMinutes, "delay after failure ${attempt + 1}")
            assertEquals(attempt + 1, f.retry.retryCount)
        }
    }

    @Test
    fun backoffResetsOnSuccess() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        f.trigger.onWorkoutCompleted()
        f.trigger.onWorkoutCompleted()
        assertEquals(15, f.retry.nextRetryDelayMinutes)
        assertEquals(2, f.retry.retryCount)

        f.target.syncResult = Result.success(2L)
        f.trigger.onWorkoutCompleted()

        assertNull(f.retry.nextRetryDelayMinutes, "No retry delay after a clean success")
        assertEquals(0, f.retry.retryCount, "Failures should reset to 0")
        assertNull(f.retry.lastErrorCategory, "Error category should be null")
    }

    // ==================== Persistent Error Tests ====================

    @Test
    fun transientFailuresNeverLatchThePersistentError() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        repeat(5) { f.trigger.onWorkoutCompleted() }

        assertFalse(f.trigger.hasPersistentError.value, "Transient failures only ratchet backoff")
        assertEquals(5, f.retry.retryCount)
    }

    @Test
    fun persistentErrorClearedOnManualReset() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Bad request", null, 400))
        f.trigger.onWorkoutCompleted()
        assertTrue(f.trigger.hasPersistentError.value, "Should have persistent error")

        f.trigger.clearError()

        assertFalse(f.trigger.hasPersistentError.value, "Persistent error should be cleared")
        assertNull(f.retry.nextRetryDelayMinutes, "Backoff should be reset")
        assertEquals(0, f.retry.retryCount, "Failures should be reset")
        assertNull(f.retry.lastErrorCategory)
    }

    // ==================== Connectivity Tests ====================

    @Test
    fun connectivityRestoredTriggersSync() = runTest {
        val f = Fixture()
        f.online = false
        f.trigger.onAppForeground()

        assertTrue(f.retry.isWaitingForConnectivity, "Should be waiting for connectivity when offline")
        assertEquals(0, f.target.syncCallCount, "Sync should not be called when offline")

        f.online = true
        f.trigger.onConnectivityRestored()

        assertFalse(f.retry.isWaitingForConnectivity, "Should no longer be waiting for connectivity")
        assertEquals(1, f.target.syncCallCount, "Sync should be called on connectivity restore")
    }

    @Test
    fun connectivityRestoredDoesNothingWhenNotWaiting() = runTest {
        val f = Fixture()

        f.trigger.onConnectivityRestored()

        assertEquals(0, f.target.syncCallCount)
    }

    @Test
    fun networkErrorSetsWaitingForConnectivity() = runTest {
        val f = Fixture()
        // A class name containing "Connection" classifies as NETWORK.
        class ConnectionException(message: String) : Exception(message)
        f.target.syncResult = Result.failure(ConnectionException("Connection refused"))

        f.trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.NETWORK, f.retry.lastErrorCategory, "Should classify as NETWORK error")
        assertTrue(f.retry.isWaitingForConnectivity, "Should be waiting for connectivity after network error")
        assertNull(f.retry.nextRetryDelayMinutes, "Backoff should not increase for network errors")
    }

    // ==================== Permanent Error Tests ====================

    @Test
    fun permanentErrorDoesNotTriggerBackoff() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Bad request", null, 400))

        f.trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.PERMANENT, f.retry.lastErrorCategory, "Should classify as PERMANENT error")
        assertNull(f.retry.nextRetryDelayMinutes, "Backoff should NOT increase for permanent errors")
        assertTrue(f.trigger.hasPersistentError.value, "Should set persistent error for permanent errors")
    }

    @Test
    fun permanentErrorResetsExistingBackoff() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        f.trigger.onWorkoutCompleted()
        f.trigger.onWorkoutCompleted()
        assertEquals(15, f.retry.nextRetryDelayMinutes, "Should have backoff from transient errors")

        f.target.syncResult = Result.failure(PortalApiException("Not found", null, 404))
        f.trigger.onWorkoutCompleted()

        assertNull(f.retry.nextRetryDelayMinutes, "Permanent error should reset backoff")
    }

    // ==================== Auth Error Tests ====================

    @Test
    fun authErrorSignalsReLoginAndDoesNotRetry() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Unauthorized", null, 401))

        f.trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.AUTH, f.retry.lastErrorCategory, "Should classify as AUTH error")
        assertNull(f.retry.nextRetryDelayMinutes, "Auth errors should NOT increase backoff")
        assertTrue(f.trigger.hasPersistentError.value, "Auth errors should set persistent error")
        assertTrue(f.retry.requiresReLogin, "Retry state should indicate re-login required")
    }

    @Test
    fun authErrorResetsExistingBackoff() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        f.trigger.onWorkoutCompleted()
        assertEquals(5, f.retry.nextRetryDelayMinutes)

        f.target.syncResult = Result.failure(PortalApiException("Token expired", null, 401))
        f.trigger.onWorkoutCompleted()

        assertNull(f.retry.nextRetryDelayMinutes, "Auth error should reset backoff")
    }

    // ==================== Edge Cases ====================

    @Test
    fun partialSuccessTreatedAsTransientFailure() = runTest {
        val f = Fixture()
        f.target.preserveSyncState = true
        f.target.setSyncState(
            SyncState.PartialSuccess(
                pushSucceeded = true,
                pullSucceeded = false,
                lastSyncTime = 1L,
                pullError = "Network timeout on pull",
            ),
        )

        f.trigger.onWorkoutCompleted()

        assertEquals(5, f.retry.nextRetryDelayMinutes, "Partial success should trigger backoff")
        assertEquals(1, f.retry.retryCount, "Should count as a failure")
    }

    @Test
    fun syncThatThrowsIsRecordedAsAFailureInsteadOfEscaping() = runTest {
        val f = Fixture()
        f.target.syncThrows = IllegalStateException("repository blew up")

        f.trigger.onWorkoutCompleted()

        assertEquals(1, f.target.syncCallCount)
        assertEquals(1, f.retry.retryCount)
        assertNotNull(f.retry.lastErrorCategory)
    }

    @Test
    fun backoffScheduleMatchesDocumentedValues() {
        assertEquals(listOf(5, 15, 30, 60), SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES)
    }

    @Test
    fun notAuthenticatedSkipsSync() = runTest {
        val f = Fixture()
        f.target.setAuthenticated(false)

        f.trigger.onWorkoutCompleted()

        assertEquals(0, f.target.syncCallCount, "Sync should not be called when not authenticated")
    }

    @Test
    fun accountSwitchAndOwnershipHoldsSkipSync() = runTest {
        listOf(
            SyncState.AccountMismatch("old", "old@x", "new", "new@x"),
            SyncState.OwnershipConflict("owned elsewhere"),
        ).forEach { hold ->
            val f = Fixture()
            f.target.setSyncState(hold)

            f.trigger.onWorkoutCompleted()

            assertEquals(0, f.target.syncCallCount, "$hold must hold auto-sync")
            assertEquals(hold, f.target.syncState.value)
        }
    }

    @Test
    fun notPremiumSkipsSyncAfterFirstSync() = runTest {
        val f = Fixture()
        f.target.setPremium(false)
        f.target.setLastSyncTime(1000L)

        f.trigger.onWorkoutCompleted()

        assertEquals(0, f.target.syncCallCount, "Sync should be skipped for non-premium after first sync")
        assertEquals(1, f.target.markPausedNotPremiumCallCount)
        assertEquals(SyncState.NotPremium, f.target.syncState.value, "Skip must show the paused state")
    }

    @Test
    fun notPremiumAllowsFirstSync() = runTest {
        val f = Fixture()
        f.target.setPremium(false)
        f.target.setLastSyncTime(0L)

        f.trigger.onWorkoutCompleted()

        assertEquals(1, f.target.syncCallCount, "First sync should be allowed for non-premium")
    }

    @Test
    fun rateLimitedErrorIsTransientAndRetryable() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("Rate limited", null, 429))

        f.trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.TRANSIENT, f.retry.lastErrorCategory, "429 should be TRANSIENT")
        assertEquals(5, f.retry.nextRetryDelayMinutes, "Should trigger backoff")
        assertFalse(f.trigger.hasPersistentError.value, "Rate limiting should not latch a persistent error")
    }

    // ==================== Issue #566 Foreground Crash Containment Tests ====================

    /**
     * Issue #566: a raw throwable from refreshPremiumStatusFromServer() must be recorded in
     * RetryState and must NOT propagate out of onAppForeground().
     */
    @Test
    fun onAppForegroundRecordsFailureAndDoesNotPropagateWhenPremiumRefreshThrows() = runTest {
        val f = Fixture()
        class SimulatedPremiumRefreshCrash(message: String) : Exception(message)
        f.target.refreshPremiumStatusThrows = SimulatedPremiumRefreshCrash("simulated premium refresh failure after wake")

        f.trigger.onAppForeground()

        assertEquals(1, f.target.refreshPremiumCallCount, "Premium refresh should have been attempted")
        assertEquals(0, f.target.syncCallCount, "attemptSync should not be reached after premium refresh threw")
        assertEquals(1, f.retry.retryCount, "RetryState should reflect the foreground failure")
        assertNotNull(f.retry.lastErrorCategory, "Error category should be classified and recorded")
    }

    /** Issue #566: cancellation propagates and is not recorded as a sync failure. */
    @Test
    fun onAppForegroundRethrowsCancellationException() = runTest {
        val f = Fixture()
        f.target.refreshPremiumStatusThrows = CancellationException("lifecycle cancelled")

        assertFailsWith<CancellationException> { f.trigger.onAppForeground() }

        assertEquals(1, f.target.refreshPremiumCallCount, "Premium refresh should have been attempted")
        assertEquals(0, f.retry.retryCount, "CancellationException must not be recorded as a sync failure")
    }
}
