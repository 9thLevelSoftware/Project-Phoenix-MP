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
}
