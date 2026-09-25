package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.data.repository.toSnapshotName
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
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
        sourceExecutionId = "42",
        sourceStableSessionId = "stable-session-a",
        sourceAttemptNumber = 1,
        logicalSetKey = LogicalSetKey("routine-session-a", "routine-exercise-a", 0, SetType.STANDARD),
        sourceExerciseIndex = 0,
        sourceSetIndex = 0,
        sourceAuthority = RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = "stable-session-a",
            sourceExecutionId = "42",
            profileId = "profile-a",
            routineIdentity = RoutineExecutionIdentity(
                profileId = "profile-a",
                routineId = "routine-a",
                routineSessionId = "routine-session-a",
                routineExerciseId = "routine-exercise-a",
                logicalSetKey = LogicalSetKey("routine-session-a", "routine-exercise-a", 0, SetType.STANDARD),
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = SetType.STANDARD.name,
            programModeName = ProgramMode.OldSchool.toSnapshotName(),
            programmedBaseWeightPerCableKg = 25f,
            configuredStartWeightPerCableKg = 25f,
            progressionKg = 0f,
            actualReps = 6,
            targetReps = 10,
            isWarmup = false,
            isEcho = false,
            isJustLift = false,
            isBodyweight = false,
            isTimed = false,
            isAmrap = false,
            isCableExercise = true,
            physicalCableCount = 2,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(
                WorkoutParameters(ProgramMode.OldSchool, reps = 10, weightPerCableKg = 25f),
            ),
        ),
        teardownSeed = RestoredTeardownSeedSnapshot(
            sourceExecutionId = 42L,
            sourceStableSessionId = "stable-session-a",
            profileId = "profile-a",
            requiresMachine = true,
        ),
        restDeadlineEpochMs = restDeadlineEpochMs,
        pausedRestRemainingSeconds = pausedRestRemainingSeconds,
        isRestPaused = isRestPaused,
        originalRestDurationSeconds = originalRestDurationSeconds,
    )
}
