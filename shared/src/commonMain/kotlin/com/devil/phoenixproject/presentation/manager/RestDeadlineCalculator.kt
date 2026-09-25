package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument

object RestDeadlineCalculator {
    fun remainingSeconds(
        document: ActiveWorkoutRuntimeDocument,
        nowEpochMs: Long,
    ): Int {
        val originalSeconds = document.originalRestDurationSeconds
        require(originalSeconds >= 0)
        if (originalSeconds == 0) return 0

        if (document.isRestPaused) {
            return document.pausedRestRemainingSeconds.orZero().coerceIn(0, originalSeconds)
        }

        val deadline = document.restDeadlineEpochMs ?: return 0
        if (deadline <= nowEpochMs) return 0

        val remainingMilliseconds = if (
            nowEpochMs < 0 && deadline > Long.MAX_VALUE + nowEpochMs
        ) {
            Long.MAX_VALUE
        } else {
            deadline - nowEpochMs
        }
        val maximumMilliseconds = originalSeconds.toLong() * MILLIS_PER_SECOND
        if (remainingMilliseconds >= maximumMilliseconds) return originalSeconds

        return ((remainingMilliseconds + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
            .toInt()
            .coerceIn(0, originalSeconds)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private const val MILLIS_PER_SECOND = 1_000L
}
