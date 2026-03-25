package com.devil.phoenixproject.domain.voice

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle of safe word detection during workouts.
 *
 * Starts listening when a workout begins (if enabled and calibrated),
 * stops when the workout ends or the app backgrounds.
 * The underlying [SafeWordListener] handles auto-restart on recognition gaps.
 *
 * Exposes a stable [detectedWord] flow that survives listener recreation,
 * bridging the underlying listener's flow to a long-lived SharedFlow.
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

    /** Stable flow that outlives individual listener instances. */
    private val _detectedWord = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Flow that emits the detected safe word each time it is recognized.
     * This flow is stable across listener recreation — collectors established
     * before [startForWorkout] will continue receiving emissions.
     */
    val detectedWord: SharedFlow<String> = _detectedWord.asSharedFlow()

    /** Coroutine bridging the current listener's detectedWord to the stable flow. */
    private var bridgeJob: Job? = null

    /** Supervisor for the bridge scope — held as a field so stop() can cancel it. */
    private var bridgeSupervisor = SupervisorJob()

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
        if (!prefs.safeWordCalibrated) {
            Logger.w(TAG) { "Voice stop enabled but safe word not calibrated, skipping" }
            return
        }

        // Stop any existing listener before starting a new one
        stop()

        Logger.i(TAG) { "Starting safe word detection for workout (word: \"$safeWord\")" }
        val newListener = listenerFactory.create(safeWord)
        listener = newListener

        // Bridge the listener's flow to our stable flow with a tracked supervisor
        bridgeSupervisor = SupervisorJob()
        bridgeJob = CoroutineScope(Dispatchers.Main + bridgeSupervisor).launch {
            newListener.detectedWord.collect { word ->
                _detectedWord.tryEmit(word)
            }
        }

        newListener.startListening()
    }

    /**
     * Stop safe word detection and release resources.
     * Safe to call even if not currently listening.
     */
    fun stop() {
        bridgeJob?.cancel()
        bridgeJob = null
        bridgeSupervisor.cancel()
        listener?.let {
            Logger.d(TAG) { "Stopping safe word detection" }
            it.stopListening()
        }
        listener = null
    }
}
