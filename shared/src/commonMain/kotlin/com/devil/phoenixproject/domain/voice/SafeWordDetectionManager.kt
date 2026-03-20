package com.devil.phoenixproject.domain.voice

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Manages the lifecycle of safe word detection during workouts.
 *
 * Starts listening when a workout begins (if enabled and calibrated),
 * stops when the workout ends or the app backgrounds.
 * The underlying [SafeWordListener] handles auto-restart on recognition gaps.
 *
 * Issue #141: Voice-activated emergency stop.
 */
class SafeWordDetectionManager(
    private val preferencesManager: PreferencesManager,
    private val listenerFactory: SafeWordListenerFactory,
) {
    private companion object {
        const val TAG = "SafeWordDetectionManager"
    }

    private var listener: SafeWordListener? = null

    /**
     * Start safe word detection for the current workout.
     * No-op if voice stop is disabled or no safe word is configured.
     */
    fun startForWorkout() {
        val prefs = preferencesManager.preferencesFlow.value
        if (!prefs.voiceStopEnabled) {
            Logger.d(TAG) { "Voice stop not enabled, skipping" }
            return
        }
        val safeWord = prefs.safeWord
        if (safeWord.isNullOrBlank()) {
            Logger.w(TAG) { "Voice stop enabled but no safe word configured, skipping" }
            return
        }

        // Stop any existing listener before starting a new one
        stop()

        Logger.i(TAG) { "Starting safe word detection for workout (word: \"$safeWord\")" }
        listener = listenerFactory.create(safeWord).also { it.startListening() }
    }

    /**
     * Stop safe word detection and release resources.
     * Safe to call even if not currently listening.
     */
    fun stop() {
        listener?.let {
            Logger.d(TAG) { "Stopping safe word detection" }
            it.stopListening()
        }
        listener = null
    }

    /**
     * Flow that emits the detected safe word each time it is recognized.
     * Returns [emptyFlow] when no listener is active.
     */
    val detectedWord: Flow<String>
        get() = listener?.detectedWord ?: emptyFlow()
}
