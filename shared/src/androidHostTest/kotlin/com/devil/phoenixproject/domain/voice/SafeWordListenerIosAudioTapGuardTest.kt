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
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.ios.kt",
        )

    private val optionalPermissionsSource: File
        get() = File(
            projectRoot,
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/OptionalPermissionsHandler.ios.kt",
        )

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

    @Test
    fun iosOptionalPermissionOnboardingRequestsMicrophonePermission() {
        val source = optionalPermissionsSource.readText()

        assertTrue(source.contains("requestRecordPermission"))
        assertTrue(source.contains("Microphone and Speech Recognition"))
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

        val stopListeningIndex = source.indexOf("actual fun stopListening()")
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
}
