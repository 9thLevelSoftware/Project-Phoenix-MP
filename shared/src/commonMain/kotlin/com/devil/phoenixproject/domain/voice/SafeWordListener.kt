package com.devil.phoenixproject.domain.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Why the safe word cannot currently stop the machine.
 *
 * F-039: every early return and failure path in the voice pipeline reports one
 * of these so the workout HUD can tell the user the emergency stop is not armed
 * instead of failing silently.
 */
enum class SafeWordUnavailableReason {
    /** The active profile is still switching, so preferences are unknown. */
    PROFILE_SWITCHING,

    /** Voice stop is on but no safe word has been chosen. */
    NOT_CONFIGURED,

    /** A safe word exists but was never calibrated, so it is not trusted. */
    NOT_CALIBRATED,

    /** The device has no usable on-device speech recognizer. */
    RECOGNIZER_UNAVAILABLE,

    /** Microphone / speech recognition permission was denied or revoked. */
    PERMISSION,

    /** Another app took audio focus, so the microphone is no longer ours. */
    AUDIO_FOCUS_LOST,

    /** Recognition could not be started (audio engine or recognizer error). */
    START_FAILED,
}

/**
 * Whether the voice safe word emergency stop is actually armed.
 *
 * Shared by [SafeWordListener] (per-listener) and
 * [SafeWordDetectionManager] (whole feature).
 */
sealed interface SafeWordState {
    /** Voice stop is off, or the listener has not been started. */
    data object Disabled : SafeWordState

    /** Start was requested; recognition is not running yet (including auto-restart gaps). */
    data object Arming : SafeWordState

    /** Recognition is running: saying the safe word will stop the set. */
    data object Armed : SafeWordState

    /** Voice stop is on but cannot work right now. */
    data class Unavailable(val reason: SafeWordUnavailableReason) : SafeWordState
}

/**
 * Platform-specific continuous speech listener that detects a configured safe word.
 *
 * Both platforms use on-device-only recognition (no network dependency):
 * - Android: SpeechRecognizer with EXTRA_PREFER_OFFLINE
 * - iOS: SFSpeechRecognizer with requiresOnDeviceRecognition
 *
 * The listener auto-restarts after each recognition segment to provide
 * continuous monitoring during workouts. It coexists with music playback
 * by using transient audio focus (Android) / mixWithOthers (iOS).
 *
 * Created by DI via platform modules — the Android implementation takes a
 * Context, the iOS one takes no extra dependencies.
 */
interface SafeWordListener {
    /**
     * Start continuous speech recognition listening for the safe word.
     * Must be called from the main thread on Android.
     * No-op if already listening.
     */
    fun startListening()

    /**
     * Stop speech recognition and release audio resources.
     * Safe to call even if not currently listening.
     */
    fun stopListening()

    /**
     * Whether this listener is actually able to hear the safe word.
     * [SafeWordState.Disabled] initially and after [stopListening];
     * [SafeWordState.Unavailable] whenever recognition failed rather than
     * failing silently.
     */
    val state: StateFlow<SafeWordState>

    /**
     * Emits the detected word each time partial results match the safe word
     * (case-insensitive). Downstream consumers use this to trigger emergency stops.
     */
    val detectedWord: SharedFlow<String>
}
