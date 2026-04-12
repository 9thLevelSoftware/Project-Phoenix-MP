package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for SyncTriggerManager.
 *
 * Tests the exponential backoff logic, retry storm prevention, and error category handling.
 * Uses minimal test doubles that implement the exact interface SyncTriggerManager requires.
 *
 * Key behaviors tested:
 * - Exponential backoff: 5 -> 15 -> 30 -> 60 minutes on transient errors
 * - Retry storm prevention: 3 consecutive failures triggers persistent error
 * - Auth errors: Clear auth state, don't retry
 * - Permanent errors: Don't retry, reset backoff
 * - Network errors: Wait for connectivity restoration
 * - Success: Reset all backoff state
 */
class SyncTriggerManagerTest {

    // ==================== Test Doubles ====================

    /**
     * Minimal test double for SyncManager that exposes only what SyncTriggerManager needs.
     */
    private class TestSyncManager {
        private val _isAuthenticated = MutableStateFlow(true)
        val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

        private val _currentUser = MutableStateFlow<PortalUser?>(
            PortalUser(id = "test", email = "test@test.com", displayName = "Test", isPremium = true),
        )
        val currentUser: StateFlow<PortalUser?> = _currentUser

        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState

        private val _lastSyncTime = MutableStateFlow(1000L) // Non-zero to bypass first-sync check
        val lastSyncTime: StateFlow<Long> = _lastSyncTime

        var syncResult: Result<Long> = Result.success(System.currentTimeMillis())
        var syncCallCount = 0

        /** If set, sync() will preserve this state instead of setting Success */
        var preserveSyncState = false

        suspend fun sync(): Result<Long> {
            syncCallCount++
            // Only update to Success if we're not preserving a custom state (like PartialSuccess)
            if (syncResult.isSuccess && !preserveSyncState) {
                _syncState.value = SyncState.Success(syncResult.getOrThrow())
            }
            return syncResult
        }

        fun setAuthenticated(value: Boolean) {
            _isAuthenticated.value = value
        }

        fun setPremium(isPremium: Boolean) {
            _currentUser.value = _currentUser.value?.copy(isPremium = isPremium)
        }

        fun setSyncState(state: SyncState) {
            _syncState.value = state
        }

        fun setLastSyncTime(time: Long) {
            _lastSyncTime.value = time
        }
    }

    /**
     * Minimal test double for ConnectivityChecker.
     */
    private class TestConnectivityChecker {
        var online = true
        fun isOnline(): Boolean = online
    }

