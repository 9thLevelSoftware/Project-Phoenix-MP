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
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * SQLDelight implementation of SyncRepository.
 * Provides database operations for syncing data with the Phoenix Portal.
 */
class SqlDelightSyncRepository(
    private val db: VitruvianDatabase,
    private val userProfileRepository: UserProfileRepository,
) : SyncRepository {

    private val queries = db.vitruvianDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

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
                duration = row.duration.toInt(),
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
                clientId = row.id.toString(),
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
     * CONFLICT RESOLUTION STRATEGY: SERVER WINS (UPSERT)
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 1 "Workout Sessions"
     *
     * This is the legacy push-path merge used for initial sync/migration.
     * For the authoritative pull-path, see [mergePortalSessions] which uses LOCAL WINS.
     *
     * Sessions are immutable workout records created by BLE execution. Mobile is authoritative.
     * The push-path upsert allows server data to populate initially, but the pull-path
     * (INSERT OR IGNORE) ensures local sessions are never overwritten after initial sync.
     */
    override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                sessions.forEach { dto ->
                    // Check if we have this session locally (by serverId or clientId)
                    val existingByServer = dto.serverId?.let {
                        queries.selectSessionByServerId(it).executeAsOneOrNull()
                    }

                    val localId = existingByServer?.id ?: dto.clientId

                    // Server wins for initial population (upsert pattern)
                    queries.upsertSyncSession(
                        id = localId,
                        timestamp = dto.timestamp,
                        mode = dto.mode,
                        targetReps = dto.targetReps.toLong(),
                        weightPerCableKg = dto.weightPerCableKg.toDouble(),
                        progressionKg = 0.0,
                        duration = dto.duration.toLong(),
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
                        profile_id = userProfileRepository.activeProfile.value?.id ?: "default",
                    )
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
                    )
                }
            }
            Logger.d { "Merged ${records.size} PRs from server" }
        }
    }

    /**
     * Merge routines from server (legacy push-path).
     *
     * CONFLICT RESOLUTION STRATEGY: SERVER WINS (UPSERT) with local field preservation
     * Reference: CONFLICT-RESOLUTION-DESIGN.md Task 2, Section 6 "Routines"
     *
     * This is the legacy push-path used for initial sync. The authoritative pull-path
     * [mergePortalRoutines] uses TIMESTAMP-BASED LWW to handle concurrent edits.
     *
     * Local-only fields preserved: lastUsed, useCount (server doesn't track usage stats).
     */
    override suspend fun mergeRoutines(routines: List<RoutineSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                routines.forEach { dto ->
                    val existingByServer = dto.serverId?.let {
                        queries.selectRoutineByServerId(it).executeAsOneOrNull()
                    }

                    val localId = existingByServer?.id ?: dto.clientId

                    // Preserve local usage stats that the server doesn't track
                    val existing = queries.selectRoutineById(localId).executeAsOneOrNull()

                    queries.upsertRoutine(
                        id = localId,
                        name = dto.name,
                        description = dto.description,
                        createdAt = dto.createdAt,
                        lastUsed = existing?.lastUsed,
                        useCount = existing?.useCount ?: 0L,
                        profile_id = userProfileRepository.activeProfile.value?.id ?: "default",
                    )

                    // Update sync fields
                    if (dto.serverId != null) {
                        queries.updateRoutineServerId(dto.serverId, localId)
                    }
                }
            }
            Logger.d { "Merged ${routines.size} routines from server" }
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
                    // Custom exercises - insert by clientId (no conflict expected)
                    queries.insertExercise(
                        id = dto.clientId,
                        name = dto.name,
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
                    )

                    if (dto.serverId != null) {
                        queries.updateExerciseServerId(dto.serverId, dto.clientId)
                    }
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
                totalVolumeKg = stats.totalVolumeKg.toLong(),
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
                    // Check if routine exists locally
                    val existing = queries.selectRoutineById(portalRoutine.id).executeAsOneOrNull()

                    if (existing != null) {
                        // TIMESTAMP LWW: Local version is newer if modified after lastSync
                        val localUpdatedAt = existing.updatedAt ?: 0L
                        if (localUpdatedAt > lastSync) {
                            // Local version is newer — skip this portal routine (local wins)
                            Logger.d { "Routine '${portalRoutine.name}' skipped: local version newer (${localUpdatedAt} > ${lastSync})" }
                            continue
                        }
                    }

                    // Either doesn't exist locally or portal version is newer — upsert
                    queries.upsertRoutine(
                        id = portalRoutine.id,
                        name = portalRoutine.name,
                        description = portalRoutine.description,
                        createdAt = existing?.createdAt ?: currentTimeMillis(),
                        lastUsed = existing?.lastUsed,
                        useCount = existing?.useCount ?: 0L,
                        profile_id = existing?.profile_id ?: profileId,
                    )

                    // SAFETY GUARD: Only replace exercises if the portal actually sent exercises.
                    // An empty exercises list means the payload is incomplete (server omission,
                    // partial response, or deserialization issue). Deleting local exercises
                    // when we have nothing to replace them with causes permanent data loss.
                    if (portalRoutine.exercises.isNotEmpty()) {
                        // Replace routine exercises and supersets: delete existing then insert portal versions
                        queries.deleteRoutineExercises(portalRoutine.id)
                        queries.deleteSupersetsByRoutine(portalRoutine.id)

                        // Create Superset rows BEFORE inserting exercises (FK constraint).
                        // Group exercises by supersetId and create one Superset per group.
                        val supersetGroups = portalRoutine.exercises
                            .filter { it.supersetId != null }
                            .groupBy { it.supersetId!! }

                        // Issue 3.5: Reverse mapping from color name -> index for round-trip.
                        // Portal sends color as name (e.g., "pink") from push adapter (PortalSyncAdapter).
                        // Must map back to index for local Superset entity.
                        val colorNameToIndex = mapOf(
                            "indigo" to 0L, // SupersetColors.INDIGO
                            "pink" to 1L,   // SupersetColors.PINK
                            "green" to 2L,  // SupersetColors.GREEN
                            "amber" to 3L,  // SupersetColors.AMBER
                        )

                        var supersetOrderIdx = 0
                        for ((ssId, ssExercises) in supersetGroups) {
                            val colorStr = ssExercises.firstOrNull()?.supersetColor?.lowercase()
                            val colorIndex = colorStr?.let { colorNameToIndex[it] }
                                ?: colorStr?.toLongOrNull()
                                ?: supersetOrderIdx.toLong()
                            queries.insertSupersetIgnore(
                                id = ssId,
                                routineId = portalRoutine.id,
                                name = "Superset ${supersetOrderIdx + 1}",
                                colorIndex = colorIndex,
                                restBetweenSeconds = 10L, // default
                                orderIndex = supersetOrderIdx.toLong(),
                            )
                            supersetOrderIdx++
                        }

                        for (exercise in portalRoutine.exercises) {
                            // Build setReps string: e.g., "10,10,10" for sets=3, reps=10
                            val repsList = List(exercise.sets) {
                                if (exercise.isAmrap && it == exercise.sets - 1) {
                                    "AMRAP"
                                } else {
                                    exercise.reps.toString()
                                }
                            }
                            val setReps = repsList.joinToString(",")

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

                            // Attempt catalog lookup so equipment and exerciseId are populated.
                            // Prevents bodyweight misclassification when equipment would default to "".
                            val catalogExercise = queries.findExerciseByName(exercise.name).executeAsOneOrNull()

                            val resolvedEquipment = when {
                                exercise.isBodyweight -> "Bodyweight"
                                catalogExercise != null -> catalogExercise.equipment
                                else -> "Cable"
                            }

                            queries.insertRoutineExercise(
                                id = exercise.id,
                                routineId = portalRoutine.id,
                                exerciseName = exercise.name,
                                exerciseMuscleGroup = exercise.muscleGroup,
                                exerciseEquipment = resolvedEquipment,
                                exerciseDefaultCableConfig = catalogExercise?.defaultCableConfig ?: "DOUBLE",
                                exerciseId = catalogExercise?.id, // Link to catalog when available
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
                                weightPercentOfPR = (exercise.prPercentage?.toInt() ?: 80).toLong(),
                                prTypeForScaling = "MAX_WEIGHT",
                                setWeightsPercentOfPR = null,
                                stallDetectionEnabled = if (exercise.stallDetection) 1L else 0L,
                                stopAtTop = if (exercise.stopAtPosition == "TOP") 1L else 0L,
                                repCountTiming = exercise.repCountTiming ?: "TOP",
                                setEchoLevels = setEchoLevels,
                                warmupSets = exercise.warmupSets ?: "",
                            )
                        }
                    } else {
                        Logger.w("SyncRepository") {
                            "Skipping exercise merge for routine '${portalRoutine.name}' (${portalRoutine.id}): " +
                                "portal sent empty exercises list (exerciseCount=${portalRoutine.exerciseCount})"
                        }
                    }
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
                    // Track the active cycle from portal (last one wins if multiple marked active)
                    if (portalCycle.status == "active") {
                        portalActiveCycleId = portalCycle.id
                    }

                    // Upsert cycle (INSERT OR IGNORE — keeps local if exists)
                    queries.insertTrainingCycleIgnore(
                        id = portalCycle.id,
                        name = portalCycle.name,
                        description = portalCycle.description,
                        created_at = currentTimeMillis(),
                        is_active = 0L, // Don't set active yet - enforce single-active at end
                        profile_id = profileId,
                    )

                    // For existing cycles, update metadata (but NOT is_active - enforce single-active at end)
                    val existing = queries.selectTrainingCycleById(portalCycle.id).executeAsOneOrNull()
                    if (existing != null) {
                        queries.updateTrainingCycle(
                            name = portalCycle.name,
                            description = portalCycle.description,
                            is_active = 0L, // Don't set active yet
                            id = portalCycle.id,
                        )
                    }

                    // Bulk delete existing days, reinsert from portal (same pattern as edge function)
                    queries.deleteCycleDaysByCycle(portalCycle.id)

                    for (day in portalCycle.days) {
                        queries.insertCycleDayIgnore(
                            id = day.id,
                            cycle_id = day.cycleId.ifEmpty { portalCycle.id },
                            day_number = day.dayNumber.toLong(),
                            name = day.notes,
                            routine_id = day.routineId,
                            is_rest_day = if (day.dayType == "rest") 1L else 0L,
                            echo_level = null,
                            eccentric_load_percent = null,
                            weight_progression_percent = day.weightAdjustment.toDouble(),
                            rep_modifier = day.repModifier.toLong(),
                            rest_time_override_seconds = day.restOverride?.toLong(),
                        )
                    }

                    // Restore CycleProgression from portal's progressionSettings JSON
                    portalCycle.progressionSettings?.let { jsonStr ->
                        try {
                            val map = json.decodeFromString<Map<String, String>>(jsonStr)
                            queries.upsertCycleProgression(
                                cycle_id = portalCycle.id,
                                frequency_cycles = map["frequencyCycles"]?.toLongOrNull() ?: 2L,
                                weight_increase_percent = map["weightIncreasePercent"]?.toDoubleOrNull(),
                                echo_level_increase = if (map["echoLevelIncrease"] == "true") 1L else 0L,
                                eccentric_load_increase_percent = map["eccentricLoadIncreasePercent"]?.toLongOrNull(),
                            )
                        } catch (e: Exception) {
                            Logger.w(e) { "Failed to parse progressionSettings for cycle ${portalCycle.id}" }
                        }
                    }
                }

                // SINGLE-ACTIVE ENFORCEMENT: Ensure exactly one cycle is active after merge
                // Priority: portal's active cycle > existing local active cycle > none
                if (portalActiveCycleId != null) {
                    // Portal specified an active cycle - deactivate all others, activate this one
                    queries.deactivateAllCycles(profileId)
                    queries.updateTrainingCycle(
                        name = cycles.first { it.id == portalActiveCycleId }.name,
                        description = cycles.first { it.id == portalActiveCycleId }.description,
                        is_active = 1L,
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
                        queries.updateTrainingCycle(
                            name = cycles.first { it.id == id }.name,
                            description = cycles.first { it.id == id }.description,
                            is_active = 1L,
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
                    )
                }
            }
            Logger.d { "Merged ${sessions.size} portal sessions (INSERT OR IGNORE)" }
        }
    }

    // === Exercise Lookup for Pull ===

    /**
     * Find exercise ID by name and optionally muscle group for session enrichment during pull.
     *
     * Lookup strategy (in order):
     * 1. Exact match on name + muscle group (if muscle group provided)
     * 2. Exact match on name only (via findExerciseByName)
     * 3. Case-insensitive match on name (fallback for portal name variations)
     *
     * This enables pulled sessions to link to the local exercise catalog, which provides
     * muscle group data, equipment info, and other metadata for analytics and display.
     *
     * @param name Exercise name from portal
     * @param muscleGroup Optional muscle group for disambiguation (e.g., "Chest Press" in Chest vs Arms)
     * @return Exercise ID if found, null otherwise (caller should log for telemetry)
     */
    override suspend fun findExerciseId(name: String, muscleGroup: String?): String? = withContext(Dispatchers.IO) {
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

    // === Portal Push Operations (full domain objects) ===

    override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsModifiedSince(timestamp, profileId = profileId, ::mapToWorkoutSession).executeAsList()
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
                lastUsed = row.lastUsed,
                useCount = row.useCount.toInt(),
            )
        }
    }

    override suspend fun getFullCyclesForSync(profileId: String): List<CycleWithContext> = withContext(Dispatchers.IO) {
        val cycles = queries.selectTrainingCyclesByProfile(profileId = profileId).executeAsList()
        val allDays = queries.selectAllCycleDaysSync().executeAsList()
        val allProgress = queries.selectAllCycleProgressSync().executeAsList()
        val allProgressions = queries.selectAllCycleProgressionsSync().executeAsList()

        val daysByCycle = allDays.groupBy { it.cycle_id }
        val progressByCycle = allProgress.associateBy { it.cycle_id }
        val progressionByCycle = allProgressions.associateBy { it.cycle_id }

        cycles.map { row ->
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
            )
        }
    }

    override suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<com.devil.phoenixproject.database.PhaseStatistics> {
        if (sessionIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            queries.selectPhaseStatsBySessionIds(sessionIds).executeAsList()
        }
    }

    override suspend fun getAllExerciseSignatures(): List<com.devil.phoenixproject.database.ExerciseSignature> = withContext(Dispatchers.IO) {
        queries.selectAllSignatures().executeAsList()
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
                records.forEach { dto ->
                    // INSERT OR IGNORE — local PRs win on conflict (same compound key)
                    val effectiveVolume = if (dto.volume > 0f) dto.volume else dto.weight * dto.reps
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
                    )
                }
            }
            Logger.d { "Merged ${records.size} personal records from portal (profile=$profileId)" }
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
        sessions: List<WorkoutSession>,
        routines: List<PullRoutineDto>,
        cycles: List<PullTrainingCycleDto>,
        badges: List<EarnedBadgeSyncDto>,
        gamificationStats: GamificationStatsSyncDto?,
        personalRecords: List<PersonalRecordSyncDto>,
        lastSync: Long,
        profileId: String,
    ) {
        withContext(Dispatchers.IO) {
            // Use a single outer transaction that wraps all entity merges.
            // SQLDelight handles nested transactions via savepoints, so if any inner
            // operation throws, the entire outer transaction rolls back.
            db.transaction {
                // 1. Sessions — INSERT OR IGNORE (local wins)
                // Full field list matches mergePortalSessions
                for (session in sessions) {
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
                        profile_id = profileId,
                    )
                }

                // 2. Routines — TIMESTAMP LWW (local wins if modified after lastSync)
                for (portalRoutine in routines) {
                    val existing = queries.selectRoutineById(portalRoutine.id).executeAsOneOrNull()

                    if (existing != null) {
                        val localUpdatedAt = existing.updatedAt ?: 0L
                        if (localUpdatedAt > lastSync) {
                            continue // Local version is newer
                        }
                    }

                    // Upsert routine
                    queries.upsertRoutine(
                        id = portalRoutine.id,
                        name = portalRoutine.name,
                        description = portalRoutine.description,
                        createdAt = existing?.createdAt ?: currentTimeMillis(),
                        lastUsed = existing?.lastUsed,
                        useCount = existing?.useCount ?: 0L,
                        profile_id = existing?.profile_id ?: profileId,
                    )

                    // Replace exercises if portal provided them (non-empty list)
                    if (portalRoutine.exercises.isNotEmpty()) {
                        queries.deleteRoutineExercises(portalRoutine.id)
                        queries.deleteSupersetsByRoutine(portalRoutine.id)

                        // Create supersets before exercises (FK constraint)
                        val supersetGroups = portalRoutine.exercises
                            .filter { it.supersetId != null }
                            .groupBy { it.supersetId!! }
                        // Issue 3.5: Reverse mapping from color name -> index for round-trip.
                        // Portal sends color as name (e.g., "pink") from push adapter.
                        val colorNameToIndex = mapOf(
                            "indigo" to 0L, // SupersetColors.INDIGO
                            "pink" to 1L,   // SupersetColors.PINK
                            "green" to 2L,  // SupersetColors.GREEN
                            "amber" to 3L,  // SupersetColors.AMBER
                        )

                        var supersetOrderIdx = 0
                        for ((ssId, ssExercises) in supersetGroups) {
                            val colorStr = ssExercises.firstOrNull()?.supersetColor?.lowercase()
                            val colorIndex = colorStr?.let { colorNameToIndex[it] }
                                ?: colorStr?.toLongOrNull()
                                ?: supersetOrderIdx.toLong()
                            queries.insertSupersetIgnore(
                                id = ssId,
                                routineId = portalRoutine.id,
                                name = "Superset ${supersetOrderIdx + 1}",
                                colorIndex = colorIndex,
                                restBetweenSeconds = 10L,
                                orderIndex = supersetOrderIdx.toLong(),
                            )
                            supersetOrderIdx++
                        }

                        for (exercise in portalRoutine.exercises) {
                            // Build setReps string
                            val repsList = List(exercise.sets) {
                                if (exercise.isAmrap && it == exercise.sets - 1) "AMRAP" else exercise.reps.toString()
                            }
                            val setReps = repsList.joinToString(",")

                            // Convert perSetWeights JSON
                            val setWeights = exercise.perSetWeights?.let { jsonStr ->
                                try {
                                    val parsed = Json.decodeFromString<List<Float>>(jsonStr)
                                    parsed.joinToString(",") { it.toString() }
                                } catch (_: Exception) { "" }
                            } ?: ""

                            val setRestSeconds = exercise.perSetRest ?: "[]"

                            // Convert perSetEchoLevels
                            val setEchoLevels = exercise.perSetEchoLevels?.let { jsonStr ->
                                try {
                                    val names = Json.decodeFromString<List<String?>>(jsonStr)
                                    val ordinals = names.map { name -> name?.let { PortalPullAdapter.parseEchoLevel(it).toInt() } }
                                    Json.encodeToString(ordinals)
                                } catch (_: Exception) { "" }
                            } ?: ""

                            val mobileMode = PortalPullAdapter.portalModeToMobileMode(exercise.mode)
                            val catalogExercise = queries.findExerciseByName(exercise.name).executeAsOneOrNull()

                            val resolvedEquipmentBulk = when {
                                exercise.isBodyweight -> "Bodyweight"
                                catalogExercise != null -> catalogExercise.equipment
                                else -> "Cable"
                            }

                            queries.insertRoutineExercise(
                                id = exercise.id,
                                routineId = portalRoutine.id,
                                exerciseName = exercise.name,
                                exerciseMuscleGroup = exercise.muscleGroup,
                                exerciseEquipment = resolvedEquipmentBulk,
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
                                weightPercentOfPR = (exercise.prPercentage?.toInt() ?: 80).toLong(),
                                prTypeForScaling = "MAX_WEIGHT",
                                setWeightsPercentOfPR = null,
                                stallDetectionEnabled = if (exercise.stallDetection) 1L else 0L,
                                stopAtTop = if (exercise.stopAtPosition == "TOP") 1L else 0L,
                                repCountTiming = exercise.repCountTiming ?: "TOP",
                                setEchoLevels = setEchoLevels,
                                warmupSets = exercise.warmupSets ?: "",
                            )
                        }
                    }
                }

                // 3. Cycles — SERVER WINS with single-active enforcement
                var portalActiveCycleId: String? = null
                for (portalCycle in cycles) {
                    if (portalCycle.status == "active") {
                        portalActiveCycleId = portalCycle.id
                    }

                    queries.insertTrainingCycleIgnore(
                        id = portalCycle.id,
                        name = portalCycle.name,
                        description = portalCycle.description,
                        created_at = currentTimeMillis(),
                        is_active = 0L,
                        profile_id = profileId,
                    )

                    val existingCycle = queries.selectTrainingCycleById(portalCycle.id).executeAsOneOrNull()
                    if (existingCycle != null) {
                        queries.updateTrainingCycle(
                            name = portalCycle.name,
                            description = portalCycle.description,
                            is_active = 0L,
                            id = portalCycle.id,
                        )
                    }

                    queries.deleteCycleDaysByCycle(portalCycle.id)
                    for (day in portalCycle.days) {
                        queries.insertCycleDayIgnore(
                            id = day.id,
                            cycle_id = day.cycleId.ifEmpty { portalCycle.id },
                            day_number = day.dayNumber.toLong(),
                            name = day.notes,
                            routine_id = day.routineId,
                            is_rest_day = if (day.dayType == "rest") 1L else 0L,
                            echo_level = null,
                            eccentric_load_percent = null,
                            weight_progression_percent = day.weightAdjustment.toDouble(),
                            rep_modifier = day.repModifier.toLong(),
                            rest_time_override_seconds = day.restOverride?.toLong(),
                        )
                    }

                    portalCycle.progressionSettings?.let { jsonStr ->
                        try {
                            val map = json.decodeFromString<Map<String, String>>(jsonStr)
                            queries.upsertCycleProgression(
                                cycle_id = portalCycle.id,
                                frequency_cycles = map["frequencyCycles"]?.toLongOrNull() ?: 2L,
                                weight_increase_percent = map["weightIncreasePercent"]?.toDoubleOrNull(),
                                echo_level_increase = if (map["echoLevelIncrease"] == "true") 1L else 0L,
                                eccentric_load_increase_percent = map["eccentricLoadIncreasePercent"]?.toLongOrNull(),
                            )
                        } catch (e: Exception) {
                            Logger.w(e) { "Failed to parse progressionSettings for cycle ${portalCycle.id}" }
                        }
                    }
                }

                // Single-active enforcement for cycles
                if (portalActiveCycleId != null) {
                    queries.deactivateAllCycles(profileId)
                    val activeCycle = cycles.first { it.id == portalActiveCycleId }
                    queries.updateTrainingCycle(
                        name = activeCycle.name,
                        description = activeCycle.description,
                        is_active = 1L,
                        id = portalActiveCycleId,
                    )
                }

                // Post-merge invariant check for cycles
                val activeCycleCount = queries.countActiveCycles(profileId).executeAsOne()
                if (activeCycleCount > 1) {
                    Logger.e { "INVARIANT VIOLATION in atomic merge: Found $activeCycleCount active cycles. Forcing deactivation." }
                    queries.deactivateAllCycles(profileId)
                    portalActiveCycleId?.let { id ->
                        val activeCycle = cycles.first { it.id == id }
                        queries.updateTrainingCycle(
                            name = activeCycle.name,
                            description = activeCycle.description,
                            is_active = 1L,
                            id = id,
                        )
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
                        totalVolumeKg = gamificationStats.totalVolumeKg.toLong(),
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
                for (pr in personalRecords) {
                    val effectiveVolume = if (pr.volume > 0f) pr.volume else pr.weight * pr.reps
                    queries.insertPRIgnore(
                        exerciseId = pr.exerciseId,
                        exerciseName = pr.exerciseName,
                        weight = pr.weight.toDouble(),
                        reps = pr.reps.toLong(),
                        oneRepMax = pr.oneRepMax.toDouble(),
                        achievedAt = pr.achievedAt,
                        workoutMode = pr.workoutMode,
                        prType = pr.prType,
                        volume = effectiveVolume.toDouble(),
                        phase = pr.phase,
                        profile_id = profileId,
                    )
                }
            }

            Logger.d {
                "Atomic merge complete: ${sessions.size} sessions, ${routines.size} routines, " +
                    "${cycles.size} cycles, ${badges.size} badges, ${personalRecords.size} PRs (profile=$profileId)"
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
        queries.selectAllPersonalRecordIdsByProfile(profileId).executeAsList()
    }
}
