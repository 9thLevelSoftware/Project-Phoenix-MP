package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.util.CommandLimits
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KD-9 end to end: a stored routine whose values exceed what this trainer may be commanded
 * to do still STARTS. The command is clamped at resolution time, the frame carries the
 * bounded values, and the user is told what was capped. Stored data is never rewritten,
 * which is what also covers portal pulls, backups and CSV imports (F-020, F-044).
 *
 * Runs on the default harness, so machine arming is ON (PR 1) and [DWSMTestHarness.fakeBleRepo]
 * fails the test on any frame outside [CommandLimits].
 *
 * Scope of the notice assertions: these drive the engine directly, which is the shape of the
 * autoplay-from-rest, recovery-replay and next-exercise start paths. The SetReady and Just Lift
 * paths additionally render a screen; those are covered by the source-wiring tests
 * (`RestTimerProgressionWiringTest`) plus the fact that the notice is held as drainable state
 * on the coordinator rather than emitted into a replay-0 flow.
 */
class StoredRoutineLimitsTest {

    @Test
    fun `a stored routine with 5 kg per rep progression still starts and commands 3`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
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
            val notice = harness.coordinator.commandLimitNotice.value
            assertEquals("Progression capped to 3 kg/rep", notice)

            completeCurrentSet(harness)
            advanceUntilIdle()
            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(40f, saved.weightPerCableKg)
            assertEquals(3f, saved.progressionKg, "history records the progression sent to the trainer")

