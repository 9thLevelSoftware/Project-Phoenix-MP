package com.devil.phoenixproject.domain.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SafeWordListenerIosAudioTapGuardTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/iosMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val safeWordListenerSource: File
        get() = File(
            projectRoot,
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/IosSafeWordListener.kt",
        )

    @Test
    fun iosSafeWordListener_playAndRecordDefaultsOutputToSpeaker() {
        val source = safeWordListenerSource.readText()
        val configureIndex = source.indexOf("private fun configureAudioSession()")
        assertTrue(configureIndex >= 0, "iOS safe-word listener must configure the shared audio session.")
        val nextFun = source.indexOf("\n    private fun ", configureIndex + 1)
        val body = if (nextFun > configureIndex) {
            source.substring(configureIndex, nextFun)
        } else {
            source.substring(configureIndex)
        }

        assertTrue(
            body.contains("AVAudioSessionCategoryPlayAndRecord"),
            "Safe-word capture must stay on PlayAndRecord so the microphone tap keeps working.",
        )
        assertTrue(
            body.contains("AVAudioSessionCategoryOptionMixWithOthers"),
            "Safe-word capture must keep MixWithOthers so background audio is not interrupted.",
        )
        assertTrue(
            body.contains("AVAudioSessionCategoryOptionDefaultToSpeaker"),
            "PlayAndRecord defaults to the receiver; DefaultToSpeaker routes workout cues to the speaker.",
        )
        assertTrue(
            body.contains("AVAudioSessionCategoryOptionMixWithOthers or"),
            "DefaultToSpeaker must be combined with MixWithOthers on the same setCategory call.",
        )
    }

    @Test
    fun iosSafeWordListener_requestsRecordPermissionBeforeInstallingAudioTap() {
        val source = safeWordListenerSource.readText()
        val requestPermissionIndex = source.indexOf("requestRecordPermission")
        val installTapIndex = source.indexOf("installTapOnBus")

        assertTrue(requestPermissionIndex >= 0, "iOS safe-word listener must request microphone recording permission.")
        assertTrue(installTapIndex >= 0, "iOS safe-word listener must install an input tap for speech audio.")
        assertTrue(
            requestPermissionIndex < installTapIndex,
            "Microphone recording permission must be resolved before installing the AVAudioNode tap.",
        )
        assertTrue(source.contains("AVAudioSessionRecordPermissionGranted"))
        assertTrue(source.contains("AVAudioSessionRecordPermissionDenied"))
    }

    @Test
    fun iosSafeWordListener_validatesRecordingFormatBeforeInstallingAudioTap() {
        val source = safeWordListenerSource.readText()
        val formatGuardIndex = source.indexOf("if (!recordingFormat.isValidForRecordingTap())")
        val installTapIndex = source.indexOf("installTapOnBus")

        assertTrue(formatGuardIndex >= 0, "iOS safe-word listener must guard invalid input formats.")
        assertTrue(installTapIndex >= 0, "iOS safe-word listener must install an input tap for speech audio.")
        assertTrue(
            formatGuardIndex < installTapIndex,
            "Invalid AVAudioFormat values must be rejected before installTapOnBus can raise an Objective-C exception.",
        )
        assertTrue(source.contains("sampleRate > 0.0 && channelCount > 0u"))
    }

    @Test
    fun iosSafeWordListener_onlyRemovesTapAfterSuccessfulInstall() {
        val source = safeWordListenerSource.readText()
        val removeTapIndex = source.indexOf("removeTapOnBus")
        val removeGuardIndex = source.lastIndexOf("if (inputTapInstalled)", removeTapIndex)

        assertTrue(source.contains("private var inputTapInstalled = false"))
        assertTrue(source.contains("inputTapInstalled = true"))
        assertTrue(removeTapIndex >= 0, "iOS safe-word listener must remove the input tap during teardown.")
        assertTrue(
            removeGuardIndex >= 0,
            "Teardown must not call removeTapOnBus unless installTapOnBus succeeded.",
        )
    }

    // ---- Issue #522: foreground + AVAudioSession interruption recovery ----

    @Test
    fun iosSafeWordListener_observesForegroundAndInterruptionNotifications() {
        val source = safeWordListenerSource.readText()

        assertTrue(
            source.contains("UIApplicationDidBecomeActiveNotification"),
            "iOS safe-word listener must observe UIApplicationDidBecomeActiveNotification for foreground recovery.",
        )
        assertTrue(
            source.contains("AVAudioSessionInterruptionNotification"),
            "iOS safe-word listener must observe AVAudioSessionInterruptionNotification for call/Siri recovery.",
        )
        assertTrue(
            source.contains("AVAudioSessionInterruptionTypeEnded"),
            "iOS safe-word listener must only react to the *ended* half of an AVAudioSession interruption.",
        )
    }

    @Test
    fun iosSafeWordListener_reconfiguresAudioSessionAndRecognitionOnLifecycleRecovery() {
        val source = safeWordListenerSource.readText()

        // The recovery path must re-configure the shared AVAudioSession and
        // re-enter startRecognition() so the AVAudioEngine + speech task get
        // re-attached. The check looks for a single combined recovery block
        // that both calls dispatchStartRecognition() and is gated by
        // shouldBeListening.
        val restartFromLifecycleIndex = source.indexOf("restartRecognitionFromLifecycle")
        val dispatchStartIndex = source.indexOf("dispatchStartRecognition()", restartFromLifecycleIndex)
        val shouldBeListeningGuardIndex = source.lastIndexOf("if (!shouldBeListening) return", restartFromLifecycleIndex)

        assertTrue(
            restartFromLifecycleIndex >= 0,
            "iOS safe-word listener must define a lifecycle-recovery entry point.",
        )
        assertTrue(
            dispatchStartIndex >= 0 && dispatchStartIndex > restartFromLifecycleIndex,
            "Lifecycle recovery must re-dispatch startRecognition() so the audio engine re-attaches.",
        )
        assertTrue(
            shouldBeListeningGuardIndex >= 0,
            "Lifecycle recovery must be gated by shouldBeListening so it is a no-op after stopListening().",
        )
    }

    @Test
    fun iosSafeWordListener_scopesLifecycleCancellationSuppressionToStaleRecognitionTask() {
        val source = safeWordListenerSource.readText()

        assertTrue(
            !source.contains("suppressRestartUntilMs"),
            "Lifecycle recovery must not use a global time window that can suppress callbacks from the fresh recognizer.",
        )

        val generationFieldIndex = source.indexOf("private var recognitionCallbackGeneration")
        val suppressionFieldIndex = source.indexOf("private var lifecycleRecoveryCancellationGeneration")
        assertTrue(
            generationFieldIndex >= 0,
            "iOS safe-word listener must track recognition callback generations.",
        )
        assertTrue(
            suppressionFieldIndex >= 0,
            "iOS safe-word listener must track only the lifecycle-cancelled stale generation.",
        )

        val startRecognitionIndex = source.indexOf("private fun startRecognition()")
        val callbackGenerationIndex = source.indexOf("val callbackGeneration = ++recognitionCallbackGeneration", startRecognitionIndex)
        val callbackHandlerPattern = Regex("""handleRecognitionResult\(\s*callbackGeneration\s*,""")
        assertTrue(
            callbackGenerationIndex >= 0,
            "startRecognition() must assign a generation to each recognition callback.",
        )
        assertTrue(
            callbackHandlerPattern.containsMatchIn(source.substring(callbackGenerationIndex)),
            "recognition callbacks must pass their captured generation into handleRecognitionResult().",
        )

        val handleResultIndex = source.indexOf("private fun handleRecognitionResult(")
        val scopedSuppressionIndex = source.indexOf("generation == lifecycleRecoveryCancellationGeneration", handleResultIndex)
        assertTrue(
            scopedSuppressionIndex >= 0,
            "handleRecognitionResult() must suppress only the lifecycle-cancelled stale generation.",
        )

        val lifecycleRecoveryIndex = source.indexOf("private fun restartRecognitionFromLifecycle")
        val setSuppressionIndex = source.indexOf(
            "lifecycleRecoveryCancellationGeneration = recognitionCallbackGeneration",
            lifecycleRecoveryIndex,
        )
        val tearDownIndex = source.indexOf("tearDown()", lifecycleRecoveryIndex)
        assertTrue(
            setSuppressionIndex >= 0 && setSuppressionIndex < tearDownIndex,
            "Lifecycle recovery must capture the stale generation before tearDown() cancels that task.",
        )
    }

    @Test
    fun iosSafeWordListener_ignoresRecognitionResultsAfterStopOrStaleGeneration() {
        val source = safeWordListenerSource.readText()
        val handleResultIndex = source.indexOf("private fun handleRecognitionResult(")
        val emitIndex = source.indexOf("_detectedWord.tryEmit", handleResultIndex)
        assertTrue(handleResultIndex >= 0, "handleRecognitionResult() must exist.")
        assertTrue(
            emitIndex > handleResultIndex,
            "handleRecognitionResult() must emit a detection for an accepted result.",
        )

        val beforeEmit = source.substring(handleResultIndex, emitIndex)
        assertTrue(
            beforeEmit.contains("!shouldBeListening"),
            "handleRecognitionResult() must return before emitting when listening has been stopped.",
        )
        assertTrue(
            beforeEmit.contains("generation == lifecycleRecoveryCancellationGeneration"),
            "handleRecognitionResult() must return before emitting a lifecycle-cancelled generation.",
        )
        assertTrue(
            beforeEmit.contains("generation != recognitionCallbackGeneration"),
            "handleRecognitionResult() must return before emitting when the callback generation is stale.",
        )
        val guardReturnIndex = beforeEmit.indexOf("return")
        val shouldBeListeningIndex = beforeEmit.indexOf("!shouldBeListening")
        assertTrue(
            guardReturnIndex > shouldBeListeningIndex,
            "The stopped/stale guard must return before any detection emit.",
        )
    }

    @Test
    fun iosSafeWordListener_installsAndRemovesLifecycleObservers() {
        val source = safeWordListenerSource.readText()

        // Both install and remove must exist, both must reference
        // NSNotificationCenter.addObserverForName / removeObserver, and the
        // remove path must be reachable from stopListening().
        assertTrue(
            source.contains("addObserverForName"),
            "iOS safe-word listener must use NSNotificationCenter.addObserverForName to install lifecycle observers.",
        )
        assertTrue(
            source.contains("installLifecycleObservers"),
            "iOS safe-word listener must wrap observer installation in a dedicated helper.",
        )
        assertTrue(
            source.contains("removeLifecycleObservers"),
            "iOS safe-word listener must wrap observer removal in a dedicated helper to avoid leaks.",
        )
        assertTrue(
            source.contains("removeObserver(observer)"),
            "iOS safe-word listener must call NSNotificationCenter.removeObserver(observer) on cleanup.",
        )

        val stopListeningIndex = source.indexOf("override fun stopListening()")
        val removeCallIndex = source.indexOf("removeLifecycleObservers()", stopListeningIndex)
        assertTrue(
            stopListeningIndex >= 0,
            "iOS safe-word listener must expose a stopListening() function.",
        )
        assertTrue(
            removeCallIndex >= 0 && removeCallIndex > stopListeningIndex,
            "stopListening() must call removeLifecycleObservers() to detach foreground / interruption observers.",
        )
    }

    // ---- F-039 / review R-7: the armed claim must be earned ----

    @Test
    fun iosSafeWordListener_armsOnlyAfterTheAudioEngineIsRunningAndBoundsRestarts() {
        val source = safeWordListenerSource.readText()

        assertTrue(
            !source.contains("_state.value = SafeWordState.Armed"),
            "Armed must come from armingTracker.onRecognizerReady(), so a restart loop that never " +
                "opens the microphone cannot claim the voice emergency stop is live.",
        )
        val engineStartedIndex = source.indexOf("if (!engineStarted)")
        val armedIndex = source.indexOf("armingTracker.onRecognizerReady()")
        assertTrue(engineStartedIndex >= 0, "iOS listener must check that the audio engine started.")
        assertTrue(
            armedIndex > engineStartedIndex,
            "The listener may only arm after the audio engine is actually running.",
        )
        assertTrue(
            source.contains("armingTracker.onStartAttempt()"),
            "Every start attempt must go through the arming budget so a permanently failing " +
                "recognizer is reported instead of restarting forever.",
        )
    }
}
