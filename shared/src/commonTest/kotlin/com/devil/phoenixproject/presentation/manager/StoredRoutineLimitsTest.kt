package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.util.CommandLimits
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KD-9 end to end: a stored routine whose values exceed what this trainer may be commanded
 * to do still STARTS. The command is clamped at resolution time, the frame carries the
 * bounded values, and the user is told what was capped. Stored data is never rewritten,
 * which is what also covers portal pulls, backups and CSV imports (F-020, F-044).
 *
 * Runs on the default harness, so machine arming is ON (PR 1) and [DWSMTestHarness.fakeBleRepo]
 * fails the test on any frame outside [CommandLimits].
 */
class StoredRoutineLimitsTest {

    @Test
    fun `a stored routine with 5 kg per rep progression still starts and commands 3`() = runTest {
        val harness = DWSMTestHarness(this)
        val feedback = mutableListOf<String>()
        try {
            collectFeedback(harness, feedback)
            startRoutine(
                harness,
                model = PhoenixModel.TrainerPlus,
                weightPerCableKg = 40f,
                progressionKg = 5f,
            )

            assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
            val frame = harness.fakeBleRepo.programCommands.single()
            assertEquals(3f, frame.progressionKg, "progression at 0x5C")
            assertEquals(40f, frame.weightPerCableKg, "targetWeight at 0x58")

            // The stored routine is untouched: only the command was bounded.
            assertEquals(5f, harness.coordinator.loadedRoutine.value!!.exercises[0].progressionKg)
            assertTrue(
                feedback.any { it.contains("Progression capped to 3 kg/rep") },
                "expected a capped notice, got $feedback",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `a 105 kg per cable routine still starts on a V-Form and commands 100`() = runTest {
        val harness = DWSMTestHarness(this)
        val feedback = mutableListOf<String>()
        try {
            collectFeedback(harness, feedback)
            startRoutine(
                harness,
                model = PhoenixModel.VFormTrainer,
                weightPerCableKg = 105f,
                progressionKg = 0f,
            )

            assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
            val frame = harness.fakeBleRepo.programCommands.single()
            assertEquals(100f, frame.weightPerCableKg, "targetWeight at 0x58")
            assertEquals(110f, frame.forceMaxKg, "forceMax follows the capped weight")

            assertEquals(105f, harness.coordinator.loadedRoutine.value!!.exercises[0].weightPerCableKg)
            assertTrue(
                feedback.any { it.contains("Weight capped to 100 kg/cable") },
                "expected a capped notice, got $feedback",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `the same 105 kg per cable routine is commanded in full on a Trainer+`() = runTest {
        val harness = DWSMTestHarness(this)
        val feedback = mutableListOf<String>()
        try {
            collectFeedback(harness, feedback)
            startRoutine(
                harness,
                model = PhoenixModel.TrainerPlus,
                weightPerCableKg = 105f,
                progressionKg = 0f,
            )

            assertEquals(105f, harness.fakeBleRepo.programCommands.single().weightPerCableKg)
            assertTrue(feedback.none { it.contains("capped") }, "unexpected notice: $feedback")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `set 1 and set 2 of the same exercise send the same progression`() = runTest {
        // F-020: set 1 used the raw stored value while every later set was clamped, so the
        // same routine exercise commanded +5kg/rep then +3kg/rep without anyone choosing it.
        val harness = DWSMTestHarness(this)
        try {
            startRoutine(
                harness,
                model = PhoenixModel.TrainerPlus,
                weightPerCableKg = 40f,
                progressionKg = 5f,
                setsPerExercise = 2,
            )
            assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)

            completeCurrentSet(harness)
            advanceUntilIdle()
            assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
            assertEquals(1, harness.coordinator.currentSetIndex.value)

            val progressions = harness.fakeBleRepo.programCommands.map { it.progressionKg }
            assertEquals(listOf(3f, 3f), progressions, "set 1 and set 2 must command the same ramp")
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.collectFeedback(harness: DWSMTestHarness, into: MutableList<String>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.userFeedbackEvents.collect(into::add)
        }
    }

    private fun TestScope.startRoutine(
        harness: DWSMTestHarness,
        model: PhoenixModel,
        weightPerCableKg: Float,
        progressionKg: Float,
        setsPerExercise: Int = 1,
    ) {
        harness.fakeBleRepo.simulateConnect("Test_Trainer", hardwareModel = model)
        val base = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = setsPerExercise,
            weightKg = weightPerCableKg,
        )
        val routine = base.copy(
            exercises = base.exercises.map { it.copy(progressionKg = progressionKg) },
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
    }

    private fun TestScope.completeCurrentSet(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        runCurrent()
    }
}
