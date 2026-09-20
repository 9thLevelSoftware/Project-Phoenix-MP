package com.devil.phoenixproject.domain.voice

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * The Android listener needs a real `SpeechRecognizer`, a main `Looper` and an
 * `AudioManager`, so it cannot be instantiated on the JVM host. This guards the
 * one wiring rule that carries the safety claim (F-039 / review R-7): the HUD may
 * only say the voice emergency stop is armed once the recognizer reports it is
 * recording, and a restart loop that never gets there must be bounded.
 *
 * The behaviour behind those calls is covered by SafeWordArmingTrackerTest.
 */
class AndroidSafeWordArmingContractTest {
    private val source: String
        get() = assertNotNull(
            readProjectFile(
                "src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/AndroidSafeWordListener.kt",
            ),
            "AndroidSafeWordListener.kt must be readable for this contract test",
        )

    @Test
    fun `armed is never published from the start path`() {
        val text = source

        assertFalse(
            Regex("""_state\.value\s*=\s*SafeWordState\.Armed""").containsMatchIn(text),
            "Armed must come from armingTracker.onRecognizerReady(), never be assigned after " +
                "startListening() returns — that only means the intent was accepted, not that " +
                "the recognizer has the microphone.",
        )
        val startListeningIndex = text.indexOf("sr.startListening(intent)")
        assertTrue(startListeningIndex >= 0, "The listener must still start the recognizer.")
        assertTrue(
            text.indexOf("_state.value = attemptState", startListeningIndex) >= 0,
            "Starting the recognizer must publish the arming state, not an armed state.",
        )
    }

    @Test
    fun `armed is published from onReadyForSpeech`() {
        val text = source
        val readyIndex = text.indexOf("override fun onReadyForSpeech(")
        assertTrue(readyIndex >= 0, "The recognition listener must implement onReadyForSpeech.")

        val readyBody = text.substring(readyIndex, minOf(readyIndex + 600, text.length))
        assertTrue(
            "armingTracker.onRecognizerReady()" in readyBody,
            "onReadyForSpeech is the only point where the recognizer holds the microphone, so it " +
                "must be what arms the HUD.",
        )
    }

    @Test
    fun `a restart loop that never becomes ready is reported instead of oscillating`() {
        val text = source
        val startRecognitionIndex = text.indexOf("private fun startRecognition()")
        assertTrue(startRecognitionIndex >= 0)

        val attemptIndex = text.indexOf("armingTracker.onStartAttempt()", startRecognitionIndex)
        assertTrue(
            attemptIndex >= 0,
            "Every start attempt must go through the arming budget.",
        )
        assertTrue(
            text.indexOf("failAndStop(attemptState.reason)", attemptIndex) >= 0,
            "An exhausted arming budget must stop and report, not restart forever.",
        )
    }
}
