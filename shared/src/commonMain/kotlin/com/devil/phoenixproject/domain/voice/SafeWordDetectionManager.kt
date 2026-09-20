package com.devil.phoenixproject.domain.voice

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val userProfileRepository: UserProfileRepository,
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

    /**
     * Whether the voice emergency stop is actually armed (F-039).
     *
     * Every early return and listener failure lands here, so the workout HUD can
     * tell the user the safe word will not stop the machine instead of the
     * feature failing silently.
     */
    private val _state = MutableStateFlow<SafeWordState>(SafeWordState.Disabled)
    val state: StateFlow<SafeWordState> = _state.asStateFlow()

    private val _unavailableAtStart = MutableSharedFlow<SafeWordUnavailableReason>(extraBufferCapacity = 1)

    /**
     * Emits once per [startForWorkout] call when voice stop is on but not armed,
     * so the screen can show a one-time warning at the start of the set.
     */
    val unavailableAtStart: SharedFlow<SafeWordUnavailableReason> = _unavailableAtStart.asSharedFlow()

    /** Coroutine bridging the current listener's detectedWord to the stable flow. */
    private var bridgeJob: Job? = null

    /** Supervisor for the bridge scope — held as a field so stop() can cancel it. */
    private var bridgeSupervisor = SupervisorJob()

    /**
     * Start safe word detection for the current workout.
     * No-op if voice stop is disabled or no safe word is configured.
     */
    fun startForWorkout() {
        val context = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        if (context == null) {
            Logger.w(TAG) { "Profile context is switching, skipping voice stop" }
            reportUnavailable(SafeWordUnavailableReason.PROFILE_SWITCHING)
            return
        }
        if (!context.preferences.workout.value.voiceStopEnabled) {
            Logger.d(TAG) { "Voice stop not enabled, skipping" }
            _state.value = SafeWordState.Disabled
            return
        }
        val safeWord = context.localSafety.safeWord
        if (safeWord.isNullOrBlank()) {
            Logger.w(TAG) { "Voice stop enabled but no safe word configured, skipping" }
            reportUnavailable(SafeWordUnavailableReason.NOT_CONFIGURED)
            return
        }
        if (!context.localSafety.safeWordCalibrated) {
            Logger.w(TAG) { "Voice stop enabled but safe word not calibrated, skipping" }
            reportUnavailable(SafeWordUnavailableReason.NOT_CALIBRATED)
            return
        }

        // Stop any existing listener before starting a new one
        stop()

        // F032: do not log the safe word itself — it is user-chosen voice PII.
        // The platform listeners already redact it in their startup logs; this
        // manager must too. Log only non-sensitive metadata.
        Logger.i(TAG) { "Starting safe word detection for workout (configured, length=${safeWord.length})" }
        val newListener = listenerFactory.create(safeWord)
        listener = newListener

        // Bridge the listener's flows to our stable flows with a tracked supervisor
        bridgeSupervisor = SupervisorJob()
        val bridgeScope = CoroutineScope(Dispatchers.Main + bridgeSupervisor)
        bridgeJob = bridgeScope.launch {
            newListener.detectedWord.collect { word ->
                _detectedWord.tryEmit(word)
            }
        }
        _state.value = SafeWordState.Arming
        bridgeScope.launch {
            var reportedForThisStart = false
            newListener.state.collect { listenerState ->
                _state.value = listenerState
                if (!reportedForThisStart && listenerState is SafeWordState.Unavailable) {
                    reportedForThisStart = true
                    _unavailableAtStart.tryEmit(listenerState.reason)
                }
            }
        }

        newListener.startListening()
    }

    /** Records why voice stop cannot work and announces it once for this set. */
    private fun reportUnavailable(reason: SafeWordUnavailableReason) {
        _state.value = SafeWordState.Unavailable(reason)
        _unavailableAtStart.tryEmit(reason)
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
        _state.value = SafeWordState.Disabled
    }
}
