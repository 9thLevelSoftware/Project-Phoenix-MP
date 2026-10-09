package com.devil.phoenixproject.domain.voice

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Final hypotheses arrive in onResults, which the platform delivers after
 * end-of-speech. Restarting from end-of-speech destroys
 * the recognizer and drops that callback, so a safe word present only in the final
 * transcript never fires.
 *
 * The listener needs a real SpeechRecognizer and main Looper, so it cannot be
 * constructed on the host JVM. This guards the callback order the same way
 * [AndroidSafeWordArmingContractTest] guards arming.
 */
class AndroidSafeWordFinalResultContractTest {
    private val source: String
        get() = assertNotNull(
            readProjectFile(
                "src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/AndroidSafeWordListener.kt",
            ),
            "AndroidSafeWordListener.kt must be readable for this contract test",
        ).let(::stripComments)

    @Test
    fun `end of speech waits for the final result instead of restarting`() {
        val endOfSpeech = functionBody(source, "override fun onEndOfSpeech()")

        assertTrue(
            "armEndOfSpeechResultFallback(generation)" in endOfSpeech,
            "End of speech must arm the fallback that waits for the final hypothesis.",
        )
        assertTrue(
            "isStale()" in endOfSpeech,
            "A replaced recognizer must not arm a fallback for the new session.",
        )
        assertTrue(
            !endOfSpeech.contains("scheduleRestart("),
            "End of speech must not restart. That destroys the recognizer and drops onResults.",
        )
        assertTrue(!endOfSpeech.contains("tearDown("))
        assertTrue(!endOfSpeech.contains(".destroy("))
        assertTrue(!endOfSpeech.contains(".cancel("))
    }

    @Test
    fun `final results are matched before the recognizer is restarted`() {
        val results = functionBody(source, "override fun onResults(")
        val processAt = results.indexOf("processResults(results)")
        val cancelAt = results.indexOf("cancelEndOfSpeechResultFallback()")
        val claimAt = results.indexOf("claimUtteranceRestart()")
        val restartAt = results.indexOf("scheduleRestart()")

        assertTrue(cancelAt >= 0, "onResults must cancel the end-of-speech fallback.")
        assertTrue(
            processAt > cancelAt,
            "The final transcript must be matched before the fallback can restart.",
        )
        assertTrue(
            claimAt > processAt && restartAt > claimAt,
            "onResults must claim the session restart only after the final transcript is matched.",
        )
        assertTrue(
            results.indexOf("isStale()") < processAt,
            "A stale recognizer must not match or restart after its session was replaced.",
        )
    }

    @Test
    fun `errors restart once and a missing callback cannot stick the listener`() {
        val error = functionBody(source, "override fun onError(")
        val cancelAt = error.indexOf("cancelEndOfSpeechResultFallback()")
        val permissionAt = error.indexOf("SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS")
        val failAt = error.indexOf("failAndStop(SafeWordUnavailableReason.PERMISSION)")
        val claimAt = error.indexOf("claimUtteranceRestart()")
        val restartAt = error.indexOf("scheduleRestart()")

        assertTrue(cancelAt >= 0 && cancelAt < permissionAt)
        assertTrue(
            permissionAt < failAt && failAt < claimAt && claimAt < restartAt,
            "A permission error must stop without restarting. Other errors restart once.",
        )

        val arm = functionBody(source, "private fun armEndOfSpeechResultFallback(")
        val runnableAt = arm.indexOf("Runnable {")
        val generationAt = arm.indexOf("generation != listeningGeneration", runnableAt)
        val claimInFallback = arm.indexOf("claimUtteranceRestart()", runnableAt)
        val restartInFallback = arm.indexOf("scheduleRestart()", runnableAt)
        assertTrue(runnableAt >= 0)
        assertTrue(
            generationAt > runnableAt && claimInFallback > generationAt && restartInFallback > claimInFallback,
            "The fallback must restart only when this session is still current and unclaimed.",
        )
        assertTrue(
            "postDelayed(fallback, END_OF_SPEECH_RESULT_GRACE_MS)" in arm,
            "The fallback must be a short delayed restart, not an immediate teardown.",
        )
        assertTrue(
            !arm.substring(0, runnableAt).contains("scheduleRestart("),
            "Arming the fallback must not restart before the grace period elapses.",
        )

        val grace = Regex("""const val END_OF_SPEECH_RESULT_GRACE_MS = (\d[\d_]*)L""").find(source)
        assertNotNull(grace, "The end-of-speech grace period must be a named timeout.")
        val graceMs = grace.groupValues[1].replace("_", "").toLong()
        assertTrue(
            graceMs in 1_000L..5_000L,
            "Grace period must stay long enough for a final hypothesis and short enough not to stick.",
        )
    }

    @Test
    fun `partial results do not restart and teardown disarms the fallback`() {
        val partial = functionBody(source, "override fun onPartialResults(")
        assertTrue("processResults(partialResults)" in partial)
        assertTrue(
            !partial.contains("scheduleRestart("),
            "Partial hypotheses must be able to match without ending the recognition session.",
        )
        assertTrue(!partial.contains("armEndOfSpeechResultFallback("))

        val tearDown = functionBody(source, "private fun tearDown()")
        val cancelAt = tearDown.indexOf("cancelEndOfSpeechResultFallback()")
        val destroyAt = tearDown.indexOf("destroy()")
        assertTrue(
            cancelAt >= 0 && destroyAt > cancelAt,
            "stopListening and restart both tear down, so teardown must disarm the fallback first.",
        )

        val start = functionBody(source, "private fun startRecognition()")
        val generationAt = start.indexOf("listeningGeneration++")
        val resetAt = start.indexOf("utteranceRestartClaimed = false")
        val listenerAt = start.indexOf("SafeWordRecognitionListener(listeningGeneration)")
        assertTrue(
            generationAt >= 0 && resetAt > generationAt && listenerAt > resetAt,
            "Each session must get a new generation and its own single restart.",
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing `$signature`")
        val open = source.indexOf('{', start)
        assertTrue(open > start, "Missing body for `$signature`")
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("Unclosed body for `$signature`")
    }

    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
}
