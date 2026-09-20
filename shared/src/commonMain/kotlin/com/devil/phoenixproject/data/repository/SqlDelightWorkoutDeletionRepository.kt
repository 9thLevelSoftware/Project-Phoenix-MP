package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SqlDelightWorkoutDeletionRepository(
    private val db: PhoenixDatabase,
) : WorkoutDeletionRepository {
    private val queries = db.phoenixDatabaseQueries

    override suspend fun pendingForOwner(ownerUserId: String): List<WorkoutDeletionMutation> {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        return withContext(Dispatchers.IO) {
            queries.selectPendingWorkoutDeletionsForOwner(ownerUserId)
                .executeAsList()
                .map { row ->
                    WorkoutDeletionMutation(
                        mutationId = row.mutation_id,
                        ownerUserId = row.owner_user_id,
                        profileId = row.profile_id,
                        scope = WorkoutDeletionScope.valueOf(row.scope),
                        portalSessionId = row.portal_session_id,
                        componentSessionId = row.component_session_id,
                        deletedAt = row.deleted_at,
                        acknowledgedAt = row.acknowledged_at,
                        source = WorkoutDeletionSource.valueOf(row.source),
                    )
                }
        }
    }

    override suspend fun acknowledge(
        ownerUserId: String,
        mutationIds: Set<String>,
        acknowledgedAt: Long,
    ) {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        if (mutationIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.transaction {
                mutationIds.forEach { mutationId ->
                    queries.acknowledgeWorkoutDeletion(
                        acknowledgedAt = acknowledgedAt,
                        ownerUserId = ownerUserId,
                        mutationId = mutationId,
                    )
                }
            }
        }
    }
}
