package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PendingProfileRecovery
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SqlDelightProfileRecoveryRepository(
    private val database: PhoenixDatabase,
    private val driver: SqlDriver,
    private val profileScopedDataMerger: ProfileScopedDataMerger,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val legacyBaselineRepair: LegacyBaselineRepair,
    private val userProfileRepository: UserProfileRepository,
    private val gamificationRepository: GamificationRepository,
    private val profileMutationBarrier: ProfileMutationBarrier,
    private val activityTracker: ProfileRecoveryActivityTracker,
    private val profileRecoverySourceVerifier: ProfileRecoverySourceVerifier? = null,
    private val now: () -> Long = ::currentTimeMillis,
    private val newId: () -> String = ::generateUUID,
) : ProfileRecoveryRepository {
    private val queries = database.phoenixDatabaseQueries
    private val _pendingRecoveries = MutableStateFlow<List<PendingProfileRecoveryGroup>>(emptyList())
    override val pendingRecoveries: StateFlow<List<PendingProfileRecoveryGroup>> =
        _pendingRecoveries.asStateFlow()

    override suspend fun refresh() {
        refreshSync()
    }

    override suspend fun keepWithDefault(
        recoveryId: String,
        signedInOwnerUserId: String?,
    ): ProfileRecoveryResolution = resolve(
        recoveryId = recoveryId,
        targetProfileId = DEFAULT_RECOVERY_PROFILE_ID,
        signedInOwnerUserId = signedInOwnerUserId,
    )

    override suspend fun moveToProfile(
        recoveryId: String,
        targetProfileId: String,
        signedInOwnerUserId: String?,
    ): ProfileRecoveryResolution = resolve(recoveryId, targetProfileId, signedInOwnerUserId)

    private suspend fun resolve(
        recoveryId: String,
        targetProfileId: String,
        signedInOwnerUserId: String?,
    ): ProfileRecoveryResolution {
        require(recoveryId.isNotBlank()) { "recoveryId must not be blank" }
        require(targetProfileId.isNotBlank()) { "targetProfileId must not be blank" }
        if (!activityTracker.tryReserveRecovery()) return ProfileRecoveryResolution.Busy
        val result = try {
            profileMutationBarrier.withExclusive {
                val pending = queries.selectUnresolvedProfileRecoveries()
                    .executeAsList()
                    .firstOrNull { it.recovery_id == recoveryId }
                    ?: return@withExclusive ProfileRecoveryResolution.Missing
                val target = queries.getProfileById(targetProfileId).executeAsOneOrNull()
                    ?: return@withExclusive ProfileRecoveryResolution.Missing
                val counts = decodeProfileRecoveryCounts(pending.counts_json)
                var persistedOwnerUserId = pending.owner_user_id
                var verifiedOwnerExpectedCountsJson: String? = null
                when (val owner = decideProfileRecoveryOwner(
                    persistedOwnerUserId = pending.owner_user_id,
                    signedInOwnerUserId = signedInOwnerUserId,
                    cloudOriginRowCount = counts.cloudOriginRowCount,
                )) {
                    is ProfileRecoveryOwnerDecision.AccountMismatch -> {
                        return@withExclusive ProfileRecoveryResolution.AccountMismatch(
                            owner.expectedOwnerUserId,
                            owner.signedInOwnerUserId,
                        )
                    }
                    ProfileRecoveryOwnerDecision.RelinkRequired -> {
                        val capturedOwner = signedInOwnerUserId
                            ?: return@withExclusive ProfileRecoveryResolution.RelinkRequired
                        val verifier = profileRecoverySourceVerifier
                            ?: return@withExclusive ProfileRecoveryResolution.RelinkRequired
                        val snapshot = captureRecoverySourceSnapshot(pending.source_profile_id)
                        if (snapshot.distinctProofCount == 0) {
                            return@withExclusive ProfileRecoveryResolution.VerificationFailed
                        }
                        val verification = verifier.verify(snapshot)
                        if (!verification.verified ||
                            verification.authenticatedOwnerUserId != capturedOwner ||
                            verification.verifiedProofCount != snapshot.distinctProofCount ||
                            captureRecoverySourceSnapshot(pending.source_profile_id) != snapshot
                        ) {
                            return@withExclusive ProfileRecoveryResolution.VerificationFailed
                        }
                        persistedOwnerUserId = capturedOwner
                        verifiedOwnerExpectedCountsJson = pending.counts_json
                    }
                    else -> Unit
                }

                if (pending.kind == ProfileRecoveryKind.LEGACY_BASELINE.name) {
                    resolveLegacyBaselines(pending, targetProfileId)
                    return@withExclusive ProfileRecoveryResolution.Resolved
                }

                val ownerUserId = (decideProfileRecoveryOwner(
                    persistedOwnerUserId,
                    signedInOwnerUserId,
                    counts.cloudOriginRowCount,
                ) as? ProfileRecoveryOwnerDecision.QueueForKnownOwner)?.ownerUserId
                if (ownerUserId != null &&
                    target.supabase_user_id != null &&
                    target.supabase_user_id != ownerUserId
                ) {
                    return@withExclusive ProfileRecoveryResolution.AccountMismatch(
                        ownerUserId,
                        target.supabase_user_id,
                    )
                }
                resolveProfileData(
                    pending = pending,
                    targetProfileId = targetProfileId,
                    targetOwnerUserId = target.supabase_user_id,
                    ownerUserId = ownerUserId,
                    verifiedOwnerExpectedCountsJson = verifiedOwnerExpectedCountsJson,
                )
                ProfileRecoveryResolution.Resolved
            }
        } finally {
            activityTracker.releaseRecoveryReservation()
        }
        refreshSync()
        return result
    }

    private fun captureRecoverySourceSnapshot(sourceProfileId: String?): ProfileRecoverySourceSnapshot {
        val ownerPredicate = if (sourceProfileId == null) "profile_id IS NULL" else "profile_id = ?"
        return ProfileRecoverySourceSnapshot(
            sourceProfileId = sourceProfileId,
            workoutSessionIds = selectStrings(
                "SELECT DISTINCT COALESCE(NULLIF(routineSessionId, ''), id) FROM WorkoutSession WHERE $ownerPredicate",
                sourceProfileId,
            ),
            routineIds = selectStrings(
                "SELECT id FROM Routine WHERE $ownerPredicate AND deletedAt IS NULL",
                sourceProfileId,
            ),
            cycleIds = selectStrings(
                "SELECT id FROM TrainingCycle WHERE $ownerPredicate",
                sourceProfileId,
            ),
            personalRecordIds = selectStrings(
                "SELECT uuid FROM PersonalRecord WHERE $ownerPredicate AND uuid IS NOT NULL",
                sourceProfileId,
            ),
            proofWorkoutSessionIds = selectStrings(
                "SELECT DISTINCT COALESCE(NULLIF(routineSessionId, ''), id) FROM WorkoutSession " +
                    "WHERE $ownerPredicate AND (portalOrigin = 1 OR serverId IS NOT NULL)",
                sourceProfileId,
            ),
            proofRoutineIds = selectStrings(
                "SELECT id FROM Routine WHERE $ownerPredicate AND serverId IS NOT NULL",
                sourceProfileId,
            ),
            proofCycleIds = selectStrings(
                    "SELECT c.id FROM TrainingCycle c JOIN CycleSyncState s ON s.cycle_id = c.id " +
                    "WHERE ${if (sourceProfileId == null) "c.profile_id IS NULL" else "c.profile_id = ?"} " +
                    "AND s.account_id IS NOT NULL",
                sourceProfileId,
            ),
            proofPersonalRecordIds = selectStrings(
                "SELECT uuid FROM PersonalRecord WHERE $ownerPredicate AND uuid IS NOT NULL AND serverId IS NOT NULL",
                sourceProfileId,
            ),
        )
    }

    private fun selectStrings(sql: String, sourceProfileId: String?): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) cursor.getString(0)?.let(ids::add)
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            parameters = if (sourceProfileId == null) 0 else 1,
        ) {
            sourceProfileId?.let { bindString(0, it) }
        }
        return ids.distinct().sorted()
    }

    private suspend fun resolveLegacyBaselines(
        pending: PendingProfileRecovery,
        targetProfileId: String,
    ) {
        val repair = legacyBaselineRepair.reconcileAfterProfileBootstrap(userProfileRepository.allProfiles.value)
        repair.ambiguous.forEach { baseline ->
            legacyBaselineRepair.assignAndConsume(
                profileId = targetProfileId,
                legacy = baseline,
                updatedAt = now(),
            )
        }
        queries.markProfileRecoveryResolved(now(), pending.recovery_id)
    }

    private suspend fun resolveProfileData(
        pending: PendingProfileRecovery,
        targetProfileId: String,
        targetOwnerUserId: String?,
        ownerUserId: String?,
        verifiedOwnerExpectedCountsJson: String?,
    ) {
        val sourceProfileId = pending.source_profile_id
        if (sourceProfileId == targetProfileId) {
            database.transaction {
                bindVerifiedOwnerIfNeeded(
                    pending,
                    ownerUserId,
                    verifiedOwnerExpectedCountsJson,
                )
                if (ownerUserId != null && targetOwnerUserId == null) {
                    queries.linkProfileToSupabase(ownerUserId, now(), targetProfileId)
                }
                queries.markProfileRecoveryResolved(now(), pending.recovery_id)
            }
            if (ownerUserId != null && targetOwnerUserId == null) {
                userProfileRepository.refreshProfiles()
            }
            return
        }
        database.transaction {
            bindVerifiedOwnerIfNeeded(
                pending,
                ownerUserId,
                verifiedOwnerExpectedCountsJson,
            )
            if (ownerUserId != null && targetOwnerUserId == null) {
                queries.linkProfileToSupabase(ownerUserId, now(), targetProfileId)
            }
            val mutation = sourceProfileId?.let { source ->
                OwnershipTransferMutation(
                    mutationId = newId(),
                    ownerUserId = ownerUserId.orEmpty(),
                    sourceProfileId = source,
                    targetProfileId = targetProfileId,
                    workoutSessionIds = queries.selectPortalSessionIdsByProfile(source).executeAsList(),
                    routineIds = queries.selectAllRoutineIdsByProfile(source).executeAsList(),
                    cycleIds = queries.selectAllRetainedCycleIdsByProfile(source).executeAsList(),
                    personalRecordIds = queries.selectAllPersonalRecordUuidsByProfile(source)
                        .executeAsList()
                        .filterNotNull(),
                )
            } ?: if (ownerUserId != null) {
                captureUnscopedOwnershipMutation(ownerUserId, targetProfileId)
            } else {
                null
            }
            if (sourceProfileId == null) {
                moveUnscopedContentRows(targetProfileId)
            } else {
                moveRegisteredOrOrphanedProfileRows(sourceProfileId, targetProfileId)
            }
            sourceProfileId?.let {
                queries.deleteIntegrationStatusByProfile(it)
                queries.deleteIntegrationSyncCursorByProfile(it)
            }
            ownerUserId?.let { owner ->
                checkNotNull(mutation) { "Account-owned recovery must capture its stable entity IDs" }
                val hasNamedEntities = mutation.workoutSessionIds.isNotEmpty() ||
                    mutation.routineIds.isNotEmpty() ||
                    mutation.cycleIds.isNotEmpty() ||
                    mutation.personalRecordIds.isNotEmpty()
                if (hasNamedEntities) {
                    queries.insertOwnershipTransferOutbox(
                        mutationId = mutation.mutationId,
                        ownerUserId = owner,
                        sourceProfileId = mutation.sourceProfileId,
                        targetProfileId = mutation.targetProfileId,
                        workoutSessionIdsJson = encodeOwnershipIds(mutation.workoutSessionIds),
                        routineIdsJson = encodeOwnershipIds(mutation.routineIds),
                        cycleIdsJson = encodeOwnershipIds(mutation.cycleIds),
                        personalRecordIdsJson = encodeOwnershipIds(mutation.personalRecordIds),
                        createdAt = nextOwnershipTransferCreatedAt(owner),
                    )
                }
            }
            assertContentGroupMoved(sourceProfileId)
        }
        if (ownerUserId != null && targetOwnerUserId == null) {
            userProfileRepository.refreshProfiles()
        }
        recomputeDerivedGamification(targetProfileId)
        database.transaction {
            sourceProfileId?.let { queries.deleteGamificationStatsByProfile(it) }
            sourceProfileId?.let { queries.deleteRpgAttributesByProfile(it) }
            queries.markProfileRecoveryResolved(now(), pending.recovery_id)
        }
    }

    private fun bindVerifiedOwnerIfNeeded(
        pending: PendingProfileRecovery,
        ownerUserId: String?,
        expectedCountsJson: String?,
    ) {
        if (expectedCountsJson == null) return
        checkNotNull(ownerUserId) { "Verified recovery owner is missing" }
        check(
            queries.bindPendingProfileRecoveryOwner(
                ownerUserId = ownerUserId,
                recoveryId = pending.recovery_id,
                expectedCountsJson = expectedCountsJson,
            ).value == 1L,
        ) { "Verified recovery group changed before its owner could be persisted" }
    }

    private fun nextOwnershipTransferCreatedAt(ownerUserId: String): Long {
        val timestamp = now()
        val latest = queries.selectAllOwnershipTransfers().executeAsList()
            .asSequence()
            .filter { it.owner_user_id == ownerUserId }
            .maxOfOrNull { it.created_at }
            ?: return timestamp
        return maxOf(timestamp, latest + 1L)
    }

    private fun captureUnscopedOwnershipMutation(
        ownerUserId: String,
        targetProfileId: String,
    ) = OwnershipTransferMutation(
        mutationId = newId(),
        ownerUserId = ownerUserId,
        sourceProfileId = null,
        targetProfileId = targetProfileId,
        workoutSessionIds = selectUnscopedStrings(
            "SELECT DISTINCT CASE WHEN routineSessionId IS NULL OR TRIM(routineSessionId) = '' THEN id ELSE routineSessionId END FROM WorkoutSession WHERE profile_id IS NULL",
        ),
        routineIds = selectUnscopedStrings("SELECT id FROM Routine WHERE profile_id IS NULL AND deletedAt IS NULL"),
        cycleIds = selectUnscopedStrings("SELECT id FROM TrainingCycle WHERE profile_id IS NULL"),
        personalRecordIds = selectUnscopedStrings("SELECT uuid FROM PersonalRecord WHERE profile_id IS NULL AND uuid IS NOT NULL"),
    )

    private fun selectUnscopedStrings(sql: String): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) cursor.getString(0)?.let(ids::add)
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return ids.distinct().sorted()
    }

    private fun moveRegisteredOrOrphanedProfileRows(
        sourceProfileId: String,
        targetProfileId: String,
    ) {
        profileScopedDataMerger.mergeForProfileRecoveryInCurrentTransaction(sourceProfileId, targetProfileId)
        baselineRepository.mergeForProfileRecoveryInCurrentTransaction(sourceProfileId, targetProfileId)
        queries.reassignRoutineGroupProfile(targetProfileId, sourceProfileId)
        queries.reassignRoutineProfile(targetProfileId, sourceProfileId)
        queries.reassignSessionProfile(targetProfileId, sourceProfileId)
        queries.reassignTrainingCycleProfile(targetProfileId, sourceProfileId)
        queries.adoptCycleSyncStateProfile(targetProfileId, sourceProfileId)
        queries.reassignStreakProfile(targetProfileId, sourceProfileId)
        queries.reassignAssessmentResultProfile(targetProfileId, sourceProfileId)
        queries.reassignVelocityOneRepMaxProfile(targetProfileId, sourceProfileId)
        queries.reassignProgressionProfile(targetProfileId, sourceProfileId)
    }

    /**
     * Legacy databases could contain NULL profile ownership before NOT NULL was enforced.
     * Move the entire discovered content group. Constraint conflicts abort the transaction
     * and leave the recovery unresolved rather than silently dropping or guessing rows.
     */
    private fun moveUnscopedContentRows(targetProfileId: String) {
        val stagingProfileId = "recovery-unscoped-${newId()}"
        queries.insertProfile(
            id = stagingProfileId,
            name = "Recovered unassigned data",
            colorIndex = 0L,
            createdAt = now(),
            isActive = 0L,
        )
        PROFILE_RECOVERY_MOVED_CONTENT_TABLES.sorted().forEach { table ->
            val column = if (table.startsWith("External")) "profileId" else "profile_id"
            driver.execute(
                identifier = null,
                sql = "UPDATE $table SET $column = ? WHERE $column IS NULL",
                parameters = 1,
            ) {
                bindString(0, stagingProfileId)
            }
        }
        moveRegisteredOrOrphanedProfileRows(stagingProfileId, targetProfileId)
        assertContentGroupMoved(stagingProfileId)
        queries.deleteProfile(stagingProfileId)
    }

    private fun assertContentGroupMoved(sourceProfileId: String?) {
        PROFILE_RECOVERY_MOVED_CONTENT_TABLES.forEach { table ->
            val column = if (table.startsWith("External")) "profileId" else "profile_id"
            var remaining = 0L
            val predicate = if (sourceProfileId == null) "$column IS NULL" else "$column = ?"
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM $table WHERE $predicate",
                mapper = { cursor ->
                    if (cursor.next().value) remaining = cursor.getLong(0) ?: 0L
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                parameters = if (sourceProfileId == null) 0 else 1,
            ) {
                sourceProfileId?.let { bindString(0, it) }
            }
            check(remaining == 0L) { "Profile recovery left $remaining rows in $table" }
        }
    }

    private suspend fun recomputeDerivedGamification(profileId: String) {
        gamificationRepository.updateStats(profileId)
        val input = gamificationRepository.getRpgInput(profileId)
        gamificationRepository.saveRpgProfile(RpgAttributeEngine.computeProfile(input), profileId)
    }

    private fun refreshSync() {
        _pendingRecoveries.value = queries.selectUnresolvedProfileRecoveries()
            .executeAsList()
            .map(PendingProfileRecovery::toRecoveryGroup)
    }
}

private const val DEFAULT_RECOVERY_PROFILE_ID = "default"

/** Tables moved through collision-aware merge functions in [ProfileScopedDataMerger]. */
internal val PROFILE_RECOVERY_MERGED_CONTENT_TABLES: Set<String> = setOf(
    "EarnedBadge",
    "ExerciseMvt",
    "ExternalActivity",
    "ExternalBodyMeasurement",
    "ExternalExerciseTemplate",
    "ExternalExerciseTemplateMapping",
    "ExternalProgram",
    "ExternalRoutine",
    "ExternalRoutineFolder",
    "PersonalRecord",
)

/** Tables moved through exact source-to-target reassignment queries. */
internal val PROFILE_RECOVERY_REASSIGNED_CONTENT_TABLES: Set<String> = setOf(
    "AssessmentResult",
    "ProgressionEvent",
    "Routine",
    "RoutineGroup",
    "StreakHistory",
    "TrainingCycle",
    "VelocityOneRepMaxEstimate",
    "WorkoutSession",
)

internal val PROFILE_RECOVERY_MOVED_CONTENT_TABLES: Set<String> =
    PROFILE_RECOVERY_MERGED_CONTENT_TABLES + PROFILE_RECOVERY_REASSIGNED_CONTENT_TABLES
