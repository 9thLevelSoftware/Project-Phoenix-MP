package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.FakeSyncTriggerTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Failure classification and persistent-error contract of the production
 * [SyncTriggerManager] (#869: this used to test a hand-copied mirror). Issue #528 tightened
 * this so TRANSIENT storms do NOT latch the user-visible persistent error — only PERMANENT
 * and AUTH do.
 *
 *   - A TRANSIENT storm still ratchets the exponential backoff, but `hasPersistentError`
 *     stays false (it backs the Settings > Cloud Sync red row, meant for actionable errors).
 *   - PERMANENT and AUTH errors set the persistent error flag immediately.
 *   - NETWORK errors don't ratchet backoff; they switch the trigger to waiting-for-connectivity.
 *   - clearError() always resets the flag and the failure/backoff counters.
 */
class SyncFailureCapTest {

    private class Fixture {
        val target = FakeSyncTriggerTarget()
        val trigger = SyncTriggerManager(syncManager = target, isOnline = { true }, nowMillis = { 1_000_000L })
        val retry: RetryState get() = trigger.retryState.value
    }

    @Test
    fun threeTransientFailuresStaysInBackoffWithoutTrippingPersistentErrorFlag() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))

        repeat(3) { attempt ->
            f.trigger.onWorkoutCompleted()
            assertFalse(f.trigger.hasPersistentError.value, "${attempt + 1} TRANSIENT failures stay in backoff")
        }
        assertEquals(3, f.retry.retryCount)
    }

    @Test
    fun clearErrorResetsFailureCountAndPersistentFlag() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("bad request", null, 400))
        f.trigger.onWorkoutCompleted()
        assertTrue(f.trigger.hasPersistentError.value, "PERMANENT error latches the persistent error flag")
        assertEquals(1, f.retry.retryCount)

        f.trigger.clearError()

        assertFalse(f.trigger.hasPersistentError.value, "Manual clear drops the flag")
        assertEquals(0, f.retry.retryCount, "Manual clear resets the counter")
    }

    @Test
    fun successfulSyncAfterFailuresResetsCount() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("boom", null, 500))
        f.trigger.onWorkoutCompleted()
        f.trigger.onWorkoutCompleted()
        assertEquals(2, f.retry.retryCount)

        f.target.syncResult = Result.success(2L)
        f.trigger.onWorkoutCompleted()

        assertEquals(0, f.retry.retryCount, "A clean success resets the consecutive-failure counter")
    }

    @Test
    fun permanentErrorIncrementsCounterAndSetsPersistentError() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("bad request", null, 400))

        f.trigger.onWorkoutCompleted()

        assertEquals(1, f.retry.retryCount, "PERMANENT increments counter")
        assertEquals(SyncErrorCategory.PERMANENT, f.retry.lastErrorCategory)
        assertTrue(f.trigger.hasPersistentError.value, "PERMANENT errors set persistent error immediately")
    }

    @Test
    fun transientErrorIncrementsCounterWithoutTrippingImmediately() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("busy", null, 503))

        f.trigger.onWorkoutCompleted()

        assertEquals(1, f.retry.retryCount, "TRANSIENT increments counter")
        assertEquals(SyncErrorCategory.TRANSIENT, f.retry.lastErrorCategory)
        assertFalse(f.trigger.hasPersistentError.value)
    }

    @Test
    fun authErrorSignalsReLoginAndBlocksAutoRetries() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("token expired", null, 401))

        f.trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.AUTH, f.retry.lastErrorCategory)
        assertTrue(f.retry.requiresReLogin, "AUTH → requiresReLogin")
        assertTrue(f.trigger.hasPersistentError.value, "AUTH triggers the re-login signal via persistent error")

        val beforeAutoRetry = f.target.syncCallCount
        f.trigger.onAppForeground()
        assertEquals(beforeAutoRetry, f.target.syncCallCount, "Auto retry must stay paused until the user re-logs in")
    }

    @Test
    fun networkErrorGatesRetryUntilConnectivityRestoredInsteadOfCountingCap() = runTest {
        val f = Fixture()
        // A class name containing "Connection" classifies as NETWORK.
        class ConnectionException(msg: String) : Exception(msg)
        f.target.syncResult = Result.failure(ConnectionException("refused"))

        repeat(3) { f.trigger.onWorkoutCompleted() }

        assertEquals(SyncErrorCategory.NETWORK, f.retry.lastErrorCategory)
        assertTrue(f.retry.isWaitingForConnectivity, "NETWORK errors wait for connectivity")
        assertFalse(f.trigger.hasPersistentError.value, "NETWORK failures never latch the persistent error")
    }

    @Test
    fun mixedTransientAndPermanentStillCountsPermanentAsPersistentError() = runTest {
        val f = Fixture()
        f.target.syncResult = Result.failure(PortalApiException("busy", null, 500))
        f.trigger.onWorkoutCompleted()
        f.trigger.onWorkoutCompleted()
        assertEquals(2, f.retry.retryCount)
        assertFalse(f.trigger.hasPersistentError.value)

        f.target.syncResult = Result.failure(PortalApiException("not found", null, 404))
        f.trigger.onWorkoutCompleted()

        assertEquals(3, f.retry.retryCount)
        assertEquals(SyncErrorCategory.PERMANENT, f.retry.lastErrorCategory)
        assertTrue(f.trigger.hasPersistentError.value)
    }
}
