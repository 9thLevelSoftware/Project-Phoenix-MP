package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class SqlDelightOwnershipEventApplierTest {
    @Test
    fun `remote parent id moves every local component and replay is exact`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val database = PhoenixDatabase(driver)
        val queries = database.phoenixDatabaseQueries
        queries.insertProfile("source", "Source", 0L, 1L, 1L)
        queries.linkProfileToSupabase("owner-a", 1L, "source")
        insertSession(driver, "component-a", "portal-parent", "source")
        insertSession(driver, "component-b", "portal-parent", "source")
        val applier = SqlDelightOwnershipEventApplier(
            database = database,
            driver = driver,
            profileScopedDataMerger = ProfileScopedDataMerger(database),
            now = { 50L },
        )
        val event = OwnershipEvent(
            mutationId = "mutation-1",
            sourceProfileId = "source",
            targetProfileId = "target",
            targetProfileName = "Recovered",
            targetProfileColorIndex = 3,
            workoutSessionIds = listOf("portal-parent"),
            routineIds = emptyList(),
            cycleIds = emptyList(),
            personalRecordIds = emptyList(),
            transferredAt = 40L,
        )

        assertEquals(OwnershipEventApplySummary(1, 0), applier.applyRemoteEvents("owner-a", listOf(event)))
        assertEquals("target", queries.selectSessionById("component-a").executeAsOne().profile_id)
        assertEquals("target", queries.selectSessionById("component-b").executeAsOne().profile_id)
        assertEquals(OwnershipEventApplySummary(0, 1), applier.applyRemoteEvents("owner-a", listOf(event)))
        assertFailsWith<OwnershipEventConflictException> {
            applier.applyRemoteEvents("owner-a", listOf(event.copy(targetProfileName = "Changed")))
        }
    }

    @Test
    fun `event retains ownership claim when the local entity has not arrived yet`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val database = PhoenixDatabase(driver)
        val event = OwnershipEvent(
            mutationId = "mutation-before-row",
            sourceProfileId = "source",
            targetProfileId = "target",
            targetProfileName = "Recovered",
            targetProfileColorIndex = 1,
            workoutSessionIds = listOf("portal-parent"),
            routineIds = emptyList(),
            cycleIds = emptyList(),
            personalRecordIds = emptyList(),
            transferredAt = 40L,
        )
        val applier = SqlDelightOwnershipEventApplier(
            database,
            driver,
            ProfileScopedDataMerger(database),
        )

        assertEquals(OwnershipEventApplySummary(1, 0), applier.applyRemoteEvents("owner-a", listOf(event)))

        val claim = database.phoenixDatabaseQueries.selectLocalOwnershipClaim(
            "owner-a",
            OwnershipEntityType.WORKOUT.name,
            "portal-parent",
        ).executeAsOne()
        assertEquals("target", claim.target_profile_id)
        assertEquals("mutation-before-row", claim.mutation_id)
        assertEquals(
            "target",
            SqlDelightLocalOwnershipClaimLookup(database).targetProfileId(
                "owner-a",
                OwnershipEntityType.WORKOUT,
                "portal-parent",
            ),
        )
    }

    private fun insertSession(
        driver: JdbcSqliteDriver,
        id: String,
        parentId: String,
        profileId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO WorkoutSession(id,timestamp,mode,targetReps,weightPerCableKg,routineSessionId,profile_id) VALUES (?,?,?,?,?,?,?)",
            7,
        ) {
            bindString(0, id)
            bindLong(1, 10L)
            bindString(2, "OldSchool")
            bindLong(3, 5L)
            bindDouble(4, 20.0)
            bindString(5, parentId)
            bindString(6, profileId)
        }
    }
}
