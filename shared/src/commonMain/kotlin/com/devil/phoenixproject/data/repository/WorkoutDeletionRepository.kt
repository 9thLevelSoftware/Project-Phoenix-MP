package com.devil.phoenixproject.data.repository

import kotlinx.serialization.Serializable

/** Scope of a permanent workout tombstone. */
@Serializable
enum class WorkoutDeletionScope {
    COMPONENT,
    WORKOUT,
}

enum class WorkoutDeletionSource {
    LOCAL,
    REMOTE,
}

data class WorkoutDeletionMutation(
    val mutationId: String,
    val ownerUserId: String?,
    val profileId: String,
    val scope: WorkoutDeletionScope,
    val portalSessionId: String,
    val componentSessionId: String?,
    val deletedAt: Long,
    val acknowledgedAt: Long?,
    val source: WorkoutDeletionSource,
)

/** Durable outbound queue. Acknowledgement is always exact owner + mutation id. */
interface WorkoutDeletionRepository {
    suspend fun pendingForOwner(ownerUserId: String): List<WorkoutDeletionMutation>

    suspend fun acknowledge(
        ownerUserId: String,
        mutationIds: Set<String>,
        acknowledgedAt: Long,
    )
}
