package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.HardwareDetection
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Send-site host tests: ASE must reject over-chassis CONFIG weights before BLE write.
 * Echo 0x4E does not carry kg; only 0x04 CONFIG floats are chassis-clamped.
 */
class ActiveSessionEngineChassisLimitHostTest {

    @Test
    fun `V-Form send site rejects 100_5 kg and does not write CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        val bleErrors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.bleErrorEvents.collect(bleErrors::add)
        }
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            assertEquals(
                PhoenixModel.VFormTrainer,
                HardwareDetection.detectModel("Vee_Test"),
            )

            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 100.5f,
                    isJustLift = true,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()

            val configWrites = harness.fakeBleRepo.commandsReceived.filter {
                it.isNotEmpty() && it[0] == BleConstants.Commands.ACTIVATION_COMMAND
            }
            assertTrue(
                configWrites.isEmpty(),
                "V-Form must not send CONFIG at 100.5 kg/cable; got ${configWrites.size} writes",
            )
            assertTrue(
                bleErrors.any { it.contains("Invalid BLE workout command") && it.contains("100.5") },
                "Expected send-site rejection of 100.5 kg on V-Form, got $bleErrors",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Trainer+ send site accepts 100_5 kg CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("VIT_Test")
            assertEquals(PhoenixModel.TrainerPlus, HardwareDetection.detectModel("VIT_Test"))

            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 100.5f,
                    isJustLift = true,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()

            val configWrites = harness.fakeBleRepo.commandsReceived.filter {
                it.isNotEmpty() && it[0] == BleConstants.Commands.ACTIVATION_COMMAND
            }
            assertTrue(configWrites.isNotEmpty(), "Trainer+ must send CONFIG at 100.5 kg/cable")
        } finally {
            harness.cleanup()
        }
    }
}
