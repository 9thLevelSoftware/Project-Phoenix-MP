package com.devil.phoenixproject.domain.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [SafeWordListener] using [SpeechRecognizer].
 *
 * Key behaviors:
 * - On-device only via [RecognizerIntent.EXTRA_PREFER_OFFLINE]
 * - Continuous listening via auto-restart after the final result or a recoverable
 *   error. End-of-speech waits for that callback; tearing the recognizer down
 *   there drops the final hypothesis.
 * - Coexists with music via [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT]
 * - All SpeechRecognizer calls dispatched to main thread (API requirement)
 * - RECORD_AUDIO is requested before the first listen when it is not already
 *   granted. Restarts do not ask again.
 */
class AndroidSafeWordListener(
    private val context: Context,
    private val safeWord: String,
) : SafeWordListener {
    private companion object {
        const val TAG = "SafeWordListener"
        const val RESTART_DELAY_MS = 500L

        /**
         * How long to wait after end-of-speech for [RecognitionListener.onResults]
         * or [RecognitionListener.onError] before restarting anyway.
         *
         * [SpeechRecognizer.cancel] and [SpeechRecognizer.destroy] drop an
         * undelivered final hypothesis, so the restart must not run from
         * end-of-speech itself. The wait is long enough for on-device recognition
         * to deliver that callback and short enough that a recognizer which
         * delivers nothing cannot stick.
         */
        const val END_OF_SPEECH_RESULT_GRACE_MS = 2_000L

        /** Minimum interval between emissions to prevent partial+final double-counting. */
        const val DEBOUNCE_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordAudioPermission = RecordAudioPermissionRequest(context)
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<SafeWordState>(SafeWordState.Disabled)
    override val state: StateFlow<SafeWordState> = _state.asStateFlow()

    private val _detectedWord = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val detectedWord: SharedFlow<String> = _detectedWord.asSharedFlow()

    /** Tracks whether we *want* to be listening (guards auto-restart). */
    private var shouldBeListening = false

    /** Bumped on every start/stop so a late permission callback cannot arm a stopped listener. */
    private var listenGeneration = 0

    /** True while the system RECORD_AUDIO dialog is outstanding. */
    private var permissionRequestInFlight = false

    /** Last time we emitted a detection — used to debounce partial+final duplicates. */
    private var lastEmitTimeMs = 0L

    /**
     * Bumped in [startRecognition]. Callbacks and the end-of-speech fallback
     * capture the value for the session that created them, so a recognizer we
     * already replaced cannot restart the new one.
     */
    private var listeningGeneration = 0

    /**
     * True once this recognition session has scheduled its single restart
     * (final results, an error, or the end-of-speech fallback).
     */
    private var utteranceRestartClaimed = false

    /** Pending end-of-speech fallback. Cleared when results or an error arrive. */
    private var endOfSpeechResultFallback: Runnable? = null

    /** Active audio focus request, held for abandoning on teardown. */
    private var audioFocusRequest: AudioFocusRequest? = null

    /** Bounds a restart loop that never reaches [RecognitionListener.onReadyForSpeech]. */
    private val armingTracker = SafeWordArmingTracker()

    override fun startListening() {
        if (shouldBeListening || permissionRequestInFlight) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // F-039: report instead of returning silently — the user believes
            // the safe word will stop the machine.
            Log.w(TAG, "Speech recognition not available on this device")
            _state.value = SafeWordState.Unavailable(SafeWordUnavailableReason.RECOGNIZER_UNAVAILABLE)
            return
        }

        // Prompt only when RECORD_AUDIO is not granted yet. Already-granted
        // sessions, and the recognition restart loop, go straight to listening.
        val generation = ++listenGeneration
        permissionRequestInFlight = true
        var completedSynchronously = false
        val status = recordAudioPermission.request(
            requestKey = "safe_word_record_audio_${System.identityHashCode(this)}_$generation",
        ) { granted ->
            completedSynchronously = true
            onRecordAudioPermissionResult(generation, granted)
        }
        if (status != RecordAudioPermissionStatus.Requesting || completedSynchronously) {
            permissionRequestInFlight = false
        }
        when (status) {
            RecordAudioPermissionStatus.Granted -> beginListening()
            RecordAudioPermissionStatus.Denied -> {
                Log.w(TAG, "RECORD_AUDIO unavailable; safe word will not arm")
                _state.value = SafeWordState.Unavailable(SafeWordUnavailableReason.PERMISSION)
            }
            RecordAudioPermissionStatus.Requesting -> Unit
        }
    }

    override fun stopListening() {
        listenGeneration++
        permissionRequestInFlight = false
        shouldBeListening = false
        mainHandler.post {
            tearDown()
            _state.value = SafeWordState.Disabled
        }
    }

    private fun onRecordAudioPermissionResult(generation: Int, granted: Boolean) {
        if (generation != listenGeneration) return
        permissionRequestInFlight = false
        if (granted) {
            beginListening()
        } else {
            Log.w(TAG, "RECORD_AUDIO denied; safe word will not arm")
            _state.value = SafeWordState.Unavailable(SafeWordUnavailableReason.PERMISSION)
        }
    }

    private fun beginListening() {
        shouldBeListening = true
        _state.value = SafeWordState.Arming
        mainHandler.post { startRecognition() }
    }

    // ---- internal ----

    /**
     * F-039: stop for good and say why, so the workout HUD can warn the user
     * that the voice emergency stop is no longer armed.
     */
    private fun failAndStop(reason: SafeWordUnavailableReason) {
        shouldBeListening = false
        mainHandler.post {
            tearDown()
            _state.value = SafeWordState.Unavailable(reason)
        }
    }

    private fun startRecognition() {
        // Ensure we're on the main thread (SpeechRecognizer requirement)
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechRecognizer must be created on the main thread"
        }

        if (!shouldBeListening) return

        // New session. Callbacks from the recognizer we are replacing must not
        // restart this one, and this session may restart exactly once.
        listeningGeneration++
        utteranceRestartClaimed = false

        // F-039: a start request only becomes Armed in onReadyForSpeech. Too many
        // starts in a row that never get there means the microphone is not coming.
        val attemptState = armingTracker.onStartAttempt()
        if (attemptState is SafeWordState.Unavailable) {
            Log.w(TAG, "Speech recognition kept restarting without ever becoming ready")
            failAndStop(attemptState.reason)
            return
        }

        try {
            requestTransientAudioFocus()

            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(SafeWordRecognitionListener(listeningGeneration))
            recognizer = sr

            val intent = createRecognizerIntent()
            sr.startListening(intent)
            _state.value = attemptState
            // fix(audit): H — do not log the configured safe word. It is user-
            // chosen and may be PII or a sensitive phrase. Log only a length
            // hint for debugging startup issues.
            Log.d(TAG, "Speech recognition start requested (safe word len=${safeWord.length})")
        } catch (e: Exception) {
            // Not reported yet: the retry below usually succeeds, and the arming
            // budget turns a persistent failure into Unavailable(START_FAILED).
            Log.e(TAG, "Failed to start speech recognition", e)
            scheduleRestart()
        }
    }

    private fun createRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Keep listening even during silence
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            5_000L,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            5_000L,
        )
    }

    private fun requestTransientAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        // F-039: losing focus permanently stops recognition; say so
                        // rather than looking indistinguishable from "switched off".
                        failAndStop(SafeWordUnavailableReason.AUDIO_FOCUS_LOST)
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus, continuing anyway", e)
        }
    }

    private fun tearDown() {
        // Drop a pending end-of-speech fallback before destroy. stopListening()
        // and a restart both land here; a fallback left armed would start a
        // second recognizer.
        cancelEndOfSpeechResultFallback()
        try {
            recognizer?.apply {
                stopListening()
                cancel()
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error tearing down recognizer", e)
        } finally {
            recognizer = null
            // Only downgrade from Armed: a terminal Unavailable(reason) recorded by
            // the caller must survive the teardown it triggers (F-039).
            if (_state.value is SafeWordState.Armed) {
                _state.value = if (shouldBeListening) SafeWordState.Arming else SafeWordState.Disabled
            }
            abandonAudioFocus()
        }
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let { request ->
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.abandonAudioFocusRequest(request)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus", e)
        } finally {
            audioFocusRequest = null
        }
    }

    /**
     * Arms a one-shot restart used only when end-of-speech is not followed by
     * [RecognitionListener.onResults] or [RecognitionListener.onError].
     * Both of those callbacks cancel this timeout and claim the session restart.
     */
    private fun armEndOfSpeechResultFallback(generation: Int) {
        cancelEndOfSpeechResultFallback()
        if (!shouldBeListening || generation != listeningGeneration || utteranceRestartClaimed) return
        val fallback = Runnable {
            if (endOfSpeechResultFallback === fallback) {
                endOfSpeechResultFallback = null
            }
            if (generation != listeningGeneration) return@Runnable
            if (!claimUtteranceRestart()) return@Runnable
            Log.w(TAG, "No final recognition result after end of speech; restarting")
            scheduleRestart()
        }
        endOfSpeechResultFallback = fallback
        mainHandler.postDelayed(fallback, END_OF_SPEECH_RESULT_GRACE_MS)
    }

    private fun cancelEndOfSpeechResultFallback() {
        endOfSpeechResultFallback?.let { mainHandler.removeCallbacks(it) }
        endOfSpeechResultFallback = null
    }

    /**
     * Returns true for the first restart of this recognition session.
     * The end-of-speech fallback, final results, and errors share it so only
     * one of them restarts.
     */
    private fun claimUtteranceRestart(): Boolean {
        if (utteranceRestartClaimed || !shouldBeListening) return false
        utteranceRestartClaimed = true
        cancelEndOfSpeechResultFallback()
        return true
    }

    private fun scheduleRestart() {
        if (!shouldBeListening) return
        // Tear down the old recognizer before restarting
        tearDown()
        mainHandler.postDelayed({
            if (shouldBeListening) {
                startRecognition()
            }
        }, RESTART_DELAY_MS)
    }

    /**
     * Checks partial or final result text for the safe word (case-insensitive).
     * Splits on whitespace so "stop now" matches a safeWord of "stop", and strips
     * punctuation from each token so "stop!", "stop.", or "stop, now" still match
     * — a safety-critical false negative otherwise (audit F062).
     */
    private fun matchesSafeWord(text: String): Boolean {
        val target = safeWord.normalizeForSafeWordMatch()
        if (target.isEmpty()) return false
        return text.split("\\s+".toRegex()).any { it.normalizeForSafeWordMatch() == target }
    }

    private fun String.normalizeForSafeWordMatch(): String = filter { it.isLetterOrDigit() }.lowercase()

    private fun processResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return
        for (match in matches) {
            if (matchesSafeWord(match)) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastEmitTimeMs < DEBOUNCE_MS) {
                    // fix(audit): H — never log recognized speech text. It
                    // contains the safe word and whatever the user said around
                    // it; both are sensitive.
                    Log.d(TAG, "Safe word match suppressed (debounce)")
                    return
                }
                lastEmitTimeMs = now
                // fix(audit): H — redacted: previously logged matched transcript.
                Log.i(TAG, "Safe word detected")
                _detectedWord.tryEmit(safeWord)
                return
            }
        }
    }

    // ---- RecognitionListener ----

    private inner class SafeWordRecognitionListener(
        private val generation: Int,
    ) : RecognitionListener {
        /** Callbacks from a recognizer we already replaced must not restart the new one. */
        private fun isStale(): Boolean = generation != listeningGeneration

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale()) return
            // F-039: only here does the recognizer actually hold the microphone,
            // so only here may the HUD claim the safe word will stop the machine.
            Log.d(TAG, "Ready for speech")
            _state.value = armingTracker.onRecognizerReady()
        }

        override fun onBeginningOfSpeech() {
            if (isStale()) return
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // No-op: we don't need volume metering
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // No-op
        }

        override fun onEndOfSpeech() {
            if (isStale()) return
            // Restarting here destroys the recognizer and drops the final hypothesis,
            // which arrives afterwards through onResults or onError.
            Log.d(TAG, "End of speech; waiting for the final recognition result")
            armEndOfSpeechResultFallback(generation)
        }

        override fun onError(error: Int) {
            if (isStale()) return
            // The utterance is over, whether or not we restart. Cancel the
            // end-of-speech fallback so it cannot start a second recognizer.
            cancelEndOfSpeechResultFallback()
            val errorName = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                else -> "UNKNOWN($error)"
            }
            Log.w(TAG, "Recognition error: $errorName")

            // Non-recoverable: permission denied — don't restart
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                failAndStop(SafeWordUnavailableReason.PERMISSION)
                return
            }

            if (claimUtteranceRestart()) {
                scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            if (isStale()) return
            // Match before any restart. Restarting destroys the recognizer, and
            // the fallback must not start a second one once this final hypothesis
            // has been taken.
            cancelEndOfSpeechResultFallback()
            processResults(results)
            if (claimUtteranceRestart()) {
                scheduleRestart()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isStale()) return
            processResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // No-op
        }
    }
}
