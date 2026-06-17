package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutState
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
    fun loadRoutine_percentOfPRResolvesStaleSnapshotWeights() = runTest {
        val harness = DWSMTestHarness(this)
        val exercise = TestFixtures.benchPress
        val routine = Routine(
            id = "routine-pr-stale",
            name = "PR Stale Snapshot Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-pr-stale",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10, 10),
                    weightPerCableKg = 5f,
                    setWeightsPerCableKg = listOf(5f, 5f),
                    programMode = ProgramMode.OldSchool,
                    usePercentOfPR = true,
                    weightPercentOfPR = 80,
                    setWeightsPercentOfPR = listOf(80, 90),
                ),
            ),
        )
        harness.fakeExerciseRepo.addExercise(exercise)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 50f,
                reps = 6,
                oneRepMax = 60f,
                timestamp = 1_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 300f,
            ),
        )
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            40f,
            params.weightPerCableKg,
            "First set should resolve from 80% of the current PR, not stale 5kg snapshot",
        )

        val loadedExercise = harness.dwsm.coordinator.loadedRoutine.value?.exercises?.single()
        assertNotNull(loadedExercise)
        assertEquals(listOf(40f, 45f), loadedExercise.setWeightsPerCableKg)
        harness.cleanup()
    }

    @Test
    fun loadRoutineFromCycleAsync_resolvesWeightsBeforeReturning() = runTest {
        val harness = DWSMTestHarness(this)
        val exercise = TestFixtures.benchPress
        val routine = Routine(
            id = "cycle-routine-pr-async",
            name = "Cycle PR Async Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "re-cycle-pr-async",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 5f,
                    setWeightsPerCableKg = listOf(5f),
                    programMode = ProgramMode.OldSchool,
                    usePercentOfPR = true,
                    weightPercentOfPR = 80,
                ),
            ),
        )
        harness.fakeExerciseRepo.addExercise(exercise)
        harness.fakeWorkoutRepo.addRoutine(routine)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 50f,
                reps = 6,
                oneRepMax = 60f,
                timestamp = 1_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 300f,
            ),
        )
        advanceUntilIdle()

        val loaded = harness.dwsm.loadRoutineFromCycleAsync(routine.id, "cycle-1", 1)
        assertTrue(loaded, "Cycle routine should load successfully")
        assertEquals(
            40f,
            harness.dwsm.coordinator.workoutParameters.value.weightPerCableKg,
            "Async cycle load must finish PR resolution before returning",
        )
        assertEquals("cycle-1", harness.dwsm.coordinator.activeCycleId)
        assertEquals(1, harness.dwsm.coordinator.activeCycleDayNumber)
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
    fun loadRoutine_percentOfPRUsesRoutineProfile() = runTest {
        val harness = DWSMTestHarness(this)
        val exercise = TestFixtures.benchPress
        val routine = Routine(
            id = "routine-pr-profile",
            name = "PR Profile Routine",
            profileId = "profile-b",
            exercises = listOf(
                RoutineExercise(
                    id = "re-pr-profile",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10, 10),
                    weightPerCableKg = 5f,
                    setWeightsPerCableKg = listOf(5f, 5f),
                    programMode = ProgramMode.OldSchool,
                    usePercentOfPR = true,
                    weightPercentOfPR = 50,
                    setWeightsPercentOfPR = listOf(50, 50),
                ),
            ),
        )
        harness.fakeExerciseRepo.addExercise(exercise)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 120f,
                reps = 1,
                oneRepMax = 120f,
                timestamp = 1_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 120f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 2,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 2_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "profile-b",
            ),
        )
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            40f,
            params.weightPerCableKg,
            "PR percentage resolution should use the routine profile, not the default profile",
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
    fun showRoutineComplete_countsOnlyCompletedNonSkippedExercisesAndSets() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._completedRoutineSetKeys.value = setOf(
            0 to 0,
            0 to 1,
            2 to 0,
        )
        harness.dwsm.coordinator._completedExercises.value = setOf(0, 1, 2)
        harness.dwsm.coordinator._skippedExercises.value = setOf(1)

        harness.dwsm.showRoutineComplete()

        val complete = assertIs<RoutineFlowState.Complete>(harness.dwsm.coordinator.routineFlowState.value)
        assertEquals(3, complete.totalSets, "Skipped exercises should not contribute completed set count")
        assertEquals(2, complete.totalExercises, "Skipped exercises should not contribute completed exercise count")
        harness.cleanup()
    }

    @Test
    fun proceedFromSummary_warmupOnlySetDoesNotMarkExerciseCompleted() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20f,
            avgLoadKgPerCable = 18f,
            repCount = 3,
            workingReps = 0,
            warmupReps = 3,
        )

        harness.dwsm.proceedFromSummary()
        advanceUntilIdle()

        assertEquals(
            emptySet(),
            harness.dwsm.coordinator.completedExercises.value,
            "Warmup-only summary should not mark the exercise complete",
        )
        val complete = assertIs<RoutineFlowState.Complete>(harness.dwsm.coordinator.routineFlowState.value)
        assertEquals(0, complete.totalExercises, "Warmup-only completion should not count as a completed exercise")
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
    fun enterRoutineOverview_withActiveRecoveryUsesRoutineProfilePR() = runTest {
        val harness = DWSMTestHarness(this)
        val exercise = TestFixtures.benchPress
        val routine = Routine(
            id = "routine-modifier-profile",
            name = "Modifier Profile Routine",
            profileId = "profile-b",
            exercises = listOf(
                RoutineExercise(
                    id = "re-modifier-profile",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(10, 10),
                    weightPerCableKg = 20f,
                    setWeightsPerCableKg = listOf(20f, 20f),
                    programMode = ProgramMode.OldSchool,
                ),
            ),
        )
        harness.fakeExerciseRepo.addExercise(exercise)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 120f,
                reps = 1,
                oneRepMax = 120f,
                timestamp = 1_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 120f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "default",
            ),
        )
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 2,
                exerciseId = exercise.id!!,
                exerciseName = exercise.name,
                weightPerCableKg = 80f,
                reps = 1,
                oneRepMax = 80f,
                timestamp = 2_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 80f,
                phase = WorkoutPhase.CONCENTRIC,
                profileId = "profile-b",
            ),
        )
        advanceUntilIdle()

        harness.dwsm.enterRoutineOverview(
            routine,
            AppliedRoutineModifier(RoutineModifierType.ACTIVE_RECOVERY, 50),
        )
        advanceUntilIdle()

        val state = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.Overview>(state)
        assertEquals(
            40f,
            state.routine.exercises.single().weightPerCableKg,
            "Active Recovery should derive launch weights from the routine profile PR",
        )
        assertEquals(listOf(40f, 40f), state.routine.exercises.single().setWeightsPerCableKg)
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

    // ===== G. Same-exercise continuation (Issue #572) =====

    /**
     * Build a routine that mirrors the user's "Legs" report from issue #572:
     *  - Lunge (2x8 OldSchool)
     *  - Lunge (1x8 TUT)             <- same exercise, different mode
     *  - Squat   (2x8 OldSchool)
     *  - Squat   (1x8 TUT)           <- same exercise, different mode
     *  - Calf Raise (1x30 OldSchool) <- different exercise
     */
    private fun createLegsStyleRoutine(): Routine {
        val lunge = TestFixtures.squat.copy(name = "Lunge", id = "lunge-572")
        val lungeTut = TestFixtures.squat.copy(name = "Lunge", id = "lunge-572")
        val squat = TestFixtures.squat.copy(name = "Squat", id = "squat-572")
        val squatTut = TestFixtures.squat.copy(name = "Squat", id = "squat-572")
        val calfRaise = TestFixtures.squat.copy(name = "Calf Raise", id = "calf-572")
        return Routine(
            id = "test-legs-routine-572",
            name = "Legs (Issue #572)",
            exercises = listOf(
                RoutineExercise(
                    id = "re-lunge-1",
                    exercise = lunge,
                    orderIndex = 0,
                    setReps = listOf(8, 8),
                    setWeightsPerCableKg = listOf(40f, 40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                ),
                RoutineExercise(
                    id = "re-lunge-2-tut",
                    exercise = lungeTut,
                    orderIndex = 1,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.TUT,
                ),
                RoutineExercise(
                    id = "re-squat-1",
                    exercise = squat,
                    orderIndex = 2,
                    setReps = listOf(8, 8),
                    setWeightsPerCableKg = listOf(60f, 60f),
                    weightPerCableKg = 60f,
                    programMode = ProgramMode.OldSchool,
                ),
                RoutineExercise(
                    id = "re-squat-2-tut",
                    exercise = squatTut,
                    orderIndex = 3,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(60f),
                    weightPerCableKg = 60f,
                    programMode = ProgramMode.TUT,
                ),
                RoutineExercise(
                    id = "re-calf",
                    exercise = calfRaise,
                    orderIndex = 4,
                    setReps = listOf(30),
                    setWeightsPerCableKg = listOf(20f),
                    weightPerCableKg = 20f,
                    programMode = ProgramMode.OldSchool,
                ),
            ),
        )
    }

    /**
     * Issue #572 acceptance criterion #1: getNextStep returns (currentExIndex, currentSetIndex + 1)
     * while the current entry's setReps still have unrun sets, even if the next entry is the
     * same exercise. The 0->1 advance inside entry #0 (Lunge 2x8) must stay in entry #0.
     */
    @Test
    fun sameExercise_getNextStep_advancesWithinEntryWhenUnrunSetsRemain() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createLegsStyleRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        val nextStep = harness.routineFlowManager.getNextStep(routine, currentExIndex = 0, currentSetIndex = 0)
        assertNotNull(nextStep, "Should have a next step from set 0 of entry #0")
        assertEquals(
            0,
            nextStep.first,
            "Should stay in entry #0 (Lunge 2x8) for set 1, not jump to entry #1 (Lunge TUT)",
        )
        assertEquals(
            1,
            nextStep.second,
            "Should advance to set 1 of entry #0",
        )
        harness.cleanup()
    }

    /**
     * Issue #572 acceptance criterion #2: with the user's exact Legs-style routine, walking
     * getNextStep from set 0 of #0 (Lunge 2x8 OldSchool) produces the sequence
     * (0, 1) -> (1, 0) -> (2, 0) [next non-skipped different exercise: Squat].
     * The 0->1 transition stays inside #0; the 0 of #0 -> 0 of #1 is the same-exercise
     * boundary (Lunge OldSchool -> Lunge TUT); the 0 of #1 -> 0 of #2 is a true exercise
     * change (Lunge -> Squat).
     */
    @Test
    fun sameExercise_getNextStep_legsRoutineFullSequence() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createLegsStyleRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        // Start: (0, 0)
        // After set 0 of #0 -> (0, 1) (still inside Lunge 2x8)
        val s1 = harness.routineFlowManager.getNextStep(routine, 0, 0)
        assertNotNull(s1)
        assertEquals(0 to 1, s1, "After set 0 of Lunge 2x8 -> set 1 of Lunge 2x8")

        // After set 1 of #0 -> (1, 0) (Lunge 2x8 -> Lunge 1x8 TUT, same-exercise boundary)
        val s2 = harness.routineFlowManager.getNextStep(routine, 0, 1)
        assertNotNull(s2)
        assertEquals(1 to 0, s2, "After set 1 of Lunge 2x8 -> set 0 of Lunge 1x8 TUT")

        // After set 0 of #1 -> (2, 0) (Lunge TUT -> Squat, true exercise change)
        val s3 = harness.routineFlowManager.getNextStep(routine, 1, 0)
        assertNotNull(s3)
        assertEquals(2 to 0, s3, "After set 0 of Lunge TUT -> set 0 of Squat (different exercise)")

        // After set 0 of #2 -> (2, 1) (inside Squat 2x8)
        val s4 = harness.routineFlowManager.getNextStep(routine, 2, 0)
        assertNotNull(s4)
        assertEquals(2 to 1, s4, "After set 0 of Squat 2x8 -> set 1 of Squat 2x8")

        // After set 1 of #2 -> (3, 0) (Squat OldSchool -> Squat TUT, same-exercise boundary)
        val s5 = harness.routineFlowManager.getNextStep(routine, 2, 1)
        assertNotNull(s5)
        assertEquals(3 to 0, s5, "After set 1 of Squat 2x8 -> set 0 of Squat 1x8 TUT")

        // After set 0 of #3 -> (4, 0) (Squat TUT -> Calf Raise, true exercise change)
        val s6 = harness.routineFlowManager.getNextStep(routine, 3, 0)
        assertNotNull(s6)
        assertEquals(4 to 0, s6, "After set 0 of Squat TUT -> set 0 of Calf Raise")

        // After set 0 of #4 -> null (end of routine)
        val s7 = harness.routineFlowManager.getNextStep(routine, 4, 0)
        assertEquals(null, s7, "After last set of last entry -> null")
        harness.cleanup()
    }

    /**
     * Issue #572 acceptance criterion #5: a routine with NO adjacent same-exercise entries
     * (existing fixture) must produce identical getNextStep outputs to the pre-fix code.
     * The 0->1 advance inside an entry stays in the entry, and the cross-entry advance
     * jumps to the next entry's set 0 just like before the fix.
     */
    @Test
    fun sameExercise_getNextStep_noAdjacentSameExercises_behavesAsBefore() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 3,
            setsPerExercise = 3,
            weightKg = 25f,
            repsPerSet = 10,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        // Internal-to-entry: (0, 0) -> (0, 1)
        val internal = harness.routineFlowManager.getNextStep(routine, 0, 0)
        assertNotNull(internal)
        assertEquals(0 to 1, internal)

        // Cross-entry: (0, 2) [last set of entry 0] -> (1, 0)
        val cross = harness.routineFlowManager.getNextStep(routine, 0, 2)
        assertNotNull(cross)
        assertEquals(1 to 0, cross)

        // Cross-entry from a middle entry: (1, 2) -> (2, 0)
        val middle = harness.routineFlowManager.getNextStep(routine, 1, 2)
        assertNotNull(middle)
        assertEquals(2 to 0, middle)

        harness.cleanup()
    }

    /**
     * Issue #572 acceptance criterion #5b: when two adjacent entries share the same name
     * but have different non-null ids (a malformed-but-possible state, e.g. an old routine
     * edited with two exercise-library entries of the same display name), the same-exercise
     * detection must NOT fire and the routine should advance to the next entry normally.
     */
    @Test
    fun sameExercise_getNextStep_differentIdsSameName_doesNotMerge() = runTest {
        val harness = DWSMTestHarness(this)
        val lungeA = TestFixtures.squat.copy(name = "Lunge", id = "lunge-A")
        val lungeB = TestFixtures.squat.copy(name = "Lunge", id = "lunge-B")
        val routine = Routine(
            id = "test-572-different-ids",
            name = "Lunge A then Lunge B (different ids)",
            exercises = listOf(
                RoutineExercise(
                    id = "re-lunge-A",
                    exercise = lungeA,
                    orderIndex = 0,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                ),
                RoutineExercise(
                    id = "re-lunge-B",
                    exercise = lungeB,
                    orderIndex = 1,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.TUT,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        // Same name but different ids -> NOT same exercise.
        // After set 0 of entry #0, advance to set 0 of entry #1 as before the fix.
        val next = harness.routineFlowManager.getNextStep(routine, 0, 0)
        assertNotNull(next)
        assertEquals(1 to 0, next, "Different ids with same name must not be treated as same exercise")

        // And isSameExercise() agrees.
        assertEquals(
            false,
            harness.routineFlowManager.isSameExercise(routine.exercises[0], routine.exercises[1]),
            "isSameExercise must be false when both ids are present and differ",
        )
        harness.cleanup()
    }

    /**
     * Issue #572 acceptance criterion #5c: same-name entries with one or both ids null
     * (legacy / unlinked exercise data) must still be detected as same-exercise.
     */
    @Test
    fun sameExercise_isSameExercise_handlesNullIdsGracefully() = runTest {
        val harness = DWSMTestHarness(this)
        // Two same-name entries where the second has id=null (legacy link)
        val namedA = TestFixtures.squat.copy(name = "Lunge", id = "lunge-572")
        val namedB = TestFixtures.squat.copy(name = "Lunge", id = null)
        val routine = Routine(
            id = "test-572-null-ids",
            name = "Lunge (with id) -> Lunge (id=null)",
            exercises = listOf(
                RoutineExercise(
                    id = "re-A",
                    exercise = namedA,
                    orderIndex = 0,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                ),
                RoutineExercise(
                    id = "re-B",
                    exercise = namedB,
                    orderIndex = 1,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.TUT,
                ),
            ),
        )
        assertEquals(
            true,
            harness.routineFlowManager.isSameExercise(routine.exercises[0], routine.exercises[1]),
            "isSameExercise must be true when names match and at least one id is null",
        )

        // Both ids null: still same-exercise (names match)
        val bothNull = TestFixtures.squat.copy(name = "Lunge", id = null)
        val routineBothNull = Routine(
            id = "test-572-both-null",
            name = "Lunge (id=null) -> Lunge (id=null)",
            exercises = listOf(
                RoutineExercise(
                    id = "re-A-null",
                    exercise = bothNull,
                    orderIndex = 0,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                ),
                RoutineExercise(
                    id = "re-B-null",
                    exercise = bothNull,
                    orderIndex = 1,
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.TUT,
                ),
            ),
        )
        assertEquals(
            true,
            harness.routineFlowManager.isSameExercise(routineBothNull.exercises[0], routineBothNull.exercises[1]),
            "isSameExercise must be true when names match and both ids are null",
        )
        harness.cleanup()
    }

    /**
     * Issue #572 acceptance criterion #6: superset behaviour is unchanged. A superset
     * containing two same-exercise entries must continue to interleave per the existing
     * superset branch (no regression to issue-334 / DWSMRoutineFlowTest).
     */
    @Test
    fun sameExercise_supersetBranchUnchangedForSameExerciseEntries() = runTest {
        val harness = DWSMTestHarness(this)
        // Build a superset of two same-name entries with different ids (so they are NOT
        // same-exercise per isSameExercise). The superset branch should interleave them.
        val lungeA = TestFixtures.squat.copy(name = "Lunge", id = "lunge-A")
        val lungeB = TestFixtures.squat.copy(name = "Lunge", id = "lunge-B")
        val supersetId = "ss-572"
        val routine = Routine(
            id = "test-572-superset",
            name = "Lunge superset (different ids)",
            exercises = listOf(
                RoutineExercise(
                    id = "re-A",
                    exercise = lungeA,
                    orderIndex = 0,
                    setReps = listOf(8, 8, 8),
                    setWeightsPerCableKg = listOf(40f, 40f, 40f),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                    supersetId = supersetId,
                    orderInSuperset = 0,
                ),
                RoutineExercise(
                    id = "re-B",
                    exercise = lungeB,
                    orderIndex = 1,
                    setReps = listOf(8, 8, 8),
                    setWeightsPerCableKg = listOf(50f, 50f, 50f),
                    weightPerCableKg = 50f,
                    programMode = ProgramMode.OldSchool,
                    supersetId = supersetId,
                    orderInSuperset = 1,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        // Superset interleaves A1 -> B1 -> A2 -> B2. From (0, 0) we expect (1, 0).
        val s1 = harness.routineFlowManager.getNextStep(routine, 0, 0)
        assertNotNull(s1)
        assertEquals(1 to 0, s1, "Superset set 0 -> next member set 0")
        val s2 = harness.routineFlowManager.getNextStep(routine, 1, 0)
        assertNotNull(s2)
        assertEquals(0 to 1, s2, "Superset set 0 of B -> set 1 of A")
        harness.cleanup()
    }

    /**
     * Issue #572 regression: same-exercise continuation must leave workoutState Idle
     * (not Resting) so navigation to SetReady can occur. Otherwise autoplay rest
     * completion strands the user on the rest screen and Skip Rest double-advances,
     * skipping the deferred TUT finisher set.
     */
    @Test
    fun sameExercise_startNextSet_transitionsToIdleSetReadyWithoutDoubleAdvance() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createLegsStyleRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakePrefsManager.setSummaryCountdownSeconds(0)
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Finished last set of Lunge OldSchool (entry #0); rest timer at 0 with autoplay off.
        harness.dwsm.coordinator._currentExerciseIndex.value = 0
        harness.dwsm.coordinator._currentSetIndex.value = 1
        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = "Lunge",
            isLastExercise = false,
            currentSet = 2,
            totalSets = 2,
        )

        harness.dwsm.startNextSet()
        advanceUntilIdle()

        assertIs<WorkoutState.Idle>(
            harness.dwsm.coordinator.workoutState.value,
            "Same-exercise continuation must set Idle so SetReady navigation can fire",
        )
        assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
        assertEquals(1, harness.dwsm.coordinator.currentExerciseIndex.value, "Should advance to TUT entry")
        assertEquals(0, harness.dwsm.coordinator.currentSetIndex.value)
        assertEquals(ProgramMode.TUT, harness.dwsm.coordinator.workoutParameters.value.programMode)

        // A second advance attempt must not skip the TUT set (would jump to Squat at index 2).
        harness.dwsm.skipRest()
        harness.dwsm.startNextSet()
        advanceUntilIdle()
        assertEquals(
            1,
            harness.dwsm.coordinator.currentExerciseIndex.value,
            "Must not double-advance past the deferred same-exercise set",
        )

        harness.cleanup()
    }

    /**
     * Issue #572: isSameExercise is false for genuinely different adjacent exercises.
     */
    @Test
    fun sameExercise_isSameExercise_falseForDifferentExercises() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = WorkoutStateFixtures.createTestRoutine()
        val a = routine.exercises[0]
        val b = routine.exercises[1]
        assertEquals(
            false,
            harness.routineFlowManager.isSameExercise(a, b),
            "Adjacent different exercises (e.g. Bench Press -> Bicep Curl) must not be same-exercise",
        )
        harness.cleanup()
    }
}
