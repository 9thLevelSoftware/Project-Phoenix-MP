package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The last-set AMRAP flag (`RoutineExercise.isAMRAP` with numeric reps still stored)
 * must use the same AMRAP duration range as a null-reps set. Execution already does
 * this via [RoutineExercise.isAmrapSet]; the estimator must follow that rule.
 */
class RoutineTimeEstimatorAmrapFlagTest {

    private val profileId = "test-profile"
    private val estimator = RoutineTimeEstimator(FakeWorkoutRepository())

    @Test
    fun lastSetIsAMRAPFlagUsesAmrapDurationRange() = runTest {
        val flagged = estimator.estimateRoutineDuration(
            routine(setReps = listOf(10, 10, 10), isAMRAP = true),
            profileId,
        )
        val fixed = estimator.estimateRoutineDuration(
            routine(setReps = listOf(10, 10, 10), isAMRAP = false),
            profileId,
        )

        val fixedSetSec = RoutineTimeEstimator.CABLE_SET_FALLBACK_SEC
        val amrapMidSec = RoutineTimeEstimator.AMRAP_FALLBACK_SEC
        val amrapLowerSec = (amrapMidSec * 1.0 / RoutineTimeEstimator.AMRAP_DURATION_MULTIPLIER).toInt()
        val amrapUpperSec = (amrapMidSec * 2.0 / RoutineTimeEstimator.AMRAP_DURATION_MULTIPLIER).toInt()
        val restSec = 120
        val fixedWorkSec = fixedSetSec * 2

        assertTrue(flagged.hasRange)
        assertEquals(fixedWorkSec + amrapMidSec + restSec, flagged.totalSeconds)
        assertEquals(fixedWorkSec + amrapLowerSec + restSec, flagged.lowerBoundSeconds)
        assertEquals(fixedWorkSec + amrapUpperSec + restSec, flagged.upperBoundSeconds)
        assertTrue(flagged.lowerBoundSeconds < flagged.totalSeconds)
        assertTrue(flagged.upperBoundSeconds > flagged.totalSeconds)

        // Same reps without the flag stay a point estimate at the cable fallback.
        assertFalse(fixed.hasRange)
        assertEquals(fixedSetSec * 3 + restSec, fixed.totalSeconds)
        assertEquals(fixed.totalSeconds, fixed.lowerBoundSeconds)
        assertEquals(fixed.totalSeconds, fixed.upperBoundSeconds)
    }

    @Test
    fun singleSetIsAMRAPFlagUsesAmrapFallbackRange() = runTest {
        val result = estimator.estimateRoutineDuration(
            routine(setReps = listOf(10), isAMRAP = true, restSeconds = emptyList()),
            profileId,
        )

        val amrapMidSec = RoutineTimeEstimator.AMRAP_FALLBACK_SEC
        val amrapLowerSec = (amrapMidSec * 1.0 / RoutineTimeEstimator.AMRAP_DURATION_MULTIPLIER).toInt()
        val amrapUpperSec = (amrapMidSec * 2.0 / RoutineTimeEstimator.AMRAP_DURATION_MULTIPLIER).toInt()

        assertTrue(result.hasRange)
        assertEquals(amrapMidSec, result.totalSeconds)
        assertEquals(amrapLowerSec, result.lowerBoundSeconds)
        assertEquals(amrapUpperSec, result.upperBoundSeconds)
    }

    private fun routine(
        setReps: List<Int?>,
        isAMRAP: Boolean,
        restSeconds: List<Int> = listOf(60, 60),
    ) = Routine(
        id = "routine-amrap-flag",
        name = "AMRAP flag",
        profileId = profileId,
        exercises = listOf(
            RoutineExercise(
                id = "re-amrap-flag",
                exercise = Exercise(
                    id = "ex-amrap-flag",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                ),
                orderIndex = 0,
                setReps = setReps,
                weightPerCableKg = 50f,
                setRestSeconds = restSeconds,
                isAMRAP = isAMRAP,
            ),
        ),
    )
}
