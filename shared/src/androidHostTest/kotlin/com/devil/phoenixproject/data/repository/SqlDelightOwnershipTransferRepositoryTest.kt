package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class SqlDelightOwnershipTransferRepositoryTest {
    @Test
    fun `pending transfer preserves portal parent ids and exact-owner ack`() = runTest {
        val database = createTestDatabase()
        val queries = database.phoenixDatabaseQueries
        queries.insertOwnershipTransferOutbox(
            mutationId = "mutation-1",
            ownerUserId = "owner-a",
            sourceProfileId = "source",
            targetProfileId = "target",
            workoutSessionIdsJson = "[\"portal-parent\"]",
            routineIdsJson = "[\"routine-1\"]",
            cycleIdsJson = "[]",
            personalRecordIdsJson = "[\"pr-uuid\"]",
            createdAt = 10L,
        )
        val repository = SqlDelightOwnershipTransferRepository(database)

        assertEquals(
            listOf("portal-parent"),
            repository.pendingForOwner("owner-a").single().workoutSessionIds,
        )
        assertEquals(10L, repository.pendingForOwner("owner-a").single().createdAt)
        assertEquals(0, repository.acknowledge("owner-b", setOf("mutation-1"), 20L))
        assertEquals(1, repository.pendingForOwner("owner-a").size)
        assertEquals(1, repository.acknowledge("owner-a", setOf("mutation-1"), 21L))
        assertEquals(emptyList(), repository.pendingForOwner("owner-a"))
    }
}