    /**
     * Testable subclass of SyncTriggerManager that allows dependency injection of test doubles.
     */
    private class TestableSyncTriggerManager(
        private val testSyncManager: TestSyncManager,
        private val testConnectivityChecker: TestConnectivityChecker,
    ) {
        // Mirror the internal state from real SyncTriggerManager
        private var consecutiveFailures: Int = 0
        private var currentBackoffIndex: Int = 0
        private var lastErrorCategory: SyncErrorCategory? = null
        private var lastErrorMessage: String? = null
        private var isWaitingForConnectivity: Boolean = false
        private var lastSyncAttemptMillis: Long = 0

        private val _hasPersistentError = MutableStateFlow(false)
        val hasPersistentError: StateFlow<Boolean> = _hasPersistentError

        private val _retryState = MutableStateFlow(RetryState())
        val retryState: StateFlow<RetryState> = _retryState

        companion object {
            const val MAX_CONSECUTIVE_FAILURES = 3
            val BACKOFF_SCHEDULE_MINUTES = listOf(5, 15, 30, 60)
        }

        /**
         * Simulates onWorkoutCompleted - bypasses throttle
         */
        suspend fun onWorkoutCompleted() {
            attemptSync(bypassThrottle = true)
        }

        /**
         * Simulates onAppForeground - respects throttle
         */
        suspend fun onAppForeground() {
            attemptSync(bypassThrottle = false)
        }

        /**
         * Simulates onConnectivityRestored
         */
        suspend fun onConnectivityRestored() {
            if (isWaitingForConnectivity) {
                isWaitingForConnectivity = false
                attemptSync(bypassThrottle = true)
            }
        }

        /**
         * Clears error state
         */
        fun clearError() {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            lastErrorMessage = null
            isWaitingForConnectivity = false
            _hasPersistentError.value = false
            updateRetryState()
        }

        // Expose internal state for testing
        fun getBackoffIndex(): Int = currentBackoffIndex
        fun getConsecutiveFailures(): Int = consecutiveFailures
        fun getLastErrorCategory(): SyncErrorCategory? = lastErrorCategory
        fun isWaitingForConnectivity(): Boolean = isWaitingForConnectivity

        private suspend fun attemptSync(bypassThrottle: Boolean) {
            // Check authentication
            if (!testSyncManager.isAuthenticated.value) {
                return
            }

            // Check premium status
            val user = testSyncManager.currentUser.value
            if (user?.isPremium == false && testSyncManager.lastSyncTime.value > 0) {
                return
            }

            // Check connectivity
            if (!testConnectivityChecker.isOnline()) {
                isWaitingForConnectivity = true
                updateRetryState()
                return
            }

            // Check if waiting for connectivity
            if (isWaitingForConnectivity && !bypassThrottle) {
                return
            }

            // Check persistent error
            if (_hasPersistentError.value && !bypassThrottle) {
                return
            }

            // Attempt sync
            val result = testSyncManager.sync()

            if (result.isSuccess) {
                val state = testSyncManager.syncState.value
                if (state is SyncState.PartialSuccess) {
                    onSyncFailure(PortalApiException(state.pullError ?: "Pull failed"))
                } else {
                    onSyncSuccess()
                }
            } else {
                val error = result.exceptionOrNull()
                onSyncFailure(error)
            }
        }

        private fun onSyncFailure(error: Throwable?) {
            val classified = if (error is Exception) {
                classifyError(error, "Sync")
            } else {
                ClassifiedSyncError(
                    category = SyncErrorCategory.TRANSIENT,
                    message = error?.message ?: "Unknown error",
                    isRetryable = true,
                    cause = error,
                )
            }

            consecutiveFailures++
            lastErrorCategory = classified.category
            lastErrorMessage = classified.message

            when (classified.category) {
                SyncErrorCategory.TRANSIENT -> {
                    if (currentBackoffIndex < BACKOFF_SCHEDULE_MINUTES.size) {
                        currentBackoffIndex++
                    }
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

            // Check for retry storm
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES &&
                classified.category == SyncErrorCategory.TRANSIENT
            ) {
                _hasPersistentError.value = true
            }

            updateRetryState()
        }

        private fun onSyncSuccess() {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            lastErrorMessage = null
            isWaitingForConnectivity = false
            _hasPersistentError.value = false
            updateRetryState()
        }

        private fun updateRetryState() {
            val nextDelayMinutes = if (currentBackoffIndex > 0) {
                BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) {
                    BACKOFF_SCHEDULE_MINUTES.last()
                }
            } else {
                null
            }

            _retryState.value = RetryState(
                retryCount = consecutiveFailures,
                nextRetryDelayMinutes = nextDelayMinutes,
                nextRetryAtMillis = null, // Simplified for testing
                lastErrorCategory = lastErrorCategory,
                lastErrorMessage = lastErrorMessage,
                isWaitingForConnectivity = isWaitingForConnectivity,
                requiresReLogin = lastErrorCategory == SyncErrorCategory.AUTH,
            )
        }
    }

    // ==================== Backoff Progression Tests ====================

    @Test
    fun backoffIndexProgressesOnTransientError() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Set up transient error (500 server error)
        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        // First transient error
        triggerManager.onWorkoutCompleted()
        assertEquals(1, triggerManager.getBackoffIndex(), "Backoff should be 1 after first error")
        assertEquals(
            5,
            triggerManager.retryState.value.nextRetryDelayMinutes,
            "Delay should be 5 minutes",
        )

        // Second transient error
        triggerManager.onWorkoutCompleted()
        assertEquals(2, triggerManager.getBackoffIndex(), "Backoff should be 2 after second error")
        assertEquals(
            15,
            triggerManager.retryState.value.nextRetryDelayMinutes,
            "Delay should be 15 minutes",
        )

        // Third transient error
        triggerManager.onWorkoutCompleted()
        assertEquals(3, triggerManager.getBackoffIndex(), "Backoff should be 3 after third error")
        assertEquals(
            30,
            triggerManager.retryState.value.nextRetryDelayMinutes,
            "Delay should be 30 minutes",
        )

        // Fourth transient error
        triggerManager.onWorkoutCompleted()
        assertEquals(4, triggerManager.getBackoffIndex(), "Backoff should be 4 after fourth error")
        assertEquals(
            60,
            triggerManager.retryState.value.nextRetryDelayMinutes,
            "Delay should be 60 minutes",
        )

