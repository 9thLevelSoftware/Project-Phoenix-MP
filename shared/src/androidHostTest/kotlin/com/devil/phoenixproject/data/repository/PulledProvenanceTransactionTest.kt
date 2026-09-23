package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.SyncExcludedEntityTypes
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PR 11 (codex #859): a pull's account provenance commits in the same transaction as the
 * pulled rows, so a failure can never leave pulled rows on disk without the evidence that
 * they belong to the pulling account, nor provenance for rows that were rolled back.
 */
class PulledProvenanceTransactionTest {
    private val routineId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val owner = "account-a"

    private fun provenance(db: PhoenixDatabase): List<String> =
        db.phoenixDatabaseQueries
            .selectSyncExcludedEntityIds(portalUserId = owner, entityType = SyncExcludedEntityTypes.reached(SyncExcludedEntityTypes.ROUTINE))
            .executeAsList()

    private suspend fun merge(repo: SqlDelightSyncRepository) = repo.mergeAllPullData(
        ownerUserId = owner,
        sessions = emptyList(),
        routines = listOf(PullRoutineDto(id = routineId, userId = owner, name = "Pulled split")),
        cycles = emptyList(),
        badges = emptyList(),
        gamificationStats = null,
        personalRecords = emptyList(),
        lastSync = 0L,
        profileId = "default",
        pulledProvenance = mapOf(SyncExcludedEntityTypes.ROUTINE to listOf(routineId)),
    )

    @Test
    fun provenanceCommitsWithThePulledRows() = runTest {
        val db = PhoenixDatabase(createTestDriver())
        val repo = SqlDelightSyncRepository(db, FakeUserProfileRepository())

        merge(repo)

        assertTrue(db.phoenixDatabaseQueries.selectRoutineById(routineId).executeAsOneOrNull() != null)
        assertTrue(routineId in provenance(db), "the pulled routine's provenance must be recorded by the merge")
    }

    @Test
    fun aRolledBackMergeLeavesNoProvenance() = runTest {
        val driver = createTestDriver()
        val db = PhoenixDatabase(driver)
        // Abort the merge transaction on the routine insert, after provenance was written.
        driver.execute(
            null,
            "CREATE TRIGGER abort_routine_insert BEFORE INSERT ON Routine BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        )
        val repo = SqlDelightSyncRepository(db, FakeUserProfileRepository())

        assertFailsWith<Exception> { merge(repo) }

        assertTrue(db.phoenixDatabaseQueries.selectRoutineById(routineId).executeAsOneOrNull() == null)
        assertTrue(provenance(db).isEmpty(), "provenance must roll back with the rows")
    }
}
