package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.FakeSyncTriggerHost
import com.devil.phoenixproject.testutil.productionSyncTriggerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Failure classification and persistent-error contract on the production
 * [SyncTriggerManager]. TRANSIENT storms do not latch the user-visible
 * persistent error — only PERMANENT and AUTH do (issue #528).
 */
class SyncFailureCapTest {

    private fun consecutiveFailures(trigger: SyncTriggerManager): Int =
        trigger.retryState.value.retryCount

    private fun lastErrorCategory(trigger: SyncTriggerManager): SyncErrorCategory? =
        trigger.retryState.value.lastErrorCategory

    @Test
    fun threeTransientFailuresStaysInBackoffWithoutTrippingPersistentErrorFlag() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("boom", null, 500))

        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "1 failure is below cap")
        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "2 failures is below cap")
        trigger.onWorkoutCompleted()
        assertFalse(
            trigger.hasPersistentError.value,
            "3 TRANSIENT failures stay in backoff (no persistent latch — Issue #528)",
        )
        assertEquals(3, consecutiveFailures(trigger))
    }

    @Test
    fun clearErrorResetsFailureCountAndPersistentFlag() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("bad request", null, 400))

        trigger.onWorkoutCompleted()
        assertTrue(
            trigger.hasPersistentError.value,
            "PERMANENT error latches the persistent error flag (clearError target)",
        )
        assertEquals(1, consecutiveFailures(trigger))

        trigger.clearError()

        assertFalse(trigger.hasPersistentError.value, "Manual clear drops the flag")
        assertEquals(0, consecutiveFailures(trigger), "Manual clear resets the counter")
    }

    @Test
    fun successfulSyncAfterFailuresResetsCount() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(2, consecutiveFailures(trigger))

        host.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()
        assertEquals(
            0,
            consecutiveFailures(trigger),
            "A clean success resets the consecutive-failure counter",
        )
    }

    @Test
    fun permanentErrorIncrementsCounterAndSetsPersistentError() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("bad request", null, 400))

        trigger.onWorkoutCompleted()

        assertEquals(1, consecutiveFailures(trigger), "PERMANENT increments counter")
        assertEquals(SyncErrorCategory.PERMANENT, lastErrorCategory(trigger))
        assertTrue(
            trigger.hasPersistentError.value,
            "PERMANENT errors set persistent error immediately (don't retry)",
        )
    }

    @Test
    fun transientErrorIncrementsCounterWithoutTrippingImmediately() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("busy", null, 503))

        trigger.onWorkoutCompleted()

        assertEquals(1, consecutiveFailures(trigger), "TRANSIENT increments counter")
        assertEquals(SyncErrorCategory.TRANSIENT, lastErrorCategory(trigger))
        assertFalse(
            trigger.hasPersistentError.value,
            "A single TRANSIENT doesn't hit the retry-storm cap",
        )
    }

    @Test
    fun authErrorSignalsReLoginAndBlocksAutoRetries() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)
        host.syncResult = Result.failure(PortalApiException("token expired", null, 401))

        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.AUTH, lastErrorCategory(trigger))
        assertTrue(trigger.retryState.value.requiresReLogin, "AUTH → requiresReLogin")
        assertTrue(
            trigger.hasPersistentError.value,
            "AUTH triggers the re-login signal via persistent error",
        )

        val beforeAutoRetry = host.syncCallCount
        trigger.onAppForeground()
        assertEquals(
            beforeAutoRetry,
            host.syncCallCount,
            "Auto retry must stay paused until user re-logs in",
        )
    }

    @Test
    fun networkErrorGatesRetryUntilConnectivityRestoredInsteadOfCountingCap() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        class ConnectionException(msg: String) : Exception(msg)
        host.syncResult = Result.failure(ConnectionException("refused"))

        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.NETWORK, lastErrorCategory(trigger))
        assertTrue(
            trigger.retryState.value.isWaitingForConnectivity,
            "NETWORK errors put the trigger into waiting-for-connectivity instead of counting TRANSIENT cap",
        )
        assertFalse(
            trigger.hasPersistentError.value,
            "Pure NETWORK failures should NOT trip the retry-storm cap (that's for TRANSIENT)",
        )
    }

    @Test
    fun mixedTransientAndPermanentStillCountsPermanentAsPersistentError() = runTest {
        val host = FakeSyncTriggerHost()
        val trigger = productionSyncTriggerManager(host)

        host.syncResult = Result.failure(PortalApiException("busy", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(2, consecutiveFailures(trigger))
        assertFalse(trigger.hasPersistentError.value)

        host.syncResult = Result.failure(PortalApiException("not found", null, 404))
        trigger.onWorkoutCompleted()
        assertEquals(3, consecutiveFailures(trigger))
        assertEquals(SyncErrorCategory.PERMANENT, lastErrorCategory(trigger))
        assertTrue(trigger.hasPersistentError.value)
    }
}
