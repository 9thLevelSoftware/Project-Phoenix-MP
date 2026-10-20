package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.local.ConnectionLogEntity
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.MachineSafetyHazardCause
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The connection-log export is the only diagnostic users can send. Every barrier decision must
 * appear there (a refused start previously logged only a bare START_FAILED), and each record must
 * carry why it exists so the warning can be worded by cause.
 */
class MachineSafetyHazardLoggingTest {
    @Test
    fun `armed set carries the set-end-unconfirmed cause and a loss rewrites it as connection lost`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"))

        assertTrue(coordinator.armBeforeMachineCommand(3L, "profile", MachineSafetyWorkoutKind.JUST_LIFT))
        assertEquals(MachineSafetyHazardCause.SET_END_UNCONFIRMED, store.rows.getValue("trainer-1").cause)

        assertTrue(coordinator.recordUnexpectedDisconnect("trainer-1", trainerName = "Vee_Test"))
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertEquals(MachineSafetyHazardCause.CONNECTION_LOST, visible.document.cause)
        assertEquals(MachineSafetyHazardCause.CONNECTION_LOST, store.rows.getValue("trainer-1").cause)
    }

    @Test
    fun `leftover arm row surfaced by a refused start keeps its set-end cause`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val first = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertTrue(first.armBeforeMachineCommand(3L, "profile", MachineSafetyWorkoutKind.ROUTINE))

        // New process: the hidden arm row was never resolved.
        val second = coordinator(store, FakeSafetyTransport("trainer-1"))
        assertFalse(second.canStartMachine())
        second.surfaceStoredHazard()

        val visible = assertIs<MachineSafetyUiState.Visible>(second.uiState.value)
        assertEquals(MachineSafetyHazardCause.SET_END_UNCONFIRMED, visible.document.cause)
    }

    @Test
    fun `refused start, hide, and acknowledgement are written to the connection log`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val logs = ConnectionLogRepository()
        val coordinator = coordinator(store, FakeSafetyTransport("trainer-1"), logs)
        assertTrue(coordinator.armBeforeMachineCommand(3L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.recordUnexpectedDisconnect("trainer-1", trainerName = "Vee_Test"))
        coordinator.hideTemporarily()

        assertFalse(coordinator.canStartMachine())
        coordinator.surfaceStoredHazard()
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        coordinator.acknowledgeUnloaded(visible.identity)
        advanceUntilIdle()

        val safety = logs.safetyEntries()
        assertTrue(safety.all { it.deviceAddress == "trainer-1" }, "every entry names the trainer: $safety")
        assertTrue(safety.any { it.message.startsWith("Machine set armed") })
        assertTrue(safety.any { it.message.startsWith("Connection lost with a machine obligation pending") })
        assertTrue(safety.any { it.message.startsWith("Machine-safety warning hidden") })
        val refused = safety.single { it.message.startsWith("Machine start refused") }
        assertEquals("WARNING", refused.level)
        assertTrue(refused.details.orEmpty().contains("cause=CONNECTION_LOST"), refused.details.orEmpty())
        assertTrue(refused.details.orEmpty().contains("storedRows=1"), refused.details.orEmpty())
        assertTrue(safety.any { it.message.startsWith("Stored machine-safety hazard shown") })
        assertTrue(safety.any { it.message.startsWith("User confirmed handles at rest") })
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
    }

    @Test
    fun `release recovery outcomes are written to the connection log`() = runTest {
        val store = InMemoryMachineSafetyHazardRepository()
        val logs = ConnectionLogRepository()
        val transport = FakeSafetyTransport("trainer-1")
        val coordinator = coordinator(store, transport, logs)
        assertTrue(coordinator.armBeforeMachineCommand(3L, "profile", MachineSafetyWorkoutKind.ROUTINE))
        assertTrue(coordinator.recordUnexpectedDisconnect("trainer-1"))
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)

        coordinator.requestReleaseRecovery(visible.identity)
        advanceUntilIdle()

        val document = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value).document
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_SENT, document.phase)
        val safety = logs.safetyEntries()
        assertTrue(safety.any { it.message.startsWith("Release recovery requested") })
        val sent = safety.single { it.message.startsWith("Stop requested over Bluetooth") }
        assertTrue(sent.details.orEmpty().contains("phase=RELEASE_REQUEST_SENT"), sent.details.orEmpty())

        transport.connectedTrainerAddress = "other-trainer"
        coordinator.requestReleaseRecovery(MachineSafetyHazardIdentity(document.trainerAddress, document.generation))
        advanceUntilIdle()
        val failed = logs.safetyEntries().single { it.message.startsWith("Release recovery could not reach") }
        assertTrue(failed.details.orEmpty().contains("connect=WRONG_TRAINER"), failed.details.orEmpty())
        assertTrue(failed.details.orEmpty().contains("connectedTrainer=other-trainer"), failed.details.orEmpty())
    }

    @Test
    fun `idle disconnect without an obligation logs that nothing was recorded`() = runTest {
        val logs = ConnectionLogRepository()
        val coordinator = coordinator(InMemoryMachineSafetyHazardRepository(), FakeSafetyTransport(null), logs)

        assertFalse(coordinator.recordUnexpectedDisconnect("trainer-1", trainerName = "Vee_Test"))

        val entry = logs.safetyEntries().single()
        assertEquals("DEBUG", entry.level)
        assertTrue(entry.message.contains("no hazard recorded"), entry.message)
        assertEquals("trainer-1", entry.deviceAddress)
    }

    private fun ConnectionLogRepository.safetyEntries(): List<ConnectionLogEntity> =
        logs.value.filter { it.eventType == LogEventType.MACHINE_SAFETY }.reversed()

    private fun TestScope.coordinator(
        store: InMemoryMachineSafetyHazardRepository,
        transport: FakeSafetyTransport,
        logs: ConnectionLogRepository? = null,
    ) = MachineSafetyCoordinator(
        repository = store,
        transport = transport,
        scope = CoroutineScope(SupervisorJob() + coroutineContext),
        nowEpochMs = { 1000L },
        logRepo = logs,
    )

    private class FakeSafetyTransport(override var connectedTrainerAddress: String?) : MachineSafetyTransport {
        override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
            connectedTrainerAddress = trainerAddress
            return Result.success(Unit)
        }

        override suspend fun stopWorkout(): Result<Unit> = Result.success(Unit)
    }
}
