package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Recovery observation must drop a snapshot once its retention window has
 * passed, even when nothing writes the database. The SQLDelight query behind
 * the flow only re-emits on table changes, so a clock frozen at collection
 * time would keep an expired snapshot listed until some unrelated write.
 */
class RoutineRecoveryObservationExpiryTest {

    @Test
    fun expiredRecoverySnapshotDropsFromObservationWithoutADatabaseWrite() = runBlocking {
        val db = createTestDatabase()
        seedProfile(db)
        var now = START_MILLIS
        val repository = SqlDelightWorkoutRepository(
            db = db,
            exerciseRepository = FakeExerciseRepository(),
            signedInPortalUserId = { PORTAL_USER_ID },
            activeProfileId = { PROFILE_ID },
            nowMillis = { now },
        )
        val expiringAt = START_MILLIS + 150L
        insertSnapshot(
            db = db,
            id = "rec-expiring",
            routineName = "Push Day",
            createdAt = START_MILLIS,
            expiresAt = expiringAt,
        )
        insertSnapshot(
            db = db,
            id = "rec-later",
            routineName = "Pull Day",
            createdAt = START_MILLIS,
            expiresAt = START_MILLIS + 60_000L,
        )
        val retainedBefore = retainedRows(db)

        val emissions = Channel<List<RoutineRecoveryItem>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            repository.observeRoutineRecoveries(PROFILE_ID, PORTAL_USER_ID).collect {
                emissions.send(it)
            }
        }
        try {
            val initial = withTimeout(15_000) {
                var next: List<RoutineRecoveryItem>
                do {
                    next = emissions.receive()
                } while (next.isEmpty())
                next
            }
            assertEquals(listOf("Pull Day", "Push Day"), initial.map { it.routineName }.sorted())

            now = expiringAt + 1L
            val afterExpiry = withTimeout(3_000) {
                var next: List<RoutineRecoveryItem>
                do {
                    next = emissions.receive()
                } while (next.any { it.routineName == "Push Day" })
                next
            }
            assertEquals(listOf("Pull Day"), afterExpiry.map { it.routineName })
            assertEquals(retainedBefore, retainedRows(db))
        } finally {
            job.cancel()
        }
    }

    private fun seedProfile(db: PhoenixDatabase) {
        val queries = db.phoenixDatabaseQueries
        queries.insertProfile(PROFILE_ID, "Active", 0L, START_MILLIS, 1L)
        queries.linkProfileToSupabase(PORTAL_USER_ID, START_MILLIS, PROFILE_ID)
    }

    private fun insertSnapshot(
        db: PhoenixDatabase,
        id: String,
        routineName: String,
        createdAt: Long,
        expiresAt: Long,
    ) {
        val payload = RoutineRecoveryPayload(
            provenance = RoutineRecoveryProvenance(
                reason = RoutineRecoveryReasons.SERVER_DELETE,
                source = "test",
                appliedAt = createdAt,
            ),
            routines = listOf(
                RoutineRecoveryRoutineGraph(
                    routine = RoutineRecoveryRoutineSnapshot(
                        id = "routine-$id",
                        name = routineName,
                        description = "",
                        createdAt = createdAt,
                        lastUsed = null,
                        useCount = 0L,
                        updatedAt = null,
                        serverId = null,
                        deletedAt = createdAt,
                        profileId = PROFILE_ID,
                        groupId = null,
                    ),
                    exercises = emptyList(),
                    supersets = emptyList(),
                    plannedSets = emptyList(),
                    cycleDayRefs = emptyList(),
                ),
            ),
        )
        db.phoenixDatabaseQueries.insertRoutineRecoveryIfAbsent(
            id = id,
            portalUserId = PORTAL_USER_ID,
            profileId = PROFILE_ID,
            canonicalIdentity = "identity-$id",
            reason = RoutineRecoveryReasons.SERVER_DELETE,
            payloadJson = Json.encodeToString(RoutineRecoveryPayload.serializer(), payload),
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    private fun retainedRows(db: PhoenixDatabase): List<String> =
        db.phoenixDatabaseQueries
            .selectRoutineRecoveriesByProfile(
                profileId = PROFILE_ID,
                portalUserId = PORTAL_USER_ID,
                now = 0L,
            )
            .executeAsList()
            .map { "${it.id}|${it.created_at}|${it.expires_at}|${it.payload_json}" }
            .sorted()

    private companion object {
        const val PROFILE_ID = "active-profile"
        const val PORTAL_USER_ID = "owner-user"
        const val START_MILLIS = 1_000L
    }
}
