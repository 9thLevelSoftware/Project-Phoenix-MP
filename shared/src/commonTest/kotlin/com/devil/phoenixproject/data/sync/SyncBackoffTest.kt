package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Tests for the documented exponential backoff schedule 5 → 15 → 30 → 60 minutes
 * in SyncTriggerManager.
 *
 * These tests reuse the documented behavior from SyncTriggerManager via a lightweight
 * testable mirror (same pattern used by SyncTriggerManagerTest) so we can assert the
 * backoff arithmetic without time-boxing real millisecond-level waits.
 *
 * Scope covered:
 *  - After N consecutive transient failures, the next-retry delay matches the schedule.
 *  - Schedule caps at 60 min (no growth beyond 4 failures).
 *  - Successful sync resets the counter to 0 (next delay goes back to null / 5-min throttle).
 *  - Manual trigger (onWorkoutCompleted) bypasses throttle/backoff and still calls sync.
 *  - Throttled trigger (onAppForeground) respects backoff and is suppressed within window.
 */
class SyncBackoffTest {

    // ==================== Test Doubles ====================

    /** Minimal SyncManager double used by the testable trigger. */
    private class TestSyncManager {
        private val _isAuthenticated = MutableStateFlow(true)
        val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

        private val _currentUser = MutableStateFlow<PortalUser?>(
            PortalUser(id = "u", email = "u@e.com", displayName = "U", isPremium = true),
        )
        val currentUser: StateFlow<PortalUser?> = _currentUser

        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState

        private val _lastSyncTime = MutableStateFlow(1000L)
        val lastSyncTime: StateFlow<Long> = _lastSyncTime

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

    /** Minimal connectivity double. */
    private class TestConnectivityChecker(var online: Boolean = true) {
        fun isOnline(): Boolean = online
    }

    /**
     * Testable mirror of SyncTriggerManager with an injected clock so we can control
     * "now" in unit tests. Mirrors the production logic in SyncTriggerManager.kt —
     * the goal is to lock in the backoff-schedule contract, not to re-invent it.
     */
    private class TestableSyncTriggerManager(
        private val syncManager: TestSyncManager,
        private val connectivity: TestConnectivityChecker,
        private var now: Long = 0L,
    ) {
        companion object {
            val BACKOFF_SCHEDULE_MINUTES = listOf(5, 15, 30, 60)
            const val DEFAULT_THROTTLE_MILLIS = 5 * 60 * 1000L
            const val MAX_CONSECUTIVE_FAILURES = 3
        }

        private var consecutiveFailures: Int = 0
        private var currentBackoffIndex: Int = 0
        private var lastErrorCategory: SyncErrorCategory? = null
        private var lastSyncAttemptMillis: Long = 0
        private var isWaitingForConnectivity: Boolean = false
        private val _hasPersistentError = MutableStateFlow(false)
        val hasPersistentError: StateFlow<Boolean> = _hasPersistentError

        fun advanceTime(deltaMillis: Long) {
            now += deltaMillis
        }

        fun currentBackoffIndex(): Int = currentBackoffIndex
        fun nextRetryDelayMinutes(): Int? = if (currentBackoffIndex > 0) {
            BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) {
                BACKOFF_SCHEDULE_MINUTES.last()
            }
        } else {
            null
        }
        fun consecutiveFailures(): Int = consecutiveFailures

        private fun currentThrottleMillis(): Long {
            return if (currentBackoffIndex == 0) {
                DEFAULT_THROTTLE_MILLIS
            } else {
                val delayMin = BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) {
                    BACKOFF_SCHEDULE_MINUTES.last()
                }
                delayMin * 60 * 1000L
            }
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

            if (!bypassThrottle && (now - lastSyncAttemptMillis) < currentThrottleMillis()) {
                // Throttled
                return
            }
            lastSyncAttemptMillis = now

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

    // ==================== Schedule Contract ====================

    @Test
    fun backoffScheduleMatchesFiveFifteenThirtySixty() {
        assertEquals(
            listOf(5, 15, 30, 60),
            TestableSyncTriggerManager.BACKOFF_SCHEDULE_MINUTES,
            "Documented schedule is 5 → 15 → 30 → 60 minutes",
        )
        assertEquals(
            listOf(5, 15, 30, 60),
            SyncTriggerManager.BACKOFF_SCHEDULE_MINUTES,
            "Production schedule must match the tested schedule",
        )
    }

