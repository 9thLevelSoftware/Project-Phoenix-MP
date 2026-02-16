package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.assertEquals

object WorkoutStateFixtures {

    /** Put DWSM into Active workout state (post-countdown) */
    suspend fun TestScope.activeDWSM(): DWSMTestHarness {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(
            WorkoutState.Active, harness.dwsm.coordinator.workoutState.value,
            "activeDWSM fixture: expected Active state"
        )
        return harness
    }

    /** Put DWSM into routine SetReady state */
    suspend fun TestScope.setReadyDWSM(routine: Routine? = null): DWSMTestHarness {
        val harness = DWSMTestHarness(this)
        val r = routine ?: createTestRoutine()
        r.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(r)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        return harness
    }

    fun createTestRoutine(
        exerciseCount: Int = 3,
        setsPerExercise: Int = 3,
        weightKg: Float = 25f,
        repsPerSet: Int = 10
    ): Routine {
        val exercises = (0 until exerciseCount).map { i ->
            RoutineExercise(
                id = "re-$i",
                exercise = TestFixtures.allExercises[i % TestFixtures.allExercises.size],
                orderIndex = i,
                setReps = List(setsPerExercise) { repsPerSet },
                weightPerCableKg = weightKg
            )
        }
        return Routine(
            id = "test-routine",
            name = "Test Routine",
            exercises = exercises
        )
    }

    fun createSupersetRoutine(): Routine {
        val supersetId = "ss-1"
        val exercises = listOf(
            RoutineExercise(
                id = "re-0", exercise = TestFixtures.benchPress,
                orderIndex = 0, setReps = listOf(10, 10, 10),
                weightPerCableKg = 25f,
                supersetId = supersetId, orderInSuperset = 0
            ),
            RoutineExercise(
                id = "re-1", exercise = TestFixtures.bicepCurl,
                orderIndex = 1, setReps = listOf(12, 12, 12),
                weightPerCableKg = 15f,
                supersetId = supersetId, orderInSuperset = 1
            ),
            RoutineExercise(
                id = "re-2", exercise = TestFixtures.squat,
                orderIndex = 2, setReps = listOf(8, 8, 8),
                weightPerCableKg = 40f
            )
        )
        return Routine(
            id = "test-superset-routine",
            name = "Superset Routine",
            exercises = exercises,
            supersets = listOf(
                Superset(
                    id = supersetId, routineId = "test-superset-routine",
                    name = "Chest/Arms", restBetweenSeconds = 10, orderIndex = 0
                )
            )
        )
    }
}
