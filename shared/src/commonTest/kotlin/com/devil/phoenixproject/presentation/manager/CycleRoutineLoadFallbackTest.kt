package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Regression tests for issue #620: "Start Workout" from a template-created cycle
 * silently did nothing.
 *
 * Root cause: TemplateConverter creates routines with a "cycle_routine_" ID prefix
 * (to hide them from the Daily Routines list), RoutineFlowManager filters those IDs
 * out of coordinator._routines, and ActiveSessionEngine.loadRoutineFromCycle[Async]
 * looked up routines ONLY in that filtered StateFlow — so template-cycle routines
 * could never load. The fix adds a direct DB fallback via
 * WorkoutRepository.getRoutineById().
 */
class CycleRoutineLoadFallbackTest {

    @Test
    fun templateCycleRoutineLoadsViaDbFallback() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // A template-created cycle routine: present in the DB, but its "cycle_routine_"
        // prefix keeps it out of the coordinator's routines StateFlow.
        val cycleRoutine = WorkoutStateFixtures.createTestRoutine()
            .copy(id = "cycle_routine_test-620", name = "Full Body A")
        cycleRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeWorkoutRepo.saveRoutine(cycleRoutine)
        advanceUntilIdle()

        // Precondition: the filter really does exclude it from the StateFlow —
        // this is the exact condition that made #620 a silent no-op.
        assertFalse(
            harness.coordinator.routines.value.any { it.id == cycleRoutine.id },
            "cycle_routine_-prefixed routine must be filtered out of the routines StateFlow",
        )

        // The fixed lookup must fall back to the DB and succeed.
        val loaded = harness.activeSessionEngine.loadRoutineFromCycleAsync(
            routineId = cycleRoutine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        assertTrue(loaded, "loadRoutineFromCycleAsync must load cycle_routine_ routines via DB fallback")
        assertEquals(
            cycleRoutine.id,
            harness.coordinator.loadedRoutine.value?.id,
            "Loaded routine should be the template cycle routine",
        )

        harness.cleanup()
    }

    @Test
    fun regularRoutineStillLoadsFromStateFlow() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        val routine = WorkoutStateFixtures.createTestRoutine()
            .copy(id = "manual-routine-1", name = "My Routine")
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeWorkoutRepo.saveRoutine(routine)
        advanceUntilIdle()

        assertTrue(
            harness.coordinator.routines.value.any { it.id == routine.id },
            "Non-prefixed routine should be present in the routines StateFlow",
        )

        val loaded = harness.activeSessionEngine.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 2,
        )
        advanceUntilIdle()

        assertTrue(loaded, "Regression: StateFlow-resident routines must still load")
        assertEquals(routine.id, harness.coordinator.loadedRoutine.value?.id)

        harness.cleanup()
    }

    @Test
    fun missingRoutineReturnsFalse() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        val loaded = harness.activeSessionEngine.loadRoutineFromCycleAsync(
            routineId = "cycle_routine_does-not-exist",
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        assertFalse(loaded, "Unknown routine ID must return false (surfaced as an error in the UI)")

        harness.cleanup()
    }
}
