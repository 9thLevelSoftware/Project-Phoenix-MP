package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Issue #1162 follow-up: an alias that appears after launch maintenance is collapsed when it
 * appears, and every coalesce keeps a recovery snapshot of the rows it removes.
 */
class Issue1162AliasFollowUpTest {
    private val lowerUuid = "abcdefab-1234-4abc-8def-abcdef123456"
    private val upperUuid = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"
    private val mixedUuid = "AbCdEfAb-1234-4AbC-8DeF-AbCdEf123456"

    private fun seedProfile(database: PhoenixDatabase, profileId: String) {
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile(id = profileId, name = profileId, colorIndex = 0L, createdAt = 1_700_000_000_000, isActive = 0L)
        queries.linkProfileToSupabase(supabase_user_id = "owner-user", last_auth_at = 1_700_000_000_000, id = profileId)
    }

    private fun seedRoutine(database: PhoenixDatabase, id: String, profileId: String, updatedAt: Long) {
        val queries = database.phoenixDatabaseQueries
        queries.insertRoutine(
            id = id,
            name = "Push Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0L,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
        queries.updateRoutineById(name = "Push Day", description = "", updatedAt = updatedAt, id = id)
    }

    private fun repository(database: PhoenixDatabase) = SqlDelightWorkoutRepository(
        db = database,
        exerciseRepository = FakeExerciseRepository(),
        signedInPortalUserId = { "owner-user" },
        activeProfileId = { "profile-a" },
    )

    private fun recoveryRows(database: PhoenixDatabase, profileId: String) =
        database.phoenixDatabaseQueries.selectRoutineRecoveriesByProfile(
            profileId = profileId,
            portalUserId = "owner-user",
            now = 1_700_000_000_000,
        ).executeAsList()

    private fun liveRoutineIds(database: PhoenixDatabase, profileId: String) =
        database.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profileId)
            .executeAsList().filter { it.deletedAt == null }.map { it.id }

    @Test
    fun `a second coalesce of the same identity keeps its own snapshot`() = runBlocking {
        val database = createTestDatabase()
        seedProfile(database, "profile-a")
        seedRoutine(database, lowerUuid, "profile-a", updatedAt = 1_700_000_000_002)
        seedRoutine(database, upperUuid, "profile-a", updatedAt = 1_700_000_000_001)
        val repository = repository(database)

        assertEquals(1, repository.getAllRoutines("profile-a").first().size)
        assertEquals(1, recoveryRows(database, "profile-a").size)

        // Another spelling arrives later (a restore, a pull) and is coalesced too. Its row is
        // a different graph, so insert-if-absent must not drop its snapshot.
        seedRoutine(database, mixedUuid, "profile-a", updatedAt = 1_700_000_000_000)
        val result = repository.runRoutineIdentityMaintenance("profile-a")

        assertEquals(1, result.componentsReconciled)
        assertEquals(listOf(lowerUuid), liveRoutineIds(database, "profile-a"))
        val rows = recoveryRows(database, "profile-a")
        assertEquals(2, rows.size, "each coalesce keeps a snapshot")
        assertTrue(rows.any { mixedUuid in it.payload_json }, "the later alias graph is retained")

        // A rerun with nothing new to collapse adds nothing.
        repository.runRoutineIdentityMaintenance("profile-a")
        assertEquals(2, recoveryRows(database, "profile-a").size)
    }

    @Test
    fun `moving a routine onto another spelling of its identity leaves one routine`() = runBlocking {
        val database = createTestDatabase()
        seedProfile(database, "profile-a")
        seedProfile(database, "profile-b")
        seedRoutine(database, lowerUuid, "profile-a", updatedAt = 1_700_000_000_002)
        seedRoutine(database, upperUuid, "profile-b", updatedAt = 1_700_000_000_001)
        val repository = repository(database)
        // Both profiles' lists have run launch maintenance; each holds one row.
        assertEquals(1, repository.getAllRoutines("profile-a").first().size)
        assertEquals(1, repository.getAllRoutines("profile-b").first().size)

        repository.moveRoutineToProfile(upperUuid, "profile-a")

        assertEquals(listOf(lowerUuid), liveRoutineIds(database, "profile-a"))
        assertEquals(emptyList<String>(), liveRoutineIds(database, "profile-b"))
        assertEquals(1, repository.getAllRoutines("profile-a").first().size)
    }
}
