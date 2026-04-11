package com.devil.phoenixproject.domain.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
 * Created by DI via platform modules — Android actual takes Context,
 * iOS actual takes no extra dependencies.
 */
expect class SafeWordListener {
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
     * Whether the listener is actively recognizing speech.
     * False initially and after [stopListening].
     */
    val isListening: StateFlow<Boolean>

    /**
     * Emits the detected word each time partial results match the safe word
     * (case-insensitive). Downstream consumers use this to trigger emergency stops.
     */
    val detectedWord: SharedFlow<String>
}
