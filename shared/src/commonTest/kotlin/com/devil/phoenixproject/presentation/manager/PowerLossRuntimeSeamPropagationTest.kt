package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.powerloss.PowerLossRuntimeGate
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #1392 P5 — propagation wiring (integration-model §4): every generated
 * WorkoutParameters / set-ready transition copies the per-exercise power-loss
 * target config into the `coordinator.powerLossRuntimeSettings` runtime seam.
 * Unsupported timed/bodyweight/TUT paths and unknown persisted target enums
 * resolve to inert REP_TARGET — POWER never arms from an unknown or excluded
 * mode — and the internal capability gate keeps POWER unarmed by default.
 */
class PowerLossRuntimeSeamPropagationTest {

    private val powerConfig = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 25,
        maxWorkingReps = 15,
        requireTwoConsecutive = true,
        savedNominalReps = 12,
    )

    private fun powerExercise(
        id: String,
        orderIndex: Int = 0,
        sets: List<Int?> = listOf(12, 10),
        mode: ProgramMode = ProgramMode.OldSchool,
        config: PowerLossConfig = powerConfig,
        duration: Int? = null,
        bodyweight: Boolean = false,
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = TestFixtures.benchPress.copy(
            id = "ex-$id",
            isBodyweightOverride = if (bodyweight) true else null,
        ),
        orderIndex = orderIndex,
        setReps = sets,
        weightPerCableKg = 30f,
        programMode = mode,
        duration = duration,
        powerLoss = config,
    )

    private fun routineOf(vararg exercises: RoutineExercise): Routine = Routine(
        id = "routine-power-loss",
        name = "Power Loss Propagation",
        exercises = exercises.toList(),
    )

    // ── Routine path: initial, next set/exercise, adjusted/retry, transition ──

    @Test
    fun `routine initial load publishes the first exercise power-loss config to the seam`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-1"))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val settings = harness.dwsm.powerLossRuntimeSettings.value
        assertEquals(TargetMode.POWER, settings.targetMode)
        assertEquals(25, settings.thresholdPercent)
        assertEquals(15, settings.maxWorkingReps)
        assertTrue(settings.requireTwoConsecutive)
        assertEquals(12, settings.savedNominalReps)
        assertEquals(PowerLossProtocolFamily.OLD_SCHOOL, settings.protocolFamily)
        harness.cleanup()
    }

    @Test
    fun `next set and next exercise paths republish the per-exercise config`() = runTest {
        val harness = DWSMTestHarness(this)
        val secondConfig = powerConfig.copy(thresholdPercent = 30, maxWorkingReps = 18)
        val routine = routineOf(
            powerExercise("re-1", orderIndex = 0),
            powerExercise("re-2", orderIndex = 1, config = secondConfig),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Next set on the same exercise: the set's nominal reps follow the seam.
        harness.dwsm.enterSetReady(0, 1)
        advanceUntilIdle()
        assertEquals(10, harness.dwsm.powerLossRuntimeSettings.value.savedNominalReps)
        assertEquals(25, harness.dwsm.powerLossRuntimeSettings.value.thresholdPercent)

        // Next exercise: the second exercise's own config replaces it.
        harness.dwsm.enterSetReady(1, 0)
        advanceUntilIdle()
        val settings = harness.dwsm.powerLossRuntimeSettings.value
        assertEquals(30, settings.thresholdPercent)
        assertEquals(18, settings.maxWorkingReps)
        assertEquals(12, settings.savedNominalReps)
        harness.cleanup()
    }

    @Test
    fun `adjusted retry path republishes with the adjusted nominal reps`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-1"))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.routineFlowManager.enterSetReadyWithAdjustments(
            exerciseIndex = 0,
            setIndex = 0,
            adjustedWeight = 32.5f,
            adjustedReps = 8,
        )
        advanceUntilIdle()

        val settings = harness.dwsm.powerLossRuntimeSettings.value
        assertEquals(TargetMode.POWER, settings.targetMode)
        assertEquals(8, settings.savedNominalReps)
        harness.cleanup()
    }

    @Test
    fun `set-ready rep edit transition follows the nominal target`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-1"))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        harness.dwsm.updateSetReadyReps(15)
        advanceUntilIdle()

        assertEquals(15, harness.dwsm.powerLossRuntimeSettings.value.savedNominalReps)
        harness.cleanup()
    }

    // ── Ad-hoc path: parameter boundary (MainViewModel / JustLift) ────────────

    @Test
    fun `ad-hoc parameter updates publish the power-loss config to the seam`() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        harness.dwsm.updateWorkoutParameters(
            harness.dwsm.coordinator.workoutParameters.value.copy(
                programMode = ProgramMode.OldSchool,
                isJustLift = true,
                reps = 0,
                powerLoss = powerConfig,
            ),
        )
        advanceUntilIdle()

        val settings = harness.dwsm.powerLossRuntimeSettings.value
        assertEquals(TargetMode.POWER, settings.targetMode)
        assertEquals(PowerLossProtocolFamily.JUST_LIFT, settings.protocolFamily)
        assertEquals(12, settings.savedNominalReps)
        harness.cleanup()
    }

    @Test
    fun `ad-hoc rep target parameters keep the seam inert`() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        harness.dwsm.updateWorkoutParameters(
            harness.dwsm.coordinator.workoutParameters.value.copy(
                programMode = ProgramMode.Pump,
                reps = 10,
                powerLoss = PowerLossConfig.DEFAULT,
            ),
        )
        advanceUntilIdle()

        assertEquals(TargetMode.REP_TARGET, harness.dwsm.powerLossRuntimeSettings.value.targetMode)
        harness.cleanup()
    }

    // ── Excluded paths resolve inert ──────────────────────────────────────────

    @Test
    fun `timed routine exercises resolve the power-loss seam to inert rep target`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-timed", duration = 30))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        assertEquals(TargetMode.REP_TARGET, harness.dwsm.powerLossRuntimeSettings.value.targetMode)
        harness.cleanup()
    }

    @Test
    fun `bodyweight routine exercises resolve the power-loss seam to inert rep target`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-bw", bodyweight = true))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        assertEquals(TargetMode.REP_TARGET, harness.dwsm.powerLossRuntimeSettings.value.targetMode)
        harness.cleanup()
    }

    @Test
    fun `tut mode resolves the power-loss seam to inert rep target even with power config`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-tut", mode = ProgramMode.TUT))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        assertEquals(TargetMode.REP_TARGET, harness.dwsm.powerLossRuntimeSettings.value.targetMode)
        harness.cleanup()
    }

    // ── Capability gate and persisted-enum strictness ─────────────────────────

    @Test
    fun `power stays unarmed behind the capability gate and arms only when validated`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = routineOf(powerExercise("re-1"))
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        try {
            val gated = harness.dwsm.powerLossRuntimeSettings.value
            assertEquals(TargetMode.POWER, gated.targetMode, "Config intent is retained")
            assertFalse(gated.powerActive, "POWER never arms without validated paired samples")

            PowerLossRuntimeGate.pairedSamplesValidated = true
            harness.dwsm.publishPowerLossRuntimeSettingsFrom(harness.dwsm.coordinator.workoutParameters.value)
            val armed = harness.dwsm.powerLossRuntimeSettings.value
            assertEquals(TargetMode.POWER, armed.targetMode)
            assertTrue(armed.powerActive, "Validated paired samples arm the retained POWER intent")
        } finally {
            PowerLossRuntimeGate.pairedSamplesValidated = false
        }
        harness.cleanup()
    }

    @Test
    fun `runtime snapshot round-trips the frozen power-loss config`() = runTest {
        val harness = DWSMTestHarness(this)
        val params = harness.dwsm.coordinator.workoutParameters.value.copy(
            programMode = ProgramMode.Echo,
            reps = 12,
            powerLoss = powerConfig,
        )

        val restored = RestoredWorkoutCommandTemplateSnapshot.from(params).toWorkoutParameters()
        assertEquals(TargetMode.POWER, restored.powerLoss.targetMode)
        assertEquals(25, restored.powerLoss.thresholdPercent)
        assertEquals(15, restored.powerLoss.maxWorkingReps)
        assertTrue(restored.powerLoss.requireTwoConsecutive)
        assertEquals(12, restored.powerLoss.savedNominalReps)
        harness.cleanup()
    }

    @Test
    fun `unknown persisted target enum resolves to inert rep target on restore`() = runTest {
        val harness = DWSMTestHarness(this)
        val params = harness.dwsm.coordinator.workoutParameters.value.copy(
            reps = 12,
            powerLoss = powerConfig,
        )
        val snapshot = RestoredWorkoutCommandTemplateSnapshot.from(params)
            .copy(powerLossTargetModeName = "NOT_A_TARGET_MODE")

        val restored = snapshot.toWorkoutParameters()
        assertEquals(TargetMode.REP_TARGET, restored.powerLoss.targetMode)
        assertEquals(25, restored.powerLoss.thresholdPercent, "Remaining persisted values are preserved")
        harness.cleanup()
    }
}
