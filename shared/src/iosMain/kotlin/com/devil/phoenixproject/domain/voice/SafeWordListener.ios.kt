@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.domain.voice

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognitionTaskStateCanceling
import platform.Speech.SFSpeechRecognitionTaskStateCompleted
import platform.Speech.SFSpeechRecognizer
// Raw values for SFSpeechRecognizerAuthorizationStatus (Xcode 26+ changed enum mapping)
private const val SPEECH_AUTH_NOT_DETERMINED = 0L
private const val SPEECH_AUTH_AUTHORIZED = 3L
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

/**
 * iOS implementation of [SafeWordListener] using SFSpeechRecognizer.
 *
 * Key behaviors:
 * - On-device only via requiresOnDeviceRecognition = true
 * - Continuous listening via auto-restart when recognition task completes
 * - Coexists with music via AVAudioSession .playAndRecord + .mixWithOthers
 * - Processes partial results to detect the safe word with minimal latency
 */
actual class SafeWordListener(
    private val safeWord: String,
) {
    private companion object {
        const val TAG = "SafeWordListener"
        const val RESTART_DELAY_NS = 500_000_000L // 500ms in nanoseconds
        /** Minimum interval between emissions to prevent partial+final double-counting. */
        const val DEBOUNCE_MS = 1000L
    }

    private val speechRecognizer = SFSpeechRecognizer()
    private var audioEngine: AVAudioEngine? = null
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null

    private val _isListening = MutableStateFlow(false)
    actual val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _detectedWord = MutableSharedFlow<String>(extraBufferCapacity = 1)
    actual val detectedWord: SharedFlow<String> = _detectedWord.asSharedFlow()

    /** Tracks whether we *want* to be listening (guards auto-restart). */
    private var shouldBeListening = false

    /** Last time we emitted a detection — used to debounce partial+final duplicates. */
    private var lastEmitTimeMs = 0L

    /** Guards against re-entrant tearDown calls from concurrent dispatch. */
    private var isTearingDown = false

    actual fun startListening() {
        if (shouldBeListening) return

        if (!speechRecognizer.isAvailable()) {
            NSLog("$TAG: Speech recognition not available on this device")
            return
        }

        val authStatus = SFSpeechRecognizer.authorizationStatus()
        when (authStatus) {
            SPEECH_AUTH_AUTHORIZED -> { /* proceed */ }
            SPEECH_AUTH_NOT_DETERMINED -> {
                SFSpeechRecognizer.requestAuthorization { newStatus ->
                    if (newStatus == SPEECH_AUTH_AUTHORIZED) {
                        // Re-enter startListening on main thread
                        dispatch_async(dispatch_get_main_queue()) { startListening() }
                    } else {
                        NSLog("$TAG: Speech recognition denied by user")
                        _isListening.value = false
                    }
                }
                return // Wait for callback
            }
            else -> {
                NSLog("$TAG: Speech recognition not authorized (status=$authStatus)")
                _isListening.value = false
                return
            }
        }

        shouldBeListening = true
        dispatch_async(dispatch_get_main_queue()) {
            startRecognition()
        }
    }

    actual fun stopListening() {
        shouldBeListening = false
        dispatch_async(dispatch_get_main_queue()) {
            tearDown()
        }
    }

    // ---- internal ----

    private fun startRecognition() {
        if (!shouldBeListening) return

        try {
            // Cancel any existing task
            cancelExistingTask()

            // Configure audio session for recording while allowing music playback
            configureAudioSession()

            // Create the recognition request
            val request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.requiresOnDeviceRecognition = true
            recognitionRequest = request

            // Set up audio engine
            val engine = AVAudioEngine()
            audioEngine = engine

            val inputNode = engine.inputNode
            val recordingFormat = inputNode.outputFormatForBus(0u)

            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 1024u,
                format = recordingFormat,
            ) { buffer, _ ->
                buffer?.let { request.appendAudioPCMBuffer(it) }
            }

            engine.prepare()
            @OptIn(BetaInteropApi::class)
            val engineStarted = memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val started = engine.startAndReturnError(errorPtr.ptr)
                if (!started) {
                    val startError = errorPtr.value
                    NSLog("$TAG: Audio engine failed to start: ${startError?.localizedDescription}")
                }
                started
            }
            if (!engineStarted) {
                _isListening.value = false
                return
            }

            // Start recognition task
            recognitionTask = speechRecognizer.recognitionTaskWithRequest(
                request,
            ) { result, error ->
                handleRecognitionResult(result, error)
            }

            _isListening.value = true
            NSLog("$TAG: Speech recognition started, listening for: \"$safeWord\"")
        } catch (e: Exception) {
            NSLog("$TAG: Failed to start speech recognition: ${e.message}")
            _isListening.value = false
            scheduleRestart()
        }
    }

    private fun configureAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionCategoryOptionMixWithOthers,
                null,
            )
            session.setMode(AVAudioSessionModeDefault, null)
            session.setActive(true, null)
        } catch (e: Exception) {
            NSLog("$TAG: Failed to configure audio session: ${e.message}")
        }
    }

    private fun handleRecognitionResult(
        result: SFSpeechRecognitionResult?,
        error: NSError?,
    ) {
        if (result != null) {
            val text = result.bestTranscription.formattedString
            if (matchesSafeWord(text)) {
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
                if (now - lastEmitTimeMs < DEBOUNCE_MS) {
                    NSLog("$TAG: Safe word match suppressed (debounce): \"$text\"")
                } else {
                    lastEmitTimeMs = now
                    NSLog("$TAG: Safe word detected in: \"$text\"")
                    _detectedWord.tryEmit(safeWord)
                }
            }
        }

        val isFinal = result?.isFinal() ?: false

        if (isFinal || error != null) {
            if (error != null) {
                NSLog("$TAG: Recognition error: ${error.localizedDescription}")
            }
            // Recognition segment ended; restart for continuous listening
            scheduleRestart()
        }
    }

    /**
     * Checks result text for the safe word (case-insensitive).
     * Splits on whitespace so "stop now" matches a safeWord of "stop".
     */
    private fun matchesSafeWord(text: String): Boolean =
        text.split("\\s+".toRegex()).any { it.equals(safeWord, ignoreCase = true) }

    private fun cancelExistingTask() {
        val taskState = recognitionTask?.state
        if (taskState != null &&
            taskState != SFSpeechRecognitionTaskStateCanceling &&
            taskState != SFSpeechRecognitionTaskStateCompleted
        ) {
            recognitionTask?.cancel()
        }
        recognitionTask = null
    }

    private fun tearDown() {
        if (isTearingDown) return
        isTearingDown = true
        try {
            audioEngine?.let { engine ->
                engine.inputNode.removeTapOnBus(0u)
                engine.stop()
            }
            audioEngine = null

            recognitionRequest?.endAudio()
            recognitionRequest = null

            cancelExistingTask()

            _isListening.value = false

            // Deactivate audio session to release resources
            try {
                AVAudioSession.sharedInstance().setActive(
                    false,
                    AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    null,
                )
            } catch (e: Exception) {
                NSLog("$TAG: Error deactivating audio session: ${e.message}")
            }
        } catch (e: Exception) {
            NSLog("$TAG: Error stopping audio engine: ${e.message}")
        } finally {
            isTearingDown = false
        }
    }

    private fun scheduleRestart() {
        if (!shouldBeListening) return

        tearDown()

        val restartTime = dispatch_time(DISPATCH_TIME_NOW, RESTART_DELAY_NS)
        dispatch_after(restartTime, dispatch_get_main_queue()) {
            if (shouldBeListening) {
                startRecognition()
            }
        }
    }
}
