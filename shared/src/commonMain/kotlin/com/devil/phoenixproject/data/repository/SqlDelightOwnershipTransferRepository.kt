package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.OwnershipTransferOutbox
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val OwnershipTransferJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

class SqlDelightOwnershipTransferRepository(
    database: PhoenixDatabase,
) : OwnershipTransferRepository {
    private val queries = database.phoenixDatabaseQueries

    override suspend fun pendingForOwner(ownerUserId: String): List<OwnershipTransferMutation> {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        return queries.selectPendingOwnershipTransfersForOwner(ownerUserId)
            .executeAsList()
            .map(OwnershipTransferOutbox::toMutation)
    }

    override suspend fun pendingAll(): List<OwnershipTransferMutation> =
        queries.selectAllPendingOwnershipTransfers()
            .executeAsList()
            .map(OwnershipTransferOutbox::toMutation)

    override suspend fun acknowledge(
        ownerUserId: String,
        mutationIds: Set<String>,
        acknowledgedAt: Long,
    ): Int {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        if (mutationIds.isEmpty()) return 0
        var acknowledged = 0
        mutationIds.forEach { mutationId ->
            require(mutationId.isNotBlank()) { "mutationId must not be blank" }
            acknowledged += queries.acknowledgeOwnershipTransfer(
                acknowledgedAt = acknowledgedAt,
                ownerUserId = ownerUserId,
                mutationId = mutationId,
            ).value.toInt()
        }
        return acknowledged
    }
}

internal fun OwnershipTransferOutbox.toMutation() = OwnershipTransferMutation(
    mutationId = mutation_id,
    ownerUserId = owner_user_id,
    sourceProfileId = source_profile_id,
    targetProfileId = target_profile_id,
    workoutSessionIds = decodeOwnershipIds(workout_session_ids_json),
    routineIds = decodeOwnershipIds(routine_ids_json),
    cycleIds = decodeOwnershipIds(cycle_ids_json),
    personalRecordIds = decodeOwnershipIds(personal_record_ids_json),
    createdAt = created_at,
)

internal fun encodeOwnershipIds(ids: Collection<String>): String = OwnershipTransferJson.encodeToString(
    ids.asSequence()
        .onEach { require(it.isNotBlank()) { "Ownership transfer contains a blank id" } }
        .distinct()
        .sorted()
        .toList(),
)

internal fun decodeOwnershipIds(json: String): List<String> = OwnershipTransferJson
    .decodeFromString<List<String>>(json)
    .also { ids -> require(ids.none(String::isBlank)) { "Ownership transfer contains a blank id" } }
