package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import com.devil.phoenixproject.presentation.manager.MachineSafetyRecoveryRequestResult
import com.devil.phoenixproject.presentation.manager.MachineSafetyUiState
import com.devil.phoenixproject.testutil.FakeMachineSafetyTransport
import com.devil.phoenixproject.testutil.createTestDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlDelightMachineSafetyHazardRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightMachineSafetyHazardRepository

    @Before
    fun setup() {
        driver = createTestDriver()
        database = PhoenixDatabase(driver)
        repository = SqlDelightMachineSafetyHazardRepository(database) { 5_000L }
    }

    @After
    fun teardown() {
        driver.close()
    }

    @Test
    fun `corrupt json and blank identity rows restore safely and clear on acknowledgement`() = runTest {
        driver.execute(
            null,
            """
            INSERT INTO MachineSafetyHazard (trainer_address, generation, document_version, hazard_json, updated_at_epoch_ms)
            VALUES ('trainer-corrupt', 5, 1, '{not-valid-json', 1000)
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO MachineSafetyHazard (trainer_address, generation, document_version, hazard_json, updated_at_epoch_ms)
            VALUES ('', 2, 1, '{}', 1000)
            """.trimIndent(),
            0,
        )

        var now = 2_000L
        val coordinator = MachineSafetyCoordinator(
            repository = repository,
            transport = FakeMachineSafetyTransport(null),
            scope = CoroutineScope(SupervisorJob() + coroutineContext),
            nowEpochMs = { now },
        )

        assertFalse(coordinator.canStartMachine())
        coordinator.restoreOnStartup()
        val first = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertTrue(first.restoreWasRejected)
        coordinator.acknowledgeUnloaded(first.identity)
        advanceUntilIdle()
        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)

        // One rejected row remains, so start stays blocked until the second row is surfaced and acknowledged.
        assertFalse(coordinator.canStartMachine())
        now = 3_000L
        coordinator.surfaceStoredHazard()
        val second = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertTrue(second.restoreWasRejected)
        coordinator.acknowledgeUnloaded(second.identity)
        advanceUntilIdle()

        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(coordinator.canStartMachine())
        assertTrue(repository.loadAll().isEmpty())
    }

    @Test
    fun `unsupported version row is rejected and clears via deleteRejectedIfMatches`() = runTest {
        driver.execute(
            null,
            """
            INSERT INTO MachineSafetyHazard (trainer_address, generation, document_version, hazard_json, updated_at_epoch_ms)
            VALUES ('trainer-v99', 3, 99, '{}', 1000)
            """.trimIndent(),
            0,
        )

        val loaded = repository.load("trainer-v99")
        val rejected = assertIs<MachineSafetyLoadResult.Rejected>(loaded)
        assertEquals(MachineSafetyRejection.UNSUPPORTED_VERSION, rejected.reason)
        assertEquals("trainer-v99", rejected.rawTrainerAddress)
        assertEquals(3L, rejected.storedGeneration)

        assertFalse(repository.deleteRejectedIfMatches("trainer-v99", 999L))
        assertTrue(repository.deleteRejectedIfMatches("trainer-v99", 3L))
        assertEquals(MachineSafetyLoadResult.Missing, repository.load("trainer-v99"))
    }

    @Test
    fun `deleteRejectedIfMatches never bypasses generation check for valid Loaded rows`() = runTest {
        val doc = MachineSafetyHazardDocument(
            generation = 4L,
            trainerAddress = "trainer-valid",
            sessionId = "valid-1",
            workoutKind = MachineSafetyWorkoutKind.JUST_LIFT,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            phase = MachineSafetyPhase.UNRESOLVED,
            physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
        )
        repository.replace(doc)

        assertFalse(repository.deleteRejectedIfMatches("trainer-valid", null))
        assertFalse(repository.deleteRejectedIfMatches("trainer-valid", 4L))
        assertIs<MachineSafetyLoadResult.Loaded>(repository.load("trainer-valid"))
        assertTrue(repository.deleteIfGenerationMatches("trainer-valid", 4L))
        assertEquals(MachineSafetyLoadResult.Missing, repository.load("trainer-valid"))
    }

    @Test
    fun `whitespace blank identity row with recovery before acknowledgement clears all rows and unblocks starts`() = runTest {
        driver.execute(
            null,
            """
            INSERT INTO MachineSafetyHazard (trainer_address, generation, document_version, hazard_json, updated_at_epoch_ms)
            VALUES ('   ', 5, 1, '{}', 1000)
            """.trimIndent(),
            0,
        )

        val coordinator = MachineSafetyCoordinator(
            repository = repository,
            transport = FakeMachineSafetyTransport(null),
            scope = CoroutineScope(SupervisorJob() + coroutineContext),
            nowEpochMs = { 2_000L },
        )

        assertFalse(coordinator.canStartMachine())
        coordinator.restoreOnStartup()
        val visible = assertIs<MachineSafetyUiState.Visible>(coordinator.uiState.value)
        assertTrue(visible.restoreWasRejected)
        assertEquals("unknown-trainer", visible.document.trainerAddress)
        assertEquals(5L, visible.document.generation)

        assertEquals(MachineSafetyRecoveryRequestResult.STARTED, coordinator.requestReleaseRecovery(visible.identity))
        for (attempt in 0 until 500) {
            advanceUntilIdle()
            val phase = (coordinator.uiState.value as? MachineSafetyUiState.Visible)?.document?.phase
            if (phase == MachineSafetyPhase.RELEASE_REQUEST_SENT || phase == MachineSafetyPhase.RELEASE_REQUEST_FAILED) break
            withContext(Dispatchers.Default) { delay(10) }
        }
        assertEquals(
            MachineSafetyPhase.RELEASE_REQUEST_SENT,
            (coordinator.uiState.value as? MachineSafetyUiState.Visible)?.document?.phase,
        )
        assertEquals(2, repository.loadAll().size)

        coordinator.acknowledgeUnloaded(visible.identity)
        advanceUntilIdle()

        assertEquals(MachineSafetyUiState.Hidden, coordinator.uiState.value)
        assertTrue(coordinator.canStartMachine())
        assertTrue(repository.loadAll().isEmpty())
    }
}
