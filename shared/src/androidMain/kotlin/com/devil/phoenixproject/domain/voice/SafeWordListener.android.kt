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
 * - Continuous listening via auto-restart on end-of-speech or recoverable errors
 * - Coexists with music via [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT]
 * - All SpeechRecognizer calls dispatched to main thread (API requirement)
 */
actual class SafeWordListener(private val context: Context, private val safeWord: String) {
    private companion object {
        const val TAG = "SafeWordListener"
        const val RESTART_DELAY_MS = 500L

        /** Minimum interval between emissions to prevent partial+final double-counting. */
        const val DEBOUNCE_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    actual val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _detectedWord = MutableSharedFlow<String>(extraBufferCapacity = 1)
    actual val detectedWord: SharedFlow<String> = _detectedWord.asSharedFlow()

    /** Tracks whether we *want* to be listening (guards auto-restart). */
    private var shouldBeListening = false

    /** Last time we emitted a detection — used to debounce partial+final duplicates. */
    private var lastEmitTimeMs = 0L

    /** Active audio focus request, held for abandoning on teardown. */
    private var audioFocusRequest: AudioFocusRequest? = null

    actual fun startListening() {
        if (shouldBeListening) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        shouldBeListening = true
        mainHandler.post { startRecognition() }
    }

    actual fun stopListening() {
        shouldBeListening = false
        mainHandler.post { tearDown() }
    }

    // ---- internal ----

    private fun startRecognition() {
        // Ensure we're on the main thread (SpeechRecognizer requirement)
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechRecognizer must be created on the main thread"
        }

        if (!shouldBeListening) return

        try {
            requestTransientAudioFocus()

            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(SafeWordRecognitionListener())
            recognizer = sr

            val intent = createRecognizerIntent()
            sr.startListening(intent)
            _isListening.value = true
            Log.d(TAG, "Speech recognition started, listening for: \"$safeWord\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            _isListening.value = false
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
                        stopListening()
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
            _isListening.value = false
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
     * Splits on whitespace so "stop now" matches a safeWord of "stop".
     */
    private fun matchesSafeWord(text: String): Boolean = text.split("\\s+".toRegex()).any { it.equals(safeWord, ignoreCase = true) }

    private fun processResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return
        for (match in matches) {
            if (matchesSafeWord(match)) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastEmitTimeMs < DEBOUNCE_MS) {
                    Log.d(TAG, "Safe word match suppressed (debounce): \"$match\"")
                    return
                }
                lastEmitTimeMs = now
                Log.i(TAG, "Safe word detected in: \"$match\"")
                _detectedWord.tryEmit(safeWord)
                return
            }
        }
    }

    // ---- RecognitionListener ----

    private inner class SafeWordRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // No-op: we don't need volume metering
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // No-op
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech, scheduling restart for continuous listening")
            scheduleRestart()
        }

        override fun onError(error: Int) {
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
                shouldBeListening = false
                tearDown()
                return
            }

            // All other errors: restart for continuous listening
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            processResults(results)
            // Final results signal end of utterance — restart
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // No-op
        }
    }
}
