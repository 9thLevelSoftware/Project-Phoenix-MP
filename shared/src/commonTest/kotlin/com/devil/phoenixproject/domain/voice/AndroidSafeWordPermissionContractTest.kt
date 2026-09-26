package com.devil.phoenixproject.domain.voice

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android safe word must ask for RECORD_AUDIO before SpeechRecognizer starts,
 * and only when it is not already granted. The listener cannot be constructed
 * on the host JVM (no SpeechRecognizer / main Looper), so this guards the
 * wiring the same way [AndroidSafeWordArmingContractTest] does.
 */
class AndroidSafeWordPermissionContractTest {
    private fun source(relativePath: String): String = assertNotNull(
        readProjectFile(relativePath),
        "$relativePath must be readable for this contract test",
    )

    @Test
    fun `startListening requests RECORD_AUDIO before the recognizer and skips the prompt when granted`() {
        val listener = source(
            "src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/AndroidSafeWordListener.kt",
        )
        val gate = source(
            "src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/RecordAudioPermissionRequest.kt",
        )

        val startListeningIndex = listener.indexOf("override fun startListening()")
        val startRecognitionIndex = listener.indexOf("private fun startRecognition()")
        val recognizerStartIndex = listener.indexOf("sr.startListening(intent)")
        assertTrue(startListeningIndex >= 0)
        assertTrue(startRecognitionIndex > startListeningIndex)
        assertTrue(recognizerStartIndex > startRecognitionIndex)

        val startBody = listener.substring(startListeningIndex, startRecognitionIndex)
        assertTrue(
            "recordAudioPermission.request(" in startBody,
            "startListening() must ask for RECORD_AUDIO before recognition starts.",
        )
        assertTrue(
            "RecordAudioPermissionStatus.Granted -> beginListening()" in startBody,
            "An already-granted microphone must begin listening without another prompt.",
        )
        assertTrue(
            "SafeWordUnavailableReason.PERMISSION" in startBody,
            "A denial must report PERMISSION so calibration can offer Settings.",
        )

        val restartBody = listener.substring(startRecognitionIndex)
        assertFalse(
            "recordAudioPermission.request(" in restartBody,
            "Recognition restarts must not request RECORD_AUDIO again.",
        )
        assertTrue(
            "SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS" in listener,
            "A permission revoked after listening started must still fail closed.",
        )

        assertTrue(
            "Manifest.permission.RECORD_AUDIO" in gate,
            "The gate must request RECORD_AUDIO.",
        )
        assertTrue(
            "checkSelfPermission" in gate,
            "The gate must read the current grant before prompting.",
        )
        assertTrue(
            "if (isGranted()) return RecordAudioPermissionStatus.Granted" in gate,
            "Already-granted sessions must not launch the system permission dialog.",
        )
        val checkIndex = gate.indexOf("checkSelfPermission")
        val launchIndex = gate.indexOf("launcher.launch(Manifest.permission.RECORD_AUDIO)")
        assertTrue(checkIndex >= 0 && launchIndex > checkIndex)
    }

    @Test
    fun `manifest keeps RECORD_AUDIO and marks the microphone optional`() {
        val manifest = source("androidApp/src/main/AndroidManifest.xml")
        assertTrue(
            manifest.contains("""<uses-permission android:name="android.permission.RECORD_AUDIO" />"""),
            "RECORD_AUDIO must stay declared so safe word can request the microphone.",
        )
        val microphone = Regex(
            """<uses-feature\s+android:name="android\.hardware\.microphone"\s+android:required="false"\s*/>""",
        )
        assertTrue(
            microphone.containsMatchIn(manifest),
            "Microphone uses-feature must set android:required=\"false\" so Play does not require a mic.",
        )
    }

    @Test
    fun `calibration keeps Open Settings and does not treat the permission prompt as a mic failure`() {
        val dialogs = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt",
        )
        assertTrue(dialogs.contains("openAppSettings()"))
        assertTrue(dialogs.contains("settings_calibration_open_settings"))
        assertTrue(
            "is SafeWordState.Unavailable -> micError = true" in dialogs,
            "A permanent denial must still surface the calibration Settings path.",
        )
        assertTrue(
            "SafeWordState.Disabled -> Unit" in dialogs,
            "The in-flight permission dialog leaves the listener Disabled and must not open Settings yet.",
        )
    }

    @Test
    fun `speech recognition is only created from the Android safe-word listener`() {
        val listener = source(
            "src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/AndroidSafeWordListener.kt",
        )
        assertTrue(listener.contains("SpeechRecognizer.createSpeechRecognizer"))
        // Other production entry points go through SafeWordListener.startListening().
        val manager = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt",
        )
        val dialogs = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSafetyDialogs.kt",
        )
        assertTrue(manager.contains("newListener.startListening()"))
        assertFalse(manager.contains("SpeechRecognizer"))
        assertTrue(dialogs.contains("newListener?.startListening()"))
        assertFalse(dialogs.contains("SpeechRecognizer"))
    }
}
