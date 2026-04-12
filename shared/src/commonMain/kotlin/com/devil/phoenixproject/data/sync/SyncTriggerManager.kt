package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.withPlatformLock
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Retry state exposed for UI to display backoff status.
 */
data class RetryState(
    val retryCount: Int = 0,
    val nextRetryDelayMinutes: Int? = null,
    val nextRetryAtMillis: Long? = null,
    val lastErrorCategory: SyncErrorCategory? = null,
    val lastErrorMessage: String? = null,
    val isWaitingForConnectivity: Boolean = false,
    val requiresReLogin: Boolean = false,
)

/**
 * Manages automatic sync triggers with throttling, failure tracking, and exponential backoff.
 *
 * Sync is triggered:
 * - On workout complete (bypasses throttle)
 * - On app foreground (respects throttle with exponential backoff)
 *
 * Sync is skipped if:
 * - Device is offline
 * - User is not authenticated
 * - Throttle/backoff period hasn't elapsed (for foreground trigger)
 *
 * Error handling with classification:
 * - TRANSIENT errors: Apply exponential backoff (5 -> 15 -> 30 -> 60 minutes)
 * - PERMANENT errors: Don't retry, reset backoff
 * - NETWORK errors: Wait for connectivity change
 * - AUTH errors: Trigger re-login flow, don't retry
 *
 * Retry storm prevention:
 * - Max 3 consecutive retries before requiring manual intervention
 * - Backoff resets on successful sync
 */
class SyncTriggerManager(private val syncManager: SyncManager, private val connectivityChecker: ConnectivityChecker) {
    companion object {
        private const val DEFAULT_THROTTLE_MILLIS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * Exponential backoff schedule in minutes.
         * After each transient failure, the next delay is taken from this list.
         * Once exhausted, the last value is used.
         */
        val BACKOFF_SCHEDULE_MINUTES = listOf(5, 15, 30, 60)
    }

    private val stateLock = Any()
    private var lastSyncAttemptMillis: Long = 0
    private var consecutiveFailures: Int = 0
    private var currentBackoffIndex: Int = 0
    private var lastErrorCategory: SyncErrorCategory? = null
    private var lastErrorMessage: String? = null
    private var isWaitingForConnectivity: Boolean = false

    private val _hasPersistentError = MutableStateFlow(false)
    val hasPersistentError: StateFlow<Boolean> = _hasPersistentError.asStateFlow()

    private val _retryState = MutableStateFlow(RetryState())
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()

    /**
     * Called when a workout is completed and saved.
     * Always attempts sync (bypasses throttle) since workout data is critical.
     */
    suspend fun onWorkoutCompleted() {
        Logger.d { "SyncTrigger: Workout completed, attempting sync" }
        attemptSync(bypassThrottle = true)
    }

    /**
     * Called when the app returns to foreground.
     * Respects throttle/backoff to avoid excessive sync attempts.
     */
    suspend fun onAppForeground() {
        Logger.d { "SyncTrigger: App foreground, checking if sync needed" }
        attemptSync(bypassThrottle = false)
    }

    /**
     * Called when network connectivity is restored.
     * If we were waiting for connectivity, this triggers an immediate retry.
     */
    suspend fun onConnectivityRestored() {
        val shouldRetry = withPlatformLock(stateLock) {
            if (isWaitingForConnectivity) {
                isWaitingForConnectivity = false
                true
            } else {
                false
            }
        }
        if (shouldRetry) {
            Logger.i { "SyncTrigger: Connectivity restored, retrying sync" }
            attemptSync(bypassThrottle = true)
        }
    }

    /**
     * Clears the persistent error state and resets backoff.
     * Called when user acknowledges the error or manually triggers sync.
     */
    fun clearError() {
        withPlatformLock(stateLock) {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            lastErrorMessage = null
            isWaitingForConnectivity = false
        }
        _hasPersistentError.value = false
        updateRetryState()
    }

