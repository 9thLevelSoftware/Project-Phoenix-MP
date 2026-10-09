package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * Timed cable sets must be estimated from [RoutineExercise.executionTimedDurationSeconds],
 * the same per-set duration the workout timer runs. Rep-count fallbacks stay for sets
 * execution does not treat as timed.
 */
class RoutineTimeEstimatorTimedDurationTest {

    private val profileId = "test-profile"

    @Test
    fun timedCableSetsUseExecutionDurationInsteadOfRepFallback() = runTest {
        val result = estimator().estimateRoutineDuration(
            routine(
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(60, 60),
                durationSeconds = 20,
            ),
            profileId,
        )

        // 3 sets * 20s configured duration + 2 * 60s rest. Not 3 * 45s fallback.
        val expected = 20 * 3 + 60 * 2
        assertFalse(result.hasRange)
        assertEquals(expected, result.totalSeconds)
        assertEquals(expected, result.lowerBoundSeconds)
        assertEquals(expected, result.upperBoundSeconds)
        assertFalse(result.isHistoryBased)
    }

    @Test
    fun launchAdjustedDurationBelowEditorMinimumIsUsed() = runTest {
        val result = estimator().estimateRoutineDuration(
            routine(
                setReps = listOf(8),
                restSeconds = emptyList(),
                durationSeconds = 4,
                isLaunchAdjustedDuration = true,
            ),
            profileId,
        )

        assertEquals(4, result.totalSeconds)
        assertFalse(result.hasRange)
    }

    @Test
    fun executionDurationBoundsAreAccepted() = runTest {
        val minimum = estimator().estimateRoutineDuration(
            routine(setReps = listOf(10), restSeconds = emptyList(), durationSeconds = 10),
            profileId,
        )
        val maximum = estimator().estimateRoutineDuration(
            routine(
                setReps = listOf(10, 10),
                restSeconds = listOf(0),
                durationSeconds = 300,
            ),
            profileId,
        )

        assertEquals(10, minimum.totalSeconds)
        assertEquals(300 * 2, maximum.totalSeconds)
    }

    @Test
    fun durationExecutionRejectsKeepsCableFallback() = runTest {
        val rejected = listOf(0, 9, 301)
        rejected.forEach { seconds ->
            val result = estimator().estimateRoutineDuration(
                routine(setReps = listOf(10), restSeconds = emptyList(), durationSeconds = seconds),
                profileId,
            )
            assertEquals(
                RoutineTimeEstimator.CABLE_SET_FALLBACK_SEC,
                result.totalSeconds,
                "duration $seconds is not a timed cable set",
            )
        }

        val launchAboveMax = estimator().estimateRoutineDuration(
            routine(
                setReps = listOf(10),
                restSeconds = emptyList(),
                durationSeconds = 301,
                isLaunchAdjustedDuration = true,
            ),
            profileId,
        )
        assertEquals(RoutineTimeEstimator.CABLE_SET_FALLBACK_SEC, launchAboveMax.totalSeconds)
    }

    @Test
    fun configuredTimedDurationOverridesHistoricalAverage() = runTest {
        val result = estimator(sessionCount = 5, averageSetDurationMs = 20_000L).estimateRoutineDuration(
            routine(setReps = listOf(10), restSeconds = emptyList(), durationSeconds = 75),
            profileId,
        )

        assertEquals(75, result.totalSeconds)
        assertFalse(result.hasRange)
    }

    @Test
    fun rejectedDurationStillUsesHistoricalAverage() = runTest {
        val result = estimator(sessionCount = 5, averageSetDurationMs = 20_000L).estimateRoutineDuration(
            routine(setReps = listOf(10), restSeconds = emptyList(), durationSeconds = 9),
            profileId,
        )

        assertEquals(20, result.totalSeconds)
        assertEquals(true, result.isHistoryBased)
    }

    @Test
    fun timedCableSupersetUsesDurationForEachWorkingSet() = runTest {
        val supersetId = "ss-timed"
        val timed = cableExercise(
            exerciseId = "ex-timed",
            setReps = listOf(10, 10),
            restSeconds = listOf(60),
            durationSeconds = 20,
            orderIndex = 0,
            supersetId = supersetId,
        )
        val reps = cableExercise(
            exerciseId = "ex-reps",
            setReps = listOf(10, 10),
            restSeconds = listOf(60),
            durationSeconds = null,
            orderIndex = 1,
            supersetId = supersetId,
            orderInSuperset = 1,
        )
        val result = estimator().estimateRoutineDuration(
            Routine(
                id = "routine-superset",
                name = "Superset",
                profileId = profileId,
                exercises = listOf(timed, reps),
                supersets = listOf(
                    Superset(
                        id = supersetId,
                        routineId = "routine-superset",
                        name = "Timed + reps",
                        restBetweenSeconds = 10,
                        orderIndex = 0,
                    ),
                ),
            ),
            profileId,
        )

        // Work: timed 2 * 20s + rep fallback 2 * 45s.
        // Intra-superset rest: 10s * 1 gap * 2 rounds. Between-round rest: 60s.
        val expected = (2 * 20) + (2 * RoutineTimeEstimator.CABLE_SET_FALLBACK_SEC) + (10 * 2) + 60
        assertEquals(expected, result.totalSeconds)
        assertFalse(result.hasRange)
    }

    private fun estimator(
        sessionCount: Long = 0,
        averageSetDurationMs: Long? = null,
    ): RoutineTimeEstimator {
        val repository: WorkoutRepository = if (sessionCount == 0L && averageSetDurationMs == null) {
            FakeWorkoutRepository()
        } else {
            FixedHistoryRepository(sessionCount, averageSetDurationMs)
        }
        return RoutineTimeEstimator(repository)
    }

    private fun routine(
        setReps: List<Int?>,
        restSeconds: List<Int>,
        durationSeconds: Int?,
        isLaunchAdjustedDuration: Boolean = false,
    ) = Routine(
        id = "routine-timed",
        name = "Timed cable",
        profileId = profileId,
        exercises = listOf(
            cableExercise(
                setReps = setReps,
                restSeconds = restSeconds,
                durationSeconds = durationSeconds,
                isLaunchAdjustedDuration = isLaunchAdjustedDuration,
            ),
        ),
    )

    private fun cableExercise(
        exerciseId: String = "ex-timed",
        setReps: List<Int?>,
        restSeconds: List<Int>,
        durationSeconds: Int?,
        isLaunchAdjustedDuration: Boolean = false,
        orderIndex: Int = 0,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
    ) = RoutineExercise(
        id = "re-$exerciseId",
        exercise = Exercise(
            id = exerciseId,
            name = "Cable Row",
            muscleGroup = "Back",
            equipment = "BAR",
        ),
        orderIndex = orderIndex,
        setReps = setReps,
        weightPerCableKg = 40f,
        setRestSeconds = restSeconds,
        duration = durationSeconds,
        isLaunchAdjustedDuration = isLaunchAdjustedDuration,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )
}

/**
 * History answers for the two estimator queries. Every other repository call
 * stays on the in-memory fake.
 */
private class FixedHistoryRepository(
    private val sessionCount: Long,
    private val averageSetDurationMs: Long?,
    private val delegate: WorkoutRepository = FakeWorkoutRepository(),
) : WorkoutRepository by delegate {
    override suspend fun getSessionCountForExercise(exerciseId: String, profileId: String): Long = sessionCount

    override suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long? = averageSetDurationMs
}
