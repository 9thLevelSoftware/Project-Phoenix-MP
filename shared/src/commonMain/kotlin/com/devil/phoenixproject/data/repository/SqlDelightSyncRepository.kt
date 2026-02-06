package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of SyncRepository.
 * Provides database operations for syncing data with the Phoenix Portal.
 */
class SqlDelightSyncRepository(
    private val db: VitruvianDatabase
) : SyncRepository {

    private val queries = db.vitruvianDatabaseQueries

    // === Push Operations ===

    override suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectSessionsModifiedSince(timestamp).executeAsList().map { row ->
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

    override suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectPRsModifiedSince(timestamp).executeAsList().map { row ->
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
                    deletedAt = row.deletedAt,
                    createdAt = row.achievedAt,
                    updatedAt = row.updatedAt ?: row.achievedAt
                )
            }
        }
    }

    override suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto> {
        return withContext(Dispatchers.IO) {
            queries.selectRoutinesModifiedSince(timestamp).executeAsList().map { row ->
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
                    totalVolumeKg = row.totalVolumeKg.toInt(),
                    longestStreak = row.longestStreak.toInt(),
                    currentStreak = row.currentStreak.toInt(),
                    updatedAt = row.updatedAt ?: row.lastUpdated
                )
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
                        estimatedCalories = null,
                        warmupAvgWeightKg = null,
                        workingAvgWeightKg = null,
                        burnoutAvgWeightKg = null,
                        peakWeightKg = null,
                        rpe = null,
                        updatedAt = dto.updatedAt,
                        serverId = dto.serverId,
                        deletedAt = dto.deletedAt
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
                    // For PRs, we upsert by exerciseId + workoutMode (unique key)
                    // Server data wins in conflicts
                    queries.upsertPR(
                        exerciseId = dto.exerciseId,
                        exerciseName = dto.exerciseName,
                        weight = dto.weight.toDouble(),
                        reps = dto.reps.toLong(),
                        oneRepMax = dto.oneRepMax.toDouble(),
                        achievedAt = dto.achievedAt,
                        workoutMode = dto.workoutMode,
                        prType = "MAX_WEIGHT",
                        volume = (dto.weight * dto.reps).toDouble()
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
                        useCount = existing?.useCount ?: 0L
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
}
