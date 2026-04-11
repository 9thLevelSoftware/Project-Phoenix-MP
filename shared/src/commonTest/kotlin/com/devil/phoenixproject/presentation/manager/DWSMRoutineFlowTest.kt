package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

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
            repsPerSet = 12,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            30f,
            params.weightPerCableKg,
            "Weight should match first exercise's weightPerCableKg",
        )
        assertEquals(
            12,
            params.reps,
            "Reps should match first exercise's first set reps",
        )
        assertEquals(
            routine.exercises[0].exercise.id,
            params.selectedExerciseId,
            "Selected exercise ID should match first exercise",
        )
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
        assertEquals(
            null,
            beforeAdvance,
            "loadedRoutine should be null before coroutine completes",
        )

        advanceUntilIdle()

        // After advancing, it should be set
        assertNotNull(
            harness.dwsm.coordinator.loadedRoutine.value,
            "loadedRoutine should be set after advanceUntilIdle",
        )
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
        assertEquals(
            WorkoutState.Idle,
            harness.dwsm.coordinator.workoutState.value,
            "loadRoutine should reset workoutState to Idle",
        )
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
        assertIs<RoutineFlowState.NotInRoutine>(
            harness.dwsm.coordinator.routineFlowState.value,
            "loadRoutine should NOT change routineFlowState (stays NotInRoutine)",
        )
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

        assertIs<RoutineFlowState.SetReady>(
            harness.dwsm.coordinator.routineFlowState.value,
            "enterSetReady should set routineFlowState to SetReady",
        )
        harness.cleanup()
    }

    @Test
    fun enterSetReady_setsCorrectWeightAndReps() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            weightKg = 35f,
            repsPerSet = 8,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(state)
        assertEquals(
            35f,
            state.adjustedWeight,
            "SetReady weight should match exercise weight",
        )
        assertEquals(
            8,
            state.adjustedReps,
            "SetReady reps should match exercise set reps",
        )
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
            repsPerSet = 6,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.enterSetReady(0, 0)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            40f,
            params.weightPerCableKg,
            "workoutParameters weight should match set weight",
        )
        assertEquals(
            6,
            params.reps,
            "workoutParameters reps should match set reps",
        )
        // Characterization: enterSetReady explicitly sets isJustLift=false (Issue #209)
        assertEquals(
            false,
            params.isJustLift,
            "enterSetReady should set isJustLift=false for routines",
        )
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
        assertEquals(
            routine.exercises[1].exercise.id,
            params.selectedExerciseId,
            "After advance, selected exercise should be the second exercise",
        )
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
        assertEquals(
            routine.exercises[2].exercise.id,
            params.selectedExerciseId,
            "After jumpToExercise(2), selected exercise should be the third exercise",
        )

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
        assertEquals(
            exerciseBefore,
            exerciseAfter,
            "jumpToExercise should be blocked during Active state - exercise should not change",
        )
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
        assertEquals(
            routine.exercises[1].exercise.id,
            params.selectedExerciseId,
            "After skip, selected exercise should be the second exercise",
        )
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
        assertEquals(
            routine.exercises[1].exercise.id,
            harness.dwsm.coordinator.workoutParameters.value.selectedExerciseId,
        )

        // Characterization: jumpToExercise blocks during Active state (Issue #125).
        // Must stop the auto-started workout before navigating again.
        // Use exitingWorkout=false to preserve _loadedRoutine (true clears it).
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceTimeBy(1000)

        // Now go back (state is SetSummary, not Active, so jumpToExercise won't be blocked)
        harness.dwsm.goToPreviousExercise()
        advanceTimeBy(7000)

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            routine.exercises[0].exercise.id,
            params.selectedExerciseId,
            "After goToPreviousExercise, should be back to the first exercise",
        )
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
        assertEquals(
            25f,
            params.weightPerCableKg,
            "Superset routine should load first exercise weight",
        )
        assertEquals(
            TestFixtures.benchPress.id,
            params.selectedExerciseId,
            "Superset routine should select first exercise (Bench Press)",
        )
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
        assertEquals(
            15f,
            state.adjustedWeight,
            "Second exercise weight should be 15f (Bicep Curl)",
        )
        assertEquals(
            12,
            state.adjustedReps,
            "Second exercise reps should be 12",
        )
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
        assertIs<RoutineFlowState.Overview>(
            state,
            "enterRoutineOverview should set routineFlowState to Overview",
        )
        assertEquals(
            0,
            state.selectedExerciseIndex,
            "Overview should start with first exercise selected",
        )
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
        assertEquals(
            1,
            state.selectedExerciseIndex,
            "selectExerciseInOverview(1) should update selectedExerciseIndex to 1",
        )
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
        assertEquals(
            0,
            state.selectedExerciseIndex,
            "Out-of-bounds selectExerciseInOverview should be silently ignored",
        )
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

        assertIs<RoutineFlowState.SetReady>(
            harness.dwsm.coordinator.routineFlowState.value,
            "Should transition from Overview to SetReady",
        )
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
        assertIs<RoutineFlowState.Overview>(
            state,
            "returnToOverview should transition back to Overview",
        )
        assertEquals(
            0,
            state.selectedExerciseIndex,
            "returnToOverview should preserve current exercise index",
        )
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

        assertIs<RoutineFlowState.NotInRoutine>(
            harness.dwsm.coordinator.routineFlowState.value,
            "exitRoutineFlow should reset routineFlowState to NotInRoutine",
        )
        assertEquals(
            null,
            harness.dwsm.coordinator.loadedRoutine.value,
            "exitRoutineFlow should clear loadedRoutine",
        )
        assertEquals(
            WorkoutState.Idle,
            harness.dwsm.coordinator.workoutState.value,
            "exitRoutineFlow should reset workoutState to Idle",
        )
        harness.cleanup()
    }

    // ===== F. Non-contiguous superset regression (Issue #334) =====

    @Test
    fun nonContiguousSuperset_getNextStep_navigatesToStandaloneAfterSuperset() = runTest {
        val harness = DWSMTestHarness(this)
        // Non-contiguous: superset A at 0, standalone at 1, superset B at 2
        val routine = WorkoutStateFixtures.createNonContiguousSupersetRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // After loading, normalizeExerciseOrder heals the routine so superset
        // members are adjacent. The loaded routine should have exercises
        // reordered: [BenchPress(ss), BicepCurl(ss), Squat(standalone)].
        val loaded = harness.dwsm.coordinator.loadedRoutine.value
        assertNotNull(loaded, "Routine should be loaded")

        // After completing the last set of the last superset exercise (BicepCurl),
        // getNextStep should return the standalone exercise (Squat), not null.
        // The superset has 3 sets each. Simulate completing all sets by asking
        // for the next step after the last set of the last superset member.
        val lastSupersetExIndex = loaded.exercises.indexOfFirst {
            it.supersetId != null && it.orderInSuperset == 1
        }
        assertTrue(lastSupersetExIndex >= 0, "Should find the second superset member")

        // Ask what comes after the last set (index 2) of the last superset member
        val nextStep = harness.routineFlowManager.getNextStep(
            loaded,
            lastSupersetExIndex,
            currentSetIndex = 2, // last set (0-indexed, 3 sets = indices 0,1,2)
        )

        assertNotNull(
            nextStep,
            "getNextStep should return standalone exercise after superset completion, not null",
        )
        val nextExercise = loaded.exercises[nextStep.first]
        assertEquals(
            TestFixtures.squat.id,
            nextExercise.exercise.id,
            "Next exercise after superset should be the standalone Squat",
        )
        assertEquals(
            0,
            nextStep.second,
            "Next step should start at set index 0",
        )
        harness.cleanup()
    }

    @Test
    fun nonContiguousSuperset_normalizeExerciseOrder_healsOnLoad() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createNonContiguousSupersetRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // Let init block settle

        // Before loading, exercises are non-contiguous:
        // [BenchPress(ss,order=0), Squat(standalone), BicepCurl(ss,order=1)]
        assertEquals(
            TestFixtures.benchPress.id,
            routine.exercises[0].exercise.id,
            "Pre-load: index 0 should be BenchPress (superset A)",
        )
        assertEquals(
            TestFixtures.squat.id,
            routine.exercises[1].exercise.id,
            "Pre-load: index 1 should be Squat (standalone, splitting the superset)",
        )
        assertEquals(
            TestFixtures.bicepCurl.id,
            routine.exercises[2].exercise.id,
            "Pre-load: index 2 should be BicepCurl (superset B)",
        )

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // After loading, normalizeExerciseOrder should heal the routine so
        // superset members are adjacent: [BenchPress(ss), BicepCurl(ss), Squat]
        val loaded = harness.dwsm.coordinator.loadedRoutine.value
        assertNotNull(loaded, "Routine should be loaded")
        assertEquals(3, loaded.exercises.size, "Should still have 3 exercises")

        assertEquals(
            TestFixtures.benchPress.id,
            loaded.exercises[0].exercise.id,
            "Post-heal: index 0 should be BenchPress (superset member A)",
        )
        assertEquals(
            TestFixtures.bicepCurl.id,
            loaded.exercises[1].exercise.id,
            "Post-heal: index 1 should be BicepCurl (superset member B, now adjacent)",
        )
        assertEquals(
            TestFixtures.squat.id,
            loaded.exercises[2].exercise.id,
            "Post-heal: index 2 should be Squat (standalone, moved after superset)",
        )

        // Verify orderIndex values were updated correctly
        assertEquals(0, loaded.exercises[0].orderIndex, "BenchPress orderIndex should be 0")
        assertEquals(1, loaded.exercises[1].orderIndex, "BicepCurl orderIndex should be 1")
        assertEquals(2, loaded.exercises[2].orderIndex, "Squat orderIndex should be 2")

        harness.cleanup()
    }
}
