package com.devil.phoenixproject.presentation.manager

internal object ExerciseCountdownCuePolicy {
    fun shouldEmitTick(
        remainingSeconds: Int,
        isPaused: Boolean,
        lastTickedSecond: Int,
        beepsEnabled: Boolean,
        countdownBeepsEnabled: Boolean,
    ): Boolean = !isPaused &&
        remainingSeconds in 1..10 &&
        remainingSeconds != lastTickedSecond &&
        beepsEnabled &&
        countdownBeepsEnabled

    fun lastTickedSecondAfterRemainingChange(
        previousRemainingSeconds: Int,
        currentRemainingSeconds: Int,
        lastTickedSecond: Int,
    ): Int = if (currentRemainingSeconds > previousRemainingSeconds) {
        -1
    } else {
        lastTickedSecond
    }

    fun playbackRate(secondsRemaining: Int): Float = when (secondsRemaining) {
        in 1..2 -> 1.4f
        in 3..5 -> 1.2f
        in 6..10 -> 1.0f
        else -> 1.0f
    }
}
