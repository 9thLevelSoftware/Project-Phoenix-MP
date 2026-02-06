package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.ConnectivityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * Manages automatic sync triggers with throttling and failure tracking.
 *
 * Sync is triggered:
 * - On workout complete (bypasses throttle)
 * - On app foreground (respects 5-minute throttle)
 *
 * Sync is skipped if:
 * - Device is offline
 * - User is not authenticated
 * - Throttle period hasn't elapsed (for foreground trigger)
 *
 * Error handling:
 * - Tracks consecutive failures
 * - Exposes hasPersistentError after 3 failures
 * - Resets on successful sync
 */
class SyncTriggerManager(
    private val syncManager: SyncManager,
    private val connectivityChecker: ConnectivityChecker
) {
    companion object {
        private const val THROTTLE_MILLIS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val stateLock = Any()
    private var lastSyncAttemptMillis: Long = 0
    private var consecutiveFailures: Int = 0

    private val _hasPersistentError = MutableStateFlow(false)
    val hasPersistentError: StateFlow<Boolean> = _hasPersistentError.asStateFlow()

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
     * Respects throttle to avoid excessive sync attempts.
     */
    suspend fun onAppForeground() {
        Logger.d { "SyncTrigger: App foreground, checking if sync needed" }
        attemptSync(bypassThrottle = false)
    }

    /**
     * Clears the persistent error state.
     * Called when user acknowledges the error or manually triggers sync.
     */
    fun clearError() {
        synchronized(stateLock) { consecutiveFailures = 0 }
        _hasPersistentError.value = false
    }

    private suspend fun attemptSync(bypassThrottle: Boolean) {
        // Check authentication
        if (!syncManager.isAuthenticated.value) {
            Logger.d { "SyncTrigger: Skipping sync - not authenticated" }
            return
        }

        // Check connectivity
        if (!connectivityChecker.isOnline()) {
            Logger.d { "SyncTrigger: Skipping sync - offline" }
            return
        }

        // Check throttle (unless bypassed for workout complete)
        val now = Clock.System.now().toEpochMilliseconds()
        val shouldSkip = synchronized(stateLock) {
            if (!bypassThrottle && (now - lastSyncAttemptMillis) < THROTTLE_MILLIS) {
                true
            } else {
                lastSyncAttemptMillis = now
                false
            }
        }
        if (shouldSkip) {
            Logger.d { "SyncTrigger: Skipping sync - throttled" }
            return
        }

        // Attempt sync (outside lock - suspend call)
        val result = syncManager.sync()

        if (result.isSuccess) {
            Logger.d { "SyncTrigger: Sync successful" }
            synchronized(stateLock) { consecutiveFailures = 0 }
            _hasPersistentError.value = false
        } else {
            val failures = synchronized(stateLock) { ++consecutiveFailures }
            Logger.w { "SyncTrigger: Sync failed (attempt $failures)" }
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                _hasPersistentError.value = true
                Logger.e { "SyncTrigger: Persistent error - $MAX_CONSECUTIVE_FAILURES consecutive failures" }
            }
        }
    }
}
