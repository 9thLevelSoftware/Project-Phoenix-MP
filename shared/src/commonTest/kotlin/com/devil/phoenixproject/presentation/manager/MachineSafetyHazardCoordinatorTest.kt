package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyPhysicalRelease
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineSafetyHazardCoordinatorTest {
    @Test
    fun `unexpected idle disconnect without a durable execution is ignored`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport(null))

        assertFalse(coordinator.recordUnexpectedDisconnect("trainer-1", trainerName = "Vee_Test"))
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `unexpected disconnect surfaces the exact durable armed execution`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        val armed = store.rows.getValue("trainer-1")

        assertTrue(coordinator.recordUnexpectedDisconnect("trainer-1", trainerName = "Vee_Test"))

        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertEquals(armed.generation, visible.document.generation)
        assertEquals(armed.executionId, visible.document.executionId)
        assertEquals("Vee_Test", visible.document.trainerName)
    }

    @Test
    fun `hidden recovery reports that no visible hazard was requested`() = runTest {
        val coordinator = coordinator(InMemoryMachineSafetyHazardRepository())

        assertEquals(
            MachineSafetyRecoveryRequestResult.NO_VISIBLE_HAZARD,
            coordinator.requestReleaseRecovery(MachineSafetyHazardIdentity("trainer-1", 1L)),
        )
    }

    @Test
    fun `stale acknowledgement identity cannot hide a newer trainer hazard`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val firstCoordinator = coordinator(store)
        assertTrue(firstCoordinator.recordTestConnectionLost("trainer-1"))
        val first = assertIs<MachineSafetyUiState.Visible>(firstCoordinator.uiState.value).document
        val second = first.copy(trainerAddress = "trainer-2", sessionId = "second-session")
        store.rows.clear()
        store.rows[second.trainerAddress] = second
        val coordinator = coordinator(store)
        coordinator.restoreOnStartup()

        coordinator.acknowledgeUnloaded(MachineSafetyHazardIdentity(first.trainerAddress, first.generation))
        advanceUntilIdle()

        val stillVisible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertEquals(second.trainerAddress, stillVisible.document.trainerAddress)
        assertEquals(second.generation, stillVisible.document.generation)
        assertTrue(store.rows.containsKey("trainer-2"))
    }

    @Test
    fun `stale recovery identity cannot control a different trainer at the same generation`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport("trainer-2")
        val coordinator = coordinator(store, transport)
        assertTrue(coordinator.recordTestConnectionLost("trainer-2"))
        val current = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)

        assertEquals(
            MachineSafetyRecoveryRequestResult.STALE_HAZARD,
            coordinator.requestReleaseRecovery(
                MachineSafetyHazardIdentity("trainer-1", current.document.generation),
            ),
        )

        assertEquals(0, transport.stopCalls)
        assertEquals(current, coordinator.uiState.value)
        assertTrue(store.rows.containsKey("trainer-2"))
    }

    @Test
    fun `acknowledgement storage failure stays visible and fails closed`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store)
        assertTrue(coordinator.recordTestConnectionLost("trainer-1"))
        val document = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        store.deleteFailure = IllegalStateException("disk unavailable")

        coordinator.acknowledgeUnloaded(MachineSafetyHazardIdentity(document.trainerAddress, document.generation))
        advanceUntilIdle()

        assertEquals(document, assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document)
        assertFalse(coordinator.canStartMachine())
    }

    @Test
    fun `acknowledgement waits for recovery to quiesce before clearing the start gate`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport("trainer-1")
        val coordinator = coordinator(store, transport)
        assertTrue(coordinator.recordTestConnectionLost("trainer-1"))
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        val stopStarted = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        transport.stopBlock = {
            stopStarted.complete(Unit)
            withContext(NonCancellable) { releaseStop.await() }
            Result.success(Unit)
        }

        assertEquals(
            MachineSafetyRecoveryRequestResult.STARTED,
            coordinator.requestReleaseRecovery(visible.identity),
        )
        stopStarted.await()
        val acknowledgement = launch { coordinator.acknowledgeUnloaded(visible.identity) }
        runCurrent()

        assertFalse(acknowledgement.isCompleted)
        assertFalse(coordinator.canStartMachine())
        assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)

        releaseStop.complete(Unit)
        acknowledgement.join()
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(coordinator.canStartMachine())
        assertEquals(1, transport.stopCalls)
    }

    @Test
    fun `loss survives dismissal and cold restore`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val first = coordinator(store)
        assertTrue(first.recordTestConnectionLost("trainer-1", kind = MachineSafetyWorkoutKind.JUST_LIFT))
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
        coordinator.recordTestConnectionLost("trainer-1")
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        coordinator.requestReleaseRecovery(visible.identity)
        advanceUntilIdle()

        val document = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        assertEquals(1, transport.stopCalls)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_SENT, document.phase)
        assertEquals(MachineSafetyPhysicalRelease.UNKNOWN, document.physicalRelease)
        coordinator.acknowledgeUnloaded(MachineSafetyHazardIdentity(document.trainerAddress, document.generation))
        advanceUntilIdle()
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
    }

    @Test
    fun `wrong trainer never sends stop or clears obligation`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val transport = FakeSafetyTransport(connectedTrainerAddress = "other")
        val coordinator = coordinator(store, transport)
        coordinator.recordTestConnectionLost("trainer-1")
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        coordinator.requestReleaseRecovery(visible.identity)
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
    fun `direct resolve never clears a recorded connection loss even after it is dismissed`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.recordTestConnectionLost("trainer-1", executionId = 7L))
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
        // Once shown, the row needs explicit acknowledgement; a clean teardown can't clear it.
        assertFalse(coordinator.resolveArmedExecution(7L))
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `refusal caused only by the live set's own arm row is not surfaced`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        coordinator.surfaceStoredHazard(liveExecutionId = 7L)
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(coordinator.resolveArmedExecution(7L))

        assertTrue(coordinator.armBeforeMachineCommand(8L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        coordinator.surfaceStoredHazard(liveExecutionId = 7L)
        assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
    }

    @Test
    fun `surfacing never replaces a warning that is already visible`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.recordTestConnectionLost("trainer-1"))
        val shown = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        store.rows["trainer-1"] = shown.copy(phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED)

        coordinator.surfaceStoredHazard()
        assertEquals(shown, assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document)
    }

    @Test
    fun `start gate waits for an in-flight resolve instead of reading the row it is clearing`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        val gate = CompletableDeferred<Unit>()
        store.beforeDelete = { gate.await() }

        val resolve = launch { assertTrue(coordinator.resolveArmedExecution(7L)) }
        runCurrent()
        var canStart: Boolean? = null
        launch { canStart = coordinator.canStartMachine() }
        runCurrent()
        assertNull(canStart, "the gate must wait for the resolve holding the row")

        gate.complete(Unit)
        advanceUntilIdle()
        resolve.join()
        assertEquals(true, canStart)
    }

    @Test
    fun `a loss on another trainer keeps this trainer's arm resolvable`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.armBeforeMachineCommand(7L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.recordTestConnectionLost("trainer-2"))
        coordinator.hideTemporarily()

        assertTrue(coordinator.resolveArmedExecution(7L))
        assertEquals(setOf("trainer-2"), store.rows.keys)
    }

    @Test
    fun `acknowledged loss row allows the next start`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(coordinator.recordTestConnectionLost("trainer-1"))
        assertFalse(coordinator.canStartMachine())

        coordinator.acknowledgeUnloaded(assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).identity)
        advanceUntilIdle()
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(store.rows.isEmpty())
        assertTrue(coordinator.canStartMachine())
    }

    private fun TestScope.coordinator(store: InMemoryMachineSafetyHazardRepository, transport: FakeSafetyTransport = FakeSafetyTransport(null)) =
        MachineSafetyCoordinator(store, transport, CoroutineScope(SupervisorJob() + coroutineContext), { 1000L })

    private suspend fun MachineSafetyCoordinator.recordTestConnectionLost(
        trainerAddress: String,
        trainerName: String? = null,
        kind: MachineSafetyWorkoutKind = MachineSafetyWorkoutKind.UNKNOWN,
        executionId: Long? = null,
    ): Boolean = recordMachineSessionArmed(
        MachineSafetyHazardDocument(
            generation = 1L,
            trainerAddress = trainerAddress,
            trainerName = trainerName,
            sessionId = "test-loss-$trainerAddress",
            executionId = executionId,
            workoutKind = kind,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L,
            phase = MachineSafetyPhase.UNRESOLVED,
            physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
        ),
        showRecoveryUi = true,
    )

    private class FakeSafetyTransport(override var connectedTrainerAddress: String?) : MachineSafetyTransport {
        var stopCalls = 0
        var stopBlock: suspend () -> Result<Unit> = { Result.success(Unit) }
        override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
            connectedTrainerAddress = trainerAddress
            return Result.success(Unit)
        }
        override suspend fun stopWorkout(): Result<Unit> {
            stopCalls++
            return stopBlock()
        }
    }
}