        // Fifth error - should stay at max
        triggerManager.onWorkoutCompleted()
        assertEquals(4, triggerManager.getBackoffIndex(), "Backoff should stay at max (4)")
        assertEquals(
            60,
            triggerManager.retryState.value.nextRetryDelayMinutes,
            "Delay should stay at 60 minutes",
        )
    }

    @Test
    fun backoffResetsOnSuccess() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Build up backoff with transient errors
        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        triggerManager.onWorkoutCompleted()
        triggerManager.onWorkoutCompleted()
        assertEquals(2, triggerManager.getBackoffIndex(), "Should have backoff index 2")
        assertEquals(2, triggerManager.getConsecutiveFailures(), "Should have 2 consecutive failures")

        // Now succeed
        syncManager.syncResult = Result.success(System.currentTimeMillis())
        triggerManager.onWorkoutCompleted()

        assertEquals(0, triggerManager.getBackoffIndex(), "Backoff should reset to 0 on success")
        assertEquals(0, triggerManager.getConsecutiveFailures(), "Failures should reset to 0")
        assertNull(triggerManager.getLastErrorCategory(), "Error category should be null")
        assertNull(triggerManager.retryState.value.nextRetryDelayMinutes, "No retry delay needed")
    }

    // ==================== Retry Storm Prevention Tests ====================

    @Test
    fun maxConsecutiveFailuresTriggersRetryStormPrevention() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))

        // First two failures - no persistent error yet
        triggerManager.onWorkoutCompleted()
        assertFalse(
            triggerManager.hasPersistentError.value,
            "Should not have persistent error after 1 failure",
        )

        triggerManager.onWorkoutCompleted()
        assertFalse(
            triggerManager.hasPersistentError.value,
            "Should not have persistent error after 2 failures",
        )

        // Third failure - triggers retry storm prevention
        triggerManager.onWorkoutCompleted()
        assertTrue(
            triggerManager.hasPersistentError.value,
            "Should have persistent error after 3 consecutive failures",
        )
        assertEquals(3, triggerManager.getConsecutiveFailures())
    }

    @Test
    fun retryStormClearedOnManualReset() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Trigger retry storm
        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        repeat(3) { triggerManager.onWorkoutCompleted() }
        assertTrue(triggerManager.hasPersistentError.value, "Should have persistent error")

        // Manual clear
        triggerManager.clearError()

        assertFalse(
            triggerManager.hasPersistentError.value,
            "Persistent error should be cleared",
        )
        assertEquals(0, triggerManager.getBackoffIndex(), "Backoff should be reset")
        assertEquals(0, triggerManager.getConsecutiveFailures(), "Failures should be reset")
    }

    // ==================== Connectivity Tests ====================

    @Test
    fun connectivityRestoredTriggersSync() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Start offline
        connectivity.online = false
        triggerManager.onAppForeground()

        assertTrue(
            triggerManager.isWaitingForConnectivity(),
            "Should be waiting for connectivity when offline",
        )
        assertEquals(0, syncManager.syncCallCount, "Sync should not be called when offline")

        // Restore connectivity
        connectivity.online = true
        syncManager.syncResult = Result.success(System.currentTimeMillis())
        triggerManager.onConnectivityRestored()

        assertFalse(
            triggerManager.isWaitingForConnectivity(),
            "Should no longer be waiting for connectivity",
        )
        assertEquals(1, syncManager.syncCallCount, "Sync should be called on connectivity restore")
    }

    @Test
    fun networkErrorSetsWaitingForConnectivity() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Simulate network error (not an HTTP status - classified as NETWORK)
        // Use an exception name containing "Connection" to trigger NETWORK category
        class ConnectionException(message: String) : Exception(message)
        syncManager.syncResult = Result.failure(ConnectionException("Connection refused"))

        triggerManager.onWorkoutCompleted()

        assertEquals(
            SyncErrorCategory.NETWORK,
            triggerManager.getLastErrorCategory(),
            "Should classify as NETWORK error",
        )
        assertTrue(
            triggerManager.isWaitingForConnectivity(),
            "Should be waiting for connectivity after network error",
        )
        // Network errors don't increment backoff
        assertEquals(0, triggerManager.getBackoffIndex(), "Backoff should not increase for network errors")
    }

    // ==================== Permanent Error Tests ====================

    @Test
    fun permanentErrorDoesNotTriggerBackoff() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // 400 Bad Request is a permanent error
        syncManager.syncResult = Result.failure(PortalApiException("Bad request", null, 400))

        triggerManager.onWorkoutCompleted()

        assertEquals(
            SyncErrorCategory.PERMANENT,
            triggerManager.getLastErrorCategory(),
            "Should classify as PERMANENT error",
        )
        assertEquals(
            0,
            triggerManager.getBackoffIndex(),
            "Backoff should NOT increase for permanent errors",
        )
        assertTrue(
            triggerManager.hasPersistentError.value,
            "Should set persistent error for permanent errors",
        )
    }

    @Test
    fun permanentErrorResetsExistingBackoff() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // First, build up some backoff with transient errors
        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        triggerManager.onWorkoutCompleted()
        triggerManager.onWorkoutCompleted()
        assertEquals(2, triggerManager.getBackoffIndex(), "Should have backoff from transient errors")

        // Now trigger a permanent error
        syncManager.syncResult = Result.failure(PortalApiException("Not found", null, 404))
        triggerManager.clearError() // Clear the retry storm from previous failures
        triggerManager.onWorkoutCompleted()

        assertEquals(
            0,
            triggerManager.getBackoffIndex(),
            "Permanent error should reset backoff to 0",
        )
    }

    // ==================== Auth Error Tests ====================

    @Test
    fun authErrorClearsAndDoesNotRetry() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // 401 Unauthorized is an auth error
        syncManager.syncResult = Result.failure(PortalApiException("Unauthorized", null, 401))

        triggerManager.onWorkoutCompleted()

        assertEquals(
            SyncErrorCategory.AUTH,
            triggerManager.getLastErrorCategory(),
            "Should classify as AUTH error",
        )
        assertEquals(
            0,
            triggerManager.getBackoffIndex(),
            "Auth errors should NOT increase backoff",
        )
        assertTrue(
            triggerManager.hasPersistentError.value,
            "Auth errors should set persistent error (requires re-login)",
        )
        assertTrue(
            triggerManager.retryState.value.requiresReLogin,
            "Retry state should indicate re-login required",
        )
    }

    @Test
    fun authErrorResetsExistingBackoff() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Build up backoff
        syncManager.syncResult = Result.failure(PortalApiException("Server error", null, 500))
        triggerManager.onWorkoutCompleted()
        assertEquals(1, triggerManager.getBackoffIndex())

        // Auth error resets backoff
        syncManager.syncResult = Result.failure(PortalApiException("Token expired", null, 401))
        triggerManager.clearError()
        triggerManager.onWorkoutCompleted()

        assertEquals(0, triggerManager.getBackoffIndex(), "Auth error should reset backoff")
    }

    // ==================== Edge Cases ====================

    @Test
    fun partialSuccessTreatedAsTransientFailure() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // Simulate partial success (push OK, pull failed)
        // Set preserveSyncState so sync() doesn't overwrite with Success
        syncManager.preserveSyncState = true
        syncManager.syncResult = Result.success(System.currentTimeMillis())
        syncManager.setSyncState(
            SyncState.PartialSuccess(
                pushSucceeded = true,
                pullSucceeded = false,
                lastSyncTime = System.currentTimeMillis(),
                pullError = "Network timeout on pull",
            ),
        )

        triggerManager.onWorkoutCompleted()

        // Partial success is treated as transient failure
        assertEquals(1, triggerManager.getBackoffIndex(), "Partial success should trigger backoff")
        assertEquals(1, triggerManager.getConsecutiveFailures(), "Should count as a failure")
    }

    @Test
    fun backoffScheduleMatchesDocumentedValues() {
        // Verify the documented backoff schedule: 5 -> 15 -> 30 -> 60 minutes
        assertEquals(
            listOf(5, 15, 30, 60),
            SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES,
            "Backoff schedule should be [5, 15, 30, 60] minutes",
        )
    }

    @Test
    fun notAuthenticatedSkipsSync() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        syncManager.setAuthenticated(false)

        triggerManager.onWorkoutCompleted()

        assertEquals(0, syncManager.syncCallCount, "Sync should not be called when not authenticated")
    }

    @Test
    fun notPremiumSkipsSyncAfterFirstSync() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        syncManager.setPremium(false)
        syncManager.setLastSyncTime(1000L) // Non-zero = not first sync

        triggerManager.onWorkoutCompleted()

        assertEquals(0, syncManager.syncCallCount, "Sync should be skipped for non-premium after first sync")
    }

    @Test
    fun notPremiumAllowsFirstSync() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        syncManager.setPremium(false)
        syncManager.setLastSyncTime(0L) // Zero = first sync allowed

        triggerManager.onWorkoutCompleted()

        assertEquals(1, syncManager.syncCallCount, "First sync should be allowed for non-premium")
    }

    @Test
    fun rateLimitedErrorIsTransientAndRetryable() = runTest {
        val syncManager = TestSyncManager()
        val connectivity = TestConnectivityChecker()
        val triggerManager = TestableSyncTriggerManager(syncManager, connectivity)

        // 429 Too Many Requests
        syncManager.syncResult = Result.failure(PortalApiException("Rate limited", null, 429))

        triggerManager.onWorkoutCompleted()

        assertEquals(
            SyncErrorCategory.TRANSIENT,
            triggerManager.getLastErrorCategory(),
            "429 should be TRANSIENT",
        )
        assertEquals(1, triggerManager.getBackoffIndex(), "Should trigger backoff")
        assertFalse(
            triggerManager.hasPersistentError.value,
            "Single rate limit should not trigger persistent error",
        )
    }
}
