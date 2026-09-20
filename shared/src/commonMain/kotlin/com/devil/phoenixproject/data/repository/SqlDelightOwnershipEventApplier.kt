package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis

class SqlDelightOwnershipEventApplier(
    private val database: PhoenixDatabase,
    private val driver: SqlDriver,
    private val profileScopedDataMerger: ProfileScopedDataMerger,
    private val now: () -> Long = ::currentTimeMillis,
) : OwnershipEventApplier {
    private val queries = database.phoenixDatabaseQueries

    override suspend fun applyRemoteEvents(
        ownerUserId: String,
        events: List<OwnershipEvent>,
    ): OwnershipEventApplySummary {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        if (events.isEmpty()) return OwnershipEventApplySummary(0, 0)
        var applied = 0
        var replayed = 0
        database.transaction {
            events.forEach { event ->
                validateEvent(event)
                val bodyHash = event.canonicalBodyHash()
                val prior = queries.selectAppliedOwnershipEvent(ownerUserId, event.mutationId)
                    .executeAsOneOrNull()
                if (prior != null) {
                    validateOwnershipEventReplay(
                        ownerUserId = ownerUserId,
                        mutationId = event.mutationId,
                        persistedCanonicalBodyHash = prior.canonical_body_hash,
                        event = event,
                    )
                    replayed++
                } else {
                    ensureTargetProfile(ownerUserId, event)
                    ensureSourceProfileCompatible(ownerUserId, event.sourceProfileId)
                    applyNamedMoves(ownerUserId, event)
                    queries.insertAppliedOwnershipEventIfAbsent(
                        ownerUserId = ownerUserId,
                        mutationId = event.mutationId,
                        canonicalBodyHash = bodyHash,
                        appliedAt = now(),
                    )
                    applied++
                }
            }
        }
        return OwnershipEventApplySummary(applied, replayed)
    }

    private fun ensureSourceProfileCompatible(ownerUserId: String, sourceProfileId: String?) {
        val source = sourceProfileId?.let { queries.getProfileById(it).executeAsOneOrNull() } ?: return
        check(source.supabase_user_id == null || source.supabase_user_id == ownerUserId) {
            "Source profile belongs to another account"
        }
    }

    private fun validateEvent(event: OwnershipEvent) {
        require(event.mutationId.isNotBlank()) { "mutationId must not be blank" }
        require(event.targetProfileId.isNotBlank()) { "targetProfileId must not be blank" }
        require(event.targetProfileName.isNotBlank()) { "targetProfileName must not be blank" }
        require(event.targetProfileColorIndex >= 0) { "targetProfileColorIndex must not be negative" }
        require(
            event.workoutSessionIds.isNotEmpty() || event.routineIds.isNotEmpty() ||
                event.cycleIds.isNotEmpty() || event.personalRecordIds.isNotEmpty(),
        ) { "Ownership event must name at least one entity" }
        event.canonicalBody() // validates every named id before any mutation occurs
    }

    private fun ensureTargetProfile(ownerUserId: String, event: OwnershipEvent) {
        val existing = queries.getProfileById(event.targetProfileId).executeAsOneOrNull()
        if (existing == null) {
            queries.insertProfile(
                id = event.targetProfileId,
                name = event.targetProfileName,
                colorIndex = event.targetProfileColorIndex.toLong(),
                createdAt = event.transferredAt,
                isActive = 0L,
            )
            queries.insertDefaultProfilePreferences(event.targetProfileId, 1L)
            queries.linkProfileToSupabase(ownerUserId, event.transferredAt, event.targetProfileId)
            return
        }
        check(existing.supabase_user_id == null || existing.supabase_user_id == ownerUserId) {
            "Target profile belongs to another account"
        }
        queries.updateProfile(
            name = event.targetProfileName,
            colorIndex = event.targetProfileColorIndex.toLong(),
            id = event.targetProfileId,
        )
        if (existing.supabase_user_id == null) {
            queries.linkProfileToSupabase(ownerUserId, event.transferredAt, event.targetProfileId)
        }
    }

    private fun applyNamedMoves(ownerUserId: String, event: OwnershipEvent) {
        event.workoutSessionIds.distinct().forEach { portalParentId ->
            if (retainClaim(ownerUserId, OwnershipEntityType.WORKOUT, portalParentId, event)) {
                reassignWorkoutParent(ownerUserId, event, portalParentId)
            }
        }
        event.routineIds.distinct().forEach { id ->
            if (retainClaim(ownerUserId, OwnershipEntityType.ROUTINE, id, event)) {
                reassignNamedRow("Routine", ownerUserId, event, id)
            }
        }
        event.cycleIds.distinct().forEach { id ->
            if (retainClaim(ownerUserId, OwnershipEntityType.CYCLE, id, event)) {
                reassignNamedRow("TrainingCycle", ownerUserId, event, id)
                queries.adoptCycleSyncStateForCycle(event.targetProfileId, id)
            }
        }
        val retainedPersonalRecordIds = event.personalRecordIds.distinct().filter { id ->
            retainClaim(ownerUserId, OwnershipEntityType.PERSONAL_RECORD, id, event)
        }
        val sourceProfileId = event.sourceProfileId
        if (sourceProfileId != null) {
            profileScopedDataMerger.mergePersonalRecordsByUuidForRecovery(
                sourceProfileId = sourceProfileId,
                targetProfileId = event.targetProfileId,
                personalRecordUuids = retainedPersonalRecordIds.toSet(),
            )
        } else {
            retainedPersonalRecordIds.forEach { uuid ->
                reassignUnscopedPersonalRecord(event.targetProfileId, uuid)
            }
        }
    }

    private fun retainClaim(
        ownerUserId: String,
        entityType: OwnershipEntityType,
        entityId: String,
        event: OwnershipEvent,
    ): Boolean {
        queries.insertLocalOwnershipClaimIfAbsent(
            ownerUserId = ownerUserId,
            entityType = entityType.name,
            entityId = entityId,
            mutationId = event.mutationId,
            sourceProfileId = event.sourceProfileId,
            targetProfileId = event.targetProfileId,
            transferredAt = event.transferredAt,
        )
        queries.replaceLocalOwnershipClaimIfNewer(
            mutationId = event.mutationId,
            sourceProfileId = event.sourceProfileId,
            targetProfileId = event.targetProfileId,
            transferredAt = event.transferredAt,
            ownerUserId = ownerUserId,
            entityType = entityType.name,
            entityId = entityId,
        )
        val retained = queries.selectLocalOwnershipClaim(ownerUserId, entityType.name, entityId)
            .executeAsOne()
        check(retained.transferred_at != event.transferredAt || retained.mutation_id == event.mutationId) {
            "Ownership claims share a non-unique transfer timestamp"
        }
        return retained.mutation_id == event.mutationId
    }

    private fun reassignWorkoutParent(
        ownerUserId: String,
        event: OwnershipEvent,
        portalParentId: String,
    ) {
        val sourcePredicate = sourcePredicate(event.sourceProfileId)
        val accountPredicate = if (event.sourceProfileId == null) "" else """
              AND profile_id IN (
                SELECT id FROM UserProfile
                WHERE supabase_user_id IS NULL OR supabase_user_id = ?
              )
        """.trimIndent()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE WorkoutSession
                SET profile_id = ?
                WHERE $sourcePredicate
                  AND (routineSessionId = ? OR ((routineSessionId IS NULL OR routineSessionId = '') AND id = ?))
                  $accountPredicate
            """.trimIndent(),
            parameters = if (event.sourceProfileId == null) 3 else 5,
        ) {
            var index = 0
            bindString(index++, event.targetProfileId)
            event.sourceProfileId?.let { bindString(index++, it) }
            bindString(index++, portalParentId)
            bindString(index++, portalParentId)
            if (event.sourceProfileId != null) bindString(index, ownerUserId)
        }
    }

    private fun reassignNamedRow(
        table: String,
        ownerUserId: String,
        event: OwnershipEvent,
        id: String,
    ) {
        check(table == "Routine" || table == "TrainingCycle")
        val sourcePredicate = sourcePredicate(event.sourceProfileId)
        val accountPredicate = if (event.sourceProfileId == null) "" else """
              AND profile_id IN (
                SELECT id FROM UserProfile
                WHERE supabase_user_id IS NULL OR supabase_user_id = ?
              )
        """.trimIndent()
        driver.execute(
            identifier = null,
            sql = """
                UPDATE $table
                SET profile_id = ?
                WHERE $sourcePredicate AND id = ?
                  $accountPredicate
            """.trimIndent(),
            parameters = if (event.sourceProfileId == null) 2 else 4,
        ) {
            var index = 0
            bindString(index++, event.targetProfileId)
            event.sourceProfileId?.let { bindString(index++, it) }
            bindString(index++, id)
            if (event.sourceProfileId != null) bindString(index, ownerUserId)
        }
    }

    private fun reassignUnscopedPersonalRecord(
        targetProfileId: String,
        uuid: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE PersonalRecord
                SET profile_id = ?
                WHERE uuid = ? AND profile_id IS NULL
            """.trimIndent(),
            parameters = 2,
        ) {
            bindString(0, targetProfileId)
            bindString(1, uuid)
        }
    }

    private fun sourcePredicate(sourceProfileId: String?): String =
        if (sourceProfileId == null) "profile_id IS NULL" else "profile_id = ?"
}
