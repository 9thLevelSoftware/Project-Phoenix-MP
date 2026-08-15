package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.SetType
import kotlin.test.Test
import kotlin.test.assertEquals

class RestDeadlineCalculatorTest {
    @Test
    fun activeDeadlineUsesExactRemainingSeconds() {
        assertEquals(
            15,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = 25_000),
                nowEpochMs = 10_000,
            ),
        )
    }

    @Test
    fun activeDeadlineRoundsPartialDisplayedSecondUp() {
        assertEquals(
            2,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = 11_001),
                nowEpochMs = 10_000,
            ),
        )
    }

    @Test
    fun pausedRestUsesStoredRemainingSeconds() {
        assertEquals(
            17,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(
                    pausedRestRemainingSeconds = 17,
                    isRestPaused = true,
                ),
                nowEpochMs = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun backwardWallClockMovementClampsToOriginalDuration() {
        assertEquals(
            60,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = 120_000),
                nowEpochMs = 1_000,
            ),
        )
    }

    @Test
    fun forwardWallClockJumpReturnsZero() {
        assertEquals(
            0,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = 30_000),
                nowEpochMs = 3_600_000,
            ),
        )
    }

    @Test
    fun expiredDeadlineReturnsZero() {
        assertEquals(
            0,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = 10_000),
                nowEpochMs = 10_000,
            ),
        )
    }

    @Test
    fun absentDeadlineReturnsZero() {
        assertEquals(0, RestDeadlineCalculator.remainingSeconds(runtime(), nowEpochMs = 10_000))
    }

    @Test
    fun zeroDurationAlwaysReturnsZero() {
        assertEquals(
            0,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(originalRestDurationSeconds = 0, restDeadlineEpochMs = Long.MAX_VALUE),
                nowEpochMs = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun extremeEpochValuesClampWithoutOverflow() {
        assertEquals(
            60,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = Long.MAX_VALUE),
                nowEpochMs = Long.MIN_VALUE,
            ),
        )
        assertEquals(
            0,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = Long.MIN_VALUE),
                nowEpochMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(
            1,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = Long.MAX_VALUE),
                nowEpochMs = Long.MAX_VALUE - 500,
            ),
        )
        assertEquals(
            1,
            RestDeadlineCalculator.remainingSeconds(
                document = runtime(restDeadlineEpochMs = Long.MIN_VALUE + 500),
                nowEpochMs = Long.MIN_VALUE,
            ),
        )
    }

    private fun runtime(
        originalRestDurationSeconds: Int = 60,
        restDeadlineEpochMs: Long? = null,
        pausedRestRemainingSeconds: Int? = null,
        isRestPaused: Boolean = false,
    ) = ActiveWorkoutRuntimeDocument(
        profileId = "profile-a",
        routineId = "routine-a",
        routineSessionId = "routine-session-a",
        routineExerciseId = "routine-exercise-a",
        sourceExecutionId = "execution-a",
        sourceStableSessionId = "stable-session-a",
        sourceAttemptNumber = 1,
        logicalSetKey = LogicalSetKey("routine-session-a", "routine-exercise-a", 0, SetType.STANDARD),
        sourceExerciseIndex = 0,
        sourceSetIndex = 0,
        restDeadlineEpochMs = restDeadlineEpochMs,
        pausedRestRemainingSeconds = pausedRestRemainingSeconds,
        isRestPaused = isRestPaused,
        originalRestDurationSeconds = originalRestDurationSeconds,
    )
}
