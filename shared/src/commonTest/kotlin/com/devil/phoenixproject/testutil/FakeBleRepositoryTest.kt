package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.CommandLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakeBleRepositoryTest {

    @Test
    fun `simulateDisconnect clears diagnostics`() {
        val repository = FakeBleRepository()
        repository.setDiagnostics(testDiagnostics())

        repository.simulateDisconnect()

        assertNull(repository.diagnostics.value)
    }

    @Test
    fun `disconnect APIs clear diagnostics`() = runTest {
        val repository = FakeBleRepository()
        repository.setDiagnostics(testDiagnostics())

        repository.disconnect()
        assertNull(repository.diagnostics.value)

        repository.setDiagnostics(testDiagnostics())
        repository.cancelConnection()
        assertNull(repository.diagnostics.value)
    }

    @Test
    fun `connect resolves hardwareModel from device name and enforces VForm 100kg ceiling`() = runTest {
        val repository = FakeBleRepository()
        val overVFormFrame = BlePacketFactory.createProgramParams(
            params = WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                weightPerCableKg = 105f,
            ),
            maxWeightPerCableKg = CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG,
        )

        val vFormConnect = repository.connect(ScannedDevice("Vee_VForm", "AA:BB:CC:DD:EE:FF", -55))
        assertTrue(vFormConnect.isSuccess)
        assertEquals(PhoenixModel.VFormTrainer, repository.model)
        assertEquals(
            PhoenixModel.VFormTrainer,
            (repository.connectionState.value as ConnectionState.Connected).hardwareModel,
        )
        assertFailsWith<AssertionError> {
            repository.sendWorkoutCommand(overVFormFrame)
        }

        val trainerPlusConnect = repository.connect(ScannedDevice("VIT_TrainerPlus", "11:22:33:44:55:66", -50))
        assertTrue(trainerPlusConnect.isSuccess)
        assertEquals(PhoenixModel.TrainerPlus, repository.model)
        assertEquals(
            PhoenixModel.TrainerPlus,
            (repository.connectionState.value as ConnectionState.Connected).hardwareModel,
        )
        assertTrue(repository.sendWorkoutCommand(overVFormFrame).isSuccess)

        val unknownConnect = repository.connect(ScannedDevice("Unknown_Unit", "22:33:44:55:66:77", -60))
        assertTrue(unknownConnect.isSuccess)
        assertEquals(PhoenixModel.Unknown, repository.model)
        assertEquals(
            PhoenixModel.Unknown,
            (repository.connectionState.value as ConnectionState.Connected).hardwareModel,
        )
        assertFailsWith<AssertionError> {
            repository.sendWorkoutCommand(overVFormFrame)
        }

        val scanConnect = repository.scanAndConnect(timeoutMs = 1_000L)
        assertTrue(scanConnect.isSuccess)
        assertEquals(PhoenixModel.VFormTrainer, repository.model)
        assertEquals(
            PhoenixModel.VFormTrainer,
            (repository.connectionState.value as ConnectionState.Connected).hardwareModel,
        )
    }

    private fun testDiagnostics(): DiagnosticPacket = DiagnosticPacket(
        runtimeSeconds = 1L,
        faultWords = listOf(1, 0, 0, 0),
        temperatures = listOf(20, 21, 22, 23, 24, 25),
        hasFaults = true,
    )
}
