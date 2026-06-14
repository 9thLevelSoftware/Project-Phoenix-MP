package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExerciseCountdownCuePolicyTest {
    @Test
    fun `should emit final countdown seconds when enabled and running`() {
        assertTrue(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 10,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertTrue(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 5,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertTrue(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 1,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
    }

    @Test
    fun `should suppress ticks outside final countdown range`() {
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 11,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 0,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
    }

    @Test
    fun `should suppress ticks while paused or disabled`() {
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 5,
                isPaused = true,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 5,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = false,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 5,
                isPaused = false,
                lastTickedSecond = -1,
                beepsEnabled = true,
                countdownBeepsEnabled = false,
            ),
        )
    }

    @Test
    fun `should suppress duplicate ticks for the same second`() {
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 5,
                isPaused = false,
                lastTickedSecond = 5,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
    }

    @Test
    fun `timer reset clears duplicate protection when remaining seconds increase`() {
        val resetLastTickedSecond = ExerciseCountdownCuePolicy.lastTickedSecondAfterRemainingChange(
            previousRemainingSeconds = 8,
            currentRemainingSeconds = 29,
            lastTickedSecond = 8,
        )

        assertEquals(-1, resetLastTickedSecond)
        assertTrue(
            ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = 8,
                isPaused = false,
                lastTickedSecond = resetLastTickedSecond,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
    }

    @Test
    fun `normal countdown preserves duplicate protection`() {
        assertEquals(
            8,
            ExerciseCountdownCuePolicy.lastTickedSecondAfterRemainingChange(
                previousRemainingSeconds = 8,
                currentRemainingSeconds = 7,
                lastTickedSecond = 8,
            ),
        )
    }

    @Test
    fun `rest ending cue aligns with restover audio duration`() {
        assertEquals(4, ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS)
    }

    @Test
    fun `rest ending cue emits only at configured second`() {
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = 5,
                isPaused = false,
                restEndingEmitted = false,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertTrue(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = 4,
                isPaused = false,
                restEndingEmitted = false,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = 3,
                isPaused = false,
                restEndingEmitted = false,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
    }

    @Test
    fun `rest ending cue respects pause duplicate and beep gates`() {
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS,
                isPaused = true,
                restEndingEmitted = false,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS,
                isPaused = false,
                restEndingEmitted = true,
                beepsEnabled = true,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS,
                isPaused = false,
                restEndingEmitted = false,
                beepsEnabled = false,
                countdownBeepsEnabled = true,
            ),
        )
        assertFalse(
            ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                remainingSeconds = ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS,
                isPaused = false,
                restEndingEmitted = false,
                beepsEnabled = true,
                countdownBeepsEnabled = false,
            ),
        )
    }

    @Test
    fun `playback rate increases near zero`() {
        for (second in 10 downTo 6) {
            assertEquals(1.0f, ExerciseCountdownCuePolicy.playbackRate(second))
        }
        for (second in 5 downTo 3) {
            assertEquals(1.2f, ExerciseCountdownCuePolicy.playbackRate(second))
        }
        for (second in 2 downTo 1) {
            assertEquals(1.4f, ExerciseCountdownCuePolicy.playbackRate(second))
        }
        assertEquals(1.0f, ExerciseCountdownCuePolicy.playbackRate(11))
        assertEquals(1.0f, ExerciseCountdownCuePolicy.playbackRate(0))
    }
}