            // Drainable state, so the screen that shows it can arrive after the send.
            harness.coordinator.consumeCommandLimitNotice()
            assertNull(harness.coordinator.commandLimitNotice.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `a 105 kg per cable routine still starts on a V-Form and commands 100`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRoutine(
                harness,
                model = PhoenixModel.VFormTrainer,
                weightPerCableKg = 105f,
                progressionKg = 0f,
            )

            assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
            val frame = harness.fakeBleRepo.programCommands.single()
            assertEquals(100f, frame.weightPerCableKg, "targetWeight at 0x58")
            // forceMax (0x54) is the firmware's force-limit headroom, deliberately
            // targetWeight + 10 and therefore ABOVE the per-cable ceiling. It is not a
            // commanded load and is the one field the model ceiling does not bound; see the
            // note on OFFSET_FORCE_MAX in BlePacketFactory.
            assertEquals(110f, frame.forceMaxKg, "forceMax is headroom, not a commanded load")

            assertEquals(105f, harness.coordinator.loadedRoutine.value!!.exercises[0].weightPerCableKg)
            assertEquals(
                "Weight capped to 100 kg/cable for this trainer",
                harness.coordinator.commandLimitNotice.value,
            )

            stopCurrentSet(harness)
            advanceUntilIdle()
            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(100f, saved.weightPerCableKg, "manual-stop history records the bounded load")
            assertEquals(105f, harness.coordinator.loadedRoutine.value!!.exercises[0].weightPerCableKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `the same 105 kg per cable routine is commanded in full on a Trainer+`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRoutine(
                harness,
                model = PhoenixModel.TrainerPlus,
                weightPerCableKg = 105f,
                progressionKg = 0f,
            )

            assertEquals(105f, harness.fakeBleRepo.programCommands.single().weightPerCableKg)
            assertNull(harness.coordinator.commandLimitNotice.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Echo keeps original weight and progression metadata because its packet encodes neither`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRoutine(
                harness,
                model = PhoenixModel.VFormTrainer,
                weightPerCableKg = 105f,
                progressionKg = 5f,
                programMode = ProgramMode.Echo,
            )

            stopCurrentSet(harness)
            advanceUntilIdle()

            val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
            assertEquals(105f, saved.weightPerCableKg)
            assertEquals(5f, saved.progressionKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `countdown reconnect resolves limits from the trainer that receives the command`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Trainer_Plus", hardwareModel = PhoenixModel.TrainerPlus)
            val routine = WorkoutStateFixtures.createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 1,
                weightKg = 105f,
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)

            harness.fakeBleRepo.simulateConnect("Vee_Reconnected", hardwareModel = PhoenixModel.VFormTrainer)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(100f, harness.fakeBleRepo.programCommands.single().weightPerCableKg)
            assertEquals(
                "Weight capped to 100 kg/cable for this trainer",
                harness.coordinator.commandLimitNotice.value,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalid stored command is rejected before machine safety is armed`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startRoutine(
                harness,
                model = PhoenixModel.TrainerPlus,
                weightPerCableKg = 25f,
                progressionKg = Float.NaN,
            )

            assertTrue(harness.fakeBleRepo.programCommands.isEmpty())
            assertTrue(harness.machineSafetyStore.rows.isEmpty())
            assertTrue(requireNotNull(harness.machineSafetyCoordinator).canStartMachine())
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
            // Re-armed for set 2 as well, not a stale value left over from set 1.
            assertEquals("Progression capped to 3 kg/rep", harness.coordinator.commandLimitNotice.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `an out of range stored progression does not make set 2 inherit set 1's weight and reps`() = runTest {
        // R-12/R-29: WeightChangePerRepControl used to report its own display clamp through the
        // user-edit handler. On a routine whose stored progression is outside +/-3 that fired on the
        // first composition of the rest card with no user interaction, latching
        // _userAdjustedWeightDuringRest, and the next advance then took currentParams instead of
        // the routine — so set 2 was commanded set 1's weight and reps. The control is display-only
        // now; this pins the consequence at the engine level.
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("VIT_Test")
            val base = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
            val routine = base.copy(
                exercises = base.exercises.map {
                    it.copy(
                        progressionKg = 5f,
                        weightPerCableKg = 40f,
                        setWeightsPerCableKg = listOf(40f, 60f),
                        setReps = listOf(8, 12),
                    )
                },
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertEquals(40f, harness.fakeBleRepo.programCommands.single().weightPerCableKg)
            assertFalse(
                harness.coordinator._userAdjustedWeightDuringRest,
                "nothing the user did should have latched the rest-edit flag",
            )

            completeCurrentSet(harness)
            advanceUntilIdle()

            assertFalse(
                harness.coordinator._userAdjustedWeightDuringRest,
                "a rest period alone must not latch the rest-edit flag",
            )
            assertEquals(
                60f,
                harness.fakeBleRepo.programCommands.last().weightPerCableKg,
                "set 2 must be commanded its own configured weight, not set 1's",
            )
            assertEquals(12, harness.coordinator.workoutParameters.value.reps, "set 2 keeps its own reps")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `live weight adjustment is bounded by the connected model`() = runTest {
        // R-21: adjustWeight's bound moved from a hardcoded 110 to the connected model's ceiling.
        // The fail-closed Unknown case is pinned explicitly so it stays a decision.
        val harness = DWSMTestHarness(this)
        try {
            advanceUntilIdle()
            harness.fakeBleRepo.simulateConnect("VIT_Test")
            harness.activeSessionEngine.adjustWeight(115f, sendToMachine = false)
            assertEquals(110f, harness.coordinator.workoutParameters.value.weightPerCableKg)

            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.activeSessionEngine.adjustWeight(105f, sendToMachine = false)
            assertEquals(100f, harness.coordinator.workoutParameters.value.weightPerCableKg)

            harness.fakeBleRepo.simulateConnect("Mystery", hardwareModel = PhoenixModel.Unknown)
            harness.activeSessionEngine.adjustWeight(105f, sendToMachine = false)
            assertEquals(100f, harness.coordinator.workoutParameters.value.weightPerCableKg)

            harness.fakeBleRepo.simulateDisconnect()
            harness.activeSessionEngine.adjustWeight(105f, sendToMachine = false)
            assertEquals(100f, harness.coordinator.workoutParameters.value.weightPerCableKg)
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.startRoutine(
        harness: DWSMTestHarness,
        model: PhoenixModel,
        weightPerCableKg: Float,
        progressionKg: Float,
        setsPerExercise: Int = 1,
        programMode: ProgramMode? = null,
    ) {
        harness.fakeBleRepo.simulateConnect("Test_Trainer", hardwareModel = model)
        val base = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = setsPerExercise,
            weightKg = weightPerCableKg,
        )
        val routine = base.copy(
            exercises = base.exercises.map {
                it.copy(
                    progressionKg = progressionKg,
                    programMode = programMode ?: it.programMode,
                )
            },
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

    private fun stopCurrentSet(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.dwsm.stopWorkout(exitingWorkout = false)
    }
}
