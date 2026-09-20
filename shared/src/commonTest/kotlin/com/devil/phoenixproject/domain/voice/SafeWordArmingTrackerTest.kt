package com.devil.phoenixproject.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-039 / review R-7: a recognizer that accepts every start request but never
 * actually opens the microphone (another app holding it, the app backgrounded, a
 * call in progress) used to oscillate between "armed" and "restarting" forever,
 * so the HUD claimed the voice emergency stop was live while it was dead.
 */
class SafeWordArmingTrackerTest {
    @Test
    fun `a recognizer that starts but never reports ready never claims armed and ends unavailable`() {
        val tracker = SafeWordArmingTracker()

        // Ten restarts, none of which ever reaches onReadyForSpeech.
        val published = (1..10).map { tracker.onStartAttempt() }

        assertTrue(
            published.none { it is SafeWordState.Armed },
            "A start request that never became ready must never publish Armed: $published",
        )
        val firstFailure = published.indexOfFirst { it is SafeWordState.Unavailable }
        assertTrue(
            firstFailure in 0 until SafeWordArmingTracker.DEFAULT_MAX_ATTEMPTS_WITHOUT_READY + 1,
            "The restart loop must be reported within the bound, was attempt ${firstFailure + 1}",
        )
        assertEquals(
            SafeWordState.Unavailable(SafeWordUnavailableReason.START_FAILED),
            published.last(),
            "Once the budget is spent the listener must stay Unavailable",
        )
    }

    @Test
    fun `reaching ready arms and resets the budget so ordinary segment restarts never warn`() {
        val tracker = SafeWordArmingTracker()

        // Continuous listening restarts after every utterance; each of those does
        // reach onReadyForSpeech, so it must never trip the warning.
        repeat(20) {
            assertEquals(SafeWordState.Arming, tracker.onStartAttempt())
            assertEquals(SafeWordState.Armed, tracker.onRecognizerReady())
        }
    }

    @Test
    fun `a few failed starts before a successful one do not warn`() {
        val tracker = SafeWordArmingTracker(maxAttemptsWithoutReady = 4)

        repeat(4) { assertEquals(SafeWordState.Arming, tracker.onStartAttempt()) }
        assertEquals(SafeWordState.Armed, tracker.onRecognizerReady())
        assertEquals(SafeWordState.Arming, tracker.onStartAttempt())
    }
}
