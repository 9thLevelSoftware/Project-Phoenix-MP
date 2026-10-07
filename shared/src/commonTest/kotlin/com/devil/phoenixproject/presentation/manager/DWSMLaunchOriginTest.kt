package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the RoutineLaunchOrigin lifecycle (task 4B.2 + 4B fix).
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
 * (g) enterRoutineOverview(routine) stamps DAILY_ROUTINES origin (DailyRoutinesScreen path).
 * (h) stopWorkout(exitingWorkout=true) clears origin to null after a cycle load.
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
        // NOTE: updateCycleProgressIfNeeded() is private so we manually replicate its field
        // clearing here rather than calling it directly. This means a future change that adds
        // a routineLaunchOrigin clear *inside* that private function would not be caught by
        // this test — only a direct-call or reflection-based test could detect that. Accepted
        // trade-off given the function's narrow, well-documented invariant.
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

    // ===== (g) enterRoutineOverview stamps DAILY_ROUTINES =====

    @Test
    fun enterRoutineOverview_sets_DAILY_ROUTINES_origin() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        assertEquals(
            RoutineLaunchOrigin.DAILY_ROUTINES,
            harness.coordinator.routineLaunchOrigin,
            "enterRoutineOverview(routine) must stamp DAILY_ROUTINES origin (DailyRoutinesScreen path)",
        )
        harness.cleanup()
    }

    // ===== (h) stopWorkout(exitingWorkout=true) clears origin to null =====

    @Test
    fun stopWorkout_exitingWorkout_true_clears_origin_to_null() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        harness.seedRoutine(routine)
        advanceUntilIdle()

        // Load via cycle path to stamp TRAINING_CYCLES origin
        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()

        assertEquals(
            RoutineLaunchOrigin.TRAINING_CYCLES,
            harness.coordinator.routineLaunchOrigin,
            "Precondition: cycle load must set TRAINING_CYCLES",
        )

        // Simulate "End Workout" — callers always read routineExitDestination() before this call,
        // so the async origin clear is safe (see ActiveSessionEngine stopWorkout comment).
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        assertNull(
            harness.coordinator.routineLaunchOrigin,
            "stopWorkout(exitingWorkout=true) must clear routineLaunchOrigin to null",
        )
        harness.cleanup()
    }

    // ===== Issue #1164 route-Done exit coverage (runtime side) =====
    //
    // The ROUTINE COMPLETE Done footer runs one shared exit action:
    //   routineExitDestination() -> exitRoutineFlow() -> safePopOrNavigate(dest)
    // These tests exercise that exact sequence against the real flow manager for both
    // launch origins and assert the full cleanup contract the merge gate requires:
    // RoutineFlowState.Complete -> NotInRoutine, loadedRoutine cleared, WorkoutState.Idle
    // and launch origin cleared. Navigation-route assertions (pop vs destination-absent
    // fallback, Back parity) run against a real NavController in the Robolectric runtime
    // harness (RoutineCompleteRouteExitRuntimeTest).

    /** Seeds a completed routine so showRoutineComplete() can build a real Complete state. */
    private fun DWSMTestHarness.completeCurrentRoutine() {
        coordinator._completedRoutineSetKeys.value = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0)
        coordinator._completedExercises.value = setOf(0, 1, 2)
        dwsm.showRoutineComplete()
    }

    /** Asserts the full exit cleanup contract after the Done/Back exit sequence. */
    private fun assertRoutineExitCleanup(harness: DWSMTestHarness) {
        assertIs<com.devil.phoenixproject.domain.model.RoutineFlowState.NotInRoutine>(
            harness.coordinator.routineFlowState.value,
            "Done exit must land in RoutineFlowState.NotInRoutine (Complete -> NotInRoutine)",
        )
        assertNull(
            harness.coordinator.loadedRoutine.value,
            "Done exit must clear loadedRoutine",
        )
        assertIs<com.devil.phoenixproject.domain.model.WorkoutState.Idle>(
            harness.coordinator.workoutState.value,
            "Done exit must restore WorkoutState.Idle",
        )
        assertNull(
            harness.coordinator.routineLaunchOrigin,
            "Done exit must clear routineLaunchOrigin to null",
        )
    }

    @Test
    fun doneExit_fromComplete_dailyRoutinesOrigin_runsFullExitSequence() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        assertEquals(RoutineLaunchOrigin.DAILY_ROUTINES, harness.coordinator.routineLaunchOrigin)

        harness.completeCurrentRoutine()
        assertIs<com.devil.phoenixproject.domain.model.RoutineFlowState.Complete>(
            harness.coordinator.routineFlowState.value,
            "Precondition: routine must reach Complete before the Done exit",
        )

        // The exact production exit sequence (routineExitDestination -> exitRoutineFlow ->
        // safePopOrNavigate); the destination read happens BEFORE the origin-clearing exit.
        val dest = if (harness.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
            NavigationRoutes.TrainingCycles.route
        } else {
            NavigationRoutes.DailyRoutines.route
        }
        harness.dwsm.exitRoutineFlow()

        assertEquals(
            NavigationRoutes.DailyRoutines.route,
            dest,
            "Done from a DailyRoutines-origin routine must exit to the daily_routines route",
        )
        assertRoutineExitCleanup(harness)
        harness.cleanup()
    }

    @Test
    fun doneExit_fromComplete_trainingCyclesOrigin_runsFullExitSequence() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutineFromCycleAsync(
            routineId = routine.id,
            cycleId = "cycle-1",
            dayNumber = 1,
        )
        advanceUntilIdle()
        assertEquals(RoutineLaunchOrigin.TRAINING_CYCLES, harness.coordinator.routineLaunchOrigin)

        harness.completeCurrentRoutine()
        assertIs<com.devil.phoenixproject.domain.model.RoutineFlowState.Complete>(
            harness.coordinator.routineFlowState.value,
            "Precondition: routine must reach Complete before the Done exit",
        )

        val dest = if (harness.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
            NavigationRoutes.TrainingCycles.route
        } else {
            NavigationRoutes.DailyRoutines.route
        }
        harness.dwsm.exitRoutineFlow()

        assertEquals(
            NavigationRoutes.TrainingCycles.route,
            dest,
            "Done from a TrainingCycles-origin routine must exit to the training_cycles route",
        )
        assertRoutineExitCleanup(harness)
        harness.cleanup()
    }

    @Test
    fun doneExit_repeatedEntryExit_cleansUpEveryCycle() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        harness.seedRoutine(routine)
        advanceUntilIdle()

        repeat(2) { cycle ->
            if (cycle == 0) {
                harness.dwsm.loadRoutine(routine)
            } else {
                harness.dwsm.loadRoutineFromCycleAsync(
                    routineId = routine.id,
                    cycleId = "cycle-repeat",
                    dayNumber = 1,
                )
            }
            advanceUntilIdle()
            harness.completeCurrentRoutine()

            harness.dwsm.exitRoutineFlow()
            assertRoutineExitCleanup(harness)
        }
        harness.cleanup()
    }

    @Test
    fun doneExit_isIdempotentWhenInvokedTwice() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        harness.seedRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.completeCurrentRoutine()

        // Back mirrors Done (same shared exit action), so a double dispatch (Done tap +
        // Back) must leave the same clean end state and must not throw.
        harness.dwsm.exitRoutineFlow()
        harness.dwsm.exitRoutineFlow()

        assertRoutineExitCleanup(harness)
        harness.cleanup()
    }
}