    @Test
    fun consecutiveFailuresStepThroughScheduleExactly() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))

        // Use onWorkoutCompleted to bypass throttle so each attempt actually runs.
        trigger.onWorkoutCompleted()
        assertEquals(5, trigger.nextRetryDelayMinutes(), "Step 1 = 5 min")

        trigger.onWorkoutCompleted()
        assertEquals(15, trigger.nextRetryDelayMinutes(), "Step 2 = 15 min")

        trigger.onWorkoutCompleted()
        assertEquals(30, trigger.nextRetryDelayMinutes(), "Step 3 = 30 min")

        trigger.onWorkoutCompleted()
        assertEquals(60, trigger.nextRetryDelayMinutes(), "Step 4 = 60 min")
    }

    @Test
    fun backoffScheduleCapsAtSixtyMinutes() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))

        repeat(6) { trigger.onWorkoutCompleted() }

        assertEquals(
            60,
            trigger.nextRetryDelayMinutes(),
            "After 5+ transient failures the delay caps at the last scheduled value (60)",
        )
        assertEquals(
            4,
            trigger.currentBackoffIndex(),
            "Backoff index never grows past the schedule length (4 entries)",
        )
    }

    // ==================== Reset on Success ====================

    @Test
    fun successfulSyncResetsBackoffCounterToZero() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc)

        // Build up some backoff.
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(3, trigger.currentBackoffIndex(), "pre-condition: backoff has ratcheted up")

        // Now succeed.
        sm.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()

        assertEquals(0, trigger.currentBackoffIndex(), "Success resets backoff index")
        assertEquals(0, trigger.consecutiveFailures(), "Success resets failure counter")
        assertNull(trigger.nextRetryDelayMinutes(), "No next-retry delay after a clean success")
    }

    // ==================== Manual vs Throttled Triggers ====================

    @Test
    fun manualWorkoutCompleteBypassesBackoffWindow() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc, now = 1_000_000L)

        // Start with a successful sync to set lastSyncAttemptMillis.
        sm.syncResult = Result.success(currentTimeMillis())
        trigger.onWorkoutCompleted()
        val afterFirst = sm.syncCallCount

        // Still within the 5-minute default throttle: a workout-complete trigger MUST still sync.
        trigger.advanceTime(30_000L) // 30 seconds later
        trigger.onWorkoutCompleted()

        assertEquals(
            afterFirst + 1,
            sm.syncCallCount,
            "onWorkoutCompleted must bypass the throttle/backoff window",
        )
    }

    @Test
    fun foregroundTriggerIsSuppressedInsideBackoffWindow() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc, now = 1_000_000L)

        // First foreground: success (resets backoff, records lastSyncAttempt).
        sm.syncResult = Result.success(currentTimeMillis())
        trigger.onAppForeground()
        val firstCount = sm.syncCallCount
        assertEquals(1, firstCount, "First foreground sync should run")

        // Advance within the default 5-minute throttle: foreground should NOT re-run.
        trigger.advanceTime(60_000L) // 1 minute later
        trigger.onAppForeground()
        assertEquals(
            firstCount,
            sm.syncCallCount,
            "Foreground trigger inside throttle window must not call sync",
        )

        // Advance past throttle: foreground should run again.
        trigger.advanceTime(5 * 60 * 1000L + 1) // >5 min later
        trigger.onAppForeground()
        assertTrue(
            sm.syncCallCount > firstCount,
            "Foreground trigger after throttle window should call sync again",
        )
    }

    @Test
    fun foregroundTriggerRespectsEscalatedBackoffAfterFailures() = runTest {
        val sm = TestSyncManager()
        val cc = TestConnectivityChecker()
        val trigger = TestableSyncTriggerManager(sm, cc, now = 1_000_000L)

        // Fail twice (manual) → backoff = 15 min.
        sm.syncResult = Result.failure(PortalApiException("boom", null, 500))
        trigger.onWorkoutCompleted()
        trigger.onWorkoutCompleted()
        assertEquals(15, trigger.nextRetryDelayMinutes(), "After 2 failures backoff = 15 min")
        val callsAfterFailures = sm.syncCallCount

        // Foreground 10 minutes later → still inside the 15-minute escalated window.
        trigger.advanceTime(10 * 60 * 1000L)
        trigger.onAppForeground()
        assertEquals(
            callsAfterFailures,
            sm.syncCallCount,
            "Foreground trigger at t=10min (inside 15-min backoff) must be suppressed",
        )

        // Foreground 20 minutes later (past the 15-min window) → should run.
        trigger.advanceTime(11 * 60 * 1000L)
        trigger.onAppForeground()
        assertTrue(
            sm.syncCallCount > callsAfterFailures,
            "Foreground trigger after the escalated backoff window must run",
        )
    }
}
