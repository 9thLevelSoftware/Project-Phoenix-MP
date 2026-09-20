package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalPullAdapter
import com.devil.phoenixproject.data.sync.PortalSyncAdapter.CycleWithContext
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.PulledWorkoutDeletionDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.RoutineExercise as RoutineExerciseRow
import com.devil.phoenixproject.database.Superset as SupersetRow
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.CycleDayBackup
import com.devil.phoenixproject.util.CycleProgressBackup
import com.devil.phoenixproject.util.CycleProgressionBackup
import com.devil.phoenixproject.util.TrainingCycleBackup
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SQLDelight implementation of SyncRepository.
 * Provides database operations for syncing data with the Phoenix Portal.
 */
class SqlDelightSyncRepository(
    private val db: PhoenixDatabase,
    private val userProfileRepository: UserProfileRepository,
    private val localOwnershipClaimLookup: LocalOwnershipClaimLookup =
        SqlDelightLocalOwnershipClaimLookup(db),
) : SyncRepository {

    private val queries = db.phoenixDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    private fun personalRecordSessionKey(exerciseId: String, timestamp: Long): String = "$exerciseId:$timestamp"

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): SQLite host
     * parameter limit is implementation-defined (999 on Android,
     * 32766 on desktop). For initial/full pulls of large histories the
     * batched preservation SELECTs would otherwise throw
     * `SQLITE_RANGE: too many SQL variables`. 500 keeps us safely under
     * the stricter limit and still eliminates the per-row round-trips.
     */
    private companion object {
        const val BATCH_LOOKUP_CHUNK_SIZE = 500
    }

    /**
     * Preserve a local template-cycle association when the portal cannot represent it.
     * Template routines deliberately use the cycle_routine_ prefix and are omitted from
     * the UUID-only sync payload, so a null pull value is lossy rather than an explicit
     * unassignment. All other null values remain authoritative.
     */
    private fun preservedTemplateCycleRoutineId(
        existingCycleProfileId: String?,
        existingCycleDeletedAt: Long?,
        existingDays: List<com.devil.phoenixproject.database.CycleDay>,
        portalDay: com.devil.phoenixproject.data.sync.PullCycleDayDto,
        profileId: String,
    ): String? {
        if (portalDay.dayType == "rest") return null
        if (portalDay.routineId != null) return portalDay.routineId
        if (existingCycleProfileId != profileId || existingCycleDeletedAt != null) return null

        val localRoutineId = existingDays
            .firstOrNull { it.day_number == portalDay.dayNumber.toLong() && it.is_rest_day == 0L }
            ?.routine_id
            ?: return null
        if (!localRoutineId.startsWith("cycle_routine_")) return null

        val localRoutine = queries.selectRoutineById(localRoutineId).executeAsOneOrNull()
        return localRoutineId.takeIf {
            localRoutine != null &&
                localRoutine.profile_id == profileId &&
                localRoutine.deletedAt == null
        }
    }

    // === Push Operations ===

    override suspend fun getSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSessionSyncDto> = withContext(Dispatchers.IO) {
        queries.selectSessionsModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
            WorkoutSessionSyncDto(
                clientId = row.id,
                serverId = row.serverId,
                timestamp = row.timestamp,
                mode = row.mode,
                targetReps = row.targetReps.toInt(),
                weightPerCableKg = row.weightPerCableKg.toFloat(),
                duration = row.duration, // Long ms — no toInt() conversion needed
                totalReps = row.totalReps.toInt(),
                exerciseId = row.exerciseId,
                exerciseName = row.exerciseName,
                deletedAt = row.deletedAt,
                createdAt = row.timestamp, // Use timestamp as createdAt
                updatedAt = row.updatedAt ?: row.timestamp,
            )
        }
    }

    override suspend fun getPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecordSyncDto> = withContext(Dispatchers.IO) {
        queries.selectPRsModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
            PersonalRecordSyncDto(
                // The Portal contract uses the immutable PR UUID. Retain the
                // legacy row-id fallback only for pre-UUID local records.
                clientId = row.uuid ?: row.id.toString(),
                serverId = row.serverId,
                exerciseId = row.exerciseId,
                exerciseName = row.exerciseName,
                weight = row.weight.toFloat(),
                reps = row.reps.toInt(),
                oneRepMax = row.oneRepMax.toFloat(),
                achievedAt = row.achievedAt,
                workoutMode = row.workoutMode,
                prType = row.prType,
                phase = row.phase,
                volume = row.volume.toFloat(),
                cableCount = row.cable_count?.toInt(),
                deletedAt = row.deletedAt,
                createdAt = row.achievedAt,
                updatedAt = row.updatedAt ?: row.achievedAt,
            )
        }
    }

    override suspend fun getRoutinesModifiedSince(timestamp: Long, profileId: String): List<RoutineSyncDto> = withContext(Dispatchers.IO) {
        queries.selectRoutinesModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
            RoutineSyncDto(
                clientId = row.id,
                serverId = row.serverId,
                name = row.name,
                description = row.description,
                deletedAt = row.deletedAt,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt ?: row.createdAt,
            )
        }
    }

    override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> = withContext(Dispatchers.IO) {
        queries.selectCustomExercisesModifiedSince(timestamp).executeAsList().map { row ->
            CustomExerciseSyncDto(
                clientId = row.id,
                serverId = row.serverId,
                name = row.name,
                displayName = row.displayName ?: row.name, // Carry display name (#404)
                muscleGroup = row.muscleGroup,
                equipment = row.equipment,
                defaultCableConfig = row.defaultCableConfig,
                deletedAt = row.deletedAt,
                createdAt = row.created,
                updatedAt = row.updatedAt ?: row.created,
            )
        }
    }

    override suspend fun getBadgesModifiedSince(timestamp: Long, profileId: String): List<EarnedBadgeSyncDto> = withContext(Dispatchers.IO) {
        queries.selectBadgesModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
            EarnedBadgeSyncDto(
                clientId = row.id.toString(),
                serverId = row.serverId,
                badgeId = row.badgeId,
                earnedAt = row.earnedAt,
                deletedAt = row.deletedAt,
                createdAt = row.earnedAt,
                updatedAt = row.updatedAt ?: row.earnedAt,
            )
        }
    }

    override suspend fun getGamificationStatsForSync(profileId: String): GamificationStatsSyncDto? = withContext(Dispatchers.IO) {
        queries.selectGamificationStatsForSync(profileId = profileId).executeAsOneOrNull()?.let { row ->
            GamificationStatsSyncDto(
                clientId = row.id.toString(),
                totalWorkouts = row.totalWorkouts.toInt(),
                totalReps = row.totalReps.toInt(),
                totalVolumeKg = row.totalVolumeKg.toFloat(),
                longestStreak = row.longestStreak.toInt(),
                currentStreak = row.currentStreak.toInt(),
                updatedAt = row.updatedAt ?: row.lastUpdated,
            )
        }
    }

    // === Post-Push Stamping ===

    override suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long) {
        withContext(Dispatchers.IO) {
            queries.updateSessionTimestamp(timestamp, sessionId)
        }
    }

    override suspend fun updatePersonalRecordTimestamp(prIds: List<Long>, timestamp: Long) {
        if (prIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            prIds.chunked(900).forEach { chunk ->
                queries.updatePRTimestamp(timestamp, chunk)
            }
        }
    }

    // === ID Mapping ===

    override suspend fun updateServerIds(mappings: IdMappings) {
        withContext(Dispatchers.IO) {
            db.transaction {
                mappings.sessions.forEach { (clientId, serverId) ->
                    queries.updateSessionServerId(serverId, clientId)
                }
                mappings.records.forEach { (clientId, serverId) ->
                    val longId = clientId.toLongOrNull()
                    if (longId == null) {
                        Logger.w { "Skipping PR server ID update: invalid clientId '$clientId'" }
                        return@forEach
                    }
                    queries.updatePRServerId(serverId, longId)
                }
                mappings.routines.forEach { (clientId, serverId) ->
                    queries.updateRoutineServerId(serverId, clientId)
                }
                mappings.exercises.forEach { (clientId, serverId) ->
                    queries.updateExerciseServerId(serverId, clientId)
                }
                mappings.badges.forEach { (clientId, serverId) ->
                    val longId = clientId.toLongOrNull()
                    if (longId == null) {
                        Logger.w { "Skipping badge server ID update: invalid clientId '$clientId'" }
                        return@forEach
                    }
                    queries.updateBadgeServerId(serverId, longId)
                }
            }
            Logger.d {
                "Updated server IDs: ${mappings.sessions.size} sessions, ${mappings.records.size} PRs, ${mappings.routines.size} routines"
            }
        }
    }

    // === Pull Operations ===

    /**
     * Merge sessions from server (legacy push-path).
     *
     * This legacy DTO path follows the same provenance guard as the current portal
     * pull path: a missing row is materialized as portal-origin, while an existing
     * row is updateable only when it is already portal-origin, belongs to the active
     * profile, is not newer, and is not a retained tombstone. The narrow UPDATE
     * intentionally leaves local capture columns and FK children untouched.
     */
    override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val activeProfileId = userProfileRepository.activeProfile.value?.id ?: "default"
                sessions.forEach { dto ->
                    // Check if we have this session locally (by serverId or clientId)
                    val existingByServer = dto.serverId?.let {
                        queries.selectSessionByServerId(it).executeAsOneOrNull()
                    }

                    val existingLocalSession = existingByServer ?: queries.selectSessionById(dto.clientId).executeAsOneOrNull()
                    val localId = existingLocalSession?.id ?: dto.clientId

                    if (existingLocalSession == null) {
                        queries.upsertSyncSession(
                            id = localId,
                            timestamp = dto.timestamp,
                            mode = dto.mode,
                            targetReps = dto.targetReps.toLong(),
                            weightPerCableKg = dto.weightPerCableKg.toDouble(),
                            progressionKg = 0.0,
                            duration = dto.duration,
                            totalReps = dto.totalReps.toLong(),
                            warmupReps = 0L,
                            workingReps = dto.totalReps.toLong(),
                            isJustLift = 0L,
                            stopAtTop = 0L,
                            eccentricLoad = 100L,
                            echoLevel = 1L,
                            exerciseId = dto.exerciseId,
                            exerciseName = dto.exerciseName,
                            routineSessionId = null,
                            routineName = null,
                            routineId = null,
                            safetyFlags = 0L,
                            deloadWarningCount = 0L,
                            romViolationCount = 0L,
                            spotterActivations = 0L,
                            peakForceConcentricA = null,
                            peakForceConcentricB = null,
                            peakForceEccentricA = null,
                            peakForceEccentricB = null,
                            avgForceConcentricA = null,
                            avgForceConcentricB = null,
                            avgForceEccentricA = null,
                            avgForceEccentricB = null,
                            heaviestLiftKg = null,
                            totalVolumeKg = null,
                            cableCount = null,
                            estimatedCalories = null,
                            warmupAvgWeightKg = null,
                            workingAvgWeightKg = null,
                            burnoutAvgWeightKg = null,
                            peakWeightKg = null,
                            rpe = null,
                            avgMcvMmS = null,
                            avgAsymmetryPercent = null,
                            totalVelocityLossPercent = null,
                            dominantSide = null,
                            strengthProfile = null,
                            formScore = null,
                            updatedAt = dto.updatedAt,
                            serverId = dto.serverId,
                            deletedAt = dto.deletedAt,
                            profile_id = activeProfileId,
                            display_multiplier = dto.displayMultiplier?.toLong(),
                            externalAddedLoadKg = 0.0,
                            counterweightKg = 0.0,
                            rackItemsJson = "[]",
                        )
                        return@forEach
                    }

                    if (existingLocalSession.profile_id == activeProfileId && dto.serverId != null) {
                        queries.attachSessionServerIdIfAbsent(dto.serverId, localId)
                    }

                    val incomingIsCurrent = existingLocalSession.updatedAt == null ||
                        dto.updatedAt >= existingLocalSession.updatedAt
                    val preservesTombstone = existingLocalSession.deletedAt == null || dto.deletedAt != null
                    if (
                        existingLocalSession.portalOrigin == 1L &&
                        existingLocalSession.profile_id == activeProfileId &&
                        incomingIsCurrent &&
                        preservesTombstone
                    ) {
                        queries.updateLegacyPortalSessionProjection(
                            timestamp = dto.timestamp,
                            mode = dto.mode,
                            targetReps = dto.targetReps.toLong(),
                            weightPerCableKg = dto.weightPerCableKg.toDouble(),
                            duration = dto.duration,
                            totalReps = dto.totalReps.toLong(),
                            workingReps = dto.totalReps.toLong(),
                            exerciseId = dto.exerciseId,
                            exerciseName = dto.exerciseName,
                            updatedAt = dto.updatedAt,
                            serverId = dto.serverId,
                            deletedAt = dto.deletedAt,
                            id = localId,
                        )
                    }
                }
            }
            Logger.d { "Merged ${sessions.size} sessions from server" }
        }
    }

    /**
     * Merge personal records from server (legacy push-path).
     *
     * CONFLICT RESOLUTION STRATEGY: SERVER WINS (UPSERT by compound key)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 2 "Personal Records"
     *
     * PRs are computed from local workout sessions. Mobile computes PRs.
     * The push-path uses UPSERT for initial population. The pull-path [mergePersonalRecords]
     * uses INSERT OR IGNORE (LOCAL WINS) to ensure locally-computed PRs are never overwritten.
     *
     * Edge case: Both devices compute same PR from same session - no conflict (same data).
     * Different PRs from different sessions should both exist (union semantics).
     */
    override suspend fun mergePRs(records: List<PersonalRecordSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                records.forEach { dto ->
                    // Upsert by compound key (exerciseId, workoutMode, prType, phase)
                    // to match the UNIQUE INDEX idx_pr_unique.
                    // Uses DTO-supplied prType/phase/volume with backward-compatible defaults.
                    val effectiveVolume = if (dto.volume > 0f) dto.volume else dto.weight * dto.reps
                    queries.upsertPR(
                        exerciseId = dto.exerciseId,
                        exerciseName = dto.exerciseName,
                        weight = dto.weight.toDouble(),
                        reps = dto.reps.toLong(),
                        oneRepMax = dto.oneRepMax.toDouble(),
                        achievedAt = dto.achievedAt,
                        workoutMode = dto.workoutMode,
                        prType = dto.prType,
                        volume = effectiveVolume.toDouble(),
                        phase = dto.phase,
                        profile_id = userProfileRepository.activeProfile.value?.id ?: "default",
                        cable_count = dto.cableCount?.toLong(),
                        uuid = dto.clientId.ifBlank { generateUUID() },
                    )
                }
            }
            Logger.d { "Merged ${records.size} PRs from server" }
        }
    }

    /**
     * Merge custom exercises from server.
     *
     * CONFLICT RESOLUTION STRATEGY: INSERT (no conflict expected)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 8 "Custom Exercises"
     *
     * Custom exercises are created locally on mobile. Server doesn't push custom exercises
     * back, so this is effectively a one-way push. Uses INSERT (not UPSERT) since duplicates
     * shouldn't occur - each custom exercise has a unique client-generated ID.
     */
    override suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                exercises.forEach { dto ->
                    // Never replace an Exercise FK parent. Seed missing rows, then update
                    // only sync-representable fields while preserving user metadata and children.
                    queries.insertExerciseIfAbsent(
                        id = dto.clientId,
                        name = dto.name,
                        displayName = dto.displayName, // Carry display name from sync (#404)
                        description = null,
                        created = dto.createdAt,
                        muscleGroup = dto.muscleGroup,
                        muscleGroups = dto.muscleGroup,
                        muscles = null,
                        equipment = dto.equipment,
                        movement = null,
                        sidedness = null,
                        grip = null,
                        gripWidth = null,
                        minRepRange = null,
                        popularity = 0.0,
                        archived = 0L,
                        isFavorite = 0L,
                        isCustom = 1L,
                        timesPerformed = 0L,
                        lastPerformed = null,
                        aliases = null,
                        defaultCableConfig = dto.defaultCableConfig,
                        one_rep_max_kg = null,
                        mvtOverrideMs = null,
                        // Custom exercises carry no explicit flag (#635); derive from equipment.
                        isBodyweight = null,
                    )

                    queries.updateCustomExerciseFromSync(
                        name = dto.name,
                        displayName = dto.displayName,
                        muscleGroup = dto.muscleGroup,
                        muscleGroups = dto.muscleGroup,
                        equipment = dto.equipment,
                        defaultCableConfig = dto.defaultCableConfig,
                        updatedAt = dto.updatedAt,
                        serverId = dto.serverId,
                        deletedAt = dto.deletedAt,
                        id = dto.clientId,
                    )
                }
            }
            Logger.d { "Merged ${exercises.size} custom exercises from server" }
        }
    }

    /**
     * Merge badges from server.
     *
     * CONFLICT RESOLUTION STRATEGY: UNION MERGE (INSERT OR IGNORE)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 3 "Badges"
     *
     * Badges are additive achievements computed by mobile. Both local and remote badges
     * should exist - they are never removed or overwritten. INSERT OR IGNORE ensures
     * that duplicate badgeIds are silently skipped while new badges are added.
     *
     * Multi-device scenario: If Device A earns badge X and Device B earns badge Y,
     * after sync both devices will have badges X and Y (union semantics).
     */
    override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                badges.forEach { dto ->
                    // INSERT OR IGNORE - union merge, duplicates silently skipped
                    queries.insertEarnedBadge(dto.badgeId, dto.earnedAt, profileId = profileId)
                }
            }
            Logger.d { "Merged ${badges.size} badges from server (profile=$profileId)" }
        }
    }

    /**
     * Merge gamification stats from server.
     *
     * CONFLICT RESOLUTION STRATEGY: SERVER WINS with local field preservation
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 4 "Gamification Stats"
     *
     * Mobile is authoritative for computation, but server aggregates from all devices.
     * Server totals may be higher than local (multi-device usage). Server values override
     * aggregate fields (totalWorkouts, totalReps, etc.), but local-only tracking fields
     * are preserved: uniqueExercisesUsed, prsAchieved, lastWorkoutDate, streakStartDate.
     *
     * Multi-device scenario: Device A has 50 workouts, Device B has 30. Server aggregates
     * to 80 total. Both devices receive 80 on next pull.
     */
    override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?, profileId: String) {
        if (stats == null) return

        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()
            val existing = queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()

            val stableId = profileId.hashCode().toLong()
            queries.upsertGamificationStats(
                id = stableId,
                totalWorkouts = stats.totalWorkouts.toLong(),
                totalReps = stats.totalReps.toLong(),
                // Round (not truncate) to minimise systematic bias; column is INTEGER in schema.
                totalVolumeKg = stats.totalVolumeKg.toDouble().roundToLong(),
                longestStreak = stats.longestStreak.toLong(),
                currentStreak = stats.currentStreak.toLong(),
                uniqueExercisesUsed = existing?.uniqueExercisesUsed ?: 0L,
                prsAchieved = existing?.prsAchieved ?: 0L,
                lastWorkoutDate = existing?.lastWorkoutDate,
                streakStartDate = existing?.streakStartDate,
                lastUpdated = now,
                profileId = profileId,
            )
            Logger.d { "Merged gamification stats from server (profile=$profileId)" }
        }
    }

    // === Portal Pull Operations (merge portal data) ===

    /**
     * Merge routines from portal (pull-path).
     *
     * CONFLICT RESOLUTION STRATEGY: TIMESTAMP-BASED LWW (Last-Write-Wins with local preference)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 6 "Routines"
     *
     * Routines are editable on both mobile and portal. Timestamp comparison prevents data loss
     * for recent local edits. If local routine was modified after lastSync, local version wins.
     * Otherwise, portal version is accepted.
     *
     * Multi-device risk: Device A edits routine, Device B edits same routine. Whichever syncs
     * second loses edits. Portal edits have a ~5 second window where they can be overwritten
     * by a pending mobile sync.
     *
     * Edge case: Empty exercises list from portal is treated as incomplete payload - we preserve
     * local exercises to prevent accidental data loss from server omissions.
     */
    override suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                for (portalRoutine in routines) {
                    mergePortalRoutine(portalRoutine, lastSync, profileId)
                }
            }
            Logger.d { "Merged ${routines.size} portal routines with exercises" }
        }
    }

    /**
     * Merge training cycles from portal (pull-path).
     *
     * CONFLICT RESOLUTION STRATEGY: SERVER WINS with single-active enforcement
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 7 "Training Cycles"
     *
     * Training cycles are primarily created/edited on portal (portal-authoritative pattern).
     * Server data is accepted for new cycles. Existing cycles get metadata updated.
     * Cycle days are fully replaced (delete-then-reinsert) to match portal state.
     *
     * IMPORTANT: Only ONE cycle can be active at a time. After processing all portal cycles,
     * we ensure exactly one cycle is marked active (the most recently activated from portal,
     * or existing local active cycle if no portal cycles are active).
     *
     * Future enhancement: Add updated_at column for timestamp-based LWW (see CONFLICT-RESOLUTION-DESIGN.md).
     */
    override suspend fun mergePortalCycles(cycles: List<PullTrainingCycleDto>, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                // Track which portal cycle is active (should be at most one)
                var portalActiveCycleId: String? = null

                for (portalCycle in cycles) {
                    // Snapshot existing days before replacement so a lossy null pull can preserve
                    // a valid local-only template association by day number.
                    val existing = queries.selectTrainingCycleById(portalCycle.id).executeAsOneOrNull()
                    val incomingUpdatedAt = portalCycle.updatedAt
                    if (existing != null && (incomingUpdatedAt == null || incomingUpdatedAt < existing.updatedAt)) {
                        continue
                    }
                    if (portalCycle.status == "active") {
                        portalActiveCycleId = portalCycle.id
                    }
                    val existingDays = existing?.let {
                        queries.selectCycleDaysByCycle(portalCycle.id).executeAsList()
                    } ?: emptyList()

                    captureCycleConflictDraftIfNeeded(
                        existing = existing,
                        existingDays = existingDays,
                        incomingUpdatedAt = incomingUpdatedAt,
                    )

                    // Upsert cycle (INSERT OR IGNORE — keeps local if exists)
                    queries.insertTrainingCycleIgnore(
                        id = portalCycle.id,
                        name = portalCycle.name,
                        description = portalCycle.description,
                        created_at = currentTimeMillis(),
                        is_active = 0L, // Don't set active yet - enforce single-active at end
                        profile_id = profileId,
                        template_id = portalCycle.templateId,
                        week_number = portalCycle.currentWeek?.toLong() ?: 1L,
                        updatedAt = incomingUpdatedAt ?: 0L,
                    )
                    queries.insertCycleSyncStateIfAbsent(
                        cycleId = portalCycle.id,
                        profileId = profileId,
                        accountId = null,
                        dirtyGeneration = 0L,
                        acknowledgedGeneration = 0L,
                        pendingDeleteUpdatedAt = null,
                        pendingDeleteGeneration = null,
                    )

                    // For pre-existing cycles only: update metadata (but NOT is_active - enforce single-active at end).
                    // Newly-inserted rows already have the correct values from insertTrainingCycleIgnore above.
                    if (existing != null) {
                        val mergedTemplateId = portalCycle.templateId ?: existing.template_id
                        val mergedWeekNumber = portalCycle.currentWeek?.toLong() ?: existing.week_number
                        queries.updateTrainingCycle(
                            name = portalCycle.name,
                            description = portalCycle.description,
                            is_active = existing.is_active, // Preserve; single-active enforcement runs at end
                            template_id = mergedTemplateId,
                            week_number = mergedWeekNumber,
                            updatedAt = requireNotNull(incomingUpdatedAt),
                            id = portalCycle.id,
                        )
                    }

                    // Bulk delete existing days, reinsert from portal (same pattern as edge function)
                    queries.deleteCycleDaysByCycle(portalCycle.id)

                    for (day in portalCycle.days) {
                        val existingDay = existingDays.firstOrNull { it.id == day.id }
                            ?: existingDays.firstOrNull { it.day_number == day.dayNumber.toLong() }
                        queries.insertCycleDayIgnore(
                            id = day.id,
                            cycle_id = day.cycleId.ifEmpty { portalCycle.id },
                            day_number = day.dayNumber.toLong(),
                            name = day.notes,
                            routine_id = preservedTemplateCycleRoutineId(
                                existingCycleProfileId = existing?.profile_id,
                                existingCycleDeletedAt = existing?.deletedAt,
                                existingDays = existingDays,
                                portalDay = day,
                                profileId = profileId,
                            ),
                            is_rest_day = if (day.dayType == "rest") 1L else 0L,
                            echo_level = if (day.echoLevelPresent == true) day.echoLevel else existingDay?.echo_level,
                            eccentric_load_percent = if (day.eccentricLoadPercentPresent == true) {
                                day.eccentricLoadPercent?.toLong()
                            } else {
                                existingDay?.eccentric_load_percent
                            },
                            weight_progression_percent = day.weightAdjustment.toDouble(),
                            rep_modifier = day.repModifier.toLong(),
                            rest_time_override_seconds = day.restOverride?.toLong(),
                        )
                    }

                    mergePulledCycleProgression(portalCycle)
                    mergePulledCycleProgress(portalCycle)
                }

                // SINGLE-ACTIVE ENFORCEMENT: Ensure exactly one cycle is active after merge
                // Priority: portal's active cycle > existing local active cycle > none
                if (portalActiveCycleId != null) {
                    // Portal specified an active cycle - deactivate all others, activate this one
                    queries.deactivateAllCycles(profileId)
                    val activeCycle = cycles.first { it.id == portalActiveCycleId }
                    val storedActiveCycle = queries.selectTrainingCycleById(portalActiveCycleId).executeAsOne()
                    queries.updateTrainingCycle(
                        name = activeCycle.name,
                        description = activeCycle.description,
                        is_active = 1L,
                        template_id = storedActiveCycle.template_id,
                        week_number = storedActiveCycle.week_number,
                        updatedAt = storedActiveCycle.updatedAt,
                        id = portalActiveCycleId,
                    )
                    Logger.d { "Set active cycle from portal: $portalActiveCycleId" }
                }
                // If no portal cycle is active, preserve existing local active cycle (don't change anything)

                // POST-MERGE INVARIANT CHECK: Verify at most 1 active cycle
                // This assertion guards against bugs in the merge logic that could leave multiple active cycles.
                val activeCycleCount = queries.countActiveCycles(profileId).executeAsOne()
                if (activeCycleCount > 1) {
                    Logger.e { "INVARIANT VIOLATION: Found $activeCycleCount active cycles after merge (expected 0 or 1). Forcing deactivation." }
                    // Recovery: deactivate all and re-activate the portal's choice (or none)
                    queries.deactivateAllCycles(profileId)
                    portalActiveCycleId?.let { id ->
                        val activeCycle = cycles.first { it.id == id }
                        val storedActiveCycle = queries.selectTrainingCycleById(id).executeAsOne()
                        queries.updateTrainingCycle(
                            name = activeCycle.name,
                            description = activeCycle.description,
                            is_active = 1L,
                            template_id = storedActiveCycle.template_id,
                            week_number = storedActiveCycle.week_number,
                            updatedAt = storedActiveCycle.updatedAt,
                            id = id,
                        )
                    }
                }
            }
            Logger.d { "Merged ${cycles.size} portal training cycles with days and progressions" }
        }
    }

    /**
     * Merge sessions from portal (pull-path).
     *
     * CONFLICT RESOLUTION STRATEGY: LOCAL WINS (INSERT OR IGNORE)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 1 "Workout Sessions"
     *
     * Sessions are immutable workout records created by BLE execution. Mobile is authoritative.
     * Once a workout is recorded locally, it should NEVER be overwritten by server data.
     * INSERT OR IGNORE ensures that if a session with the same ID exists locally, the
     * remote data is silently dropped.
     *
     * Multi-device scenario: Device A records session X, Device B records session Y.
     * Both sessions have unique UUIDs. After sync, both devices have sessions X and Y.
     * UUID collision is theoretically possible but probability is negligible (~1 in 10^38).
     */
    override suspend fun mergePortalSessions(sessions: List<WorkoutSession>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                for (session in sessions) {
                    // INSERT OR IGNORE - local session wins if ID exists
                    queries.insertSessionIgnore(
                        id = session.id,
                        timestamp = session.timestamp,
                        mode = session.mode,
                        targetReps = session.reps.toLong(),
                        weightPerCableKg = session.weightPerCableKg.toDouble(),
                        progressionKg = session.progressionKg.toDouble(),
                        duration = session.duration,
                        totalReps = session.totalReps.toLong(),
                        warmupReps = session.warmupReps.toLong(),
                        workingReps = session.workingReps.toLong(),
                        isJustLift = if (session.isJustLift) 1L else 0L,
                        stopAtTop = if (session.stopAtTop) 1L else 0L,
                        eccentricLoad = session.eccentricLoad.toLong(),
                        echoLevel = session.echoLevel.toLong(),
                        exerciseId = session.exerciseId,
                        exerciseName = session.exerciseName,
                        routineSessionId = session.routineSessionId,
                        routineName = session.routineName,
                        routineId = session.routineId,
                        safetyFlags = session.safetyFlags.toLong(),
                        deloadWarningCount = session.deloadWarningCount.toLong(),
                        romViolationCount = session.romViolationCount.toLong(),
                        spotterActivations = session.spotterActivations.toLong(),
                        peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
                        peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
                        peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
                        peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
                        avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
                        avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
                        avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
                        avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
                        heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
                        totalVolumeKg = session.totalVolumeKg?.toDouble(),
                        cableCount = session.cableCount?.toLong(),
                        estimatedCalories = session.estimatedCalories?.toDouble(),
                        warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                        workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                        burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                        peakWeightKg = session.peakWeightKg?.toDouble(),
                        rpe = session.rpe?.toLong(),
                        avgMcvMmS = session.avgMcvMmS?.toDouble(),
                        avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
                        totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
                        dominantSide = session.dominantSide,
                        strengthProfile = session.strengthProfile,
                        formScore = session.formScore?.toLong(),
                        updatedAt = session.timestamp, // Mark as already-synced to prevent re-push
                        profile_id = session.profileId,
                        display_multiplier = session.displayMultiplier?.toLong(),
                        externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
                        counterweightKg = session.counterweightKg.toDouble(),
                        rackItemsJson = session.rackItemsJson,
                    )
                }
            }
            Logger.d { "Merged ${sessions.size} portal sessions (INSERT OR IGNORE)" }
        }
    }

    // === Exercise Lookup for Pull ===

    /**
     * Find exercise ID by catalog ID, name, and optionally muscle group for session enrichment during pull.
     *
     * Lookup strategy (in order):
     * 0. Direct ID lookup (unambiguous, O(1)) — added for #404
     * 1. Exact match on name + muscle group (if muscle group provided)
     * 2. Exact match on name only (via findExerciseByName)
     * 3. Case-insensitive match on name (fallback for portal name variations)
     *
     * @param name Exercise name from portal
     * @param muscleGroup Optional muscle group for disambiguation
     * @param exerciseId Optional catalog exercise ID (preferred, unambiguous)
     * @return Exercise ID if found, null otherwise
     */
    override suspend fun findExerciseId(name: String, muscleGroup: String?, exerciseId: String?): String? = withContext(Dispatchers.IO) {
        // Strategy 0: Direct ID lookup — O(1), unambiguous (#404)
        exerciseId?.let { id ->
            val match = queries.selectExerciseById(id).executeAsOneOrNull()
            if (match != null) return@withContext match.id
        }

        // Strategy 1: Try exact match with muscle group (most specific)
        if (muscleGroup != null) {
            val exactMatch = queries.findExerciseByNameAndMuscle(name, muscleGroup).executeAsOneOrNull()
            if (exactMatch != null) {
                return@withContext exactMatch.id
            }
        }

        // Strategy 2: Try exact match on name only
        val nameMatch = queries.findExerciseByName(name).executeAsOneOrNull()
        if (nameMatch != null) {
            return@withContext nameMatch.id
        }

        // Strategy 3: Case-insensitive fallback (handles "Bench Press" vs "bench press")
        val caseInsensitiveMatch = queries.findExerciseByNameCaseInsensitive(name).executeAsOneOrNull()
        return@withContext caseInsensitiveMatch?.id
    }

    /**
     * Resolves the catalog muscle group for a performed exercise during push.
     * Mirrors [findExerciseId]'s lookup order (ID → exact name → case-insensitive
     * name) but returns the muscle group rather than the ID. Returns null when the
     * exercise is not in the catalog so the caller can default to "General".
     */
    override suspend fun getExerciseMuscleGroup(exerciseId: String?, name: String?): String? = withContext(Dispatchers.IO) {
        // Strategy 0: Direct ID lookup - O(1), unambiguous. When the catalog row
        // is found by id it is the single source of truth, so return its
        // muscleGroup directly rather than falling through to fuzzier name-based
        // lookups (which could return a different exercise's group).
        if (exerciseId != null) {
            val byId = queries.selectExerciseById(exerciseId).executeAsOneOrNull()
            if (byId != null) return@withContext byId.muscleGroup
        }

        if (!name.isNullOrBlank()) {
            // Strategy 1: Exact name match
            queries.findExerciseByName(name).executeAsOneOrNull()?.muscleGroup?.let { return@withContext it }
            // Strategy 2: Case-insensitive name match
            queries.findExerciseByNameCaseInsensitive(name).executeAsOneOrNull()?.muscleGroup?.let { return@withContext it }
        }

        return@withContext null
    }

    // === Portal Push Operations (full domain objects) ===

    override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsModifiedSince(timestamp, profileId = profileId, ::mapToWorkoutSession).executeAsList()
    }

    override suspend fun getDirtyWorkoutSnapshot(profileId: String): WorkoutSyncSnapshot =
        withContext(Dispatchers.IO) {
            var snapshot = WorkoutSyncSnapshot(emptyList())
            db.transaction {
                val portalSessionIds = queries
                    .selectDirtyWorkoutPortalParentIds(profileId)
                    .executeAsList()
                if (portalSessionIds.isEmpty()) return@transaction

                val storageRows = queries
                    .selectLiveWorkoutComponentsForPortalParents(profileId, portalSessionIds)
                    .executeAsList()
                val sessions = queries
                    .selectLiveWorkoutComponentsForPortalParents(
                        profileId = profileId,
                        portalSessionIds = portalSessionIds,
                        mapper = ::mapToWorkoutSession,
                    )
                    .executeAsList()
                check(storageRows.size == sessions.size) {
                    "Workout snapshot storage/domain projection size mismatch"
                }
                val componentIds = storageRows.map { it.id }
                val repMetricsByComponentId = componentIds.associateWith { componentId ->
                    queries.selectRepMetricsBySession(componentId).executeAsList().map { row ->
                        RepMetricData(
                            repNumber = row.repNumber.toInt(),
                            isWarmup = row.isWarmup != 0L,
                            startTimestamp = row.startTimestamp,
                            endTimestamp = row.endTimestamp,
                            durationMs = row.durationMs,
                            concentricDurationMs = row.concentricDurationMs,
                            concentricPositions = row.concentricPositions.toFloatArrayFromJson(),
                            concentricLoadsA = row.concentricLoadsA.toFloatArrayFromJson(),
                            concentricLoadsB = row.concentricLoadsB.toFloatArrayFromJson(),
                            concentricVelocities = row.concentricVelocities.toFloatArrayFromJson(),
                            concentricTimestamps = row.concentricTimestamps.toLongArrayFromJson(),
                            eccentricDurationMs = row.eccentricDurationMs,
                            eccentricPositions = row.eccentricPositions.toFloatArrayFromJson(),
                            eccentricLoadsA = row.eccentricLoadsA.toFloatArrayFromJson(),
                            eccentricLoadsB = row.eccentricLoadsB.toFloatArrayFromJson(),
                            eccentricVelocities = row.eccentricVelocities.toFloatArrayFromJson(),
                            eccentricTimestamps = row.eccentricTimestamps.toLongArrayFromJson(),
                            peakForceA = row.peakForceA.toFloat(),
                            peakForceB = row.peakForceB.toFloat(),
                            avgForceConcentricA = row.avgForceConcentricA.toFloat(),
                            avgForceConcentricB = row.avgForceConcentricB.toFloat(),
                            avgForceEccentricA = row.avgForceEccentricA.toFloat(),
                            avgForceEccentricB = row.avgForceEccentricB.toFloat(),
                            peakVelocity = row.peakVelocity.toFloat(),
                            avgVelocityConcentric = row.avgVelocityConcentric.toFloat(),
                            avgVelocityEccentric = row.avgVelocityEccentric.toFloat(),
                            rangeOfMotionMm = row.rangeOfMotionMm.toFloat(),
                            peakPowerWatts = row.peakPowerWatts.toFloat(),
                            avgPowerWatts = row.avgPowerWatts.toFloat(),
                        )
                    }
                }
                val completedSets = if (componentIds.isEmpty()) {
                    emptyList()
                } else {
                    queries.selectCompletedSetsBySessionIds(componentIds) {
                            id,
                            sessionId,
                            plannedSetId,
                            routineExerciseId,
                            setNumber,
                            setType,
                            attemptNumber,
                            actualReps,
                            actualWeightKg,
                            loggedRpe,
                            isPr,
                            completedAt,
                            setEndReason,
                        ->
                        CompletedSet(
                            id = id,
                            sessionId = sessionId,
                            plannedSetId = plannedSetId,
                            routineExerciseId = routineExerciseId,
                            setNumber = setNumber.toInt(),
                            setType = SetType.valueOf(setType),
                            attemptNumber = attemptNumber.toInt().coerceAtLeast(1),
                            actualReps = actualReps.toInt(),
                            actualWeightKg = actualWeightKg.toFloat(),
                            loggedRpe = loggedRpe?.toInt(),
                            isPr = isPr == 1L,
                            completedAt = completedAt,
                            setEndReason = SetEndReason.fromPersisted(setEndReason),
                        )
                    }.executeAsList()
                }
                val phaseStatistics = componentIds.chunked(BATCH_LOOKUP_CHUNK_SIZE)
                    .flatMap { ids -> queries.selectPhaseStatsBySessionIds(ids).executeAsList() }
                val portalSessionIdsForNotes = storageRows.mapTo(linkedSetOf()) { row ->
                    row.routineSessionId?.takeIf { it.isNotBlank() } ?: row.id
                }
                val sessionNotesByPortalId = portalSessionIdsForNotes.chunked(BATCH_LOOKUP_CHUNK_SIZE)
                    .flatMap { ids -> queries.selectSessionNotesForIds(ids).executeAsList() }
                    .associate { row ->
                        row.routineSessionId to SessionNotesEntry(
                            notes = row.notes,
                            updatedAtMillis = row.updatedAt ?: 0L,
                        )
                    }
                snapshot = WorkoutSyncSnapshot(
                    components = storageRows.zip(sessions) { row, session ->
                        WorkoutComponentSnapshot(
                            session = session,
                            portalSessionId = row.routineSessionId?.takeIf { it.isNotBlank() } ?: row.id,
                            localSyncGeneration = row.local_sync_generation,
                        )
                    },
                    repMetricsByComponentId = repMetricsByComponentId,
                    completedSetsByComponentId = completedSets.groupBy { it.sessionId },
                    phaseStatisticsByComponentId = phaseStatistics.groupBy { it.sessionId },
                    sessionNotesByPortalId = sessionNotesByPortalId,
                )
            }
            snapshot
        }

    override suspend fun acknowledgeWorkoutSnapshot(
        snapshot: WorkoutSyncSnapshot,
        acceptedPortalSessionIds: Set<String>,
    ) = withContext(Dispatchers.IO) {
        if (acceptedPortalSessionIds.isEmpty()) return@withContext
        db.transaction {
            snapshot.components
                .asSequence()
                .filter { it.portalSessionId in acceptedPortalSessionIds }
                .forEach { component ->
                    queries.ackWorkoutComponentSnapshot(
                        id = component.session.id,
                        snapshotGeneration = component.localSyncGeneration,
                    )
                }
        }
    }

    override suspend fun getDeletedRoutineIdsSince(timestamp: Long, profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectDeletedRoutinesSince(timestamp, profileId = profileId).executeAsList().map { row ->
            // Prefer serverId (the ID the server knows) over local clientId
            row.serverId ?: row.id
        }
    }

    override suspend fun getDeletedCycleIdsSince(timestamp: Long, profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectDeletedCyclesSince(timestamp, profileId = profileId).executeAsList()
    }

    override suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String): List<Routine> = withContext(Dispatchers.IO) {
        val routineRows = queries.selectRoutinesModifiedSince(timestamp, profileId = profileId).executeAsList()
        routineRows.map { row ->
            val exerciseRows = queries.selectExercisesByRoutine(row.id).executeAsList()
            val supersetRows = queries.selectSupersetsByRoutine(row.id).executeAsList()

            val supersets = supersetRows.map { ssRow ->
                Superset(
                    id = ssRow.id,
                    routineId = ssRow.routineId,
                    name = ssRow.name,
                    colorIndex = ssRow.colorIndex.toInt(),
                    restBetweenSeconds = ssRow.restBetweenSeconds.toInt(),
                    orderIndex = ssRow.orderIndex.toInt(),
                )
            }

            val exercises = exerciseRows.mapNotNull { exRow ->
                try {
                    val exercise = Exercise(
                        id = exRow.exerciseId,
                        name = exRow.exerciseName,
                        muscleGroup = exRow.exerciseMuscleGroup,
                        muscleGroups = exRow.exerciseMuscleGroup,
                        equipment = exRow.exerciseEquipment,
                        isBodyweightOverride = exRow.isBodyweight?.let { it != 0L },
                    )

                    val setReps: List<Int?> = try {
                        exRow.setReps.split(",").map { value ->
                            val trimmed = value.trim()
                            if (trimmed.equals("AMRAP", ignoreCase = true)) null else trimmed.toIntOrNull()
                        }
                    } catch (_: Exception) {
                        listOf(10)
                    }

                    val setWeights: List<Float> = try {
                        if (exRow.setWeights.isBlank()) {
                            emptyList()
                        } else {
                            exRow.setWeights.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val setRestSeconds: List<Int> = try {
                        json.decodeFromString<List<Int>>(exRow.setRestSeconds)
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val setEchoLevels: List<EchoLevel?> = try {
                        if (exRow.setEchoLevels.isBlank()) {
                            emptyList()
                        } else {
                            json.decodeFromString<List<Int?>>(exRow.setEchoLevels).map { ordinal ->
                                ordinal?.let { EchoLevel.entries.getOrNull(it) }
                            }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val eccentricLoad = mapEccentricLoadFromDb(exRow.eccentricLoad)
                    val echoLevel = EchoLevel.entries.getOrNull(exRow.echoLevel.toInt()) ?: EchoLevel.HARDER
                    val programMode = parseProgramMode(exRow.mode)

                    val prTypeForScaling = try {
                        PRType.valueOf(exRow.prTypeForScaling)
                    } catch (_: Exception) {
                        PRType.MAX_WEIGHT
                    }

                    val setWeightsPercentOfPR: List<Int> = try {
                        if (exRow.setWeightsPercentOfPR.isNullOrBlank()) {
                            emptyList()
                        } else {
                            json.decodeFromString<List<Int>>(exRow.setWeightsPercentOfPR)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val warmupSets: List<WarmupSet> = try {
                        if (exRow.warmupSets.isBlank()) {
                            emptyList()
                        } else {
                            json.decodeFromString<List<WarmupSet>>(exRow.warmupSets)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val defaultRackItemIds: List<String> = try {
                        if (exRow.defaultRackItemIds.isBlank()) {
                            emptyList()
                        } else {
                            json.decodeFromString<List<String>>(exRow.defaultRackItemIds)
                                .filter { it.isNotBlank() }
                                .distinct()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val rackBehaviorOverrides: Map<String, RackItemBehavior> = try {
                        if (exRow.rackBehaviorOverrides.isBlank() || exRow.rackBehaviorOverrides == "{}") {
                            emptyMap()
                        } else {
                            json.decodeFromString<Map<String, RackItemBehavior>>(exRow.rackBehaviorOverrides)
                        }
                    } catch (_: Exception) {
                        emptyMap()
                    }

                    RoutineExercise(
                        id = exRow.id,
                        exercise = exercise,
                        orderIndex = exRow.orderIndex.toInt(),
                        setReps = setReps,
                        weightPerCableKg = exRow.weightPerCableKg.toFloat(),
                        setWeightsPerCableKg = setWeights,
                        programMode = programMode,
                        eccentricLoad = eccentricLoad,
                        echoLevel = echoLevel,
                        progressionKg = exRow.progressionKg.toFloat(),
                        setRestSeconds = setRestSeconds,
                        setEchoLevels = setEchoLevels,
                        duration = exRow.duration?.toInt(),
                        isAMRAP = exRow.isAMRAP == 1L,
                        perSetRestTime = exRow.perSetRestTime == 1L,
                        stallDetectionEnabled = exRow.stallDetectionEnabled == 1L,
                        dropSetEnabled = exRow.dropSetEnabled == 1L,
                        dropSetMinWeightKg = exRow.dropSetMinWeightKg?.toFloat(),
                        repCountTiming = try {
                            RepCountTiming.valueOf(exRow.repCountTiming)
                        } catch (
                            _: Exception,
                        ) {
                            RepCountTiming.TOP
                        },
                        stopAtTop = exRow.stopAtTop == 1L,
                        supersetId = exRow.supersetId,
                        orderInSuperset = exRow.orderInSuperset.toInt(),
                        usePercentOfPR = exRow.usePercentOfPR == 1L,
                        weightPercentOfPR = exRow.weightPercentOfPR.toInt(),
                        prTypeForScaling = prTypeForScaling,
                        setWeightsPercentOfPR = setWeightsPercentOfPR,
                        warmupSets = warmupSets,
                        defaultRackItemIds = defaultRackItemIds,
                        rackBehaviorOverrides = rackBehaviorOverrides,
                    )
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to map routine exercise: ${exRow.exerciseId}" }
                    null
                }
            }

            Routine(
                id = row.id,
                name = row.name,
                description = row.description,
                exercises = exercises,
                supersets = supersets,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                lastUsed = row.lastUsed,
                useCount = row.useCount.toInt(),
                profileId = row.profile_id,
                groupId = row.groupId,
            )
        }
    }

    override suspend fun getFullCyclesForSync(profileId: String): List<CycleWithContext> = withContext(Dispatchers.IO) {
        getFullCyclesForSyncNow(profileId)
    }

    private fun getFullCyclesForSyncNow(profileId: String): List<CycleWithContext> {
        val cycles = queries.selectTrainingCyclesByProfile(profileId = profileId).executeAsList()
        val allDays = queries.selectAllCycleDaysSync().executeAsList()
        val allProgress = queries.selectAllCycleProgressSync().executeAsList()
        val allProgressions = queries.selectAllCycleProgressionsSync().executeAsList()

        val daysByCycle = allDays.groupBy { it.cycle_id }
        val progressByCycle = allProgress.associateBy { it.cycle_id }
        val progressionByCycle = allProgressions.associateBy { it.cycle_id }

        return cycles.map { row ->
            val days = (daysByCycle[row.id] ?: emptyList()).map { d ->
                CycleDay(
                    id = d.id,
                    cycleId = d.cycle_id,
                    dayNumber = d.day_number.toInt(),
                    name = d.name,
                    routineId = d.routine_id,
                    isRestDay = d.is_rest_day == 1L,
                    echoLevel = d.echo_level?.let { lvl ->
                        try {
                            EchoLevel.valueOf(lvl)
                        } catch (_: Exception) {
                            null
                        }
                    },
                    eccentricLoadPercent = d.eccentric_load_percent?.toInt(),
                    weightProgressionPercent = d.weight_progression_percent?.toFloat(),
                    repModifier = d.rep_modifier?.toInt(),
                    restTimeOverrideSeconds = d.rest_time_override_seconds?.toInt(),
                )
            }

            val progress = progressByCycle[row.id]?.let { p ->
                CycleProgress(
                    id = p.id,
                    cycleId = p.cycle_id,
                    currentDayNumber = p.current_day_number.toInt(),
                    lastCompletedDate = p.last_completed_date,
                    cycleStartDate = p.cycle_start_date,
                    lastAdvancedAt = p.last_advanced_at,
                    completedDays = parseCycleDaySet(p.completed_days),
                    missedDays = parseCycleDaySet(p.missed_days),
                    rotationCount = p.rotation_count.toInt(),
                )
            }

            val progression = progressionByCycle[row.id]?.let { pg ->
                CycleProgression(
                    cycleId = pg.cycle_id,
                    frequencyCycles = pg.frequency_cycles.toInt(),
                    weightIncreasePercent = pg.weight_increase_percent?.toFloat(),
                    echoLevelIncrease = pg.echo_level_increase != 0L,
                    eccentricLoadIncreasePercent = pg.eccentric_load_increase_percent?.toInt(),
                )
            }

            CycleWithContext(
                cycle = TrainingCycle(
                    id = row.id,
                    name = row.name,
                    description = row.description,
                    days = days,
                    createdAt = row.created_at,
                    isActive = row.is_active == 1L,
                    weekNumber = row.week_number.toInt(),
                    profileId = row.profile_id,
                    templateId = row.template_id,
                    updatedAt = row.updatedAt,
                ),
                progress = progress,
                progression = progression,
            )
        }
    }

    // === Private Mappers (replicated from SqlDelightWorkoutRepository) ===

    @Suppress("LongParameterList")
    private fun mapToWorkoutSession(
        id: String,
        timestamp: Long,
        mode: String,
        targetReps: Long,
        weightPerCableKg: Double,
        progressionKg: Double,
        duration: Long,
        totalReps: Long,
        warmupReps: Long,
        workingReps: Long,
        isJustLift: Long,
        stopAtTop: Long,
        eccentricLoad: Long,
        echoLevel: Long,
        exerciseId: String?,
        exerciseName: String?,
        routineSessionId: String?,
        routineName: String?,
        routineId: String?,
        safetyFlags: Long,
        deloadWarningCount: Long,
        romViolationCount: Long,
        spotterActivations: Long,
        peakForceConcentricA: Double?,
        peakForceConcentricB: Double?,
        peakForceEccentricA: Double?,
        peakForceEccentricB: Double?,
        avgForceConcentricA: Double?,
        avgForceConcentricB: Double?,
        avgForceEccentricA: Double?,
        avgForceEccentricB: Double?,
        heaviestLiftKg: Double?,
        totalVolumeKg: Double?,
        cableCount: Long?,
        estimatedCalories: Double?,
        warmupAvgWeightKg: Double?,
        workingAvgWeightKg: Double?,
        burnoutAvgWeightKg: Double?,
        peakWeightKg: Double?,
        rpe: Long?,
        avgMcvMmS: Double?,
        avgAsymmetryPercent: Double?,
        totalVelocityLossPercent: Double?,
        dominantSide: String?,
        strengthProfile: String?,
        formScore: Long?,
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Multi-profile support (migration 21)
        profileId: String,
        // Equipment-aware weight display (migration 29)
        displayMultiplier: Long?,
        // Equipment rack context (migration 33)
        externalAddedLoadKg: Double,
        counterweightKg: Double,
        rackItemsJson: String,
        portalOrigin: Long,
        localSyncGeneration: Long,
        syncedSyncGeneration: Long,
    ): WorkoutSession = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = targetReps.toInt(),
        weightPerCableKg = weightPerCableKg.toFloat(),
        progressionKg = progressionKg.toFloat(),
        duration = duration,
        totalReps = totalReps.toInt(),
        warmupReps = warmupReps.toInt(),
        workingReps = workingReps.toInt(),
        isJustLift = isJustLift == 1L,
        stopAtTop = stopAtTop == 1L,
        eccentricLoad = eccentricLoad.toInt(),
        echoLevel = echoLevel.toInt(),
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = routineName,
        routineId = routineId,
        safetyFlags = safetyFlags.toInt(),
        deloadWarningCount = deloadWarningCount.toInt(),
        romViolationCount = romViolationCount.toInt(),
        spotterActivations = spotterActivations.toInt(),
        peakForceConcentricA = peakForceConcentricA?.toFloat(),
        peakForceConcentricB = peakForceConcentricB?.toFloat(),
        peakForceEccentricA = peakForceEccentricA?.toFloat(),
        peakForceEccentricB = peakForceEccentricB?.toFloat(),
        avgForceConcentricA = avgForceConcentricA?.toFloat(),
        avgForceConcentricB = avgForceConcentricB?.toFloat(),
        avgForceEccentricA = avgForceEccentricA?.toFloat(),
        avgForceEccentricB = avgForceEccentricB?.toFloat(),
        heaviestLiftKg = heaviestLiftKg?.toFloat(),
        totalVolumeKg = totalVolumeKg?.toFloat(),
        cableCount = cableCount?.toInt(),
        displayMultiplier = displayMultiplier?.toInt(),
        externalAddedLoadKg = externalAddedLoadKg.toFloat(),
        counterweightKg = counterweightKg.toFloat(),
        rackItemsJson = rackItemsJson,
        estimatedCalories = estimatedCalories?.toFloat(),
        warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
        workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
        burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
        peakWeightKg = peakWeightKg?.toFloat(),
        rpe = rpe?.toInt(),
        avgMcvMmS = avgMcvMmS?.toFloat(),
        avgAsymmetryPercent = avgAsymmetryPercent?.toFloat(),
        totalVelocityLossPercent = totalVelocityLossPercent?.toFloat(),
        dominantSide = dominantSide,
        strengthProfile = strengthProfile,
        formScore = formScore?.toInt(),
        profileId = profileId,
        updatedAt = updatedAt,
    )

    private fun mapEccentricLoadFromDb(dbValue: Long): EccentricLoad {
        val safeValue = dbValue.toInt().coerceIn(0, 150)
        return when (safeValue) {
            0 -> EccentricLoad.LOAD_0

            50 -> EccentricLoad.LOAD_50

            75 -> EccentricLoad.LOAD_75

            100 -> EccentricLoad.LOAD_100

            110 -> EccentricLoad.LOAD_110

            120 -> EccentricLoad.LOAD_120

            130 -> EccentricLoad.LOAD_130

            140 -> EccentricLoad.LOAD_140

            150 -> EccentricLoad.LOAD_150

            else -> EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safeValue) }
                ?: EccentricLoad.LOAD_100
        }
    }

    private fun parseProgramMode(modeStr: String): ProgramMode = when {
        modeStr.startsWith("Program:") -> {
            when (modeStr.removePrefix("Program:")) {
                "OldSchool" -> ProgramMode.OldSchool
                "Pump" -> ProgramMode.Pump
                "TUT" -> ProgramMode.TUT
                "TUTBeast" -> ProgramMode.TUTBeast
                "EccentricOnly" -> ProgramMode.EccentricOnly
                "Echo" -> ProgramMode.Echo
                else -> ProgramMode.OldSchool
            }
        }

        modeStr == "Echo" || modeStr.startsWith("Echo") -> ProgramMode.Echo

        modeStr == "Pump" -> ProgramMode.Pump

        modeStr == "TUT" -> ProgramMode.TUT

        modeStr == "TUTBeast" -> ProgramMode.TUTBeast

        modeStr == "EccentricOnly" -> ProgramMode.EccentricOnly

        modeStr == "OldSchool" -> ProgramMode.OldSchool

        else -> ProgramMode.OldSchool
    }

    // === Extended Sync Methods (GAPs 1-9) ===

    override suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecord> = withContext(Dispatchers.IO) {
        queries.selectPRsModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
            PersonalRecord(
                id = row.id,
                exerciseId = row.exerciseId,
                exerciseName = row.exerciseName,
                weightPerCableKg = row.weight.toFloat(),
                reps = row.reps.toInt(),
                oneRepMax = row.oneRepMax.toFloat(),
                timestamp = row.achievedAt,
                workoutMode = row.workoutMode,
                prType = when (row.prType) {
                    "MAX_VOLUME" -> PRType.MAX_VOLUME
                    else -> PRType.MAX_WEIGHT
                },
                volume = row.volume.toFloat(),
                phase = when (row.phase) {
                    "CONCENTRIC" -> WorkoutPhase.CONCENTRIC
                    "ECCENTRIC" -> WorkoutPhase.ECCENTRIC
                    else -> WorkoutPhase.COMBINED
                },
                profileId = row.profile_id,
                cableCount = row.cable_count?.toInt(),
                uuid = row.uuid,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
    }

    override suspend fun backfillPhaseSpecificPRs(
        profileId: String,
        fromSessionTimestamp: Long,
    ): PhasePRBackfillResult = withContext(Dispatchers.IO) {
        val prRepository = SqlDelightPersonalRecordRepository(db)
        val sessions = queries.selectSessionsForPhasePRBackfill(
            fromSessionTimestamp = fromSessionTimestamp,
            profileId = profileId,
            mapper = ::mapToWorkoutSession,
        ).executeAsList()
        var changedRows = 0
        var maxScannedSessionTimestamp: Long? = null
        var hadFailure = false

        for (session in sessions) {
            maxScannedSessionTimestamp = maxOf(
                maxScannedSessionTimestamp ?: session.timestamp,
                session.timestamp,
            )
            val exerciseId = session.exerciseId?.takeIf { it.isNotBlank() } ?: continue
            val reps = when {
                session.workingReps > 0 -> session.workingReps
                session.totalReps > 0 -> session.totalReps
                else -> 0
            }
            if (reps <= 0) continue

            val peakConcentric = maxOf(
                session.peakForceConcentricA ?: 0f,
                session.peakForceConcentricB ?: 0f,
            )
            val peakEccentric = maxOf(
                session.peakForceEccentricA ?: 0f,
                session.peakForceEccentricB ?: 0f,
            )
            if (peakConcentric <= 0f && peakEccentric <= 0f) continue

            val result = prRepository.updatePhaseSpecificPRs(
                exerciseId = exerciseId,
                workoutMode = session.mode,
                timestamp = session.timestamp,
                reps = reps,
                peakConcentricForceKg = peakConcentric,
                peakEccentricForceKg = peakEccentric,
                profileId = session.profileId,
                cableCount = session.displayMultiplier ?: session.cableCount,
            )
            changedRows += result.getOrElse { error ->
                hadFailure = true
                Logger.e(error) {
                    "Phase PR backfill failed for session=${session.id}, exercise=$exerciseId, profile=${session.profileId}"
                }
                emptyList()
            }.size
        }

        val checkpointTimestamp = maxScannedSessionTimestamp
            ?: queries.selectLatestSessionForPhasePRBackfill(
                fromSessionTimestamp = fromSessionTimestamp,
                profileId = profileId,
                mapper = ::mapToWorkoutSession,
            ).executeAsOneOrNull()
                ?.timestamp

        PhasePRBackfillResult(
            changedRows = changedRows,
            maxScannedSessionTimestamp = checkpointTimestamp.takeUnless { hadFailure },
        )
    }

    override suspend fun findSessionIdsForPersonalRecords(
        records: List<PersonalRecord>,
        profileId: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext emptyMap()

        records
            .distinctBy { record -> personalRecordSessionKey(record.exerciseId, record.timestamp) }
            .mapNotNull { record ->
                val sessionId = queries.selectSessionIdForPersonalRecord(
                    exerciseId = record.exerciseId,
                    timestamp = record.timestamp,
                    profileId = profileId,
                ).executeAsOneOrNull()
                sessionId?.let { personalRecordSessionKey(record.exerciseId, record.timestamp) to it }
            }
            .toMap()
    }

    override suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<com.devil.phoenixproject.database.PhaseStatistics> {
        if (sessionIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            queries.selectPhaseStatsBySessionIds(sessionIds).executeAsList()
        }
    }

    override suspend fun getAllAssessments(profileId: String): List<com.devil.phoenixproject.database.AssessmentResult> = withContext(Dispatchers.IO) {
        queries.selectAllAssessments(profileId = profileId).executeAsList()
    }

    /**
     * Merge personal records from portal (pull-path).
     *
     * CONFLICT RESOLUTION STRATEGY: LOCAL WINS (INSERT OR IGNORE)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 2 "Personal Records"
     *
     * PRs are computed from local workout sessions. Mobile computes PRs.
     * If the same PR exists locally (same compound key: exerciseId, workoutMode, prType, phase),
     * the local version is authoritative and the remote PR is silently dropped.
     *
     * Multi-device scenario: Both devices compute same PR from same session - no conflict
     * because the data is identical. Different PRs from different sessions both get inserted
     * (different compound keys).
     */
    override suspend fun mergePersonalRecords(records: List<PersonalRecordSyncDto>, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                mergePersonalRecordRows(records, profileId)
            }
            Logger.d { "Merged ${records.size} personal records from portal (profile=$profileId)" }
        }
    }

    /** Must run inside the same transaction that accepts the incoming cycle projection. */
    private fun captureCycleConflictDraftIfNeeded(
        existing: com.devil.phoenixproject.database.TrainingCycle?,
        existingDays: List<com.devil.phoenixproject.database.CycleDay>,
        incomingUpdatedAt: Long?,
    ) {
        if (existing == null || incomingUpdatedAt == null || incomingUpdatedAt <= existing.updatedAt) return
        val syncState = queries.selectCycleSyncState(existing.id).executeAsOneOrNull() ?: return
        if (existing.deletedAt != null || syncState.dirty_generation <= syncState.acknowledged_generation) return

        val progress = queries.selectCycleProgressByCycle(existing.id).executeAsOneOrNull()
        val progression = queries.selectCycleProgression(existing.id).executeAsOneOrNull()
        val payload = PersistedCycleDraftPayload(
            cycle = TrainingCycleBackup(
                id = existing.id,
                name = existing.name,
                description = existing.description,
                createdAt = existing.created_at,
                isActive = false,
                profileId = existing.profile_id,
                templateId = existing.template_id,
                weekNumber = existing.week_number.toInt(),
                updatedAt = existing.updatedAt,
            ),
            days = existingDays.map { day ->
                CycleDayBackup(
                    id = day.id,
                    cycleId = day.cycle_id,
                    dayNumber = day.day_number.toInt(),
                    name = day.name,
                    routineId = day.routine_id,
                    isRestDay = day.is_rest_day == 1L,
                    echoLevel = day.echo_level,
                    eccentricLoadPercent = day.eccentric_load_percent?.toInt(),
                    weightProgressionPercent = day.weight_progression_percent?.toFloat(),
                    repModifier = day.rep_modifier?.toInt(),
                    restTimeOverrideSeconds = day.rest_time_override_seconds?.toInt(),
                )
            },
            progress = progress?.let {
                CycleProgressBackup(
                    id = it.id,
                    cycleId = it.cycle_id,
                    currentDayNumber = it.current_day_number.toInt(),
                    lastCompletedDate = it.last_completed_date,
                    cycleStartDate = it.cycle_start_date,
                    lastAdvancedAt = it.last_advanced_at,
                    completedDays = it.completed_days,
                    missedDays = it.missed_days,
                    rotationCount = it.rotation_count.toInt(),
                )
            },
            progression = progression?.let {
                CycleProgressionBackup(
                    cycleId = it.cycle_id,
                    frequencyCycles = it.frequency_cycles.toInt(),
                    weightIncreasePercent = it.weight_increase_percent?.toFloat(),
                    echoLevelIncrease = if (it.echo_level_increase != 0L) 1 else 0,
                    eccentricLoadIncreasePercent = it.eccentric_load_increase_percent?.toInt(),
                )
            },
        )
        queries.insertCycleConflictDraftIfAbsent(
            id = "${existing.id}:${existing.updatedAt}",
            cycleId = existing.id,
            originalProfileId = existing.profile_id,
            rejectedUpdatedAt = incomingUpdatedAt,
            payloadJson = json.encodeToString(payload),
            createdAt = currentTimeMillis(),
            resolution = null,
        )
    }

    /**
     * Applies an authoritative progression projection without confusing an explicit clear
     * with an older payload that did not carry presence metadata.
     *
     * Legacy payloads with a non-null value still update the row. A missing value without
     * `progressionSettingsPresent=true` preserves the local row, while true + null deletes it.
     */
    private fun mergePulledCycleProgression(portalCycle: PullTrainingCycleDto) {
        val progressionJson = portalCycle.progressionSettings
        val authoritative = portalCycle.progressionSettingsPresent == true
        if (!authoritative && progressionJson == null) return
        if (authoritative && progressionJson == null) {
            queries.deleteCycleProgression(portalCycle.id)
            return
        }

        try {
            val map = json.decodeFromString<Map<String, String>>(requireNotNull(progressionJson))
            queries.upsertCycleProgression(
                cycle_id = portalCycle.id,
                frequency_cycles = map["frequencyCycles"]?.toLongOrNull() ?: 2L,
                weight_increase_percent = map["weightIncreasePercent"]?.toDoubleOrNull(),
                echo_level_increase = if (map["echoLevelIncrease"] == "true") 1L else 0L,
                eccentric_load_increase_percent = map["eccentricLoadIncreasePercent"]?.toLongOrNull(),
            )
        } catch (error: Exception) {
            Logger.w(error) { "Failed to parse progressionSettings for cycle ${portalCycle.id}" }
        }
    }

    /** Apply complete remote progress only when the presence bit makes it authoritative. */
    private fun mergePulledCycleProgress(portalCycle: PullTrainingCycleDto) {
        if (portalCycle.progressStatePresent != true) return
        val state = portalCycle.progressState
        if (state == null) {
            queries.deleteCycleProgress(portalCycle.id)
            return
        }

        val existing = queries.selectCycleProgressByCycle(portalCycle.id).executeAsOneOrNull()
        val completedDays = json.encodeToString(state.completedDays.distinct().sorted())
        val missedDays = json.encodeToString(state.missedDays.distinct().sorted())
        if (existing == null) {
            queries.insertCycleProgress(
                id = generateUUID(),
                cycle_id = portalCycle.id,
                current_day_number = state.currentDayNumber.toLong(),
                last_completed_date = state.lastCompletedDate,
                cycle_start_date = state.cycleStartDate,
                last_advanced_at = state.lastAdvancedAt,
                completed_days = completedDays,
                missed_days = missedDays,
                rotation_count = state.rotationCount.toLong(),
            )
        } else {
            queries.updateCycleProgress(
                current_day_number = state.currentDayNumber.toLong(),
                last_completed_date = state.lastCompletedDate,
                cycle_start_date = state.cycleStartDate,
                last_advanced_at = state.lastAdvancedAt,
                completed_days = completedDays,
                missed_days = missedDays,
                rotation_count = state.rotationCount.toLong(),
                cycle_id = portalCycle.id,
            )
        }
    }

    private fun mergePersonalRecordRows(records: List<PersonalRecordSyncDto>, profileId: String) {
        records.forEach { dto ->
            val prUuid = dto.clientId.ifBlank { generateUUID() }
            // Materialize an unknown remote PR first. The state-only LWW update below
            // immediately turns it into a hidden tombstone when appropriate.
            val effectiveVolume = if (dto.volume > 0f) dto.volume else dto.weight * dto.reps
            queries.adoptServerPrUuid(
                uuid = prUuid,
                exerciseId = dto.exerciseId,
                prType = dto.prType,
                phase = dto.phase,
                profileId = profileId,
                achievedAt = dto.achievedAt,
            )
            queries.insertPRIgnore(
                exerciseId = dto.exerciseId,
                exerciseName = dto.exerciseName,
                weight = dto.weight.toDouble(),
                reps = dto.reps.toLong(),
                oneRepMax = dto.oneRepMax.toDouble(),
                achievedAt = dto.achievedAt,
                workoutMode = dto.workoutMode,
                prType = dto.prType,
                volume = effectiveVolume.toDouble(),
                phase = dto.phase,
                profile_id = profileId,
                cable_count = dto.cableCount?.toLong(),
                uuid = prUuid,
            )
            // A newer active row can restore a record; an older active row cannot
            // clear a tombstone, and an equal-time tombstone wins deterministically.
            queries.applyPulledPersonalRecordTombstoneState(
                deletedAt = dto.deletedAt,
                updatedAt = dto.updatedAt,
                exerciseName = dto.exerciseName,
                weight = dto.weight.toDouble(),
                reps = dto.reps.toLong(),
                oneRepMax = dto.oneRepMax.toDouble(),
                achievedAt = dto.achievedAt,
                volume = effectiveVolume.toDouble(),
                cableCount = dto.cableCount?.toLong(),
                uuid = prUuid,
                profileId = profileId,
            )
        }
    }

    // === Atomic Pull Merge ===

    /**
     * Atomically merge all pulled entities in a single database transaction.
     *
     * This implementation wraps all merge operations in a single transaction to ensure
     * atomicity. If ANY merge fails, the entire transaction rolls back, leaving the
     * database in its pre-pull state.
     *
     * This prevents partial sync states like:
     * - Sessions merged but their linked routines missing
     * - Cycles merged but badges/stats left inconsistent
     * - PRs merged but exercise catalog links broken
     *
     * DESIGN NOTE: Each individual merge method (mergePortalSessions, mergePortalRoutines, etc.)
     * also wraps in its own transaction for backward compatibility. SQLDelight nested transactions
     * are handled via savepoints, so this outer transaction safely encompasses all inner operations.
     *
     * @see mergePortalSessions for session conflict resolution (LOCAL WINS)
     * @see mergePortalRoutines for routine conflict resolution (TIMESTAMP LWW)
     * @see mergePortalCycles for cycle conflict resolution (SERVER WINS + single-active enforcement)
     * @see mergeBadges for badge conflict resolution (UNION)
     * @see mergeGamificationStats for stats conflict resolution (SERVER WINS)
     * @see mergePersonalRecords for PR conflict resolution (LOCAL WINS)
     */
    override suspend fun mergeAllPullData(
        ownerUserId: String,
        workoutDeletions: List<PulledWorkoutDeletionDto>,
        sessions: List<WorkoutSession>,
        routines: List<PullRoutineDto>,
        cycles: List<PullTrainingCycleDto>,
        badges: List<EarnedBadgeSyncDto>,
        gamificationStats: GamificationStatsSyncDto?,
        personalRecords: List<PersonalRecordSyncDto>,
        lastSync: Long,
        profileId: String,
        sessionNotes: Map<String, SessionNotesEntry>,
        sessionUpdatedAtById: Map<String, Long>,
    ) {
        withContext(Dispatchers.IO) {
            // Use a single outer transaction that wraps all entity merges.
            // SQLDelight handles nested transactions via savepoints, so if any inner
            // operation throws, the entire outer transaction rolls back.
            db.transaction {
                // Permanent account-scoped tombstones are applied before any live
                // projection so a stale/late pull cannot resurrect deleted content.
                for (deletion in workoutDeletions) {
                    applyPulledWorkoutDeletion(ownerUserId, deletion, profileId)
                }

                val routedSessions = sessions.map { session ->
                    val portalSessionId = session.routineSessionId?.takeIf { it.isNotBlank() } ?: session.id
                    val claimedTargetProfileId = claimedPullProfileId(
                        ownerUserId = ownerUserId,
                        entityType = OwnershipEntityType.WORKOUT,
                        entityId = portalSessionId,
                    )
                    val targetProfileId = claimedTargetProfileId ?: profileId
                    if (claimedTargetProfileId != null) {
                        queries.adoptSessionProfile(profileId = targetProfileId, id = session.id)
                    }
                    session.copy(profileId = targetProfileId)
                }

                val liveSessions = routedSessions.filterNot { session ->
                    val portalSessionId = session.routineSessionId?.takeIf { it.isNotBlank() } ?: session.id
                    queries.selectBlockingWorkoutDeletion(
                        ownerUserId = ownerUserId,
                        portalSessionId = portalSessionId,
                        componentSessionId = session.id,
                    ).executeAsOneOrNull() != null
                }
                val liveSessionNotes = sessionNotes.filterKeys { portalSessionId ->
                    queries.selectBlockingWorkoutDeletion(
                        ownerUserId = ownerUserId,
                        portalSessionId = portalSessionId,
                        componentSessionId = null,
                    ).executeAsOneOrNull() == null
                }

                val useLwwProjection = sessionUpdatedAtById.values.any { it > 0L }
                if (useLwwProjection) {
                    mergeSessionsLwwInTransaction(liveSessions, sessionUpdatedAtById)
                }

                // 1. Sessions — INSERT OR IGNORE (local wins)
                // Full field list matches mergePortalSessions
                for (session in if (useLwwProjection) emptyList() else liveSessions) {
                    queries.insertSessionIgnore(
                        id = session.id,
                        timestamp = session.timestamp,
                        mode = session.mode,
                        targetReps = session.reps.toLong(),
                        weightPerCableKg = session.weightPerCableKg.toDouble(),
                        progressionKg = session.progressionKg.toDouble(),
                        duration = session.duration,
                        totalReps = session.totalReps.toLong(),
                        warmupReps = session.warmupReps.toLong(),
                        workingReps = session.workingReps.toLong(),
                        isJustLift = if (session.isJustLift) 1L else 0L,
                        stopAtTop = if (session.stopAtTop) 1L else 0L,
                        eccentricLoad = session.eccentricLoad.toLong(),
                        echoLevel = session.echoLevel.toLong(),
                        exerciseId = session.exerciseId,
                        exerciseName = session.exerciseName,
                        routineSessionId = session.routineSessionId,
                        routineName = session.routineName,
                        routineId = session.routineId,
                        safetyFlags = session.safetyFlags.toLong(),
                        deloadWarningCount = session.deloadWarningCount.toLong(),
                        romViolationCount = session.romViolationCount.toLong(),
                        spotterActivations = session.spotterActivations.toLong(),
                        peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
                        peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
                        peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
                        peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
                        avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
                        avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
                        avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
                        avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
                        heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
                        totalVolumeKg = session.totalVolumeKg?.toDouble(),
                        cableCount = session.cableCount?.toLong(),
                        estimatedCalories = session.estimatedCalories?.toDouble(),
                        warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                        workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                        burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                        peakWeightKg = session.peakWeightKg?.toDouble(),
                        rpe = session.rpe?.toLong(),
                        avgMcvMmS = session.avgMcvMmS?.toDouble(),
                        avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
                        totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
                        dominantSide = session.dominantSide,
                        strengthProfile = session.strengthProfile,
                        formScore = session.formScore?.toLong(),
                        updatedAt = session.timestamp, // Mark as already-synced
                        profile_id = session.profileId,
                        display_multiplier = session.displayMultiplier?.toLong(),
                        externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
                        counterweightKg = session.counterweightKg.toDouble(),
                        rackItemsJson = session.rackItemsJson,
                    )
                }

                // 2. Routines — TIMESTAMP LWW (local wins if modified after lastSync)
                for (portalRoutine in routines) {
                    val claimedTargetProfileId = claimedPullProfileId(
                        ownerUserId = ownerUserId,
                        entityType = OwnershipEntityType.ROUTINE,
                        entityId = portalRoutine.id,
                    )
                    val targetProfileId = claimedTargetProfileId ?: profileId
                    if (claimedTargetProfileId != null) {
                        queries.adoptRoutineProfile(profileId = targetProfileId, id = portalRoutine.id)
                    }
                    mergePortalRoutine(portalRoutine, lastSync, targetProfileId)
                }

                // 3. Cycles — SERVER WINS with single-active enforcement
                val portalActiveCycleIdsByProfile = linkedMapOf<String, String>()
                val affectedCycleProfileIds = linkedSetOf<String>()
                for (portalCycle in cycles) {
                    val claimedTargetProfileId = claimedPullProfileId(
                        ownerUserId = ownerUserId,
                        entityType = OwnershipEntityType.CYCLE,
                        entityId = portalCycle.id,
                    )
                    val targetProfileId = claimedTargetProfileId ?: profileId
                    affectedCycleProfileIds += targetProfileId
                    if (claimedTargetProfileId != null) {
                        queries.adoptTrainingCycleProfile(profileId = targetProfileId, id = portalCycle.id)
                        queries.adoptCycleSyncStateForCycle(
                            targetProfileId = targetProfileId,
                            cycleId = portalCycle.id,
                        )
                    }
                    // Snapshot existing days before replacement so a lossy null pull can preserve
                    // a valid local-only template association by day number.
                    val existingCycle = queries.selectTrainingCycleById(portalCycle.id).executeAsOneOrNull()
                    val incomingUpdatedAt = portalCycle.updatedAt
                    if (existingCycle != null &&
                        (incomingUpdatedAt == null || incomingUpdatedAt < existingCycle.updatedAt)
                    ) {
                        continue
                    }
                    if (portalCycle.status == "active") {
                        portalActiveCycleIdsByProfile[targetProfileId] = portalCycle.id
                    }
                    val existingCycleDays = existingCycle?.let {
                        queries.selectCycleDaysByCycle(portalCycle.id).executeAsList()
                    } ?: emptyList()

                    captureCycleConflictDraftIfNeeded(
                        existing = existingCycle,
                        existingDays = existingCycleDays,
                        incomingUpdatedAt = incomingUpdatedAt,
                    )

                    queries.insertTrainingCycleIgnore(
                        id = portalCycle.id,
                        name = portalCycle.name,
                        description = portalCycle.description,
                        created_at = currentTimeMillis(),
                        is_active = 0L,
                        profile_id = targetProfileId,
                        template_id = portalCycle.templateId,
                        week_number = portalCycle.currentWeek?.toLong() ?: 1L,
                        updatedAt = incomingUpdatedAt ?: 0L,
                    )
                    queries.insertCycleSyncStateIfAbsent(
                        cycleId = portalCycle.id,
                        profileId = targetProfileId,
                        accountId = ownerUserId,
                        dirtyGeneration = 0L,
                        acknowledgedGeneration = 0L,
                        pendingDeleteUpdatedAt = null,
                        pendingDeleteGeneration = null,
                    )

                    // Only update pre-existing cycles; newly-inserted rows already have correct values.
                    if (existingCycle != null) {
                        val mergedTemplateId = portalCycle.templateId ?: existingCycle.template_id
                        val mergedWeekNumber = portalCycle.currentWeek?.toLong() ?: existingCycle.week_number
                        queries.updateTrainingCycle(
                            name = portalCycle.name,
                            description = portalCycle.description,
                            is_active = existingCycle.is_active, // Preserve; single-active enforcement runs at end
                            template_id = mergedTemplateId,
                            week_number = mergedWeekNumber,
                            updatedAt = requireNotNull(incomingUpdatedAt),
                            id = portalCycle.id,
                        )
                    }

                    queries.deleteCycleDaysByCycle(portalCycle.id)
                    for (day in portalCycle.days) {
                        val existingDay = existingCycleDays.firstOrNull { it.id == day.id }
                            ?: existingCycleDays.firstOrNull { it.day_number == day.dayNumber.toLong() }
                        queries.insertCycleDayIgnore(
                            id = day.id,
                            cycle_id = day.cycleId.ifEmpty { portalCycle.id },
                            day_number = day.dayNumber.toLong(),
                            name = day.notes,
                            routine_id = preservedTemplateCycleRoutineId(
                                existingCycleProfileId = existingCycle?.profile_id,
                                existingCycleDeletedAt = existingCycle?.deletedAt,
                                existingDays = existingCycleDays,
                                portalDay = day,
                                profileId = targetProfileId,
                            ),
                            is_rest_day = if (day.dayType == "rest") 1L else 0L,
                            echo_level = if (day.echoLevelPresent == true) day.echoLevel else existingDay?.echo_level,
                            eccentric_load_percent = if (day.eccentricLoadPercentPresent == true) {
                                day.eccentricLoadPercent?.toLong()
                            } else {
                                existingDay?.eccentric_load_percent
                            },
                            weight_progression_percent = day.weightAdjustment.toDouble(),
                            rep_modifier = day.repModifier.toLong(),
                            rest_time_override_seconds = day.restOverride?.toLong(),
                        )
                    }

                    mergePulledCycleProgression(portalCycle)
                    mergePulledCycleProgress(portalCycle)
                }

                // Single-active enforcement for cycles
                for ((targetProfileId, portalActiveCycleId) in portalActiveCycleIdsByProfile) {
                    queries.deactivateAllCycles(targetProfileId)
                    val activeCycle = cycles.first { it.id == portalActiveCycleId }
                    val storedActiveCycle = queries.selectTrainingCycleById(portalActiveCycleId).executeAsOne()
                    queries.updateTrainingCycle(
                        name = activeCycle.name,
                        description = activeCycle.description,
                        is_active = 1L,
                        template_id = storedActiveCycle.template_id,
                        week_number = storedActiveCycle.week_number,
                        updatedAt = storedActiveCycle.updatedAt,
                        id = portalActiveCycleId,
                    )
                }

                // Post-merge invariant check for cycles
                for (targetProfileId in affectedCycleProfileIds) {
                    val activeCycleCount = queries.countActiveCycles(targetProfileId).executeAsOne()
                    if (activeCycleCount > 1) {
                        Logger.e { "INVARIANT VIOLATION in atomic merge: Found $activeCycleCount active cycles. Forcing deactivation." }
                        queries.deactivateAllCycles(targetProfileId)
                        portalActiveCycleIdsByProfile[targetProfileId]?.let { id ->
                            val activeCycle = cycles.first { it.id == id }
                            val storedActiveCycle = queries.selectTrainingCycleById(id).executeAsOne()
                            queries.updateTrainingCycle(
                                name = activeCycle.name,
                                description = activeCycle.description,
                                is_active = 1L,
                                template_id = storedActiveCycle.template_id,
                                week_number = storedActiveCycle.week_number,
                                updatedAt = storedActiveCycle.updatedAt,
                                id = id,
                            )
                        }
                    }
                }

                // 4. Badges — UNION (INSERT OR IGNORE)
                for (badge in badges) {
                    queries.insertEarnedBadge(badge.badgeId, badge.earnedAt, profileId = profileId)
                }

                // 5. Gamification stats — SERVER WINS with local field preservation
                if (gamificationStats != null) {
                    val now = currentTimeMillis()
                    val existingStats = queries.selectGamificationStats(profileId = profileId).executeAsOneOrNull()
                    val stableId = profileId.hashCode().toLong()
                    queries.upsertGamificationStats(
                        id = stableId,
                        totalWorkouts = gamificationStats.totalWorkouts.toLong(),
                        totalReps = gamificationStats.totalReps.toLong(),
                        // Round (not truncate) to minimise systematic bias; column is INTEGER in schema.
                        totalVolumeKg = gamificationStats.totalVolumeKg.toDouble().roundToLong(),
                        longestStreak = gamificationStats.longestStreak.toLong(),
                        currentStreak = gamificationStats.currentStreak.toLong(),
                        uniqueExercisesUsed = existingStats?.uniqueExercisesUsed ?: 0L,
                        prsAchieved = existingStats?.prsAchieved ?: 0L,
                        lastWorkoutDate = existingStats?.lastWorkoutDate,
                        streakStartDate = existingStats?.streakStartDate,
                        lastUpdated = now,
                        profileId = profileId,
                    )
                }

                // 6. Personal records — INSERT OR IGNORE (local wins)
                for (personalRecord in personalRecords) {
                    val stableId = personalRecord.clientId.ifBlank { generateUUID() }
                    val claimedTargetProfileId = claimedPullProfileId(
                        ownerUserId = ownerUserId,
                        entityType = OwnershipEntityType.PERSONAL_RECORD,
                        entityId = stableId,
                    )
                    val targetProfileId = claimedTargetProfileId ?: profileId
                    if (claimedTargetProfileId != null) {
                        queries.adoptPersonalRecordProfileByUuid(
                            profileId = targetProfileId,
                            uuid = stableId,
                        )
                    }
                    mergePersonalRecordRows(listOf(personalRecord), targetProfileId)
                }

                // Session notes share the deletion/live transaction. A WORKOUT
                // tombstone blocks its aggregate note row permanently.
                mergeSessionNotesInTransaction(liveSessionNotes)
            }

            Logger.d {
                "Atomic merge complete: ${sessions.size} sessions, ${routines.size} routines, " +
                    "${cycles.size} cycles, ${badges.size} badges, ${personalRecords.size} PRs (profile=$profileId)"
            }
        }
    }

    // === Parity Reconciliation ===

    override suspend fun hardDeleteCyclesByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.transaction {
                // Delete progress and days first (explicit cleanup before parent deletion)
                for (id in ids) {
                    queries.deleteCycleProgress(id)
                    queries.deleteCycleDaysByCycle(id)
                }
                queries.hardDeleteCyclesByIds(ids)
            }
        }
    }

    override suspend fun hardDeleteRoutinesByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.transaction {
                for (id in ids) {
                    queries.deleteRoutineExercises(id)
                    queries.deleteSupersetsByRoutine(id)
                }
                queries.hardDeleteRoutinesByIds(ids)
            }
        }
    }

    // === Parity Sync Operations ===

    override suspend fun getAllSessionIds(profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectAllSessionIdsByProfile(profileId).executeAsList()
    }

    override suspend fun getAllRoutineIds(profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectAllRoutineIdsByProfile(profileId).executeAsList()
    }

    override suspend fun getAllCycleIds(profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectAllCycleIdsByProfile(profileId).executeAsList()
    }

    override suspend fun getAllBadgeIds(profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectAllBadgeIdsByProfile(profileId).executeAsList()
    }

    override suspend fun getAllPersonalRecordIds(profileId: String): List<String> = withContext(Dispatchers.IO) {
        queries.selectAllPersonalRecordUuidsByProfile(profileId).executeAsList()
    }

    /**
     * Phase 3.5 — Persist session-level notes from the portal pull.
     *
     * SQLDelight is on the sqlite 3.18 dialect which does not support
     * INSERT ... ON CONFLICT DO UPDATE WHERE, so the LWW gate lives here in
     * Kotlin: SELECT existing.updatedAt, compare against incoming, then
     * either INSERT OR REPLACE or skip. Wrapped in a single transaction so
     * the read+write pair is atomic per row.
     */

    /**
     * Merge the portal exercise-row projection without replacing its local parent
     * row. New rows are inserted as portal-origin. Existing rows are eligible only
     * when their stored provenance is portal-origin, their owner matches, the
     * incoming version is current, and no retained tombstone would be resurrected.
     * The read, version decision, and narrow UPDATE share one transaction.
     *
     * `updatedAtBySessionId` keys on the per-exercise WorkoutSession.id
     * (== portal exercise id; one portal session expands to N mobile rows
     * in PortalPullAdapter.toWorkoutSessionsWithLookup). Missing entries
     * default to "older" so first-time pulls always write.
     *
     * Issue #591: When the incoming pull row is null in detailed metric
     * columns (`peakForce*`, `avgForce*`, biomechanics, etc.) but the
     * local row has richer measurements, do not erase those measurements.
     * Only portal-origin rows reach this field-level projection; captured and
     * legacy-local rows are preserved as complete local facts.
     */
    override suspend fun mergeSessionsLww(
        sessions: List<WorkoutSession>,
        updatedAtBySessionId: Map<String, Long>,
    ) = withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) return@withContext
        db.transaction {
            mergeSessionsLwwInTransaction(sessions, updatedAtBySessionId)
        }
    }

    private fun parseCycleDaySet(value: String?): Set<Int> = value
        ?.trim('[', ']')
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.toSet()
        .orEmpty()

    override suspend fun getDirtyCycleSnapshot(profileId: String): CycleSyncSnapshot = withContext(Dispatchers.IO) {
        db.transactionWithResult {
            val dirtyGenerations = queries.selectDirtyCycleStates(profileId)
                .executeAsList()
                .associate { it.cycle_id to it.dirty_generation }
            if (dirtyGenerations.isEmpty()) return@transactionWithResult CycleSyncSnapshot(emptyList())
            val complete = getFullCyclesForSyncNow(profileId).associateBy { it.cycle.id }
            CycleSyncSnapshot(
                dirtyGenerations.mapNotNull { (cycleId, generation) ->
                    complete[cycleId]?.let { context ->
                        CycleComponentSnapshot(context, generation)
                    }
                },
            )
        }
    }

    override suspend fun acknowledgeCycleSnapshot(
        snapshot: CycleSyncSnapshot,
        acceptedCycleIds: Set<String>,
    ) {
        if (acceptedCycleIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.transaction {
                snapshot.components.forEach { component ->
                    if (component.context.cycle.id in acceptedCycleIds) {
                        queries.ackCycleStateIfGenerationMatches(
                            generation = component.localSyncGeneration,
                            cycleId = component.context.cycle.id,
                        )
                    }
                }
            }
        }
    }

    private fun applyPulledWorkoutDeletion(
        ownerUserId: String,
        deletion: PulledWorkoutDeletionDto,
        fallbackProfileId: String,
    ) {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        val deletedAt = kotlin.time.Instant.parse(deletion.deletedAt).toEpochMilliseconds()
        val routedProfileId = deletion.profileId?.takeIf { it.isNotBlank() } ?: fallbackProfileId
        val componentSessionId = deletion.componentSessionId
        require(deletion.scope != WorkoutDeletionScope.COMPONENT || !componentSessionId.isNullOrBlank()) {
            "COMPONENT workout deletion requires componentSessionId"
        }

        val existing = queries.selectWorkoutDeletionByMutationId(deletion.mutationId).executeAsOneOrNull()
        if (existing == null) {
            queries.insertWorkoutDeletion(
                mutationId = deletion.mutationId,
                ownerUserId = ownerUserId,
                profileId = routedProfileId,
                scope = deletion.scope.name,
                portalSessionId = deletion.portalSessionId,
                componentSessionId = componentSessionId,
                deletedAt = deletedAt,
                source = WorkoutDeletionSource.REMOTE.name,
            )
        } else {
            check(existing.owner_user_id == ownerUserId) {
                "Workout deletion mutation owner conflict: ${deletion.mutationId}"
            }
            check(
                existing.scope == deletion.scope.name &&
                    existing.portal_session_id == deletion.portalSessionId &&
                    existing.component_session_id == componentSessionId &&
                    existing.deleted_at == deletedAt,
            ) {
                "Workout deletion mutation body conflict: ${deletion.mutationId}"
            }
        }

        when (deletion.scope) {
            WorkoutDeletionScope.WORKOUT -> {
                val targets = queries.selectLiveWorkoutComponentsForDeletion(deletion.portalSessionId).executeAsList()
                targets.forEach { target ->
                    if (profileOwnerMatches(target.profile_id, ownerUserId)) {
                        queries.hardDeleteWorkoutComponent(target.id)
                    }
                }
            }

            WorkoutDeletionScope.COMPONENT -> {
                val exactId = requireNotNull(componentSessionId)
                val target = queries.selectSessionById(exactId).executeAsOneOrNull()
                val targetPortalParent = target?.routineSessionId?.takeIf { it.isNotBlank() } ?: target?.id
                if (
                    target != null &&
                    targetPortalParent == deletion.portalSessionId &&
                    profileOwnerMatches(target.profile_id, ownerUserId)
                ) {
                    queries.hardDeleteWorkoutComponent(exactId)
                }
            }
        }
    }

    private fun profileOwnerMatches(profileId: String, expectedOwnerUserId: String): Boolean =
        queries.getProfileById(profileId).executeAsOneOrNull()?.supabase_user_id == expectedOwnerUserId

    /**
     * Resolve a retained ownership claim before a pulled root is materialized. Claims outlive
     * source-profile deletion, so the target profile is authoritative for every later pull.
     * A claim may point at an unbound local profile, but never at another account's profile.
     */
    private fun claimedPullProfileId(
        ownerUserId: String,
        entityType: OwnershipEntityType,
        entityId: String,
    ): String? {
        val claimedProfileId = localOwnershipClaimLookup.targetProfileId(
            ownerUserId = ownerUserId,
            entityType = entityType,
            entityId = entityId,
        ) ?: return null
        val claimedProfile = queries.getProfileById(claimedProfileId).executeAsOneOrNull()
            ?: throw IllegalStateException(
                "Ownership claim target profile does not exist: owner=$ownerUserId " +
                    "entity=${entityType.name}:$entityId target=$claimedProfileId",
            )
        val boundOwner = claimedProfile.supabase_user_id
        if (boundOwner != null && boundOwner != ownerUserId) {
            throw IllegalStateException(
                "Ownership claim target belongs to another account: owner=$ownerUserId " +
                    "entity=${entityType.name}:$entityId target=$claimedProfileId",
            )
        }
        return claimedProfileId
    }

    private fun mergeSessionsLwwInTransaction(
        sessions: List<WorkoutSession>,
        updatedAtBySessionId: Map<String, Long>,
    ) {
            // Issue #591 follow-up: collapse the per-row
            // selectSessionUpdatedAt + selectSessionById pair into a single
            // batched round-trip per concern. For a 20-set routine this
            // drops 40+ queries (one LWW gate read + one full-row read per
            // incoming pull) down to two queries total.
            //
            // chatgpt-codex-connector P2 (follow-up): chunk the batched
            // SELECTs because SQLite's host-parameter limit is
            // implementation-defined (commonly 999 on Android, 32766 on
            // desktop). For initial/full pulls of large histories the id
            // list can exceed the limit and throw "too many SQL variables".
            // Chunking at CHUNK_SIZE 500 keeps us safely under both
            // limits while still eliminating the per-row round-trips.
            val ids = sessions.map { it.id }
            val existingStateById: Map<String, SessionMergeState> =
                ids.chunked(BATCH_LOOKUP_CHUNK_SIZE)
                    .flatMap { chunk ->
                        queries.selectSessionsUpdatedAtByIds(chunk)
                            .executeAsList()
                            .map {
                                SessionMergeState(
                                    id = it.id,
                                    updatedAt = it.updatedAt,
                                    deletedAt = it.deletedAt,
                                    profileId = it.profile_id,
                                    portalOrigin = it.portalOrigin == 1L,
                                )
                            }
                    }
                    .associateBy { it.id }
            val preservationRowsById: Map<String, PreservationRow> =
                ids.chunked(BATCH_LOOKUP_CHUNK_SIZE)
                    .flatMap { chunk ->
                        queries.selectSessionsMetricsForPreservationByIds(chunk)
                            .executeAsList()
                            .map { it.toPreservationRow() }
                    }
                    .associate { it.id to it }

            for (session in sessions) {
                val existing = existingStateById[session.id]
                val incomingTs = updatedAtBySessionId[session.id]
                if (existing == null) {
                    queries.mergeSessionLww(
                        id = session.id,
                        timestamp = session.timestamp,
                        mode = session.mode,
                        targetReps = session.reps.toLong(),
                        weightPerCableKg = session.weightPerCableKg.toDouble(),
                        progressionKg = session.progressionKg.toDouble(),
                        duration = session.duration,
                        totalReps = session.totalReps.toLong(),
                        warmupReps = session.warmupReps.toLong(),
                        workingReps = session.workingReps.toLong(),
                        isJustLift = if (session.isJustLift) 1L else 0L,
                        stopAtTop = if (session.stopAtTop) 1L else 0L,
                        eccentricLoad = session.eccentricLoad.toLong(),
                        echoLevel = session.echoLevel.toLong(),
                        exerciseId = session.exerciseId,
                        exerciseName = session.exerciseName,
                        routineSessionId = session.routineSessionId,
                        routineName = session.routineName,
                        routineId = session.routineId,
                        safetyFlags = session.safetyFlags.toLong(),
                        deloadWarningCount = session.deloadWarningCount.toLong(),
                        romViolationCount = session.romViolationCount.toLong(),
                        spotterActivations = session.spotterActivations.toLong(),
                        peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
                        peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
                        peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
                        peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
                        avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
                        avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
                        avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
                        avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
                        heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
                        totalVolumeKg = session.totalVolumeKg?.toDouble(),
                        cableCount = session.cableCount?.toLong(),
                        estimatedCalories = session.estimatedCalories?.toDouble(),
                        warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                        workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                        burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                        peakWeightKg = session.peakWeightKg?.toDouble(),
                        rpe = session.rpe?.toLong(),
                        avgMcvMmS = session.avgMcvMmS?.toDouble(),
                        avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
                        totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
                        dominantSide = session.dominantSide,
                        strengthProfile = session.strengthProfile,
                        formScore = session.formScore?.toLong(),
                        updatedAt = incomingTs ?: 0L,
                        profile_id = session.profileId,
                        display_multiplier = session.displayMultiplier?.toLong(),
                        externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
                        counterweightKg = session.counterweightKg.toDouble(),
                        rackItemsJson = session.rackItemsJson,
                    )
                    continue
                }

                // A portal projection can update only a row originally materialized by
                // portal pull, within the same owner/profile, with a non-stale version.
                // Retained tombstones are never resurrected by an active projection.
                val accept = existing.portalOrigin &&
                    existing.profileId == session.profileId &&
                    existing.deletedAt == null &&
                    incomingTs != null &&
                    (existing.updatedAt == null || incomingTs >= existing.updatedAt)
                if (!accept) continue

                // Issue #591: Preserve non-null detailed metric columns from
                // the existing local row when the incoming pull is null.
                // The pull path does not (yet) populate peakForce* / avgForce*
                // / biomechanics fields from PullSetDto.repSummaries for every
                // session; without this guard, an incoming row with null
                // metrics would clobber locally captured metrics and force
                // HistoryTab to render the stale "after v0.2.1" placeholder.
                val preservation = preservationRowsById[session.id]
                val preserved = if (preservation != null) {
                    preserveMetrics(preservation, session)
                } else {
                    session
                }

                queries.updatePortalSessionProjection(
                    id = preserved.id,
                    timestamp = preserved.timestamp,
                    mode = preserved.mode,
                    targetReps = preserved.reps.toLong(),
                    weightPerCableKg = preserved.weightPerCableKg.toDouble(),
                    duration = preserved.duration,
                    totalReps = preserved.totalReps.toLong(),
                    warmupReps = preserved.warmupReps.toLong(),
                    workingReps = preserved.workingReps.toLong(),
                    exerciseId = preserved.exerciseId,
                    exerciseName = preserved.exerciseName,
                    routineSessionId = preserved.routineSessionId,
                    routineName = preserved.routineName,
                    peakForceConcentricA = preserved.peakForceConcentricA?.toDouble(),
                    peakForceConcentricB = preserved.peakForceConcentricB?.toDouble(),
                    peakForceEccentricA = preserved.peakForceEccentricA?.toDouble(),
                    peakForceEccentricB = preserved.peakForceEccentricB?.toDouble(),
                    avgForceConcentricA = preserved.avgForceConcentricA?.toDouble(),
                    avgForceConcentricB = preserved.avgForceConcentricB?.toDouble(),
                    avgForceEccentricA = preserved.avgForceEccentricA?.toDouble(),
                    avgForceEccentricB = preserved.avgForceEccentricB?.toDouble(),
                    heaviestLiftKg = preserved.heaviestLiftKg?.toDouble(),
                    totalVolumeKg = preserved.totalVolumeKg?.toDouble(),
                    cableCount = preserved.cableCount?.toLong(),
                    estimatedCalories = preserved.estimatedCalories?.toDouble(),
                    warmupAvgWeightKg = preserved.warmupAvgWeightKg?.toDouble(),
                    workingAvgWeightKg = preserved.workingAvgWeightKg?.toDouble(),
                    burnoutAvgWeightKg = preserved.burnoutAvgWeightKg?.toDouble(),
                    peakWeightKg = preserved.peakWeightKg?.toDouble(),
                    rpe = preserved.rpe?.toLong(),
                    avgMcvMmS = preserved.avgMcvMmS?.toDouble(),
                    avgAsymmetryPercent = preserved.avgAsymmetryPercent?.toDouble(),
                    totalVelocityLossPercent = preserved.totalVelocityLossPercent?.toDouble(),
                    dominantSide = preserved.dominantSide,
                    strengthProfile = preserved.strengthProfile,
                    formScore = preserved.formScore?.toLong(),
                    updatedAt = incomingTs,
                )
            }
    }

    /**
     * Issue #591: For detailed metric / biomechanics columns, prefer the
     * existing local value whenever the incoming pull is null. All other
     * columns use the incoming value (LWW semantics).
     *
     * Exception: `PortalPullAdapter` can only hydrate per-cable concentric
     * peaks from `leftForceAvg` / `rightForceAvg`, which are average-force
     * proxies from the push DTO rather than true peak captures. If a local
     * row already has true per-cable peak values, keep those instead of
     * replacing them with the pull-side proxy. First-time pulls still keep
     * the proxy value because there is no local capture to preserve.
     *
     * Backed by `selectSessionsMetricsForPreservationByIds` so the LWW
     * gate does not issue a full-row read per incoming pull. The column
     * list here must stay in sync with that SQL query.
     *
     * Columns preserved when local is non-null and incoming is null:
     *   - peakForce* / avgForce* (concentric + eccentric, A + B)
     *   - heaviestLiftKg, totalVolumeKg
     *   - estimatedCalories, warmupAvgWeightKg, workingAvgWeightKg,
     *     burnoutAvgWeightKg, peakWeightKg
     *   - avgMcvMmS, avgAsymmetryPercent, totalVelocityLossPercent
     *   - dominantSide, strengthProfile
     *   - cableCount, displayMultiplier
     *   - rpe, formScore
     *
     * `weightPerCableKg`, `totalReps`, `duration`, and exercise/routine identity
     * follow the incoming projection only for rows already marked portal-origin.
     */
    private fun preserveMetrics(
        existing: PreservationRow,
        incoming: WorkoutSession,
    ): WorkoutSession = incoming.copy(
        peakForceConcentricA = existing.peakForceConcentricA?.toFloat() ?: incoming.peakForceConcentricA,
        peakForceConcentricB = existing.peakForceConcentricB?.toFloat() ?: incoming.peakForceConcentricB,
        peakForceEccentricA = incoming.peakForceEccentricA ?: existing.peakForceEccentricA?.toFloat(),
        peakForceEccentricB = incoming.peakForceEccentricB ?: existing.peakForceEccentricB?.toFloat(),
        avgForceConcentricA = incoming.avgForceConcentricA ?: existing.avgForceConcentricA?.toFloat(),
        avgForceConcentricB = incoming.avgForceConcentricB ?: existing.avgForceConcentricB?.toFloat(),
        avgForceEccentricA = incoming.avgForceEccentricA ?: existing.avgForceEccentricA?.toFloat(),
        avgForceEccentricB = incoming.avgForceEccentricB ?: existing.avgForceEccentricB?.toFloat(),
        heaviestLiftKg = incoming.heaviestLiftKg ?: existing.heaviestLiftKg?.toFloat(),
        totalVolumeKg = incoming.totalVolumeKg ?: existing.totalVolumeKg?.toFloat(),
        cableCount = incoming.cableCount ?: existing.cableCount?.toInt(),
        displayMultiplier = incoming.displayMultiplier ?: existing.displayMultiplier?.toInt(),
        estimatedCalories = incoming.estimatedCalories ?: existing.estimatedCalories?.toFloat(),
        warmupAvgWeightKg = incoming.warmupAvgWeightKg ?: existing.warmupAvgWeightKg?.toFloat(),
        workingAvgWeightKg = incoming.workingAvgWeightKg ?: existing.workingAvgWeightKg?.toFloat(),
        burnoutAvgWeightKg = incoming.burnoutAvgWeightKg ?: existing.burnoutAvgWeightKg?.toFloat(),
        peakWeightKg = incoming.peakWeightKg ?: existing.peakWeightKg?.toFloat(),
        rpe = incoming.rpe ?: existing.rpe?.toInt(),
        avgMcvMmS = incoming.avgMcvMmS ?: existing.avgMcvMmS?.toFloat(),
        avgAsymmetryPercent = incoming.avgAsymmetryPercent ?: existing.avgAsymmetryPercent?.toFloat(),
        totalVelocityLossPercent = incoming.totalVelocityLossPercent ?: existing.totalVelocityLossPercent?.toFloat(),
        dominantSide = incoming.dominantSide ?: existing.dominantSide,
        strengthProfile = incoming.strengthProfile ?: existing.strengthProfile,
        formScore = incoming.formScore ?: existing.formScore?.toInt(),
    )

    /**
     * Issue #591 follow-up: in-memory projection of the
     * `selectSessionsMetricsForPreservationByIds` SQL row, holding only
     * the metric / biomechanics columns the LWW preservation guard
     * consumes. Keeps [preserveMetrics] free of SqlDelight-generated
     * row types so the column contract lives in one obvious place.
     */
    private data class PreservationRow(
        val id: String,
        val peakForceConcentricA: Double?,
        val peakForceConcentricB: Double?,
        val peakForceEccentricA: Double?,
        val peakForceEccentricB: Double?,
        val avgForceConcentricA: Double?,
        val avgForceConcentricB: Double?,
        val avgForceEccentricA: Double?,
        val avgForceEccentricB: Double?,
        val heaviestLiftKg: Double?,
        val totalVolumeKg: Double?,
        val cableCount: Long?,
        val displayMultiplier: Long?,
        val estimatedCalories: Double?,
        val warmupAvgWeightKg: Double?,
        val workingAvgWeightKg: Double?,
        val burnoutAvgWeightKg: Double?,
        val peakWeightKg: Double?,
        val rpe: Long?,
        val avgMcvMmS: Double?,
        val avgAsymmetryPercent: Double?,
        val totalVelocityLossPercent: Double?,
        val dominantSide: String?,
        val strengthProfile: String?,
        val formScore: Long?,
    )

    private data class SessionMergeState(
        val id: String,
        val updatedAt: Long?,
        val deletedAt: Long?,
        val profileId: String,
        val portalOrigin: Boolean,
    )

    /**
     * Bridge the SqlDelight-generated row into [PreservationRow]. The
     * generated row type name follows the SQL query name
     * (`SelectSessionsMetricsForPreservationByIds`). If the SQL column
     * list changes, regenerate and update this helper.
     */
    private fun com.devil.phoenixproject.database.SelectSessionsMetricsForPreservationByIds.toPreservationRow(): PreservationRow = PreservationRow(
        id = id,
        peakForceConcentricA = peakForceConcentricA,
        peakForceConcentricB = peakForceConcentricB,
        peakForceEccentricA = peakForceEccentricA,
        peakForceEccentricB = peakForceEccentricB,
        avgForceConcentricA = avgForceConcentricA,
        avgForceConcentricB = avgForceConcentricB,
        avgForceEccentricA = avgForceEccentricA,
        avgForceEccentricB = avgForceEccentricB,
        heaviestLiftKg = heaviestLiftKg,
        totalVolumeKg = totalVolumeKg,
        cableCount = cableCount,
        displayMultiplier = display_multiplier,
        estimatedCalories = estimatedCalories,
        warmupAvgWeightKg = warmupAvgWeightKg,
        workingAvgWeightKg = workingAvgWeightKg,
        burnoutAvgWeightKg = burnoutAvgWeightKg,
        peakWeightKg = peakWeightKg,
        rpe = rpe,
        avgMcvMmS = avgMcvMmS,
        avgAsymmetryPercent = avgAsymmetryPercent,
        totalVelocityLossPercent = totalVelocityLossPercent,
        dominantSide = dominantSide,
        strengthProfile = strengthProfile,
        formScore = formScore,
    )

    /**
     * Merge one portal routine (pull path) with TIMESTAMP LWW, shared by [mergePortalRoutines]
     * and [mergeAllPullData]. Must be called inside a [db.transaction] block.
     *
     * - A locally soft-deleted routine stays deleted (its tombstone may not be pushed yet).
     * - A routine edited locally after [lastSync] wins over the portal copy.
     * - The routine row is updated in place (never REPLACEd), so its exercises, supersets and
     *   CycleDay links are not cascade-deleted.
     * - SAFETY GUARD: an empty portal exercise list is treated as an incomplete payload and
     *   leaves the local exercises alone.
     */
    private fun mergePortalRoutine(portalRoutine: PullRoutineDto, lastSync: Long, profileId: String) {
        val existing = queries.selectRoutineById(portalRoutine.id).executeAsOneOrNull()
        if (existing != null) {
            if (existing.deletedAt != null) {
                Logger.d { "Routine '${portalRoutine.name}' skipped: deleted locally" }
                return
            }
            val localUpdatedAt = existing.updatedAt ?: 0L
            if (localUpdatedAt > lastSync) {
                Logger.d { "Routine '${portalRoutine.name}' skipped: local version newer ($localUpdatedAt > $lastSync)" }
                return
            }
        }

        // Read local structure before any write.
        val localExercises = queries.selectExercisesByRoutine(portalRoutine.id).executeAsList()
        val localSupersets = queries.selectSupersetsByRoutine(portalRoutine.id).executeAsList()

        val updatedAt = portalRoutine.updatedAt ?: currentTimeMillis()
        if (existing == null) {
            queries.insertRoutineIgnore(
                id = portalRoutine.id,
                name = portalRoutine.name,
                description = portalRoutine.description,
                createdAt = currentTimeMillis(),
                lastUsed = null,
                useCount = 0L,
                updatedAt = updatedAt,
                profile_id = profileId,
                groupId = null,
            )
        } else {
            queries.updateRoutineById(
                name = portalRoutine.name,
                description = portalRoutine.description,
                updatedAt = updatedAt,
                id = portalRoutine.id,
            )
        }

        if (portalRoutine.exercises.isNotEmpty()) {
            mergePortalExercisesForRoutine(portalRoutine.id, portalRoutine.exercises, localExercises, localSupersets)
        } else {
            Logger.w("SyncRepository") {
                "Skipping exercise merge for routine '${portalRoutine.name}' (${portalRoutine.id}): " +
                    "portal sent empty exercises list (exerciseCount=${portalRoutine.exerciseCount})"
            }
        }
    }

    /**
     * Bring a routine's supersets and exercises in line with the portal copy, diffing by id:
     * matched rows are UPDATEd in place, new rows inserted, rows the portal no longer has deleted.
     * Columns the wire does not carry (progressionKg, duration, prTypeForScaling,
     * setWeightsPercentOfPR, scalingBasis, defaultRackItemIds, cableConfig, omitted drop-set
     * config, superset name/rest) keep their local values on matched rows.
     *
     * Must be called inside a [db.transaction] block, with [localExercises]/[localSupersets]
     * read before any write for this routine.
     */
    private fun mergePortalExercisesForRoutine(
        routineId: String,
        portalExercises: List<PullRoutineExerciseDto>,
        localExercises: List<RoutineExerciseRow>,
        localSupersets: List<SupersetRow>,
    ) {
        val localExercisesById = localExercises.associateBy { it.id }
        val localSupersetsById = localSupersets.associateBy { it.id }

        // Create/update Superset rows BEFORE writing exercises (FK constraint).
        val supersetGroups = portalExercises
            .filter { it.supersetId != null }
            .groupBy { it.supersetId!! }

        // Issue 3.5: Reverse mapping from color name -> index for round-trip.
        // Portal sends color as name (e.g., "pink") from push adapter (PortalSyncAdapter).
        // Must map back to index for local Superset entity.
        val colorNameToIndex = mapOf(
            "indigo" to 0L, // SupersetColors.INDIGO
            "pink" to 1L, // SupersetColors.PINK
            "green" to 2L, // SupersetColors.GREEN
            "amber" to 3L, // SupersetColors.AMBER
        )

        var supersetOrderIdx = 0
        for ((ssId, ssExercises) in supersetGroups) {
            val colorStr = ssExercises.firstOrNull()?.supersetColor?.lowercase()
            val colorIndex = colorStr?.let { colorNameToIndex[it] }
                ?: colorStr?.toLongOrNull()
                ?: supersetOrderIdx.toLong()
            val localSuperset = localSupersetsById[ssId]
            if (localSuperset != null) {
                // Name and rest are not on the wire: keep the local values.
                queries.updateSuperset(
                    name = localSuperset.name,
                    colorIndex = colorIndex,
                    restBetweenSeconds = localSuperset.restBetweenSeconds,
                    orderIndex = supersetOrderIdx.toLong(),
                    id = ssId,
                )
            } else {
                queries.insertSupersetIgnore(
                    id = ssId,
                    routineId = routineId,
                    name = "Superset ${supersetOrderIdx + 1}",
                    colorIndex = colorIndex,
                    restBetweenSeconds = 10L,
                    orderIndex = supersetOrderIdx.toLong(),
                )
            }
            supersetOrderIdx++
        }

        for (exercise in portalExercises) {
            // Parse perSetReps JSON if available, otherwise reconstruct from scalar
            val setReps = exercise.perSetReps?.let { jsonStr ->
                try {
                    val parsed = Json.decodeFromString<List<Int?>>(jsonStr)
                    parsed.joinToString(",") { it?.toString() ?: "AMRAP" }
                } catch (_: Exception) {
                    null
                }
            } ?: run {
                // Fallback: reconstruct from scalar (old portal data without perSetReps)
                val repsList = List(exercise.sets) {
                    if (exercise.isAmrap && it == exercise.sets - 1) "AMRAP" else exercise.reps.toString()
                }
                repsList.joinToString(",")
            }

            // Convert perSetWeights JSON "[50,55,60]" to comma-separated "50.0,55.0,60.0"
            val setWeights = exercise.perSetWeights?.let { jsonStr ->
                try {
                    val parsed = Json.decodeFromString<List<Float>>(jsonStr)
                    parsed.joinToString(",") { it.toString() }
                } catch (_: Exception) {
                    ""
                }
            } ?: ""

            // perSetRest is already JSON array format, use as setRestSeconds
            val setRestSeconds = exercise.perSetRest ?: "[]"

            // Convert perSetEchoLevels from portal names to ordinal JSON
            val setEchoLevels = exercise.perSetEchoLevels?.let { jsonStr ->
                try {
                    val names = Json.decodeFromString<List<String?>>(jsonStr)
                    val ordinals = names.map { name ->
                        name?.let { PortalPullAdapter.parseEchoLevel(it).toInt() }
                    }
                    Json.encodeToString(ordinals)
                } catch (_: Exception) {
                    ""
                }
            } ?: ""

            val mobileMode = PortalPullAdapter.portalModeToMobileMode(exercise.mode)

            // ID-first catalog lookup: use exerciseId when available, fall back to name (#404)
            val catalogExercise = exercise.exerciseId?.let { id ->
                queries.selectExerciseById(id).executeAsOneOrNull()
            } ?: queries.findExerciseByName(exercise.name).executeAsOneOrNull()

            // #635: the explicit flag is stored in its own column — the portal's
            // per-exercise toggle is honored directly. Equipment stays real catalog
            // metadata (the old "Bodyweight"/"Cable" sentinel corrupted classification
            // because reconstructed snapshot Exercises derived isBodyweight from it).
            val resolvedEquipment = catalogExercise?.equipment ?: ""

            val local = localExercisesById[exercise.id]
            // Explicit portal flag when present; otherwise inherit the catalog's
            // stored classification (e.g. Squat = cable despite empty equipment).
            // Never coerce an omitted field to cable, and never leave a known
            // catalog flag behind — the push snapshot builder reconstructs the
            // Exercise from this row alone, without a catalog lookup (#635).
            val isBodyweight = exercise.isBodyweight?.let { if (it) 1L else 0L }
                ?: catalogExercise?.isBodyweight
            val rackBehaviorOverrides = exercise.rackBehaviorOverrides
                ?: local?.rackBehaviorOverrides
                ?: "{}"
            val dropSetEnabled = when {
                exercise.dropSetEnabled != null -> if (exercise.dropSetEnabled) 1L else 0L
                else -> local?.dropSetEnabled ?: 0L
            }
            val dropSetMinWeightKg = when {
                exercise.dropSetEnabled != null -> exercise.dropSetMinWeightKg?.toDouble()
                else -> local?.dropSetMinWeightKg
            }

            val weightPercentOfPR = (exercise.prPercentage?.toInt() ?: 80).toLong()

            if (local != null) {
                // The per-set % list is not on the wire and resolvers prefer it over the base %.
                // Keep it only when the portal sends no % (mode off, list unused) or when the row
                // was already in %-of-PR mode at the same base. A base change on another device
                // (e.g. a deload 80 -> 70) or re-enabling % mode (the stored base may be the
                // `?: 80` fallback) clears it so the incoming base drives the load.
                val setWeightsPercentOfPR = local.setWeightsPercentOfPR.takeIf {
                    exercise.prPercentage == null ||
                        (local.usePercentOfPR == 1L && weightPercentOfPR == local.weightPercentOfPR)
                }
                queries.updateRoutineExercise(
                    exerciseName = exercise.name,
                    exerciseMuscleGroup = exercise.muscleGroup,
                    exerciseEquipment = resolvedEquipment,
                    exerciseDefaultCableConfig = catalogExercise?.defaultCableConfig ?: "DOUBLE",
                    exerciseId = catalogExercise?.id,
                    cableConfig = local.cableConfig,
                    orderIndex = exercise.orderIndex.toLong(),
                    setReps = setReps,
                    weightPerCableKg = exercise.weight.toDouble(),
                    setWeights = setWeights,
                    mode = mobileMode,
                    eccentricLoad = PortalPullAdapter.parseEccentricLoad(exercise.eccentricLoad),
                    echoLevel = PortalPullAdapter.parseEchoLevel(exercise.echoLevel),
                    progressionKg = local.progressionKg,
                    restSeconds = exercise.restSeconds.toLong(),
                    duration = local.duration,
                    setRestSeconds = setRestSeconds,
                    perSetRestTime = if (exercise.perSetRest != null) 1L else 0L,
                    isAMRAP = if (exercise.isAmrap) 1L else 0L,
                    supersetId = exercise.supersetId,
                    orderInSuperset = (exercise.supersetOrder ?: 0).toLong(),
                    usePercentOfPR = if (exercise.prPercentage != null) 1L else 0L,
                    weightPercentOfPR = weightPercentOfPR,
                    prTypeForScaling = local.prTypeForScaling,
                    setWeightsPercentOfPR = setWeightsPercentOfPR,
                    stallDetectionEnabled = if (exercise.stallDetection) 1L else 0L,
                    stopAtTop = if (exercise.stopAtPosition == "TOP") 1L else 0L,
                    repCountTiming = exercise.repCountTiming ?: "TOP",
                    setEchoLevels = setEchoLevels,
                    warmupSets = exercise.warmupSets ?: "",
                    defaultRackItemIds = local.defaultRackItemIds,
                    rackBehaviorOverrides = rackBehaviorOverrides,
                    scalingBasis = local.scalingBasis,
                    isBodyweight = isBodyweight,
                    dropSetEnabled = dropSetEnabled,
                    dropSetMinWeightKg = dropSetMinWeightKg,
                    id = exercise.id,
                )
            } else {
                queries.insertRoutineExercise(
                    id = exercise.id,
                    routineId = routineId,
                    exerciseName = exercise.name,
                    exerciseMuscleGroup = exercise.muscleGroup,
                    exerciseEquipment = resolvedEquipment,
                    exerciseDefaultCableConfig = catalogExercise?.defaultCableConfig ?: "DOUBLE",
                    exerciseId = catalogExercise?.id,
                    cableConfig = "DOUBLE",
                    orderIndex = exercise.orderIndex.toLong(),
                    setReps = setReps,
                    weightPerCableKg = exercise.weight.toDouble(),
                    setWeights = setWeights,
                    mode = mobileMode,
                    eccentricLoad = PortalPullAdapter.parseEccentricLoad(exercise.eccentricLoad),
                    echoLevel = PortalPullAdapter.parseEchoLevel(exercise.echoLevel),
                    progressionKg = 0.0,
                    restSeconds = exercise.restSeconds.toLong(),
                    duration = null,
                    setRestSeconds = setRestSeconds,
                    perSetRestTime = if (exercise.perSetRest != null) 1L else 0L,
                    isAMRAP = if (exercise.isAmrap) 1L else 0L,
                    supersetId = exercise.supersetId,
                    orderInSuperset = (exercise.supersetOrder ?: 0).toLong(),
                    usePercentOfPR = if (exercise.prPercentage != null) 1L else 0L,
                    weightPercentOfPR = weightPercentOfPR,
                    prTypeForScaling = "MAX_WEIGHT",
                    setWeightsPercentOfPR = null,
                    stallDetectionEnabled = if (exercise.stallDetection) 1L else 0L,
                    stopAtTop = if (exercise.stopAtPosition == "TOP") 1L else 0L,
                    repCountTiming = exercise.repCountTiming ?: "TOP",
                    setEchoLevels = setEchoLevels,
                    warmupSets = exercise.warmupSets ?: "",
                    defaultRackItemIds = "[]",
                    rackBehaviorOverrides = rackBehaviorOverrides,
                    scalingBasis = null,
                    isBodyweight = isBodyweight,
                    dropSetEnabled = dropSetEnabled,
                    dropSetMinWeightKg = dropSetMinWeightKg,
                )
            }
        }

        // Drop what the portal no longer has (exercises first; their superset refs are SET NULL anyway).
        val portalExerciseIds = portalExercises.mapTo(HashSet()) { it.id }
        localExercises.filter { it.id !in portalExerciseIds }.forEach { queries.deleteRoutineExerciseById(it.id) }
        localSupersets.filter { it.id !in supersetGroups.keys }.forEach { queries.deleteSuperset(it.id) }
    }

    override suspend fun mergeSessionNotes(
        notes: Map<String, SessionNotesEntry>,
    ) = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext
        db.transaction {
            mergeSessionNotesInTransaction(notes)
        }
    }

    override suspend fun saveLocalSessionNotes(
        portalSessionId: String,
        notes: String?,
        updatedAtMillis: Long,
    ) = withContext(Dispatchers.IO) {
        require(portalSessionId.isNotBlank()) { "portalSessionId must not be blank" }
        db.transaction {
            queries.upsertSessionNotes(
                routineSessionId = portalSessionId,
                notes = notes,
                updatedAt = updatedAtMillis,
            )
            queries.markWorkoutPortalParentDirty(portalSessionId)
        }
    }

    override suspend fun getSessionNotesForPortalParents(
        portalSessionIds: List<String>,
    ): Map<String, SessionNotesEntry> = withContext(Dispatchers.IO) {
        if (portalSessionIds.isEmpty()) return@withContext emptyMap()
        portalSessionIds.distinct().chunked(BATCH_LOOKUP_CHUNK_SIZE)
            .flatMap { ids -> queries.selectSessionNotesForIds(ids).executeAsList() }
            .associate { row ->
                row.routineSessionId to SessionNotesEntry(
                    notes = row.notes,
                    updatedAtMillis = row.updatedAt ?: 0L,
                )
            }
    }

    private fun mergeSessionNotesInTransaction(notes: Map<String, SessionNotesEntry>) {
        for ((routineSessionId, entry) in notes) {
            val existingUpdatedAt = queries
                .selectSessionNotesUpdatedAt(routineSessionId)
                .executeAsOneOrNull()
                ?.updatedAt
            val incomingMillis = entry.updatedAtMillis
            val accept = existingUpdatedAt == null || incomingMillis >= existingUpdatedAt
            if (accept) {
                queries.upsertSessionNotes(
                    routineSessionId = routineSessionId,
                    notes = entry.notes,
                    updatedAt = incomingMillis,
                )
            }
        }
    }
}
