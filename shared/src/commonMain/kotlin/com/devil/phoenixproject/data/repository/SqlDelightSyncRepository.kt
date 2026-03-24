package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
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
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WarmupSet
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
    private val db: VitruvianDatabase
) : SyncRepository {

    private val queries = db.vitruvianDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    // === Push Operations ===

    override suspend fun getSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSessionSyncDto> {
        return withContext(Dispatchers.IO) {
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
                    updatedAt = row.updatedAt ?: row.timestamp
                )
            }
        }
    }

    override suspend fun getPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecordSyncDto> {
        return withContext(Dispatchers.IO) {
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
                    updatedAt = row.updatedAt ?: row.achievedAt
                )
            }
        }
    }

    override suspend fun getRoutinesModifiedSince(timestamp: Long, profileId: String): List<RoutineSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectRoutinesModifiedSince(timestamp, profileId = profileId).executeAsList().map { row ->
                RoutineSyncDto(
                    clientId = row.id,
                    serverId = row.serverId,
                    name = row.name,
                    description = row.description,
                    deletedAt = row.deletedAt,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt ?: row.createdAt
                )
            }
        }
    }

    override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> {
        return withContext(Dispatchers.IO) {
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
                    updatedAt = row.updatedAt ?: row.created
                )
            }
        }
    }

    override suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectBadgesModifiedSince(timestamp).executeAsList().map { row ->
                EarnedBadgeSyncDto(
                    clientId = row.id.toString(),
                    serverId = row.serverId,
                    badgeId = row.badgeId,
                    earnedAt = row.earnedAt,
                    deletedAt = row.deletedAt,
                    createdAt = row.earnedAt,
                    updatedAt = row.updatedAt ?: row.earnedAt
                )
            }
        }
    }

    override suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto? {
        return withContext(Dispatchers.IO) {
            queries.selectGamificationStatsForSync().executeAsOneOrNull()?.let { row ->
                GamificationStatsSyncDto(
                    clientId = row.id.toString(),
                    totalWorkouts = row.totalWorkouts.toInt(),
                    totalReps = row.totalReps.toInt(),
                    totalVolumeKg = row.totalVolumeKg.toFloat(),
                    longestStreak = row.longestStreak.toInt(),
                    currentStreak = row.currentStreak.toInt(),
                    updatedAt = row.updatedAt ?: row.lastUpdated
                )
            }
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
            Logger.d { "Updated server IDs: ${mappings.sessions.size} sessions, ${mappings.records.size} PRs, ${mappings.routines.size} routines" }
        }
    }

    // === Pull Operations ===

    override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                sessions.forEach { dto ->
                    // Check if we have this session locally (by serverId or clientId)
                    val existingByServer = dto.serverId?.let {
                        queries.selectSessionByServerId(it).executeAsOneOrNull()
                    }

                    val localId = existingByServer?.id ?: dto.clientId

                    // Server wins for conflict resolution (last-write-wins)
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
                        profile_id = "default"
                    )
                }
            }
            Logger.d { "Merged ${sessions.size} sessions from server" }
        }
    }

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
                        profile_id = "default"
                    )
                }
            }
            Logger.d { "Merged ${records.size} PRs from server" }
        }
    }

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
                        profile_id = "default"
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

    override suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                exercises.forEach { dto ->
                    // Custom exercises - upsert by clientId
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
                        one_rep_max_kg = null
                    )

                    if (dto.serverId != null) {
                        queries.updateExerciseServerId(dto.serverId, dto.clientId)
                    }
                }
            }
            Logger.d { "Merged ${exercises.size} custom exercises from server" }
        }
    }

    override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                badges.forEach { dto ->
                    queries.insertEarnedBadge(dto.badgeId, dto.earnedAt)
                }
            }
            Logger.d { "Merged ${badges.size} badges from server" }
        }
    }

    override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?) {
        if (stats == null) return

        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()
            val existing = queries.selectGamificationStats().executeAsOneOrNull()

            queries.upsertGamificationStats(
                totalWorkouts = stats.totalWorkouts.toLong(),
                totalReps = stats.totalReps.toLong(),
                totalVolumeKg = stats.totalVolumeKg.toLong(),
                longestStreak = stats.longestStreak.toLong(),
                currentStreak = stats.currentStreak.toLong(),
                uniqueExercisesUsed = existing?.uniqueExercisesUsed ?: 0L,
                prsAchieved = existing?.prsAchieved ?: 0L,
                lastWorkoutDate = existing?.lastWorkoutDate,
                streakStartDate = existing?.streakStartDate,
                lastUpdated = now
            )
            Logger.d { "Merged gamification stats from server" }
        }
    }

    // === Portal Pull Operations (merge portal data) ===

    override suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                for (portalRoutine in routines) {
                    // Check if routine exists locally
                    val existing = queries.selectRoutineById(portalRoutine.id).executeAsOneOrNull()

                    if (existing != null) {
                        // Routine exists locally — check if it was modified since last sync
                        val localUpdatedAt = existing.updatedAt ?: 0L
                        if (localUpdatedAt > lastSync) {
                            // Local version is newer — skip this portal routine (PULL-03)
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
                        profile_id = existing?.profile_id ?: profileId
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
                        var supersetOrderIdx = 0
                        for ((ssId, ssExercises) in supersetGroups) {
                            val colorStr = ssExercises.firstOrNull()?.supersetColor
                            val colorIndex = colorStr?.toLongOrNull() ?: supersetOrderIdx.toLong()
                            queries.insertSupersetIgnore(
                                id = ssId,
                                routineId = portalRoutine.id,
                                name = "Superset ${supersetOrderIdx + 1}",
                                colorIndex = colorIndex,
                                restBetweenSeconds = 10L, // default
                                orderIndex = supersetOrderIdx.toLong()
                            )
                            supersetOrderIdx++
                        }

                        for (exercise in portalRoutine.exercises) {
                            // Build setReps string: e.g., "10,10,10" for sets=3, reps=10
                            val repsList = List(exercise.sets) {
                                if (exercise.isAmrap && it == exercise.sets - 1) "AMRAP"
                                else exercise.reps.toString()
                            }
                            val setReps = repsList.joinToString(",")

                            // Convert perSetWeights JSON "[50,55,60]" to comma-separated "50.0,55.0,60.0"
                            val setWeights = exercise.perSetWeights?.let { jsonStr ->
                                try {
                                    val parsed = Json.decodeFromString<List<Float>>(jsonStr)
                                    parsed.joinToString(",") { it.toString() }
                                } catch (_: Exception) { "" }
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
                                } catch (_: Exception) { "" }
                            } ?: ""

                            val mobileMode = PortalPullAdapter.portalModeToMobileMode(exercise.mode)

                            // Attempt catalog lookup so equipment and exerciseId are populated.
                            // Prevents bodyweight misclassification when equipment would default to "".
                            val catalogExercise = queries.findExerciseByName(exercise.name).executeAsOneOrNull()

                            queries.insertRoutineExercise(
                                id = exercise.id,
                                routineId = portalRoutine.id,
                                exerciseName = exercise.name,
                                exerciseMuscleGroup = exercise.muscleGroup,
                                exerciseEquipment = catalogExercise?.equipment ?: "Cable",
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
                                warmupSets = exercise.warmupSets ?: ""
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

    override suspend fun mergePortalCycles(cycles: List<PullTrainingCycleDto>, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                for (portalCycle in cycles) {
                    // Upsert cycle (INSERT OR IGNORE — keeps local if exists)
                    queries.insertTrainingCycleIgnore(
                        id = portalCycle.id,
                        name = portalCycle.name,
                        description = portalCycle.description,
                        created_at = currentTimeMillis(),
                        is_active = if (portalCycle.status == "active") 1L else 0L,
                        profile_id = profileId
                    )

                    // For existing cycles, update metadata (updateTrainingCycle takes 4 params: name, description, is_active, id)
                    // NOTE: Do NOT use setActiveTrainingCycle here — it deactivates ALL other cycles,
                    // which would clobber the user's local active cycle during a merge.
                    val existing = queries.selectTrainingCycleById(portalCycle.id).executeAsOneOrNull()
                    if (existing != null) {
                        queries.updateTrainingCycle(
                            name = portalCycle.name,
                            description = portalCycle.description,
                            is_active = if (portalCycle.status == "active") 1L else 0L,
                            id = portalCycle.id
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
                            rest_time_override_seconds = day.restOverride?.toLong()
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
                                eccentric_load_increase_percent = map["eccentricLoadIncreasePercent"]?.toLongOrNull()
                            )
                        } catch (e: Exception) {
                            Logger.w(e) { "Failed to parse progressionSettings for cycle ${portalCycle.id}" }
                        }
                    }
                }
            }
            Logger.d { "Merged ${cycles.size} portal training cycles with days and progressions" }
        }
    }

    override suspend fun mergePortalSessions(sessions: List<WorkoutSession>) {
        withContext(Dispatchers.IO) {
            db.transaction {
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
                        updatedAt = session.timestamp, // Mark as already-synced to prevent re-push
                        profile_id = session.profileId
                    )
                }
            }
            Logger.d { "Merged ${sessions.size} portal sessions (INSERT OR IGNORE)" }
        }
    }

    // === Portal Push Operations (full domain objects) ===

    override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSession> {
        return withContext(Dispatchers.IO) {
            queries.selectSessionsModifiedSince(timestamp, profileId = profileId, ::mapToWorkoutSession).executeAsList()
        }
    }

    override suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String): List<Routine> {
        return withContext(Dispatchers.IO) {
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
                        orderIndex = ssRow.orderIndex.toInt()
                    )
                }

                val exercises = exerciseRows.mapNotNull { exRow ->
                    try {
                        val exercise = Exercise(
                            id = exRow.exerciseId,
                            name = exRow.exerciseName,
                            muscleGroup = exRow.exerciseMuscleGroup,
                            muscleGroups = exRow.exerciseMuscleGroup,
                            equipment = exRow.exerciseEquipment
                        )

                        val setReps: List<Int?> = try {
                            exRow.setReps.split(",").map { value ->
                                val trimmed = value.trim()
                                if (trimmed.equals("AMRAP", ignoreCase = true)) null else trimmed.toIntOrNull()
                            }
                        } catch (_: Exception) { listOf(10) }

                        val setWeights: List<Float> = try {
                            if (exRow.setWeights.isBlank()) emptyList()
                            else exRow.setWeights.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        } catch (_: Exception) { emptyList() }

                        val setRestSeconds: List<Int> = try {
                            json.decodeFromString<List<Int>>(exRow.setRestSeconds)
                        } catch (_: Exception) { emptyList() }

                        val setEchoLevels: List<EchoLevel?> = try {
                            if (exRow.setEchoLevels.isBlank()) emptyList()
                            else json.decodeFromString<List<Int?>>(exRow.setEchoLevels).map { ordinal ->
                                ordinal?.let { EchoLevel.entries.getOrNull(it) }
                            }
                        } catch (_: Exception) { emptyList() }

                        val eccentricLoad = mapEccentricLoadFromDb(exRow.eccentricLoad)
                        val echoLevel = EchoLevel.entries.getOrNull(exRow.echoLevel.toInt()) ?: EchoLevel.HARDER
                        val programMode = parseProgramMode(exRow.mode)

                        val prTypeForScaling = try {
                            PRType.valueOf(exRow.prTypeForScaling)
                        } catch (_: Exception) { PRType.MAX_WEIGHT }

                        val setWeightsPercentOfPR: List<Int> = try {
                            if (exRow.setWeightsPercentOfPR.isNullOrBlank()) emptyList()
                            else json.decodeFromString<List<Int>>(exRow.setWeightsPercentOfPR)
                        } catch (_: Exception) { emptyList() }

                        val warmupSets: List<WarmupSet> = try {
                            if (exRow.warmupSets.isBlank()) emptyList()
                            else json.decodeFromString<List<WarmupSet>>(exRow.warmupSets)
                        } catch (_: Exception) { emptyList() }

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
                            repCountTiming = try { RepCountTiming.valueOf(exRow.repCountTiming) } catch (_: Exception) { RepCountTiming.TOP },
                            stopAtTop = exRow.stopAtTop == 1L,
                            supersetId = exRow.supersetId,
                            orderInSuperset = exRow.orderInSuperset.toInt(),
                            usePercentOfPR = exRow.usePercentOfPR == 1L,
                            weightPercentOfPR = exRow.weightPercentOfPR.toInt(),
                            prTypeForScaling = prTypeForScaling,
                            setWeightsPercentOfPR = setWeightsPercentOfPR,
                            warmupSets = warmupSets
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
                    useCount = row.useCount.toInt()
                )
            }
        }
    }

    override suspend fun getFullCyclesForSync(): List<CycleWithContext> {
        return withContext(Dispatchers.IO) {
            val cycles = queries.selectAllTrainingCyclesSync().executeAsList()
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
                            try { EchoLevel.valueOf(lvl) } catch (_: Exception) { null }
                        },
                        eccentricLoadPercent = d.eccentric_load_percent?.toInt(),
                        weightProgressionPercent = d.weight_progression_percent?.toFloat(),
                        repModifier = d.rep_modifier?.toInt(),
                        restTimeOverrideSeconds = d.rest_time_override_seconds?.toInt()
                    )
                }

                val progress = progressByCycle[row.id]?.let { p ->
                    CycleProgress(
                        id = p.id,
                        cycleId = p.cycle_id,
                        currentDayNumber = p.current_day_number.toInt(),
                        lastCompletedDate = p.last_completed_date,
                        cycleStartDate = p.cycle_start_date,
                        lastAdvancedAt = p.last_advanced_at
                    )
                }

                val progression = progressionByCycle[row.id]?.let { pg ->
                    CycleProgression(
                        cycleId = pg.cycle_id,
                        frequencyCycles = pg.frequency_cycles.toInt(),
                        weightIncreasePercent = pg.weight_increase_percent?.toFloat(),
                        echoLevelIncrease = pg.echo_level_increase != 0L,
                        eccentricLoadIncreasePercent = pg.eccentric_load_increase_percent?.toInt()
                    )
                }

                CycleWithContext(
                    cycle = TrainingCycle(
                        id = row.id,
                        name = row.name,
                        description = row.description,
                        days = days,
                        createdAt = row.created_at,
                        isActive = row.is_active == 1L
                    ),
                    progress = progress,
                    progression = progression
                )
            }
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
        profileId: String
    ): WorkoutSession {
        return WorkoutSession(
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
            profileId = profileId
        )
    }

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

    private fun parseProgramMode(modeStr: String): ProgramMode {
        return when {
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
    }

    // === Extended Sync Methods (GAPs 1-9) ===

    override suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecord> {
        return withContext(Dispatchers.IO) {
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
                    }
                )
            }
        }
    }

    override suspend fun getPhaseStatisticsForSessions(
        sessionIds: List<String>
    ): List<com.devil.phoenixproject.database.PhaseStatistics> {
        if (sessionIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            queries.selectPhaseStatsBySessionIds(sessionIds).executeAsList()
        }
    }

    override suspend fun getAllExerciseSignatures(): List<com.devil.phoenixproject.database.ExerciseSignature> {
        return withContext(Dispatchers.IO) {
            queries.selectAllSignatures().executeAsList()
        }
    }

    override suspend fun getAllAssessments(profileId: String): List<com.devil.phoenixproject.database.AssessmentResult> {
        return withContext(Dispatchers.IO) {
            queries.selectAllAssessments(profileId = profileId).executeAsList()
        }
    }
}
