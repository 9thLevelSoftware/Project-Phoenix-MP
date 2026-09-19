package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyPhysicalRelease
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MachineSafetyHazardCoordinatorTest {
    @Test
    fun `loss survives dismissal and cold restore`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val first = coordinator(store)
        assertTrue(first.recordConnectionLost("trainer-1", kind = MachineSafetyWorkoutKind.JUST_LIFT))
        val generation = (first.uiState.value as MachineSafetyUiState.Visible).document.generation
        first.hideTemporarily()

        val second = coordinator(store)
        second.restoreOnStartup()
        val restored = assertIs<MachineSafetyUiState.Visible>(second.uiState.value)
        assertEquals(generation, restored.document.generation)
        assertEquals(MachineSafetyPhase.UNRESOLVED, restored.document.phase)
    }

    @Test
    fun `matching recovery sends stop only and ack does not claim physical unload`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport(connectedTrainerAddress = "trainer-1")
        val coordinator = coordinator(store, transport)
        coordinator.recordConnectionLost("trainer-1")
        coordinator.requestReleaseRecovery()
        advanceUntilIdle()

        val document = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        assertEquals(1, transport.stopCalls)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_SENT, document.phase)
        assertEquals(MachineSafetyPhysicalRelease.UNKNOWN, document.physicalRelease)
        coordinator.acknowledgeUnloaded(document.generation)
        advanceUntilIdle()
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
    }

    @Test
    fun `wrong trainer never sends stop or clears obligation`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport(connectedTrainerAddress = "other")
        val coordinator = coordinator(store, transport)
        coordinator.recordConnectionLost("trainer-1")
        coordinator.requestReleaseRecovery()
        advanceUntilIdle()
        val document = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        assertEquals(0, transport.stopCalls)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_FAILED, document.phase)
        assertTrue(store.rows.isNotEmpty())
    }

    @Test
    fun `machine command obligation blocks later starts until the armed execution is resolved`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertFalse(coordinator.canStartMachine())

        assertTrue(coordinator.resolveArmedExecution(7L))
        assertTrue(store.rows.isEmpty())
        assertTrue(coordinator.canStartMachine())
        assertTrue(coordinator.armBeforeMachineCommand(8L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertEquals(8L, store.rows.getValue("trainer-1").executionId)
    }

    @Test
    fun `unresolved arm row survives a relaunch and cannot be resolved by the new process`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val first = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(first.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        val restored = coordinator(store, FakeSafetyTransport("trainer-1"))
        restored.restoreOnStartup()
        assertIs<MachineSafetyUiState.Visible>(restored.uiState.value)
        assertFalse(restored.resolveArmedExecution(7L))
        assertFalse(restored.canStartMachine())
    }

    @Test
    fun `stale execution id never clears a newer arm`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.armBeforeMachineCommand(8L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        assertFalse(coordinator.resolveArmedExecution(7L))
        assertEquals(8L, store.rows.getValue("trainer-1").executionId)
        assertFalse(coordinator.canStartMachine())
    }

    @Test
    fun `resolve requires the armed trainer to still be connected`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport("trainer-1")
        val coordinator = coordinator(store, transport)
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        transport.connectedTrainerAddress = null
        assertFalse(coordinator.resolveArmedExecution(7L))
        transport.connectedTrainerAddress = "other"
        assertFalse(coordinator.resolveArmedExecution(7L))
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `resolve never clears a recorded connection loss even after it is dismissed`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.recordConnectionLost("trainer-1", executionId = 7L))
        val loss = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document

        assertFalse(coordinator.resolveArmedExecution(7L))
        coordinator.hideTemporarily()
        assertFalse(coordinator.resolveArmedExecution(7L))
        assertEquals(loss.generation, store.rows.getValue("trainer-1").generation)
        assertFalse(coordinator.canStartMachine())
    }

    @Test
    fun `refused start can surface the stored hidden hazard`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        coordinator.surfaceStoredHazard()
        assertEquals(7L, assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document.executionId)
    }

    @Test
    fun `interrupted resume authorization is one shot and never bypasses visible safety recovery`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(9L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        coordinator.authorizeInterruptedWorkoutResume()
        assertTrue(coordinator.canStartMachine())
        assertFalse(coordinator.canStartMachine())

        coordinator.recordConnectionLost("trainer-1")
        coordinator.authorizeInterruptedWorkoutResume()
        assertFalse(coordinator.canStartMachine())
    }

    @Test
    fun `visible loss clears authorization granted before the loss`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(10L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        coordinator.authorizeInterruptedWorkoutResume()
        assertTrue(coordinator.recordConnectionLost("trainer-1"))

        assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertFalse(coordinator.canStartMachine())
    }

    private fun TestScope.coordinator(store: InMemoryMachineSafetyHazardRepository, transport: FakeSafetyTransport = FakeSafetyTransport(null)) =
        MachineSafetyCoordinator(store, transport, CoroutineScope(SupervisorJob() + coroutineContext), { 1000L })

    private class FakeSafetyTransport(override var connectedTrainerAddress: String?) : MachineSafetyTransport {
        var stopCalls = 0
        override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
            connectedTrainerAddress = trainerAddress
            return Result.success(Unit)
        }
        override suspend fun stopWorkout(): Result<Unit> { stopCalls++; return Result.success(Unit) }
    }
}
