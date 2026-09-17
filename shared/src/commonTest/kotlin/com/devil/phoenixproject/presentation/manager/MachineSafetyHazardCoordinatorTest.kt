package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyHazardRepository
import com.devil.phoenixproject.data.repository.MachineSafetyLoadResult
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyPhysicalRelease
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
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
        val store = FakeHazardStore()
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
        val store = FakeHazardStore()
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
        val store = FakeHazardStore()
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
    fun `machine command obligation blocks every later machine start until physical acknowledgement`() = runTest {
        val store = FakeHazardStore()
        val transport = FakeSafetyTransport("trainer-1")
        val first = coordinator(store, transport)
        assertTrue(first.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertEquals(MachineSafetyUiState.Hidden, first.uiState.value)
        assertFalse(first.canStartMachine())
        first.hideTemporarily()
        assertFalse(first.canStartMachine())

        val restored = coordinator(store, FakeSafetyTransport("trainer-1"))
        restored.restoreOnStartup()
        assertFalse(restored.canStartMachine())
    }

    @Test
    fun `interrupted resume authorization is one shot and never bypasses visible safety recovery`() = runTest {
        val store = FakeHazardStore()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(9L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        coordinator.authorizeInterruptedWorkoutResume()
        assertTrue(coordinator.canStartMachine())
        assertFalse(coordinator.canStartMachine())

        coordinator.recordConnectionLost("trainer-1")
        coordinator.authorizeInterruptedWorkoutResume()
        assertFalse(coordinator.canStartMachine())
    }

    private fun TestScope.coordinator(store: FakeHazardStore, transport: FakeSafetyTransport = FakeSafetyTransport(null)) =
        MachineSafetyCoordinator(store, transport, CoroutineScope(SupervisorJob() + coroutineContext), { 1000L })

    private class FakeHazardStore : MachineSafetyHazardRepository {
        val rows = linkedMapOf<String, MachineSafetyHazardDocument>()
        override suspend fun load(trainerAddress: String) = rows[trainerAddress]?.let(MachineSafetyLoadResult::Loaded) ?: MachineSafetyLoadResult.Missing
        override suspend fun loadAll() = rows.values.map(MachineSafetyLoadResult::Loaded)
        override suspend fun replace(document: MachineSafetyHazardDocument) { rows[document.trainerAddress] = document }
        override suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean =
            rows[trainerAddress]?.takeIf { it.generation == generation }?.let { rows.remove(trainerAddress); true } ?: false
    }

    private class FakeSafetyTransport(override var connectedTrainerAddress: String?) : MachineSafetyTransport {
        var stopCalls = 0
        override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
            connectedTrainerAddress = trainerAddress
            return Result.success(Unit)
        }
        override suspend fun stopWorkout(): Result<Unit> { stopCalls++; return Result.success(Unit) }
    }
}
