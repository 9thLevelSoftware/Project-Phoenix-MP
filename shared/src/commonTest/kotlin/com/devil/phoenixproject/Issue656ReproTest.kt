// Evidence unit test for issue #656 (Phoenix recreation).
// This test reproduces the reported failure via the existing DWSMTestHarness
// using the same code paths as the live app. It documents the bug, not a fix.
//
// To run inside the project (from /tmp/phoenix-mp-656-recreation):
//   ./gradlew :shared:jvmTest --tests "com.devil.phoenixproject.Issue656ReproTest"
//
// Expected (with bug): first proceedFromSummary keeps workoutState == SetSummary,
// ActiveWorkoutScreen's "navigate to SetReady" LaunchedEffect is gated on
// `!isWorkoutActive` (which includes SetSummary), so no navigation occurs and
// the user remains stuck on the summary card. Subsequent taps succeed guard
// checks (workoutState is still SetSummary) and eventually drive nextStep to
// null, which routes to RoutineComplete -> "End Routine".
//
// Target (with fix): first proceedFromSummary transitions workoutState out of
// SetSummary (e.g. to Idle, so the LaunchedEffect's `!isWorkoutActive` is true)
// and routineFlowState becomes SetReady, causing ActiveWorkoutScreen to
// navigate to the SetReady route.

package com.devil.phoenixproject

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class Issue656ReproTest {

    @Test
    fun proceedFromSummary_unlimitedSummaryDoesNotNavigateOutOfSetSummary() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                weightUnit = WeightUnit.KG,
                weightIncrement = 2.5f,
                summaryCountdownSeconds = 0, // 0 = Unlimited = autoplay OFF
            ),
        )
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val exercise = Exercise(
            id = "bench-press-001",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "Barbell",
        )
        val routine = Routine(
            id = "issue-656-temp-routine",
            name = "Single Exercise: Bench Press",
            exercises = listOf(
                RoutineExercise(
                    id = "issue-656-ex-1",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(8, 8, 8),
                    setRestSeconds = listOf(60, 90, 120), // different rest times per set
                    setWeightsPerCableKg = listOf(20f, 20f, 20f),
                    weightPerCableKg = 20f,
                    programMode = ProgramMode.OldSchool,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        // Simulate finishing set 0: force workoutState into SetSummary.
        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20f,
            avgLoadKgPerCable = 18f,
            repCount = 8,
            workingReps = 8,
            warmupReps = 0,
        )

        // First "Next Set" tap.
        harness.dwsm.proceedFromSummary()
        advanceUntilIdle()

        // State after first tap:
        //   routineFlowState -> SetReady (advance to set 1)
        //   workoutState     -> STILL SetSummary (BUG: should be Idle to allow nav)
        assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)

        val stateAfterFirstTap = harness.dwsm.coordinator.workoutState.value
        // Bug fingerprint: workoutState did not transition out of SetSummary.
        assertNotEquals(
            WorkoutState.SetSummary::class.simpleName,
            stateAfterFirstTap::class.simpleName,
            "After proceedFromSummary with unlimited summary, workoutState must leave SetSummary " +
                "so ActiveWorkoutScreen can navigate to SetReady. Got: ${stateAfterFirstTap::class.simpleName}",
        )

        harness.cleanup()
    }

    @Test
    fun proceedFromSummary_unlimitedSecondTapAdvancesFurtherAndEventuallyCompletes() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                weightUnit = WeightUnit.KG,
                weightIncrement = 2.5f,
                summaryCountdownSeconds = 0, // Unlimited
            ),
        )
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val exercise = Exercise(
            id = "bench-press-001",
            name = "Bench Press",
            muscleGroup = "Chest",
            equipment = "Barbell",
        )
        val routine = Routine(
            id = "issue-656-temp-routine",
            name = "Single Exercise: Bench Press",
            exercises = listOf(
                RoutineExercise(
                    id = "issue-656-ex-1",
                    exercise = exercise,
                    orderIndex = 0,
                    setReps = listOf(8, 8, 8),
                    setRestSeconds = listOf(60, 90, 120),
                    setWeightsPerCableKg = listOf(20f, 20f, 20f),
                    weightPerCableKg = 20f,
                    programMode = ProgramMode.OldSchool,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Place directly at set 2 (third set) with workoutState = SetSummary to mimic the
        // state a stuck UI would reach after multiple ignored Next Set taps.
        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20f,
            avgLoadKgPerCable = 18f,
            repCount = 8,
            workingReps = 8,
            warmupReps = 0,
        )
        // Pretend currentSetIndex is already at 2 (last set).
        harness.dwsm.coordinator._currentSetIndex.value = 2

        // Single tap at last set should drive to RoutineComplete (End Routine) — matches
        // the user's symptom: "after roughly the total number of configured sets of taps
        // the Summary Page changed to End Routine and exited the exercise."
        harness.dwsm.proceedFromSummary()
        advanceUntilIdle()

        assertIs<RoutineFlowState.Complete>(harness.dwsm.coordinator.routineFlowState.value)

        harness.cleanup()
    }
}
