package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Tests for the 3-consecutive-failure retry-storm cap documented by SyncTriggerManager:
 *   - After 3 consecutive transient failures the trigger manager flips into a
 *     `hasPersistentError` state that blocks further automatic retries until a
 *     manual/bypass trigger (or clearError) clears it.
 *   - Manual retry (clearError / bypass) resets the failure count.
 *   - Mixed error categories: PERMANENT & TRANSIENT increment the counter, AUTH sets
 *     persistent error and emits a re-login signal, NETWORK keeps the trigger waiting
 *     for connectivity rather than counting toward the TRANSIENT retry-storm cap.
 */
class SyncFailureCapTest {

    private class TestSyncManager {
        private val _isAuthenticated = MutableStateFlow(true)
        val isAuthenticated: StateFlow<Boolean> = _isAuthenticated
        private val _currentUser = MutableStateFlow<PortalUser?>(
            PortalUser(id = "u", email = "u@e.com", displayName = "U", isPremium = true),
        )
        val currentUser: StateFlow<PortalUser?> = _currentUser
        private val _lastSyncTime = MutableStateFlow(1000L)
        val lastSyncTime: StateFlow<Long> = _lastSyncTime
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState
        var syncResult: Result<Long> = Result.success(currentTimeMillis())
        var syncCallCount = 0
        suspend fun sync(): Result<Long> {
            syncCallCount++
            if (syncResult.isSuccess) {
                _syncState.value = SyncState.Success(syncResult.getOrThrow())
            }
            return syncResult
        }
    }

    private class TestConnectivityChecker(var online: Boolean = true) {
        fun isOnline(): Boolean = online
    }

    /**
     * Shared testable SyncTriggerManager mirror. Identical contract-wise to the production class
     * at SyncTriggerManager.kt; we recreate it here so we can observe private state for the
     * failure-cap assertions without touching production code.
     */
    private class TestableSyncTriggerManager(
        private val syncManager: TestSyncManager,
        private val connectivity: TestConnectivityChecker,
    ) {
        companion object {
            val BACKOFF_SCHEDULE_MINUTES = listOf(5, 15, 30, 60)
            const val MAX_CONSECUTIVE_FAILURES = 3
        }

        private var consecutiveFailures: Int = 0
        private var currentBackoffIndex: Int = 0
        private var lastErrorCategory: SyncErrorCategory? = null
        private var isWaitingForConnectivity: Boolean = false
        private val _hasPersistentError = MutableStateFlow(false)
        val hasPersistentError: StateFlow<Boolean> = _hasPersistentError

        fun consecutiveFailures(): Int = consecutiveFailures
        fun isWaitingForConnectivity(): Boolean = isWaitingForConnectivity
        fun lastErrorCategory(): SyncErrorCategory? = lastErrorCategory
        fun requiresReLogin(): Boolean = lastErrorCategory == SyncErrorCategory.AUTH

        fun clearError() {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            isWaitingForConnectivity = false
            _hasPersistentError.value = false
        }

        suspend fun onWorkoutCompleted() = attemptSync(bypassThrottle = true)
        suspend fun onAppForeground() = attemptSync(bypassThrottle = false)

        private suspend fun attemptSync(bypassThrottle: Boolean) {
            if (!syncManager.isAuthenticated.value) return
            val user = syncManager.currentUser.value
            if (user?.isPremium == false && syncManager.lastSyncTime.value > 0) return
            if (!connectivity.isOnline()) {
                isWaitingForConnectivity = true
                return
            }
            if (isWaitingForConnectivity && !bypassThrottle) return
            if (_hasPersistentError.value && !bypassThrottle) return

            val result = syncManager.sync()
            if (result.isSuccess) {
                val state = syncManager.syncState.value
                if (state is SyncState.PartialSuccess) {
                    onSyncFailure(PortalApiException(state.pullError ?: "Pull failed"))
                } else {
                    onSyncSuccess()
                }
            } else {
                onSyncFailure(result.exceptionOrNull())
            }
        }

        private fun onSyncFailure(error: Throwable?) {
            val classified = if (error is Exception) {
                classifyError(error, "Sync")
            } else {
                ClassifiedSyncError(
                    category = SyncErrorCategory.TRANSIENT,
                    message = error?.message ?: "Unknown",
                    isRetryable = true,
                    cause = error,
                )
            }
            consecutiveFailures++
            lastErrorCategory = classified.category
            when (classified.category) {
                SyncErrorCategory.TRANSIENT -> {
                    if (currentBackoffIndex < BACKOFF_SCHEDULE_MINUTES.size) currentBackoffIndex++
                }
                SyncErrorCategory.PERMANENT -> {
                    currentBackoffIndex = 0
                    _hasPersistentError.value = true
                }
                SyncErrorCategory.NETWORK -> {
                    isWaitingForConnectivity = true
                }
                SyncErrorCategory.AUTH -> {
                    currentBackoffIndex = 0
                    _hasPersistentError.value = true
                }
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES &&
                classified.category == SyncErrorCategory.TRANSIENT
            ) {
                _hasPersistentError.value = true
            }
        }

        private fun onSyncSuccess() {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            isWaitingForConnectivity = false
            _hasPersistentError.value = false
        }
    }