    /**
     * Gets the current throttle delay in milliseconds, accounting for backoff.
     */
    private fun getCurrentThrottleMillis(): Long {
        return if (currentBackoffIndex == 0) {
            DEFAULT_THROTTLE_MILLIS
        } else {
            val delayMinutes = BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) {
                BACKOFF_SCHEDULE_MINUTES.last()
            }
            delayMinutes * 60 * 1000L
        }
    }

    /**
     * Handles sync failure by classifying the error and updating retry state.
     */
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

        withPlatformLock(stateLock) {
            consecutiveFailures++
            lastErrorCategory = classified.category
            lastErrorMessage = classified.message

            when (classified.category) {
                SyncErrorCategory.TRANSIENT -> {
                    // Apply exponential backoff
                    if (currentBackoffIndex < BACKOFF_SCHEDULE_MINUTES.size) {
                        currentBackoffIndex++
                    }
                    Logger.w {
                        "SyncTrigger: Transient error, backoff index=$currentBackoffIndex, " +
                            "next delay=${BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) { BACKOFF_SCHEDULE_MINUTES.last() }} min"
                    }
                }

                SyncErrorCategory.PERMANENT -> {
                    // Don't retry, reset backoff
                    currentBackoffIndex = 0
                    Logger.e { "SyncTrigger: Permanent error, not retrying: ${classified.message}" }
                    _hasPersistentError.value = true
                }

                SyncErrorCategory.NETWORK -> {
                    // Wait for connectivity, don't increment backoff
                    isWaitingForConnectivity = true
                    Logger.w { "SyncTrigger: Network error, waiting for connectivity" }
                }

                SyncErrorCategory.AUTH -> {
                    // Trigger re-login, don't retry
                    currentBackoffIndex = 0
                    Logger.e { "SyncTrigger: Auth error, user needs to re-login" }
                    _hasPersistentError.value = true
                }
            }

            // Check for retry storm (max 3 consecutive failures for same error type)
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && classified.category == SyncErrorCategory.TRANSIENT) {
                _hasPersistentError.value = true
                Logger.e {
                    "SyncTrigger: Retry storm detected - $MAX_CONSECUTIVE_FAILURES consecutive failures"
                }
            }
        }

        updateRetryState()
    }

    /**
     * Handles successful sync by resetting backoff state.
     */
    private fun onSyncSuccess() {
        withPlatformLock(stateLock) {
            consecutiveFailures = 0
            currentBackoffIndex = 0
            lastErrorCategory = null
            lastErrorMessage = null
            isWaitingForConnectivity = false
        }
        _hasPersistentError.value = false
        updateRetryState()
    }

    /**
     * Updates the exposed RetryState for UI consumption.
     */
    private fun updateRetryState() {
        val state = withPlatformLock(stateLock) {
            val nextDelayMinutes = if (currentBackoffIndex > 0) {
                BACKOFF_SCHEDULE_MINUTES.getOrElse(currentBackoffIndex - 1) {
                    BACKOFF_SCHEDULE_MINUTES.last()
                }
            } else {
                null
            }

            val nextRetryAt = if (nextDelayMinutes != null) {
                lastSyncAttemptMillis + (nextDelayMinutes * 60 * 1000L)
            } else {
                null
            }

            RetryState(
                retryCount = consecutiveFailures,
                nextRetryDelayMinutes = nextDelayMinutes,
                nextRetryAtMillis = nextRetryAt,
                lastErrorCategory = lastErrorCategory,
                lastErrorMessage = lastErrorMessage,
                isWaitingForConnectivity = isWaitingForConnectivity,
                requiresReLogin = lastErrorCategory == SyncErrorCategory.AUTH,
            )
        }
        _retryState.value = state
    }

    private suspend fun attemptSync(bypassThrottle: Boolean) {
        // Check authentication
        if (!syncManager.isAuthenticated.value) {
            Logger.d { "SyncTrigger: Skipping sync - not authenticated" }
            return
        }

        // Check premium status -- skip auto-sync for users confirmed as free.
        // Allow first sync attempt (lastSyncTime == 0) so premium status can be discovered.
        val user = syncManager.currentUser.value
        if (user?.isPremium == false && syncManager.lastSyncTime.value > 0) {
            Logger.d { "SyncTrigger: Skipping sync - not premium" }
            return
        }

        // Check connectivity
        if (!connectivityChecker.isOnline()) {
            Logger.d { "SyncTrigger: Skipping sync - offline" }
            withPlatformLock(stateLock) { isWaitingForConnectivity = true }
            updateRetryState()
            return
        }

        // Check if waiting for connectivity (shouldn't auto-retry until connectivity event)
        val waitingForConnectivity = withPlatformLock(stateLock) { isWaitingForConnectivity }
        if (waitingForConnectivity && !bypassThrottle) {
            Logger.d { "SyncTrigger: Skipping sync - waiting for connectivity restoration" }
            return
        }

        // Check throttle/backoff (unless bypassed for workout complete)
        val now = Clock.System.now().toEpochMilliseconds()
        val currentThrottle = getCurrentThrottleMillis()
        val shouldSkip = withPlatformLock(stateLock) {
            if (!bypassThrottle && (now - lastSyncAttemptMillis) < currentThrottle) {
                true
            } else {
                lastSyncAttemptMillis = now
                false
            }
        }
        if (shouldSkip) {
            val remainingSeconds = withPlatformLock(stateLock) {
                ((lastSyncAttemptMillis + currentThrottle) - now) / 1000
            }
            Logger.d { "SyncTrigger: Skipping sync - backoff active, ${remainingSeconds}s remaining" }
            return
        }

        // Check for persistent error requiring manual retry
        if (_hasPersistentError.value && !bypassThrottle) {
            Logger.d { "SyncTrigger: Skipping sync - persistent error requires manual retry" }
            return
        }

        // Attempt sync (outside lock - suspend call)
        val result = syncManager.sync()

        if (result.isSuccess) {
            // Check if this was a partial success (push OK, pull failed)
            val state = syncManager.syncState.value
            if (state is SyncState.PartialSuccess) {
                Logger.w { "SyncTrigger: Partial sync success - pull failed" }
                // Treat as transient failure for retry purposes
                onSyncFailure(PortalApiException(state.pullError ?: "Pull failed"))
            } else {
                Logger.d { "SyncTrigger: Sync successful" }
                onSyncSuccess()
            }
        } else {
            val error = result.exceptionOrNull()
            Logger.w { "SyncTrigger: Sync failed: ${error?.message}" }
            onSyncFailure(error)
        }
    }
}
