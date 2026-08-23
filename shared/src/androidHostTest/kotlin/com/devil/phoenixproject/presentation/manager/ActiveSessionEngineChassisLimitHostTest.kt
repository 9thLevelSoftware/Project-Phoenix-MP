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
import kotlinx.coroutines.test.TestScope
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
        assertRejectedConfig(
            deviceName = "Vee_Test",
            expectedModel = PhoenixModel.VFormTrainer,
            weightPerCableKg = 100.5f,
        )
    }

    @Test
    fun `V-Form send site accepts 100 kg CONFIG with forceMax 100`() = runTest {
        val config = assertAcceptedConfig(
            deviceName = "Vee_Test",
            expectedModel = PhoenixModel.VFormTrainer,
            weightPerCableKg = 100f,
        )
        assertEquals(100f, readFloatLE(config, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        assertEquals(100f, readFloatLE(config, BleConstants.ActivationPacket.OFFSET_FORCE_MAX))
    }

    @Test
    fun `Trainer+ send site accepts 100_5 kg CONFIG with forceMax 110`() = runTest {
        val config = assertAcceptedConfig(
            deviceName = "VIT_Test",
            expectedModel = PhoenixModel.TrainerPlus,
            weightPerCableKg = 100.5f,
        )
        assertEquals(100.5f, readFloatLE(config, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        assertEquals(110f, readFloatLE(config, BleConstants.ActivationPacket.OFFSET_FORCE_MAX))
    }

    @Test
    fun `unknown advertised name send site rejects 100_5 kg`() = runTest {
        assertRejectedConfig(
            deviceName = "Phoenix_Test",
            expectedModel = PhoenixModel.Unknown,
            weightPerCableKg = 100.5f,
        )
    }

    private fun TestScope.assertRejectedConfig(
        deviceName: String,
        expectedModel: PhoenixModel,
        weightPerCableKg: Float,
    ) {
        val harness = DWSMTestHarness(this)
        val bleErrors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.bleErrorEvents.collect(bleErrors::add)
        }
        try {
            harness.fakeBleRepo.simulateConnect(deviceName)
            assertEquals(expectedModel, HardwareDetection.detectModel(deviceName))
            startJustLift(harness, weightPerCableKg)
            advanceUntilIdle()
            assertTrue(
                configWrites(harness).isEmpty(),
                "$deviceName must not send CONFIG at ${weightPerCableKg}kg; got ${configWrites(harness).size}",
            )
            assertTrue(
                bleErrors.any { it.contains("Invalid BLE workout command") && it.contains(weightPerCableKg.toString()) },
                "Expected send-site rejection of $weightPerCableKg kg on $deviceName, got $bleErrors",
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.assertAcceptedConfig(
        deviceName: String,
        expectedModel: PhoenixModel,
        weightPerCableKg: Float,
    ): ByteArray {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect(deviceName)
            assertEquals(expectedModel, HardwareDetection.detectModel(deviceName))
            startJustLift(harness, weightPerCableKg)
            advanceUntilIdle()
            val writes = configWrites(harness)
            assertTrue(writes.isNotEmpty(), "$deviceName must send CONFIG at ${weightPerCableKg}kg")
            return writes.first()
        } finally {
            harness.cleanup()
        }
    }

    private fun startJustLift(harness: DWSMTestHarness, weightPerCableKg: Float) {
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = weightPerCableKg,
                isJustLift = true,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
    }

    private fun configWrites(harness: DWSMTestHarness): List<ByteArray> =
        harness.fakeBleRepo.commandsReceived.filter {
            it.isNotEmpty() && it[0] == BleConstants.Commands.ACTIVATION_COMMAND
        }

    private fun readFloatLE(buffer: ByteArray, offset: Int): Float {
        val bits = (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }
}
