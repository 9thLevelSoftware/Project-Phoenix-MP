package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Real SQLDelight storage of the portal cycle version that is sent back as baseUpdatedAt. */
class CycleServerVersionSyncTest {
    private val cycleId = "88888888-8888-4888-a888-888888888888"
    private val serverVersion = "2026-09-19T10:11:12.123456+00:00"

    private fun pulled(updatedAt: String?) = PullTrainingCycleDto(id = cycleId, name = "Portal name", updatedAt = updatedAt)

    private suspend fun pushedBase(repo: SqlDelightSyncRepository): String? {
        val ctx = repo.getFullCyclesForSync("default").single { it.cycle.id == cycleId }
        return PortalSyncAdapter.toPortalTrainingCycle(ctx, "user-1").baseUpdatedAt
    }

    @Test
    fun atomicPullStoresUpdatedAtVerbatimAndNextPushCarriesIt() = runTest {
        val repo = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())

        repo.mergeAllPullData(
            sessions = emptyList(), routines = emptyList(), badges = emptyList(),
            gamificationStats = null, personalRecords = emptyList(), lastSync = 0L,
            profileId = "default", cycles = listOf(pulled(serverVersion)),
        )

        assertEquals(serverVersion, pushedBase(repo))
    }

    @Test
    fun standalonePullStoresUpdatedAtAndOlderPortalKeepsIt() = runTest {
        val repo = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())

        repo.mergePortalCycles(listOf(pulled(serverVersion)), "default")
        assertEquals(serverVersion, pushedBase(repo))

        // A portal that does not report updatedAt must not erase the known base.
        repo.mergePortalCycles(listOf(pulled(null)), "default")
        assertEquals(serverVersion, pushedBase(repo))
    }

    @Test
    fun pushAckUpdatesBaseWithoutAPullAndMissingCyclesKeepTheirBase() = runTest {
        val repo = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())
        repo.mergePortalCycles(listOf(pulled(serverVersion)), "default")

        val acked = "2026-09-19T11:00:00.654321+00:00"
        repo.updateCycleServerVersions(mapOf(cycleId to acked))
        assertEquals(acked, pushedBase(repo))

        repo.updateCycleServerVersions(mapOf("some-other-cycle" to "2026-09-20T00:00:00+00:00"))
        assertEquals(acked, pushedBase(repo))
    }

    private suspend fun atomicPull(repo: SqlDelightSyncRepository, updatedAt: String?) = repo.mergeAllPullData(
        sessions = emptyList(), routines = emptyList(), badges = emptyList(),
        gamificationStats = null, personalRecords = emptyList(), lastSync = 0L,
        profileId = "default", cycles = listOf(pulled(updatedAt)),
    )

    @Test
    fun rePullOfAnExistingCycleAdvancesTheBaseOnBothMergePaths() = runTest {
        val v2 = "2026-09-19T12:00:00.000001+00:00"

        val atomic = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())
        atomicPull(atomic, serverVersion)
        atomicPull(atomic, v2)
        assertEquals(v2, pushedBase(atomic))

        val standalone = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())
        standalone.mergePortalCycles(listOf(pulled(serverVersion)), "default")
        standalone.mergePortalCycles(listOf(pulled(v2)), "default")
        assertEquals(v2, pushedBase(standalone))
    }

    @Test
    fun malformedServerVersionsAreNeverStored() = runTest {
        val tooLong = "2026-09-19T10:11:12" + "0".repeat(60) + "Z"
        val malformed = listOf("", "   ", "not-a-timestamp", "12345", tooLong)

        val atomic = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())
        atomicPull(atomic, serverVersion)
        malformed.forEach { atomicPull(atomic, it) }
        assertEquals(serverVersion, pushedBase(atomic), "malformed pull keeps the previous base")

        val standalone = SqlDelightSyncRepository(createTestDatabase(), FakeUserProfileRepository())
        standalone.mergePortalCycles(listOf(pulled("garbage")), "default")
        assertNull(pushedBase(standalone), "a first malformed pull stores nothing")
        standalone.mergePortalCycles(listOf(pulled(serverVersion)), "default")
        malformed.forEach { standalone.updateCycleServerVersions(mapOf(cycleId to it)) }
        assertEquals(serverVersion, pushedBase(standalone), "malformed push ack keeps the previous base")
    }

    @Test
    fun localOnlyCyclePushesNullBase() = runTest {
        val db = createTestDatabase()
        SqlDelightTrainingCycleRepository(db).saveCycle(TrainingCycle.create(id = cycleId, name = "Local"))
        val repo = SqlDelightSyncRepository(db, FakeUserProfileRepository())

        assertNull(pushedBase(repo))
    }

    @Test
    fun serverWinningEditClearsLocalDeleteAndPendingDeletionOnBothMergePaths() = runTest {
        val db = createTestDatabase()
        val queries = db.phoenixDatabaseQueries
        queries.insertProfile("default", "Default", 0L, 1L, 0L)
        queries.linkProfileToSupabase("user-1", 1L, "default")
        val cycleRepo = SqlDelightTrainingCycleRepository(db)
        val syncRepo = SqlDelightSyncRepository(db, FakeUserProfileRepository())

        // Create and soft-delete c1 at t=100
        cycleRepo.saveCycle(TrainingCycle.create(id = cycleId, name = "Local Name", profileId = "default"))
        queries.softDeleteTrainingCycle(deletedAt = 100L, updatedAt = 100L, id = cycleId)
        queries.markCycleDeletionPending(
            profileId = "default",
            accountId = "user-1",
            deletedAt = 100L,
            cycleId = cycleId,
        )

        // Tie timestamp (t=100) must NOT clear local delete
        val tieIso = kotlin.time.Instant.fromEpochMilliseconds(100L).toString()
        syncRepo.mergeAllPullData(
            ownerUserId = "user-1",
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(PullTrainingCycleDto(id = cycleId, name = "Tie Edit", updatedAt = tieIso)),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )
        assertEquals(100L, queries.selectTrainingCycleById(cycleId).executeAsOne().deletedAt)
        assertEquals(1, queries.selectPendingCycleDeletions("user-1", "default").executeAsList().size)

        // Newer server edit (t=200) via mergeAllPullData must clear deletedAt and pending deletion
        val newerIso = kotlin.time.Instant.fromEpochMilliseconds(200L).toString()
        syncRepo.mergeAllPullData(
            ownerUserId = "user-1",
            sessions = emptyList(),
            routines = emptyList(),
            cycles = listOf(PullTrainingCycleDto(id = cycleId, name = "Portal Edit", updatedAt = newerIso)),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )
        val restored = queries.selectTrainingCycleById(cycleId).executeAsOne()
        assertNull(restored.deletedAt)
        assertEquals("Portal Edit", restored.name)
        assertEquals(listOf(cycleId), queries.selectAllTrainingCycles("default").executeAsList().map { it.id })
        assertTrue(queries.selectPendingCycleDeletions("user-1", "default").executeAsList().isEmpty())
        assertTrue(queries.selectDirtyCycleStates("default").executeAsList().isEmpty())

        // Also verify standalone mergePortalCycles clears local delete & pending deletion
        queries.softDeleteTrainingCycle(deletedAt = 300L, updatedAt = 300L, id = cycleId)
        queries.markCycleDeletionPending(
            profileId = "default",
            accountId = "user-1",
            deletedAt = 300L,
            cycleId = cycleId,
        )
        val t400Iso = kotlin.time.Instant.fromEpochMilliseconds(400L).toString()
        syncRepo.mergePortalCycles(
            cycles = listOf(PullTrainingCycleDto(id = cycleId, name = "Portal Standalone Edit", updatedAt = t400Iso)),
            profileId = "default",
        )
        val restoredStandalone = queries.selectTrainingCycleById(cycleId).executeAsOne()
        assertNull(restoredStandalone.deletedAt)
        assertEquals("Portal Standalone Edit", restoredStandalone.name)
        assertEquals(listOf(cycleId), queries.selectAllTrainingCycles("default").executeAsList().map { it.id })
        assertTrue(queries.selectPendingCycleDeletions("user-1", "default").executeAsList().isEmpty())
        assertTrue(queries.selectDirtyCycleStates("default").executeAsList().isEmpty())
    }
}
