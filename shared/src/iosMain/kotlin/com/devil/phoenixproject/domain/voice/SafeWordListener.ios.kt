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
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognitionTaskStateCanceling
import platform.Speech.SFSpeechRecognitionTaskStateCompleted
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * iOS implementation of [SafeWordListener] using SFSpeechRecognizer.
 *
 * Key behaviors:
 * - On-device only via requiresOnDeviceRecognition = true
 * - Continuous listening via auto-restart when recognition task completes
 * - Coexists with music via AVAudioSession .playAndRecord + .mixWithOthers
 * - Processes partial results to detect the safe word with minimal latency
 */
actual class SafeWordListener(private val safeWord: String) {
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
    private var inputTapInstalled = false

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

    /**
     * Identifies recognition callbacks so lifecycle recovery can suppress only
     * the stale task it intentionally cancelled, without muting callbacks from
     * the fresh recognizer started immediately afterward.
     */
    private var recognitionCallbackGeneration = 0L
    private var lifecycleRecoveryCancellationGeneration: Long? = null

    // Issue #522: Observer tokens for iOS app-foreground and AVAudioSession
    // interruption notifications. Installed on the first startListening() and
    // removed in stopListening() so the listener can recover after the user
    // backgrounds then foregrounds the app, or after a phone call / Siri
    // interruption ends.
    private val lifecycleObservers = mutableListOf<NSObjectProtocol>()
    private var lifecycleObserversInstalled = false
    private var lifecycleObserverGeneration = 0L

    actual fun startListening() {
        if (shouldBeListening) return

        if (!speechRecognizer.isAvailable()) {
            NSLog("$TAG: Speech recognition not available on this device")
            return
        }

        val authStatus = SFSpeechRecognizer.authorizationStatus()
        when (authStatus) {
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> { /* proceed */ }

            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> {
                SFSpeechRecognizer.requestAuthorization { newStatus ->
                    if (newStatus == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
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
        val generation = ++lifecycleObserverGeneration
        dispatch_async(dispatch_get_main_queue()) {
            if (shouldBeListening && generation == lifecycleObserverGeneration) {
                installLifecycleObservers()
            }
        }
        startRecognitionWhenRecordPermissionGranted()
    }

    actual fun stopListening() {
        shouldBeListening = false
        val generation = ++lifecycleObserverGeneration
        dispatch_async(dispatch_get_main_queue()) {
            // Issue #522: Confine observer mutations to the main queue, but use
            // a generation guard so a queued stop from a rapid stop→start cycle
            // cannot remove freshly installed observers for the restarted
            // listener.
            if (generation == lifecycleObserverGeneration) {
                removeLifecycleObservers()
            }
            tearDown()
        }
    }

    // ---- internal ----

    private fun startRecognitionWhenRecordPermissionGranted() {
        val session = AVAudioSession.sharedInstance()
        when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> dispatchStartRecognition()

            AVAudioSessionRecordPermissionUndetermined -> {
                session.requestRecordPermission { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (shouldBeListening) {
                            if (granted) {
                                startRecognition()
                            } else {
                                NSLog("$TAG: Microphone recording permission denied by user")
                                shouldBeListening = false
                                _isListening.value = false
                            }
                        }
                    }
                }
            }

            AVAudioSessionRecordPermissionDenied -> {
                NSLog("$TAG: Microphone recording permission denied")
                shouldBeListening = false
                _isListening.value = false
            }

            else -> {
                NSLog("$TAG: Microphone recording permission unavailable (status=${session.recordPermission})")
                shouldBeListening = false
                _isListening.value = false
            }
        }
    }

    private fun dispatchStartRecognition() {
        dispatch_async(dispatch_get_main_queue()) {
            startRecognition()
        }
    }

    private fun startRecognition() {
        if (!shouldBeListening) return

        try {
            // Clear any existing task/engine before creating a fresh input tap.
            cancelExistingTask()
            if (audioEngine != null) {
                tearDown()
            }

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
            if (!recordingFormat.isValidForRecordingTap()) {
                // AVAudioNode raises an Objective-C exception for invalid tap
                // formats, which Kotlin/Native cannot catch as Exception.
                NSLog(
                    "$TAG: Invalid input format for speech recognition " +
                        "(sampleRate=${recordingFormat.sampleRate}, channels=${recordingFormat.channelCount})",
                )
                _isListening.value = false
                scheduleRestart()
                return
            }

            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 1024u,
                format = recordingFormat,
            ) { buffer, _ ->
                buffer?.let { request.appendAudioPCMBuffer(it) }
            }
            inputTapInstalled = true

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
                scheduleRestart()
                return
            }

            // Start recognition task
            val callbackGeneration = ++recognitionCallbackGeneration
            recognitionTask = speechRecognizer.recognitionTaskWithRequest(
                request,
            ) { result, error ->
                handleRecognitionResult(callbackGeneration, result, error)
            }

            _isListening.value = true
            // fix(audit): H — do not log the configured safe word. It is user-
            // chosen and may be PII or a sensitive phrase. Log only a length
            // hint for debugging startup issues.
            NSLog("$TAG: Speech recognition started (safe word len=${safeWord.length})")
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

    private fun handleRecognitionResult(generation: Long, result: SFSpeechRecognitionResult?, error: NSError?) {
        if (result != null) {
            val text = result.bestTranscription.formattedString
            if (matchesSafeWord(text)) {
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
                if (now - lastEmitTimeMs < DEBOUNCE_MS) {
                    // fix(audit): H — never log recognized speech text. It
                    // contains the safe word and whatever the user said around
                    // it; both are sensitive.
                    NSLog("$TAG: Safe word match suppressed (debounce)")
                } else {
                    lastEmitTimeMs = now
                    // fix(audit): H — redacted: previously logged matched transcript.
                    NSLog("$TAG: Safe word detected")
                    _detectedWord.tryEmit(safeWord)
                }
            }
        }

        val isFinal = result?.isFinal() ?: false

        if (isFinal || error != null) {
            if (generation == lifecycleRecoveryCancellationGeneration) {
                NSLog("$TAG: Suppressing restart from lifecycle recovery cancellation")
                return
            }
            if (generation != recognitionCallbackGeneration) {
                NSLog("$TAG: Ignoring completion from stale recognition generation")
                return
            }
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
    private fun matchesSafeWord(text: String): Boolean = text.split("\\s+".toRegex()).any { it.equals(safeWord, ignoreCase = true) }

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
                if (inputTapInstalled) {
                    inputTapInstalled = false
                    engine.inputNode.removeTapOnBus(0u)
                }
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

    /**
     * Issue #522: Wire up iOS app-foreground and AVAudioSession interruption
     * observers so the listener recovers when the user backgrounds and then
     * foregrounds the app, or when a phone call / Siri / Alarm interruption
     * ends. Both blocks run on the main queue and only act when
     * [shouldBeListening] is still true.
     */
    private fun installLifecycleObservers() {
        if (lifecycleObserversInstalled) return
        lifecycleObserversInstalled = true
        val center = NSNotificationCenter.defaultCenter

        val foregroundObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { _ ->
                restartRecognitionFromLifecycle("foreground")
            },
        )
        val interruptionObserver = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = { notification ->
                val typeValue = (notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)
                    ?.unsignedLongLongValue
                if (typeValue == AVAudioSessionInterruptionTypeEnded) {
                    restartRecognitionFromLifecycle("interruption-ended")
                }
            },
        )
        lifecycleObservers += foregroundObserver
        lifecycleObservers += interruptionObserver
    }

    private fun removeLifecycleObservers() {
        if (!lifecycleObserversInstalled) return
        lifecycleObserversInstalled = false
        lifecycleObservers.forEach { observer ->
            try {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
            } catch (e: Exception) {
                NSLog("$TAG: Error removing lifecycle observer: ${e.message}")
            }
        }
        lifecycleObservers.clear()
    }

    /**
     * Issue #522: Re-attach recognition after a foreground or interruption
     * recovery event. Tears down the stale audio engine + recognition task,
     * then re-runs startRecognition() on the main queue (matching the
     * normal restart path).
     */
    private fun restartRecognitionFromLifecycle(reason: String) {
        if (!shouldBeListening) return
        NSLog("$TAG: Recovering speech recognition (reason=$reason)")
        lifecycleRecoveryCancellationGeneration = recognitionCallbackGeneration
        tearDown()
        dispatchStartRecognition()
    }

    private fun AVAudioFormat.isValidForRecordingTap(): Boolean = sampleRate > 0.0 && channelCount > 0u
}
