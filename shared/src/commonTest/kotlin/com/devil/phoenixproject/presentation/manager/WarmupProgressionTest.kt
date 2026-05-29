package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #481: When an exercise has variable warm-up sets AND "weight change per rep"
 * (progression) is configured for the working sets, the warm-up sets must NOT inherit
 * that per-rep progression on the machine — warm-up weight should stay flat.
 *
 * Regression guard: zeroing progression for warm-up must not leak into the working set
 * that follows (the working set must still send the configured progression to the machine).
 */
class WarmupProgressionTest {

    private val workingWeightKg = 40f
    private val warmupPercent = 50
    private val progressionKg = 2f

    /** Read a little-endian float from a byte array at the given offset. */
    private fun readFloatLE(buffer: ByteArray, offset: Int): Float {
        val bits = (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    /** First activation (0x04) packet captured by the fake BLE repo. */
    private fun DWSMTestHarness.firstActivationPacket(): ByteArray =
        fakeBleRepo.commandsReceived.first { it.firstOrNull() == 0x04.toByte() }

    private fun warmupRoutine(): Routine {
        val exercise = RoutineExercise(
            id = "warmup-progression-ex",
            exercise = TestFixtures.benchPress, // equipment "BAR" => cable (non-bodyweight)
            orderIndex = 0,
            setReps = listOf(8),
            weightPerCableKg = workingWeightKg,
            setWeightsPerCableKg = listOf(workingWeightKg),
            programMode = ProgramMode.OldSchool,
            progressionKg = progressionKg,
            warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = warmupPercent)),
        )
        return Routine(
            id = "warmup-progression-routine",
            name = "Warm-up Progression Routine",
            exercises = listOf(exercise),
        )
    }

    @Test
    fun `warm-up set does not send per-rep progression to the machine`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = warmupRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Sanity: routine load should have entered the warm-up phase.
        assertEquals(
            0,
            harness.coordinator.currentWarmupSetIndex.value,
            "Routine with warmupSets should start in warm-up phase",
        )

        harness.fakeBleRepo.commandsReceived.clear()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val warmupPacket = harness.firstActivationPacket()

        // Confirms the warm-up override actually fired (50% of 40kg = 20kg).
        assertEquals(
            workingWeightKg * warmupPercent / 100f,
            readFloatLE(warmupPacket, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT),
            "Warm-up set should send warm-up weight (50% of working)",
        )

        // The bug: warm-up must NOT carry the working-set per-rep progression.
        assertEquals(
            0f,
            readFloatLE(warmupPacket, BleConstants.ActivationPacket.OFFSET_PROGRESSION),
            "Issue #481: warm-up set must send 0 per-rep progression",
        )

        harness.cleanup()
    }

    @Test
    fun `working set after warm-up retains configured per-rep progression`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = warmupRoutine()
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Start (and implicitly send) the single warm-up set.
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertTrue(
            harness.fakeBleRepo.commandsReceived.any { it.firstOrNull() == 0x04.toByte() },
            "Warm-up set should have sent an activation packet",
        )

        // Complete the warm-up set -> transition to the working set.
        harness.fakeBleRepo.commandsReceived.clear()
        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        // Warm-up phase should be over.
        assertEquals(
            -1,
            harness.coordinator.currentWarmupSetIndex.value,
            "Single warm-up set should transition to the working phase",
        )

        val workingPacket = harness.firstActivationPacket()

        // Working weight restored (no warm-up override).
        assertEquals(
            workingWeightKg,
            readFloatLE(workingPacket, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT),
            "Working set should send full working weight",
        )

        // Regression guard: zeroing warm-up progression must not break working-set progression.
        assertEquals(
            progressionKg,
            readFloatLE(workingPacket, BleConstants.ActivationPacket.OFFSET_PROGRESSION),
            "Issue #481: working set must still send configured per-rep progression",
        )

        harness.cleanup()
    }
}
