package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class DWSMEquipmentRackTest {
    @Test
    fun `selected counterweight adjusts non Echo set start packet without changing programmed weight`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT)),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )
        harness.dwsm.updateActiveRackSelection(listOf("assist"))

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val command = harness.fakeBleRepo.commandsReceived.single()
        assertEquals(30f, readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        assertEquals(40f, harness.dwsm.coordinator.workoutParameters.value.weightPerCableKg)
        harness.cleanup()
    }

    @Test
    fun `active rack edits during active set do not send mid set command and saved session uses set start snapshot`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeEquipmentRackRepo.saveItems(
            listOf(
                rackItem("assist", 10f, RackItemBehavior.COUNTERWEIGHT),
                rackItem("vest", 20f, RackItemBehavior.ADDED_RESISTANCE),
            ),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )
        harness.dwsm.updateActiveRackSelection(listOf("assist"))
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        val commandsAfterStart = harness.fakeBleRepo.commandsReceived.size

        harness.dwsm.updateActiveRackSelection(listOf("vest"))
        advanceUntilIdle()

        assertEquals(commandsAfterStart, harness.fakeBleRepo.commandsReceived.size)

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 40f,
                loadB = 40f,
                positionA = 100f,
                positionB = 100f,
                velocityA = 50.0,
                velocityB = 50.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(40f, session.weightPerCableKg)
        assertEquals(0f, session.externalAddedLoadKg)
        assertEquals(10f, session.counterweightKg)
        assertTrue(session.rackItemsJson.contains("assist"))
        assertTrue(!session.rackItemsJson.contains("vest"))
        harness.cleanup()
    }

    @Test
    fun `no rack workout sends programmed packet unchanged`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 40f,
            ),
        )

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        val command = harness.fakeBleRepo.commandsReceived.single()
        assertEquals(40f, readFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        harness.cleanup()
    }

    private fun rackItem(id: String, weightKg: Float, behavior: RackItemBehavior): RackItem = RackItem(
        id = id,
        name = id,
        category = RackItemCategory.OTHER,
        weightKg = weightKg,
        behavior = behavior,
    )

    private fun readFloatLE(packet: ByteArray, offset: Int): Float {
        val bits = (packet[offset].toInt() and 0xFF) or
            ((packet[offset + 1].toInt() and 0xFF) shl 8) or
            ((packet[offset + 2].toInt() and 0xFF) shl 16) or
            ((packet[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }
}
