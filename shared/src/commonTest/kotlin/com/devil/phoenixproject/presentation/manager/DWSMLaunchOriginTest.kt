package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the RoutineLaunchOrigin lifecycle (task 4B.2).
 *
 * Verifies:
 * (a) Cycle launch (loadRoutineFromCycleAsync) sets TRAINING_CYCLES origin.
 * (b) Origin SURVIVES routine completion — updateCycleProgressIfNeeded clears activeCycleId
 *     but must not clear routineLaunchOrigin.
 * (c) exitRoutineFlow() clears origin to null.
 * (d) Destination route mapping: TRAINING_CYCLES → training_cycles route,
 *     DAILY_ROUTINES and null → daily_routines route.
 * (e) Normal loadRoutine() sets DAILY_ROUTINES origin.
 * (f) Cycle load after daily load overwrites origin to TRAINING_CYCLES.
 *
 * Each test calls harness.cleanup() to prevent UncompletedCoroutinesError from DWSM's
 * long-running init collectors (see DWSMTestHarness KDoc).
 *
 * IMPORTANT: advanceUntilIdle() MUST be called after DWSMTestHarness construction and
 * before loadRoutine* calls to let the DWSM init block settle. See DWSMRoutineFlowTest KDoc.
 */
class DWSMLaunchOriginTest {

    /**
     * Adds [routine] to both the fake exercise repo (for weight-resolution) and the fake
     * workout repo (so coordinator._routines is populated for loadRoutineFromCycleAsync).
     */
    private fun DWSMTestHarness.seedRoutine(routine: com.devil.phoenixproject.domain.model.Routine) {
        routine.exercises.forEach { fakeExerciseRepo.addExercise(it.exercise) }
        fakeWorkoutRepo.addRoutine(routine)
    }

    // ===== (a) Cycle launch sets TRAINING_CYCLES =====

    @Test
    fun loadRoutineFromCycleAsync_sets_TRAINING_CYCLES_origin() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle() // Let init block + routines collector settle

        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        assertEquals(
            RoutineLaunchOrigin.TRAINING_CYCLES,
            harness.coordinator.routineLaunchOrigin,
            "Cycle-launched routine must set origin to TRAINING_CYCLES",
        )
        harness.cleanup()
    }

    // ===== (b) Origin survives updateCycleProgressIfNeeded (activeCycleId clear) =====

    @Test
    fun origin_survives_activeCycleId_being_cleared() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        // Confirm origin is set before simulating cycle-completion cleanup.
        assertEquals(RoutineLaunchOrigin.TRAINING_CYCLES, harness.coordinator.routineLaunchOrigin)

        // Simulate what updateCycleProgressIfNeeded() does: clears activeCycleId and
        // activeCycleDayNumber but must NOT touch routineLaunchOrigin.
        harness.coordinator.activeCycleId = null
        harness.coordinator.activeCycleDayNumber = null

        assertEquals(
            RoutineLaunchOrigin.TRAINING_CYCLES,
            harness.coordinator.routineLaunchOrigin,
            "routineLaunchOrigin must survive activeCycleId being nulled out (updateCycleProgressIfNeeded invariant)",
        )
        harness.cleanup()
    }

    // ===== (c) exitRoutineFlow clears origin =====

    @Test
    fun exitRoutineFlow_clears_origin_to_null() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        assertNotNull(
            harness.coordinator.routineLaunchOrigin,
            "Origin must be set before exit",
        )

        harness.dwsm.exitRoutineFlow()

        assertNull(
            harness.coordinator.routineLaunchOrigin,
            "exitRoutineFlow() must clear routineLaunchOrigin to null",
        )
        harness.cleanup()
    }

    // ===== (d) Destination route mapping =====

    @Test
    fun destination_mapping_TRAINING_CYCLES_returns_trainingCycles_route() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        // Apply the same logic as MainViewModel.routineExitDestination()
        val actualRoute = if (harness.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
            NavigationRoutes.TrainingCycles.route
        } else {
            NavigationRoutes.DailyRoutines.route
        }
        assertEquals(
            NavigationRoutes.TrainingCycles.route,
            actualRoute,
            "TRAINING_CYCLES origin must map to TrainingCycles route",
        )
        harness.cleanup()
    }

    @Test
    fun destination_mapping_DAILY_ROUTINES_returns_dailyRoutines_route() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        // Normal daily-routines load path
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Apply the same logic as MainViewModel.routineExitDestination()
        val actualRoute = if (harness.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
            NavigationRoutes.TrainingCycles.route
        } else {
            NavigationRoutes.DailyRoutines.route
        }
        assertEquals(
            NavigationRoutes.DailyRoutines.route,
            actualRoute,
            "DAILY_ROUTINES origin must map to DailyRoutines route",
        )
        harness.cleanup()
    }

    @Test
    fun destination_mapping_null_origin_defaults_to_dailyRoutines_route() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // No routine loaded; origin remains null
        assertNull(harness.coordinator.routineLaunchOrigin, "Origin must be null before any load")

        // Apply the same logic as MainViewModel.routineExitDestination()
        val actualRoute = if (harness.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
            NavigationRoutes.TrainingCycles.route
        } else {
            NavigationRoutes.DailyRoutines.route
        }
        assertEquals(
            NavigationRoutes.DailyRoutines.route,
            actualRoute,
            "null origin must default to DailyRoutines route",
        )
        harness.cleanup()
    }

    // ===== (e) Normal load sets DAILY_ROUTINES =====

    @Test
    fun loadRoutine_sets_DAILY_ROUTINES_origin() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        assertEquals(
            RoutineLaunchOrigin.DAILY_ROUTINES,
            harness.coordinator.routineLaunchOrigin,
            "Normal loadRoutine must set origin to DAILY_ROUTINES",
        )
        harness.cleanup()
    }

    // ===== (f) Cycle load after daily load overwrites origin to TRAINING_CYCLES =====

    @Test
    fun cycleLoad_after_dailyLoad_overwrites_origin_to_TRAINING_CYCLES() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        // First: daily load
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        assertEquals(RoutineLaunchOrigin.DAILY_ROUTINES, harness.coordinator.routineLaunchOrigin)

        // Then: cycle load must overwrite
        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-2",
            dayNumber = 2,
        )
        advanceUntilIdle()

        assertEquals(
            RoutineLaunchOrigin.TRAINING_CYCLES,
            harness.coordinator.routineLaunchOrigin,
            "Cycle load after daily load must overwrite origin to TRAINING_CYCLES",
        )
        harness.cleanup()
    }
}