    // ==================== Retry-Storm Cap ====================

    @Test
    fun threeTransientFailuresTripsPersistentErrorFlag() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))

        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "1 failure is below cap")
        trigger.onWorkoutCompleted()
        assertFalse(trigger.hasPersistentError.value, "2 failures is below cap")
        trigger.onWorkoutCompleted()
        assertTrue(trigger.hasPersistentError.value, "3 failures trips the manual-intervention flag")
        assertEquals(3, trigger.consecutiveFailures())
    }

    @Test
    fun clearErrorResetsFailureCountAndPersistentFlag() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))

        repeat(3) { trigger.onWorkoutCompleted() }
        assertTrue(trigger.hasPersistentError.value)
        assertEquals(3, trigger.consecutiveFailures())

        trigger.clearError()

        assertFalse(trigger.hasPersistentError.value, "Manual clear drops the flag")
        assertEquals(0, trigger.consecutiveFailures(), "Manual clear resets the counter")
    }

    @Test
    fun successfulSyncAfterFailuresResetsCount() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(2, trigger.consecutiveFailures())

        sm.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()
        assertEquals(
            0,
            trigger.consecutiveFailures(),
            "A clean success resets the consecutive-failure counter",
        )
    }

    // ==================== Mixed Error Categories ====================

    @Test
    fun permanentErrorIncrementsCounterAndSetsPersistentError() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        sm.syncResult = Result.failure(PortalApiException("bad request", null, 400))
        trigger.onWorkoutCompleted()

        assertEquals(1, trigger.consecutiveFailures(), "PERMANENT increments counter")
        assertEquals(SyncErrorCategory.PERMANENT, trigger.lastErrorCategory())
        assertTrue(
            trigger.hasPersistentError.value,
            "PERMANENT errors set persistent error immediately (don't retry)",
        )
    }

    @Test
    fun transientErrorIncrementsCounterWithoutTrippingImmediately() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        sm.syncResult = Result.failure(PortalApiException("busy", null, 503))
        trigger.onWorkoutCompleted()

        assertEquals(1, trigger.consecutiveFailures(), "TRANSIENT increments counter")
        assertEquals(SyncErrorCategory.TRANSIENT, trigger.lastErrorCategory())
        assertFalse(
            trigger.hasPersistentError.value,
            "A single TRANSIENT doesn't hit the retry-storm cap",
        )
    }

    @Test
    fun authErrorSignalsReLoginAndBlocksAutoRetries() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        sm.syncResult = Result.failure(PortalApiException("token expired", null, 401))
        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.AUTH, trigger.lastErrorCategory())
        assertTrue(trigger.requiresReLogin(), "AUTH → requiresReLogin()")
        assertTrue(
            trigger.hasPersistentError.value,
            "AUTH triggers the re-login signal via persistent error",
        )

        // A subsequent foreground trigger should NOT call sync (blocked by persistent error).
        val beforeAutoRetry = sm.syncCallCount
        trigger.onAppForeground()
        assertEquals(
            beforeAutoRetry,
            sm.syncCallCount,
            "Auto retry must stay paused until user re-logs in",
        )
    }

    @Test
    fun networkErrorGatesRetryUntilConnectivityRestoredInsteadOfCountingCap() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        // Force a NETWORK classification via a class name containing "Connection".
        class ConnectionException(msg: String) : Exception(msg)
        sm.syncResult = Result.failure(ConnectionException("refused"))

        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()

        assertEquals(SyncErrorCategory.NETWORK, trigger.lastErrorCategory())
        assertTrue(
            trigger.isWaitingForConnectivity(),
            "NETWORK errors put the trigger into waiting-for-connectivity instead of counting TRANSIENT cap",
        )
        assertFalse(
            trigger.hasPersistentError.value,
            "Pure NETWORK failures should NOT trip the retry-storm cap (that's for TRANSIENT)",
        )
    }

    @Test
    fun mixedTransientAndPermanentStillCountsPermanentAsPersistentError() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        // Two transients…
        sm.syncResult = Result.failure(PortalApiException("busy", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(2, trigger.consecutiveFailures())
        assertFalse(trigger.hasPersistentError.value)

        // …then a permanent — persistent error flag flips on immediately.
        sm.syncResult = Result.failure(PortalApiException("not found", null, 404))
        trigger.onWorkoutCompleted()
        assertEquals(3, trigger.consecutiveFailures())
        assertEquals(SyncErrorCategory.PERMANENT, trigger.lastErrorCategory())
        assertTrue(trigger.hasPersistentError.value)
    }
}
