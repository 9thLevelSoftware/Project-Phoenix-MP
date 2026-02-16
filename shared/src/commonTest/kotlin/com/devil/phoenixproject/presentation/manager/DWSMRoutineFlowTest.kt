package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.testutil.TestFixtures
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Characterization tests for DefaultWorkoutSessionManager routine flow.
 *
 * These tests lock in EXISTING behavior. If behavior is surprising,
 * we document it with a "Characterization:" comment rather than changing it.
 *
 * Each test calls harness.cleanup() before exiting to cancel DWSM's long-running
 * init collectors and prevent UncompletedCoroutinesError.
 *
 * IMPORTANT: An advanceUntilIdle() call MUST be placed after DWSMTestHarness construction
 * and BEFORE calling loadRoutine()/enterRoutineOverview(). This lets DWSM's init block
 * coroutines (flow collectors, importExercises, etc.) settle first. Without this,
 * the init block and loadRoutine coroutines interleave and create an infinite
 * re-dispatch loop that causes advanceUntilIdle() to hang forever.
 */
class DWSMRoutineFlowTest {

    // ===== A. loadRoutine =====

    @Test
    fun loadRoutine_setsFirstExerciseParams() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            weightKg = 30f,
            repsPerSet = 12
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(30f, params.weightPerCableKg,
            "Weight should match first exercise's weightPerCableKg")
        assertEquals(12, params.reps,
            "Reps should match first exercise's first set reps")
        assertEquals(routine.exercises[0].exercise.id, params.selectedExerciseId,
            "Selected exercise ID should match first exercise")
        harness.cleanup()
    }

    @Test
    fun loadRoutine_isAsync_stateNotImmediatelyAvailable() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        // Characterization: loadRoutine launches a coroutine to resolve weights,
        // so loadedRoutine is NOT set synchronously
        harness.dwsm.loadRoutine(routine)

        // Before advancing, loadedRoutine should still be null (async)
        val beforeAdvance = harness.dwsm.coordinator.loadedRoutine.value
        // Characterization: loadRoutine is async, loadedRoutine is null before coroutine runs
        assertEquals(null, beforeAdvance,
            "loadedRoutine should be null before coroutine completes")

        advanceUntilIdle()

        // After advancing, it should be set
        assertNotNull(harness.dwsm.coordinator.loadedRoutine.value,
            "loadedRoutine should be set after advanceUntilIdle")
        harness.cleanup()
    }

    @Test
    fun loadRoutine_resetsWorkoutStateToIdle() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Characterization: loadRoutineInternal explicitly resets workout state to Idle
        assertEquals(WorkoutState.Idle, harness.dwsm.coordinator.workoutState.value,
            "loadRoutine should reset workoutState to Idle")
        harness.cleanup()
    }

    @Test
    fun loadRoutine_doesNotSetRoutineFlowState() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Characterization: loadRoutine does NOT set routineFlowState.
        // Only enterRoutineOverview does that. loadRoutine only loads parameters.
        assertIs<RoutineFlowState.NotInRoutine>(harness.dwsm.coordinator.routineFlowState.value,
            "loadRoutine should NOT change routineFlowState (stays NotInRoutine)")
        harness.cleanup()
    }

    // ===== B. enterSetReady =====

    @Test
    fun enterSetReady_updatesRoutineFlowState() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)

        assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value,
            "enterSetReady should set routineFlowState to SetReady")
        harness.cleanup()
    }

    @Test
    fun enterSetReady_setsCorrectWeightAndReps() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            weightKg = 35f,
            repsPerSet = 8
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(state)
        assertEquals(35f, state.adjustedWeight,
            "SetReady weight should match exercise weight")
        assertEquals(8, state.adjustedReps,
            "SetReady reps should match exercise set reps")
        harness.cleanup()
    }

    @Test
    fun enterSetReady_secondSet_incrementsSetIndex() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(setsPerExercise = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 1)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(state)
        assertEquals(0, state.exerciseIndex, "exerciseIndex should be 0")
        assertEquals(1, state.setIndex, "setIndex should be 1")
        harness.cleanup()
    }

    @Test
    fun enterSetReady_updatesWorkoutParameters() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            weightKg = 40f,
            repsPerSet = 6
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(40f, params.weightPerCableKg,
            "workoutParameters weight should match set weight")
        assertEquals(6, params.reps,
            "workoutParameters reps should match set reps")
        // Characterization: enterSetReady explicitly sets isJustLift=false (Issue #209)
        assertEquals(false, params.isJustLift,
            "enterSetReady should set isJustLift=false for routines")
        harness.cleanup()
    }

    // ===== C. Navigation =====

    @Test
    fun advanceToNextExercise_movesToNextIndex() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Characterization: advanceToNextExercise calls jumpToExercise which sends BLE
        // commands, navigates, then auto-starts a workout (skipCountdown=false).
        // Using advanceTimeBy instead of advanceUntilIdle because the auto-started workout
        // re-awakens init block collectors and creates an infinite re-dispatch loop.
        // 7s covers: BLE delays (250ms) + countdown (5s) + START delay (100ms) + margin.
        harness.dwsm.advanceToNextExercise()
        advanceTimeBy(7000)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(routine.exercises[1].exercise.id, params.selectedExerciseId,
            "After advance, selected exercise should be the second exercise")
        harness.cleanup()
    }

    @Test
    fun jumpToExercise_navigatesToSpecificIndex() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.jumpToExercise(2)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(routine.exercises[2].exercise.id, params.selectedExerciseId,
            "After jumpToExercise(2), selected exercise should be the third exercise")

        // Stop the auto-started workout to clean up monitoring coroutines
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()
        harness.cleanup()
    }

    @Test
    fun jumpToExercise_blockedDuringActiveState() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Start a workout to get to Active state
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        val exerciseBefore = harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId

        // Characterization: jumpToExercise is blocked during Active state (Issue #125)
        harness.dwsm.jumpToExercise(2)
        advanceUntilIdle()

        val exerciseAfter = harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId
        assertEquals(exerciseBefore, exerciseAfter,
            "jumpToExercise should be blocked during Active state - exercise should not change")
        harness.cleanup()
    }

    @Test
    fun skipCurrentExercise_advancesToNext() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Characterization: skipCurrentExercise calls jumpToExercise which auto-starts
        // a workout after navigation. advanceTimeBy avoids infinite re-dispatch loop.
        harness.dwsm.skipCurrentExercise()
        advanceTimeBy(7000)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(routine.exercises[1].exercise.id, params.selectedExerciseId,
            "After skip, selected exercise should be the second exercise")
        harness.cleanup()
    }

    @Test
    fun goToPreviousExercise_navigatesBackward() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Advance to exercise 1 (jumpToExercise auto-starts a workout).
        // advanceTimeBy avoids infinite re-dispatch loop from init block interaction.
        harness.dwsm.advanceToNextExercise()
        advanceTimeBy(7000)
        assertEquals(routine.exercises[1].exercise.id,
            harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId)

        // Characterization: jumpToExercise blocks during Active state (Issue #125).
        // Must stop the auto-started workout before navigating again.
        // Use exitingWorkout=false to preserve _loadedRoutine (true clears it).
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceTimeBy(1000)

        // Now go back (state is SetSummary, not Active, so jumpToExercise won't be blocked)
        harness.dwsm.goToPreviousExercise()
        advanceTimeBy(7000)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(routine.exercises[0].exercise.id, params.selectedExerciseId,
            "After goToPreviousExercise, should be back to the first exercise")
        harness.cleanup()
    }

    // ===== D. Superset navigation =====

    @Test
    fun supersetRoutine_loadRoutine_setsFirstExerciseParams() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createSupersetRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        // First exercise in superset routine is Bench Press with 25f weight
        assertEquals(25f, params.weightPerCableKg,
            "Superset routine should load first exercise weight")
        assertEquals(TestFixtures.benchPress.id, params.selectedExerciseId,
            "Superset routine should select first exercise (Bench Press)")
        harness.cleanup()
    }

    @Test
    fun supersetRoutine_enterSetReady_secondExercise() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createSupersetRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Enter set ready for second exercise (Bicep Curl in superset)
        harness.dwsm.enterSetReady(1, 0)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(state)
        assertEquals(1, state.exerciseIndex)
        assertEquals(15f, state.adjustedWeight,
            "Second exercise weight should be 15f (Bicep Curl)")
        assertEquals(12, state.adjustedReps,
            "Second exercise reps should be 12")
        harness.cleanup()
    }

    @Test
    fun supersetRoutine_navigateThroughAll() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createSupersetRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Navigate through all 3 exercises.
        // Characterization: jumpToExercise auto-starts a workout (Active state) and
        // blocks further navigation (Issue #125). Must stop between navigations.
        // Use exitingWorkout=false to preserve _loadedRoutine (true clears it).
        // advanceTimeBy avoids infinite re-dispatch loop from init block interaction.
        val exerciseIds = mutableListOf<String?>()
        exerciseIds.add(harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId)

        harness.dwsm.advanceToNextExercise()
        advanceTimeBy(7000)
        exerciseIds.add(harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId)

        // Stop the auto-started workout so next navigation isn't blocked
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceTimeBy(1000)

        harness.dwsm.advanceToNextExercise()
        advanceTimeBy(7000)
        exerciseIds.add(harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId)

        assertEquals(3, exerciseIds.size, "Should have visited 3 exercises")
        assertEquals(TestFixtures.benchPress.id, exerciseIds[0])
        assertEquals(TestFixtures.bicepCurl.id, exerciseIds[1])
        assertEquals(TestFixtures.squat.id, exerciseIds[2])
        harness.cleanup()
    }

    // ===== E. Overview =====

    @Test
    fun enterRoutineOverview_setsOverviewState() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.Overview>(state,
            "enterRoutineOverview should set routineFlowState to Overview")
        assertEquals(0, state.selectedExerciseIndex,
            "Overview should start with first exercise selected")
        harness.cleanup()
    }

    @Test
    fun selectExerciseInOverview_updatesSelectedIndex() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        harness.dwsm.selectExerciseInOverview(1)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.Overview>(state)
        assertEquals(1, state.selectedExerciseIndex,
            "selectExerciseInOverview(1) should update selectedExerciseIndex to 1")
        harness.cleanup()
    }

    @Test
    fun selectExerciseInOverview_outOfBounds_noChange() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        // Characterization: out-of-bounds index is silently ignored
        harness.dwsm.selectExerciseInOverview(10)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.Overview>(state)
        assertEquals(0, state.selectedExerciseIndex,
            "Out-of-bounds selectExerciseInOverview should be silently ignored")
        harness.cleanup()
    }

    @Test
    fun enterRoutineOverview_thenEnterSetReady_transitions() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        assertIs<RoutineFlowState.Overview>(harness.dwsm.coordinator.routineFlowState.value)

        harness.dwsm.enterSetReady(0, 0)

        assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value,
            "Should transition from Overview to SetReady")
        harness.cleanup()
    }

    @Test
    fun returnToOverview_fromSetReady() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)
        assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)

        harness.dwsm.returnToOverview()

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.Overview>(state,
            "returnToOverview should transition back to Overview")
        assertEquals(0, state.selectedExerciseIndex,
            "returnToOverview should preserve current exercise index")
        harness.cleanup()
    }

    @Test
    fun exitRoutineFlow_resetsToNotInRoutine() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.enterRoutineOverview(routine)
        advanceUntilIdle()

        harness.dwsm.exitRoutineFlow()

        assertIs<RoutineFlowState.NotInRoutine>(harness.dwsm.coordinator.routineFlowState.value,
            "exitRoutineFlow should reset routineFlowState to NotInRoutine")
        assertEquals(null, harness.dwsm.coordinator.loadedRoutine.value,
            "exitRoutineFlow should clear loadedRoutine")
        assertEquals(WorkoutState.Idle, harness.dwsm.coordinator.workoutState.value,
            "exitRoutineFlow should reset workoutState to Idle")
        harness.cleanup()
    }
}
