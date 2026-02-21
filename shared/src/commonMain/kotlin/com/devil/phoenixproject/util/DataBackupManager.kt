package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Platform-agnostic interface for backup/restore operations.
 * Platform implementations handle file I/O and sharing.
 */
interface DataBackupManager {
    /**
     * Export all data to a BackupData object
     */
    suspend fun exportAllData(): BackupData

    /**
     * Export all data as a JSON string
     */
    suspend fun exportToJson(): String

    /**
     * Import data from a JSON string
     * Uses "skip duplicates" strategy - existing records are not overwritten
     */
    suspend fun importFromJson(jsonString: String): Result<ImportResult>

    /**
     * Save backup to platform-specific location (Downloads on Android, Documents on iOS)
     * Returns the file path on success
     */
    suspend fun saveToFile(backup: BackupData): Result<String>

    /**
     * Export all data to a file using streaming JSON to avoid OOM on large datasets.
     * Writes data incrementally to disk -- peak memory is ~1 session's worth of metrics.
     * Returns the final file path on success.
     */
    suspend fun exportToFile(onProgress: (BackupProgress) -> Unit = {}): Result<String>

    /**
     * Import data from a file path
     */
    suspend fun importFromFile(filePath: String): Result<ImportResult>

    /**
     * Get shareable content (JSON string) for sharing via platform share sheet
     */
    suspend fun getShareableContent(): String

    /**
     * Share backup via platform share sheet (Android Intent, iOS UIActivityViewController)
     */
    suspend fun shareBackup()
}

/**
 * Common implementation that handles database operations.
 * Platform implementations extend this and add file I/O.
 */
abstract class BaseDataBackupManager(
    private val database: VitruvianDatabase
) : DataBackupManager {

    protected val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true  // Forward compatibility
        encodeDefaults = true
    }

    private val queries get() = database.vitruvianDatabaseQueries

    /**
     * Create a platform-specific JSON writer for streaming export.
     * The writer should point to a temporary/cache file location.
     */
    protected abstract fun createBackupWriter(): BackupJsonWriter

    /**
     * Platform-specific finalization after streaming export.
     * Copies/moves the temp file to the platform's standard backup location.
     */
    protected abstract suspend fun finalizeExport(tempFilePath: String): Result<String>

    private data class RoutineNameResolutionContext(
        val routineNameById: Map<String, String>,
        val uniqueRoutineNameByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseName: Map<String, String>
    )

    // -- Streaming export (Discussion #244 OOM fix) --

    override suspend fun exportToFile(onProgress: (BackupProgress) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val cachePath = exportToCache(onProgress)
                onProgress(BackupProgress(BackupPhase.FINALIZING, 0, 0))
                finalizeExport(cachePath)
            } catch (e: Exception) {
                Logger.e(e) { "Streaming export failed" }
                Result.failure(e)
            }
        }

    /**
     * Stream export to a cache/temp file. Returns the file path.
     * Used by both exportToFile() and shareBackup().
     */
    protected suspend fun exportToCache(onProgress: (BackupProgress) -> Unit = {}): String {
        val writer = createBackupWriter()
        try {
            writer.open()
            streamExportToWriter(writer, onProgress)
            writer.close()
            return writer.filePath
        } catch (e: Exception) {
            runCatching { writer.close() }
            runCatching { writer.delete() }
            throw e
        }
    }

    // -- Legacy export (kept for backward compatibility) --

    override suspend fun exportAllData(): BackupData = withContext(Dispatchers.IO) {
        val sessions = queries.selectAllSessionsSync().executeAsList()

        // IMPORTANT: Load metrics per-session to avoid memory exhaustion on iOS.
        // Loading all metrics at once can cause OOM crashes on iOS due to how the
        // native SQLite driver handles large result sets.
        val metrics = mutableListOf<MetricSample>()
        for (session in sessions) {
            val sessionMetrics = queries.selectMetricsBySession(session.id).executeAsList()
            metrics.addAll(sessionMetrics)
        }

        val routines = queries.selectAllRoutinesSync().executeAsList()
        val routineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
        val routineNameResolutionContext = buildRoutineNameResolutionContext(routines, routineExercises)
        // Supersets table might not exist on older databases
        val supersets = runCatching { queries.selectAllSupersetsSync().executeAsList() }.getOrElse { emptyList() }
        val personalRecords = queries.selectAllRecords { id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume, _, _, _ ->
            PersonalRecordBackup(
                id = id,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                weight = weight.toFloat(),
                reps = reps.toInt(),
                oneRepMax = oneRepMax.toFloat(),
                achievedAt = achievedAt,
                workoutMode = workoutMode,
                prType = prType,
                volume = volume.toFloat()
            )
        }.executeAsList()
        // Training cycles tables might not exist on older databases
        val trainingCycles = runCatching { queries.selectAllTrainingCycles().executeAsList() }.getOrElse { emptyList() }
        val cycleDays = trainingCycles.flatMap { cycle ->
            runCatching { queries.selectCycleDaysByCycle(cycle.id).executeAsList() }.getOrElse { emptyList() }
        }

        // New tables for complete backup - wrapped in try-catch because these tables
        // might not exist on older database versions. If a query fails (table missing,
        // lock contention, etc.), we return empty list rather than crash.
        val cycleProgress = runCatching { queries.selectAllCycleProgressSync().executeAsList() }.getOrElse { emptyList() }
        val cycleProgressions = runCatching { queries.selectAllCycleProgressionsSync().executeAsList() }.getOrElse { emptyList() }
        val plannedSets = runCatching { queries.selectAllPlannedSetsSync().executeAsList() }.getOrElse { emptyList() }
        val completedSets = runCatching { queries.selectAllCompletedSetsSync().executeAsList() }.getOrElse { emptyList() }
        val progressionEvents = runCatching { queries.selectAllProgressionEventsSync().executeAsList() }.getOrElse { emptyList() }
        val earnedBadges = runCatching { queries.selectAllEarnedBadgesSync().executeAsList() }.getOrElse { emptyList() }
        val streakHistory = runCatching { queries.selectAllStreakHistorySync().executeAsList() }.getOrElse { emptyList() }
        val gamificationStats = runCatching { queries.selectGamificationStatsSync().executeAsOneOrNull() }.getOrNull()
        val userProfiles = runCatching { queries.selectAllUserProfilesSync().executeAsList() }.getOrElse { emptyList() }

        BackupData(
            version = 1,
            exportedAt = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd") + "T" +
                    KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss") + "Z",
            appVersion = Constants.APP_VERSION,
            data = BackupContent(
                workoutSessions = sessions.map { session -> mapSessionToBackup(session, routineNameResolutionContext) },
                metricSamples = metrics.map { metric ->
                    MetricSampleBackup(
                        id = metric.id,
                        sessionId = metric.sessionId,
                        timestamp = metric.timestamp,
                        position = metric.position?.toFloat(),
                        positionB = metric.positionB?.toFloat(),
                        velocity = metric.velocity?.toFloat(),
                        velocityB = metric.velocityB?.toFloat(),
                        load = metric.load?.toFloat(),
                        loadB = metric.loadB?.toFloat(),
                        power = metric.power?.toFloat(),
                        status = metric.status.toInt()
                    )
                },
                routines = routines.map { routine -> mapRoutineToBackup(routine) },
                routineExercises = routineExercises.map { exercise ->
                    RoutineExerciseBackup(
                        id = exercise.id,
                        routineId = exercise.routineId,
                        exerciseName = exercise.exerciseName,
                        exerciseMuscleGroup = exercise.exerciseMuscleGroup,
                        exerciseEquipment = exercise.exerciseEquipment,
                        exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
                        exerciseId = exercise.exerciseId,
                        cableConfig = exercise.cableConfig,
                        orderIndex = exercise.orderIndex.toInt(),
                        setReps = exercise.setReps,
                        weightPerCableKg = exercise.weightPerCableKg.toFloat(),
                        setWeights = exercise.setWeights,
                        mode = exercise.mode,
                        eccentricLoad = exercise.eccentricLoad.toInt(),
                        echoLevel = exercise.echoLevel.toInt(),
                        progressionKg = exercise.progressionKg.toFloat(),
                        restSeconds = exercise.restSeconds.toInt(),
                        duration = exercise.duration?.toInt(),
                        setRestSeconds = exercise.setRestSeconds,
                        perSetRestTime = exercise.perSetRestTime != 0L,
                        isAMRAP = exercise.isAMRAP != 0L,
                        supersetId = exercise.supersetId,
                        orderInSuperset = exercise.orderInSuperset.toInt(),
                        // PR percentage scaling fields
                        usePercentOfPR = exercise.usePercentOfPR != 0L,
                        weightPercentOfPR = exercise.weightPercentOfPR.toInt(),
                        prTypeForScaling = exercise.prTypeForScaling,
                        setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
                        stallDetectionEnabled = exercise.stallDetectionEnabled != 0L,
                        stopAtTop = exercise.stopAtTop != 0L,
                        repCountTiming = exercise.repCountTiming
                    )
                },
                supersets = supersets.map { superset ->
                    SupersetBackup(
                        id = superset.id,
                        routineId = superset.routineId,
                        name = superset.name,
                        colorIndex = superset.colorIndex.toInt(),
                        restBetweenSeconds = superset.restBetweenSeconds.toInt(),
                        orderIndex = superset.orderIndex.toInt()
                    )
                },
                personalRecords = personalRecords,
                trainingCycles = trainingCycles.map { cycle -> mapTrainingCycleToBackup(cycle) },
                cycleDays = cycleDays.map { day ->
                    CycleDayBackup(
                        id = day.id,
                        cycleId = day.cycle_id,
                        dayNumber = day.day_number.toInt(),
                        name = day.name,
                        routineId = day.routine_id,
                        isRestDay = day.is_rest_day != 0L
                    )
                },
                cycleProgress = cycleProgress.map { cp ->
                    CycleProgressBackup(
                        id = cp.id,
                        cycleId = cp.cycle_id,
                        currentDayNumber = cp.current_day_number.toInt(),
                        lastCompletedDate = cp.last_completed_date,
                        cycleStartDate = cp.cycle_start_date,
                        lastAdvancedAt = cp.last_advanced_at,
                        completedDays = cp.completed_days,
                        missedDays = cp.missed_days,
                        rotationCount = cp.rotation_count.toInt()
                    )
                },
                cycleProgressions = cycleProgressions.map { cprog ->
                    CycleProgressionBackup(
                        cycleId = cprog.cycle_id,
                        frequencyCycles = cprog.frequency_cycles.toInt(),
                        weightIncreasePercent = cprog.weight_increase_percent?.toFloat(),
                        echoLevelIncrease = cprog.echo_level_increase.toInt(),
                        eccentricLoadIncreasePercent = cprog.eccentric_load_increase_percent?.toInt()
                    )
                },
                plannedSets = plannedSets.map { ps ->
                    PlannedSetBackup(
                        id = ps.id,
                        routineExerciseId = ps.routine_exercise_id,
                        setNumber = ps.set_number.toInt(),
                        setType = ps.set_type,
                        targetReps = ps.target_reps?.toInt(),
                        targetWeightKg = ps.target_weight_kg?.toFloat(),
                        targetRpe = ps.target_rpe?.toInt(),
                        restSeconds = ps.rest_seconds?.toInt()
                    )
                },
                completedSets = completedSets.map { cs ->
                    CompletedSetBackup(
                        id = cs.id,
                        sessionId = cs.session_id,
                        plannedSetId = cs.planned_set_id,
                        setNumber = cs.set_number.toInt(),
                        setType = cs.set_type,
                        actualReps = cs.actual_reps.toInt(),
                        actualWeightKg = cs.actual_weight_kg.toFloat(),
                        loggedRpe = cs.logged_rpe?.toInt(),
                        isPr = cs.is_pr != 0L,
                        completedAt = cs.completed_at
                    )
                },
                progressionEvents = progressionEvents.map { pe ->
                    ProgressionEventBackup(
                        id = pe.id,
                        exerciseId = pe.exercise_id,
                        suggestedWeightKg = pe.suggested_weight_kg.toFloat(),
                        previousWeightKg = pe.previous_weight_kg.toFloat(),
                        reason = pe.reason,
                        userResponse = pe.user_response,
                        actualWeightKg = pe.actual_weight_kg?.toFloat(),
                        timestamp = pe.timestamp
                    )
                },
                earnedBadges = earnedBadges.map { eb ->
                    EarnedBadgeBackup(
                        id = eb.id,
                        badgeId = eb.badgeId,
                        earnedAt = eb.earnedAt,
                        celebratedAt = eb.celebratedAt
                    )
                },
                streakHistory = streakHistory.map { sh ->
                    StreakHistoryBackup(
                        id = sh.id,
                        startDate = sh.startDate,
                        endDate = sh.endDate,
                        length = sh.length.toInt()
                    )
                },
                gamificationStats = gamificationStats?.let { gs ->
                    GamificationStatsBackup(
                        totalWorkouts = gs.totalWorkouts.toInt(),
                        totalReps = gs.totalReps.toInt(),
                        totalVolumeKg = gs.totalVolumeKg.toInt(),
                        longestStreak = gs.longestStreak.toInt(),
                        currentStreak = gs.currentStreak.toInt(),
                        uniqueExercisesUsed = gs.uniqueExercisesUsed.toInt(),
                        prsAchieved = gs.prsAchieved.toInt(),
                        lastWorkoutDate = gs.lastWorkoutDate,
                        streakStartDate = gs.streakStartDate,
                        lastUpdated = gs.lastUpdated
                    )
                },
                userProfiles = userProfiles.map { up ->
                    UserProfileBackup(
                        id = up.id,
                        name = up.name,
                        colorIndex = up.colorIndex.toInt(),
                        createdAt = up.createdAt,
                        isActive = up.isActive != 0L
                    )
                }
            )
        )
    }

    override suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(exportAllData())
    }

    override suspend fun importFromJson(jsonString: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val backup = json.decodeFromString<BackupData>(jsonString)

            if (backup.version > 1) {
                Logger.w { "Backup version ${backup.version} is newer than supported (v1). Proceeding with forward compatibility." }
            }

            // Get existing IDs for duplicate detection (before transaction)
            val existingSessionIds = queries.selectAllSessionIds().executeAsList().toSet()
            val existingRoutineIds = queries.selectAllRoutineIds().executeAsList().toSet()
            val existingSupersetIds = queries.selectAllSupersetIds().executeAsList().toSet()
            val existingPRIds = queries.selectAllPRIds().executeAsList().toSet()
            val existingCycleIds = queries.selectAllTrainingCycles().executeAsList().map { it.id }.toSet()
            val existingUserProfileIds = queries.selectAllUserProfileIds().executeAsList().toSet()
            val importRoutineNameResolutionContext = buildRoutineNameResolutionContextFromBackup(
                backup.data.routines,
                backup.data.routineExercises
            )

            // Track import counts
            var sessionsImported = 0
            var sessionsSkipped = 0
            var metricsImported = 0
            var routinesImported = 0
            var routinesSkipped = 0
            var routineExercisesImported = 0
            var supersetsImported = 0
            var supersetsSkipped = 0
            var personalRecordsImported = 0
            var personalRecordsSkipped = 0
            var trainingCyclesImported = 0
            var trainingCyclesSkipped = 0
            var cycleDaysImported = 0
            var userProfilesImported = 0
            var userProfilesSkipped = 0
            var cycleProgressImported = 0
            var cycleProgressionsImported = 0
            var plannedSetsImported = 0
            var completedSetsImported = 0
            var progressionEventsImported = 0
            var earnedBadgesImported = 0
            var streakHistoryImported = 0
            var gamificationStatsImported = false

            // Wrap all imports in a transaction for atomicity
            database.transaction {
                // Import workout sessions
                backup.data.workoutSessions.forEach { session ->
                    if (session.id !in existingSessionIds) {
                        // Sanitize eccentric load to prevent machine faults (hardware limit 150%)
                        val safeEccentricLoad = session.eccentricLoad.sanitizeEccentricLoad()
                        if (session.eccentricLoad != safeEccentricLoad) {
                            Logger.w { "Backup import: session ${session.id} eccentricLoad ${session.eccentricLoad}% clamped to ${safeEccentricLoad}% (hardware limit)" }
                        }
                        // Preserve original routineSessionId -- don't fabricate unique IDs.
                        // Fabricating per-session IDs breaks history grouping.
                        // Also filter out legacy_session_* IDs from earlier buggy exports.
                        val resolvedRoutineSessionId = sanitizeRoutineSessionId(session.routineSessionId)
                        val resolvedRoutineName = resolveImportedRoutineName(
                            session = session,
                            routineNameResolutionContext = importRoutineNameResolutionContext
                        )

                        queries.insertSession(
                            id = session.id,
                            timestamp = session.timestamp,
                            mode = session.mode,
                            targetReps = session.targetReps.toLong(),
                            weightPerCableKg = session.weightPerCableKg.toDouble(),
                            progressionKg = session.progressionKg.toDouble(),
                            duration = session.duration,
                            totalReps = session.totalReps.toLong(),
                            warmupReps = session.warmupReps.toLong(),
                            workingReps = session.workingReps.toLong(),
                            isJustLift = if (session.isJustLift) 1L else 0L,
                            stopAtTop = if (session.stopAtTop) 1L else 0L,
                            eccentricLoad = safeEccentricLoad.toLong(),
                            echoLevel = session.echoLevel.toLong(),
                            exerciseId = session.exerciseId,
                            exerciseName = session.exerciseName,
                            routineSessionId = resolvedRoutineSessionId,
                            routineName = resolvedRoutineName,
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
                            estimatedCalories = session.estimatedCalories?.toDouble(),
                            warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                            workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                            peakWeightKg = session.peakWeightKg?.toDouble(),
                            rpe = session.rpe?.toLong()
                        )
                        sessionsImported++
                    } else {
                        sessionsSkipped++
                    }
                }

                // Import metrics (only for imported sessions)
                val importedSessionIds = backup.data.workoutSessions
                    .filter { it.id !in existingSessionIds }
                    .map { it.id }
                    .toSet()

                backup.data.metricSamples.forEach { metric ->
                    if (metric.sessionId in importedSessionIds) {
                        queries.insertMetric(
                            sessionId = metric.sessionId,
                            timestamp = metric.timestamp,
                            position = metric.position?.toDouble(),
                            positionB = metric.positionB?.toDouble(),
                            velocity = metric.velocity?.toDouble(),
                            velocityB = metric.velocityB?.toDouble(),
                            load = metric.load?.toDouble(),
                            loadB = metric.loadB?.toDouble(),
                            power = metric.power?.toDouble(),
                            status = metric.status.toLong()
                        )
                        metricsImported++
                    }
                }

                // Import routines
                backup.data.routines.forEach { routine ->
                    if (routine.id !in existingRoutineIds) {
                        queries.insertRoutine(
                            id = routine.id,
                            name = routine.name,
                            description = routine.description,
                            createdAt = routine.createdAt,
                            lastUsed = routine.lastUsed,
                            useCount = routine.useCount.toLong()
                        )
                        routinesImported++
                    } else {
                        routinesSkipped++
                    }
                }

                // Import supersets (BEFORE routine exercises since exercises reference supersets)
                val importedRoutineIds = backup.data.routines
                    .filter { it.id !in existingRoutineIds }
                    .map { it.id }
                    .toSet()

                backup.data.supersets.forEach { superset ->
                    // Import superset if its routine is being imported or if superset doesn't exist
                    if (superset.routineId in importedRoutineIds || superset.id !in existingSupersetIds) {
                        if (superset.id !in existingSupersetIds) {
                            queries.insertSupersetIgnore(
                                id = superset.id,
                                routineId = superset.routineId,
                                name = superset.name,
                                colorIndex = superset.colorIndex.toLong(),
                                restBetweenSeconds = superset.restBetweenSeconds.toLong(),
                                orderIndex = superset.orderIndex.toLong()
                            )
                            supersetsImported++
                        } else {
                            supersetsSkipped++
                        }
                    }
                }

                // Import routine exercises (only for imported routines)
                backup.data.routineExercises.forEach { exercise ->
                    if (exercise.routineId in importedRoutineIds) {
                        // Sanitize eccentric load to prevent machine faults (hardware limit 150%)
                        val safeExerciseEccentricLoad = exercise.eccentricLoad.sanitizeEccentricLoad()
                        if (exercise.eccentricLoad != safeExerciseEccentricLoad) {
                            Logger.w { "Backup import: routine exercise ${exercise.exerciseName} eccentricLoad ${exercise.eccentricLoad}% clamped to ${safeExerciseEccentricLoad}% (hardware limit)" }
                        }

                        queries.insertRoutineExerciseIgnore(
                            id = exercise.id,
                            routineId = exercise.routineId,
                            exerciseName = exercise.exerciseName,
                            exerciseMuscleGroup = exercise.exerciseMuscleGroup,
                            exerciseEquipment = exercise.exerciseEquipment,
                            exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
                            exerciseId = exercise.exerciseId,
                            cableConfig = exercise.cableConfig,
                            orderIndex = exercise.orderIndex.toLong(),
                            setReps = exercise.setReps,
                            weightPerCableKg = exercise.weightPerCableKg.toDouble(),
                            setWeights = exercise.setWeights,
                            mode = exercise.mode,
                            eccentricLoad = safeExerciseEccentricLoad.toLong(),
                            echoLevel = exercise.echoLevel.toLong(),
                            progressionKg = exercise.progressionKg.toDouble(),
                            restSeconds = exercise.restSeconds.toLong(),
                            duration = exercise.duration?.toLong(),
                            setRestSeconds = exercise.setRestSeconds,
                            perSetRestTime = if (exercise.perSetRestTime) 1L else 0L,
                            isAMRAP = if (exercise.isAMRAP) 1L else 0L,
                            supersetId = exercise.supersetId,
                            orderInSuperset = exercise.orderInSuperset.toLong(),
                            usePercentOfPR = if (exercise.usePercentOfPR) 1L else 0L,
                            weightPercentOfPR = exercise.weightPercentOfPR.toLong(),
                            prTypeForScaling = exercise.prTypeForScaling,
                            setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
                            stallDetectionEnabled = if (exercise.stallDetectionEnabled) 1L else 0L,
                            stopAtTop = if (exercise.stopAtTop) 1L else 0L,
                            repCountTiming = exercise.repCountTiming
                        )
                        routineExercisesImported++
                    }
                }

                // Import personal records
                backup.data.personalRecords.forEach { pr ->
                    if (pr.id !in existingPRIds) {
                        queries.insertRecord(
                            exerciseId = pr.exerciseId,
                            exerciseName = pr.exerciseName,
                            weight = pr.weight.toDouble(),
                            reps = pr.reps.toLong(),
                            oneRepMax = pr.oneRepMax.toDouble(),
                            achievedAt = pr.achievedAt,
                            workoutMode = pr.workoutMode,
                            prType = pr.prType,
                            volume = pr.volume.toDouble()
                        )
                        personalRecordsImported++
                    } else {
                        personalRecordsSkipped++
                    }
                }

                // Import training cycles
                backup.data.trainingCycles.forEach { cycle ->
                    if (cycle.id !in existingCycleIds) {
                        queries.insertTrainingCycle(
                            id = cycle.id,
                            name = cycle.name,
                            description = cycle.description,
                            created_at = cycle.createdAt,
                            is_active = if (cycle.isActive) 1L else 0L
                        )
                        trainingCyclesImported++
                    } else {
                        trainingCyclesSkipped++
                    }
                }

                // Import cycle days (only for imported cycles)
                val importedCycleIds = backup.data.trainingCycles
                    .filter { it.id !in existingCycleIds }
                    .map { it.id }
                    .toSet()

                backup.data.cycleDays.forEach { day ->
                    if (day.cycleId in importedCycleIds) {
                        queries.insertCycleDay(
                            id = day.id,
                            cycle_id = day.cycleId,
                            day_number = day.dayNumber.toLong(),
                            name = day.name,
                            routine_id = day.routineId,
                            is_rest_day = if (day.isRestDay) 1L else 0L,
                            echo_level = null,
                            eccentric_load_percent = null,
                            weight_progression_percent = null,
                            rep_modifier = null,
                            rest_time_override_seconds = null
                        )
                        cycleDaysImported++
                    }
                }

                // Import user profiles
                backup.data.userProfiles.forEach { profile ->
                    if (profile.id !in existingUserProfileIds) {
                        queries.insertUserProfileIgnore(
                            id = profile.id,
                            name = profile.name,
                            colorIndex = profile.colorIndex.toLong(),
                            createdAt = profile.createdAt,
                            isActive = if (profile.isActive) 1L else 0L
                        )
                        userProfilesImported++
                    } else {
                        userProfilesSkipped++
                    }
                }

                // Import cycle progress (only for imported cycles)
                backup.data.cycleProgress.forEach { progress ->
                    if (progress.cycleId in importedCycleIds) {
                        queries.insertCycleProgressIgnore(
                            id = progress.id,
                            cycle_id = progress.cycleId,
                            current_day_number = progress.currentDayNumber.toLong(),
                            last_completed_date = progress.lastCompletedDate,
                            cycle_start_date = progress.cycleStartDate,
                            last_advanced_at = progress.lastAdvancedAt,
                            completed_days = progress.completedDays,
                            missed_days = progress.missedDays,
                            rotation_count = progress.rotationCount.toLong()
                        )
                        cycleProgressImported++
                    }
                }

                // Import cycle progressions (only for imported cycles)
                backup.data.cycleProgressions.forEach { progression ->
                    if (progression.cycleId in importedCycleIds) {
                        queries.insertCycleProgressionIgnore(
                            cycle_id = progression.cycleId,
                            frequency_cycles = progression.frequencyCycles.toLong(),
                            weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
                            echo_level_increase = progression.echoLevelIncrease.toLong(),
                            eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong()
                        )
                        cycleProgressionsImported++
                    }
                }

                // Import planned sets (only for imported routine exercises)
                val importedRoutineExerciseIds = backup.data.routineExercises
                    .filter { it.routineId in importedRoutineIds }
                    .map { it.id }
                    .toSet()

                backup.data.plannedSets.forEach { plannedSet ->
                    if (plannedSet.routineExerciseId in importedRoutineExerciseIds) {
                        queries.insertPlannedSetIgnore(
                            id = plannedSet.id,
                            routine_exercise_id = plannedSet.routineExerciseId,
                            set_number = plannedSet.setNumber.toLong(),
                            set_type = plannedSet.setType,
                            target_reps = plannedSet.targetReps?.toLong(),
                            target_weight_kg = plannedSet.targetWeightKg?.toDouble(),
                            target_rpe = plannedSet.targetRpe?.toLong(),
                            rest_seconds = plannedSet.restSeconds?.toLong()
                        )
                        plannedSetsImported++
                    }
                }

                // Import completed sets (only for imported sessions)
                backup.data.completedSets.forEach { completedSet ->
                    if (completedSet.sessionId in importedSessionIds) {
                        queries.insertCompletedSetIgnore(
                            id = completedSet.id,
                            session_id = completedSet.sessionId,
                            planned_set_id = completedSet.plannedSetId,
                            set_number = completedSet.setNumber.toLong(),
                            set_type = completedSet.setType,
                            actual_reps = completedSet.actualReps.toLong(),
                            actual_weight_kg = completedSet.actualWeightKg.toDouble(),
                            logged_rpe = completedSet.loggedRpe?.toLong(),
                            is_pr = if (completedSet.isPr) 1L else 0L,
                            completed_at = completedSet.completedAt
                        )
                        completedSetsImported++
                    }
                }

                // Import progression events
                backup.data.progressionEvents.forEach { event ->
                    queries.insertProgressionEventIgnore(
                        id = event.id,
                        exercise_id = event.exerciseId,
                        suggested_weight_kg = event.suggestedWeightKg.toDouble(),
                        previous_weight_kg = event.previousWeightKg.toDouble(),
                        reason = event.reason,
                        user_response = event.userResponse,
                        actual_weight_kg = event.actualWeightKg?.toDouble(),
                        timestamp = event.timestamp
                    )
                    progressionEventsImported++
                }

                // Import earned badges
                backup.data.earnedBadges.forEach { badge ->
                    queries.insertEarnedBadgeIgnore(
                        badgeId = badge.badgeId,
                        earnedAt = badge.earnedAt,
                        celebratedAt = badge.celebratedAt
                    )
                    earnedBadgesImported++
                }

                // Import streak history
                backup.data.streakHistory.forEach { streak ->
                    queries.insertStreakHistoryIgnore(
                        startDate = streak.startDate,
                        endDate = streak.endDate,
                        length = streak.length.toLong()
                    )
                    streakHistoryImported++
                }

                // Import gamification stats (upsert - replaces existing)
                backup.data.gamificationStats?.let { stats ->
                    queries.upsertGamificationStats(
                        totalWorkouts = stats.totalWorkouts.toLong(),
                        totalReps = stats.totalReps.toLong(),
                        totalVolumeKg = stats.totalVolumeKg.toLong(),
                        longestStreak = stats.longestStreak.toLong(),
                        currentStreak = stats.currentStreak.toLong(),
                        uniqueExercisesUsed = stats.uniqueExercisesUsed.toLong(),
                        prsAchieved = stats.prsAchieved.toLong(),
                        lastWorkoutDate = stats.lastWorkoutDate,
                        streakStartDate = stats.streakStartDate,
                        lastUpdated = stats.lastUpdated
                    )
                    gamificationStatsImported = true
                }
            }

            Result.success(
                ImportResult(
                    sessionsImported = sessionsImported,
                    sessionsSkipped = sessionsSkipped,
                    metricsImported = metricsImported,
                    routinesImported = routinesImported,
                    routinesSkipped = routinesSkipped,
                    routineExercisesImported = routineExercisesImported,
                    supersetsImported = supersetsImported,
                    supersetsSkipped = supersetsSkipped,
                    personalRecordsImported = personalRecordsImported,
                    personalRecordsSkipped = personalRecordsSkipped,
                    trainingCyclesImported = trainingCyclesImported,
                    trainingCyclesSkipped = trainingCyclesSkipped,
                    cycleDaysImported = cycleDaysImported,
                    cycleProgressImported = cycleProgressImported,
                    cycleProgressionsImported = cycleProgressionsImported,
                    plannedSetsImported = plannedSetsImported,
                    completedSetsImported = completedSetsImported,
                    progressionEventsImported = progressionEventsImported,
                    earnedBadgesImported = earnedBadgesImported,
                    streakHistoryImported = streakHistoryImported,
                    gamificationStatsImported = gamificationStatsImported,
                    userProfilesImported = userProfilesImported,
                    userProfilesSkipped = userProfilesSkipped
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getShareableContent(): String = exportToJson()

    // -- Streaming JSON writer --

    private fun streamExportToWriter(
        writer: BackupJsonWriter,
        onProgress: (BackupProgress) -> Unit
    ) {
        // Phase 1: Count
        onProgress(BackupProgress(BackupPhase.COUNTING, 0, 0))
        val sessionCount = queries.countTotalWorkouts().executeAsOne()
        val metricCount = runCatching { queries.countAllMetricSamples().executeAsOne() }.getOrElse { 0L }
        val routines = queries.selectAllRoutinesSync().executeAsList()
        val routineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
        val routineNameResolutionContext = buildRoutineNameResolutionContext(routines, routineExercises)

        // JSON header
        val exportedAt = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd") + "T" +
            KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss") + "Z"
        writer.write("""{"version":1,"exportedAt":"$exportedAt","appVersion":"${Constants.APP_VERSION}","data":{""")

        // Phase 2: Sessions
        onProgress(BackupProgress(BackupPhase.SESSIONS, 0, sessionCount))
        writer.write("\"workoutSessions\":[")
        val sessions = queries.selectAllSessionsSync().executeAsList()
        sessions.forEachIndexed { index, session ->
            if (index > 0) writer.write(",")
            writer.write(json.encodeToString(WorkoutSessionBackup.serializer(), mapSessionToBackup(session, routineNameResolutionContext)))
            val current = (index + 1).toLong()
            if (current % 100 == 0L || current == sessions.size.toLong()) {
                writer.flush()
                onProgress(BackupProgress(BackupPhase.SESSIONS, current, sessionCount))
            }
        }
        writer.write("],")
        writer.flush()

        // Phase 3: Metrics (critical path -- per-session to avoid OOM)
        onProgress(BackupProgress(BackupPhase.METRICS, 0, metricCount))
        writer.write("\"metricSamples\":[")
        var metricIndex = 0L
        var firstMetric = true
        for (session in sessions) {
            val sessionMetrics = queries.selectMetricsBySession(session.id).executeAsList()
            for (metric in sessionMetrics) {
                if (!firstMetric) writer.write(",")
                firstMetric = false
                writer.write(json.encodeToString(MetricSampleBackup.serializer(), mapMetricToBackup(metric)))
                metricIndex++
            }
            if (sessionMetrics.isNotEmpty()) {
                writer.flush()
                onProgress(BackupProgress(BackupPhase.METRICS, metricIndex, metricCount))
            }
        }
        writer.write("],")
        writer.flush()

        // Phase 4: Routines
        onProgress(BackupProgress(BackupPhase.ROUTINES, 0, 0))
        writeJsonArray(writer, "routines", routines.map { json.encodeToString(RoutineBackup.serializer(), mapRoutineToBackup(it)) })
        writer.write(",")
        writeJsonArray(writer, "routineExercises", routineExercises.map { json.encodeToString(RoutineExerciseBackup.serializer(), mapRoutineExerciseToBackup(it)) })
        writer.write(",")

        // Phase 5: Remaining tables (small, bulk-load is safe)
        onProgress(BackupProgress(BackupPhase.OTHER, 0, 0))

        val supersets = runCatching { queries.selectAllSupersetsSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "supersets", supersets.map { json.encodeToString(SupersetBackup.serializer(), mapSupersetToBackup(it)) })
        writer.write(",")

        val personalRecords = queries.selectAllRecords().executeAsList()
        writeJsonArray(writer, "personalRecords", personalRecords.map { json.encodeToString(PersonalRecordBackup.serializer(), mapPersonalRecordToBackup(it)) })
        writer.write(",")

        val trainingCycles = runCatching { queries.selectAllTrainingCycles().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "trainingCycles", trainingCycles.map { json.encodeToString(TrainingCycleBackup.serializer(), mapTrainingCycleToBackup(it)) })
        writer.write(",")

        val cycleDays = trainingCycles.flatMap { cycle ->
            runCatching { queries.selectCycleDaysByCycle(cycle.id).executeAsList() }.getOrElse { emptyList() }
        }
        writeJsonArray(writer, "cycleDays", cycleDays.map { json.encodeToString(CycleDayBackup.serializer(), mapCycleDayToBackup(it)) })
        writer.write(",")

        val cycleProgress = runCatching { queries.selectAllCycleProgressSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "cycleProgress", cycleProgress.map { json.encodeToString(CycleProgressBackup.serializer(), mapCycleProgressToBackup(it)) })
        writer.write(",")

        val cycleProgressions = runCatching { queries.selectAllCycleProgressionsSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "cycleProgressions", cycleProgressions.map { json.encodeToString(CycleProgressionBackup.serializer(), mapCycleProgressionToBackup(it)) })
        writer.write(",")

        val plannedSets = runCatching { queries.selectAllPlannedSetsSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "plannedSets", plannedSets.map { json.encodeToString(PlannedSetBackup.serializer(), mapPlannedSetToBackup(it)) })
        writer.write(",")

        val completedSets = runCatching { queries.selectAllCompletedSetsSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "completedSets", completedSets.map { json.encodeToString(CompletedSetBackup.serializer(), mapCompletedSetToBackup(it)) })
        writer.write(",")

        val progressionEvents = runCatching { queries.selectAllProgressionEventsSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "progressionEvents", progressionEvents.map { json.encodeToString(ProgressionEventBackup.serializer(), mapProgressionEventToBackup(it)) })
        writer.write(",")

        val earnedBadges = runCatching { queries.selectAllEarnedBadgesSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "earnedBadges", earnedBadges.map { json.encodeToString(EarnedBadgeBackup.serializer(), mapEarnedBadgeToBackup(it)) })
        writer.write(",")

        val streakHistory = runCatching { queries.selectAllStreakHistorySync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "streakHistory", streakHistory.map { json.encodeToString(StreakHistoryBackup.serializer(), mapStreakHistoryToBackup(it)) })
        writer.write(",")

        // gamificationStats -- single object or null
        val gamificationStats = runCatching { queries.selectGamificationStatsSync().executeAsOneOrNull() }.getOrNull()
        if (gamificationStats != null) {
            writer.write("\"gamificationStats\":")
            writer.write(json.encodeToString(GamificationStatsBackup.serializer(), mapGamificationStatsToBackup(gamificationStats)))
        } else {
            writer.write("\"gamificationStats\":null")
        }
        writer.write(",")

        val userProfiles = runCatching { queries.selectAllUserProfilesSync().executeAsList() }.getOrElse { emptyList() }
        writeJsonArray(writer, "userProfiles", userProfiles.map { json.encodeToString(UserProfileBackup.serializer(), mapUserProfileToBackup(it)) })

        // Close JSON
        writer.write("}}")
        writer.flush()
    }

    private fun writeJsonArray(writer: BackupJsonWriter, fieldName: String, jsonStrings: List<String>) {
        writer.write("\"$fieldName\":[")
        jsonStrings.forEachIndexed { index, s ->
            if (index > 0) writer.write(",")
            writer.write(s)
        }
        writer.write("]")
    }

    // -- Mapper functions (DB types → Backup types) --

    /**
     * Legacy sessions may have null routine metadata due to older client behavior.
     * Normalize to stable non-null placeholders during export so backups remain
     * self-describing and don't require manual data repair by end users.
     */
    private fun normalizeRoutineMetadataForBackup(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext? = null
    ): Pair<String?, String?> {
        val existingSessionId = sanitizeRoutineSessionId(session.routineSessionId)
        val existingRoutineName = sanitizeRoutineName(session.routineName)

        // Direct lookup via routineId (most reliable - added in migration 12)
        val directLookupName = session.routineId?.let { routineId ->
            routineNameResolutionContext?.routineNameById?.get(routineId)
        }

        // Heuristic inference for legacy sessions without routineId
        val inferredRoutineNameById = session.exerciseId?.let { exerciseId ->
            routineNameResolutionContext?.uniqueRoutineNameByExerciseId?.get(exerciseId)
        }
        val inferredRoutineNameByExerciseName = normalizeExerciseToken(session.exerciseName)?.let { normalizedExerciseName ->
            routineNameResolutionContext?.uniqueRoutineNameByExerciseName?.get(normalizedExerciseName)
        }
        val inferredRoutineName = inferredRoutineNameById ?: inferredRoutineNameByExerciseName
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        // Don't fabricate routineSessionId -- if none exists, leave null.
        // Fabricating unique IDs per session breaks history grouping.
        val normalizedRoutineName = when {
            session.isJustLift != 0L -> "Just Lift"
            directLookupName != null -> directLookupName
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null && !existingLooksLikeExercisePlaceholder -> existingRoutineName
            else -> null  // Can't determine routine - leave null (standalone exercise)
        }

        return existingSessionId to normalizedRoutineName
    }

    private fun buildRoutineNameResolutionContext(
        routines: List<Routine>,
        routineExercises: List<RoutineExercise>
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }
        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNames(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseId = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val exerciseId = sanitizeLegacyLabel(exercise.exerciseId) ?: return@forEach
                routineIdsByExerciseId.getOrPut(exerciseId) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseId.forEach { (exerciseId, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseId] = routineName
            }
            return uniqueRoutineNames
        }

        fun collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseName = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val normalizedExerciseName = normalizeExerciseToken(exercise.exerciseName) ?: return@forEach
                routineIdsByExerciseName.getOrPut(normalizedExerciseName) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseName.forEach { (normalizedExerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[normalizedExerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        // Prefer user-authored/non-template routines first to avoid cycle template noise.
        val uniqueFromNonTemplate = collectUniqueRoutineNames(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueFromAll = collectUniqueRoutineNames()
        val uniqueRoutineNameByExerciseId = uniqueFromAll.toMutableMap().apply {
            putAll(uniqueFromNonTemplate)
        }
        val uniqueByNameFromNonTemplate = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueByNameFromAll = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueByNameFromAll.toMutableMap().apply {
            putAll(uniqueByNameFromNonTemplate)
        }

        return RoutineNameResolutionContext(
            routineNameById = routineNameById,
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName
        )
    }

    private fun buildRoutineNameResolutionContextFromBackup(
        routines: List<RoutineBackup>,
        routineExercises: List<RoutineExerciseBackup>
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }
        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNames(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseId = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val exerciseId = sanitizeLegacyLabel(exercise.exerciseId) ?: return@forEach
                routineIdsByExerciseId.getOrPut(exerciseId) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseId.forEach { (exerciseId, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseId] = routineName
            }
            return uniqueRoutineNames
        }

        fun collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds: Set<String>? = null
        ): Map<String, String> {
            val routineIdsByExerciseName = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val normalizedExerciseName = normalizeExerciseToken(exercise.exerciseName) ?: return@forEach
                routineIdsByExerciseName.getOrPut(normalizedExerciseName) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseName.forEach { (normalizedExerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[normalizedExerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        // Prefer user-authored/non-template routines first to avoid cycle template noise.
        val uniqueFromNonTemplate = collectUniqueRoutineNames(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueFromAll = collectUniqueRoutineNames()
        val uniqueRoutineNameByExerciseId = uniqueFromAll.toMutableMap().apply {
            putAll(uniqueFromNonTemplate)
        }
        val uniqueByNameFromNonTemplate = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() }
        )
        val uniqueByNameFromAll = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueByNameFromAll.toMutableMap().apply {
            putAll(uniqueByNameFromNonTemplate)
        }

        return RoutineNameResolutionContext(
            routineNameById = routineNameById,
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName
        )
    }

    private fun resolveImportedRoutineName(
        session: WorkoutSessionBackup,
        routineNameResolutionContext: RoutineNameResolutionContext
    ): String? {
        val existingRoutineName = sanitizeRoutineName(session.routineName)
        val directLookupName = session.routineId?.let { routineId ->
            routineNameResolutionContext.routineNameById[routineId]
        }
        val inferredRoutineNameById = session.exerciseId?.let { exerciseId ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseId[exerciseId]
        }
        val inferredRoutineNameByExerciseName = normalizeExerciseToken(session.exerciseName)?.let { normalizedExerciseName ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseName[normalizedExerciseName]
        }
        val inferredRoutineName = inferredRoutineNameById ?: inferredRoutineNameByExerciseName
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        return when {
            session.isJustLift -> "Just Lift"
            directLookupName != null -> directLookupName
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null && !existingLooksLikeExercisePlaceholder -> existingRoutineName
            else -> null
        }
    }

    /**
     * Treat low-quality legacy values as missing.
     * Examples filtered out: blank, "null", ":", "--", punctuation-only tokens.
     */
    private fun sanitizeLegacyLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        if (!trimmed.any { it.isLetterOrDigit() }) return null
        return trimmed
    }

    /**
     * Generic placeholder routine names set by external imports (e.g. Vitruvian cloud).
     * These don't identify a real routine and should be treated as null/unknown.
     */
    private val GARBAGE_ROUTINE_NAMES = setOf(
        "imported strength training session"
    )

    /**
     * Sanitize a routine name, also filtering out known garbage placeholder values
     * that were injected by external imports and don't represent real routine names.
     */
    private fun sanitizeRoutineName(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        if (sanitized.lowercase().trim() in GARBAGE_ROUTINE_NAMES) return null
        return sanitized
    }

    /**
     * Sanitize a routineSessionId, also filtering out fabricated `legacy_session_*` IDs
     * that were incorrectly generated by an earlier version of the export/import code.
     */
    private fun sanitizeRoutineSessionId(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        if (sanitized.startsWith("legacy_session_", ignoreCase = true)) return null
        return sanitized
    }

    private fun sanitizeEntityName(raw: String?, fallback: String): String {
        return sanitizeLegacyLabel(raw) ?: fallback
    }

    private fun normalizeExerciseToken(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        val collapsedWhitespace = sanitized
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapsedWhitespace.ifEmpty { null }
    }

    private fun mapSessionToBackup(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext? = null
    ): WorkoutSessionBackup {
        val (routineSessionId, routineName) = normalizeRoutineMetadataForBackup(session, routineNameResolutionContext)
        return WorkoutSessionBackup(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            targetReps = session.targetReps.toInt(),
            weightPerCableKg = session.weightPerCableKg.toFloat(),
            progressionKg = session.progressionKg.toFloat(),
            duration = session.duration,
            totalReps = session.totalReps.toInt(),
            warmupReps = session.warmupReps.toInt(),
            workingReps = session.workingReps.toInt(),
            isJustLift = session.isJustLift != 0L,
            stopAtTop = session.stopAtTop != 0L,
            eccentricLoad = session.eccentricLoad.toInt(),
            echoLevel = session.echoLevel.toInt(),
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = routineSessionId,
            routineName = routineName,
            routineId = session.routineId,
            safetyFlags = session.safetyFlags.toInt(),
            deloadWarningCount = session.deloadWarningCount.toInt(),
            romViolationCount = session.romViolationCount.toInt(),
            spotterActivations = session.spotterActivations.toInt(),
            peakForceConcentricA = session.peakForceConcentricA?.toFloat(),
            peakForceConcentricB = session.peakForceConcentricB?.toFloat(),
            peakForceEccentricA = session.peakForceEccentricA?.toFloat(),
            peakForceEccentricB = session.peakForceEccentricB?.toFloat(),
            avgForceConcentricA = session.avgForceConcentricA?.toFloat(),
            avgForceConcentricB = session.avgForceConcentricB?.toFloat(),
            avgForceEccentricA = session.avgForceEccentricA?.toFloat(),
            avgForceEccentricB = session.avgForceEccentricB?.toFloat(),
            heaviestLiftKg = session.heaviestLiftKg?.toFloat(),
            totalVolumeKg = session.totalVolumeKg?.toFloat(),
            estimatedCalories = session.estimatedCalories?.toFloat(),
            warmupAvgWeightKg = session.warmupAvgWeightKg?.toFloat(),
            workingAvgWeightKg = session.workingAvgWeightKg?.toFloat(),
            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toFloat(),
            peakWeightKg = session.peakWeightKg?.toFloat(),
            rpe = session.rpe?.toInt()
        )
    }

    private fun mapMetricToBackup(metric: MetricSample): MetricSampleBackup =
        MetricSampleBackup(
            id = metric.id,
            sessionId = metric.sessionId,
            timestamp = metric.timestamp,
            position = metric.position?.toFloat(),
            positionB = metric.positionB?.toFloat(),
            velocity = metric.velocity?.toFloat(),
            velocityB = metric.velocityB?.toFloat(),
            load = metric.load?.toFloat(),
            loadB = metric.loadB?.toFloat(),
            power = metric.power?.toFloat(),
            status = metric.status.toInt()
        )

    private fun mapRoutineToBackup(routine: Routine): RoutineBackup =
        RoutineBackup(
            id = routine.id,
            name = sanitizeEntityName(routine.name, "Unnamed Routine"),
            description = routine.description,
            createdAt = routine.createdAt,
            lastUsed = routine.lastUsed,
            useCount = routine.useCount.toInt()
        )

    private fun mapRoutineExerciseToBackup(exercise: RoutineExercise): RoutineExerciseBackup =
        RoutineExerciseBackup(
            id = exercise.id,
            routineId = exercise.routineId,
            exerciseName = exercise.exerciseName,
            exerciseMuscleGroup = exercise.exerciseMuscleGroup,
            exerciseEquipment = exercise.exerciseEquipment,
            exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
            exerciseId = exercise.exerciseId,
            cableConfig = exercise.cableConfig,
            orderIndex = exercise.orderIndex.toInt(),
            setReps = exercise.setReps,
            weightPerCableKg = exercise.weightPerCableKg.toFloat(),
            setWeights = exercise.setWeights,
            mode = exercise.mode,
            eccentricLoad = exercise.eccentricLoad.toInt(),
            echoLevel = exercise.echoLevel.toInt(),
            progressionKg = exercise.progressionKg.toFloat(),
            restSeconds = exercise.restSeconds.toInt(),
            duration = exercise.duration?.toInt(),
            setRestSeconds = exercise.setRestSeconds,
            perSetRestTime = exercise.perSetRestTime != 0L,
            isAMRAP = exercise.isAMRAP != 0L,
            supersetId = exercise.supersetId,
            orderInSuperset = exercise.orderInSuperset.toInt(),
            usePercentOfPR = exercise.usePercentOfPR != 0L,
            weightPercentOfPR = exercise.weightPercentOfPR.toInt(),
            prTypeForScaling = exercise.prTypeForScaling,
            setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
            stallDetectionEnabled = exercise.stallDetectionEnabled != 0L,
            stopAtTop = exercise.stopAtTop != 0L,
            repCountTiming = exercise.repCountTiming
        )

    private fun mapSupersetToBackup(superset: Superset): SupersetBackup =
        SupersetBackup(
            id = superset.id,
            routineId = superset.routineId,
            name = superset.name,
            colorIndex = superset.colorIndex.toInt(),
            restBetweenSeconds = superset.restBetweenSeconds.toInt(),
            orderIndex = superset.orderIndex.toInt()
        )

    private fun mapPersonalRecordToBackup(pr: PersonalRecord): PersonalRecordBackup =
        PersonalRecordBackup(
            id = pr.id,
            exerciseId = pr.exerciseId,
            exerciseName = pr.exerciseName,
            weight = pr.weight.toFloat(),
            reps = pr.reps.toInt(),
            oneRepMax = pr.oneRepMax.toFloat(),
            achievedAt = pr.achievedAt,
            workoutMode = pr.workoutMode,
            prType = pr.prType,
            volume = pr.volume.toFloat()
        )

    private fun mapTrainingCycleToBackup(cycle: TrainingCycle): TrainingCycleBackup =
        TrainingCycleBackup(
            id = cycle.id,
            name = sanitizeEntityName(cycle.name, "Unnamed Cycle"),
            description = cycle.description,
            createdAt = cycle.created_at,
            isActive = cycle.is_active != 0L
        )

    private fun mapCycleDayToBackup(day: CycleDay): CycleDayBackup =
        CycleDayBackup(
            id = day.id,
            cycleId = day.cycle_id,
            dayNumber = day.day_number.toInt(),
            name = day.name,
            routineId = day.routine_id,
            isRestDay = day.is_rest_day != 0L
        )

    private fun mapCycleProgressToBackup(cp: CycleProgress): CycleProgressBackup =
        CycleProgressBackup(
            id = cp.id,
            cycleId = cp.cycle_id,
            currentDayNumber = cp.current_day_number.toInt(),
            lastCompletedDate = cp.last_completed_date,
            cycleStartDate = cp.cycle_start_date,
            lastAdvancedAt = cp.last_advanced_at,
            completedDays = cp.completed_days,
            missedDays = cp.missed_days,
            rotationCount = cp.rotation_count.toInt()
        )

    private fun mapCycleProgressionToBackup(cprog: CycleProgression): CycleProgressionBackup =
        CycleProgressionBackup(
            cycleId = cprog.cycle_id,
            frequencyCycles = cprog.frequency_cycles.toInt(),
            weightIncreasePercent = cprog.weight_increase_percent?.toFloat(),
            echoLevelIncrease = cprog.echo_level_increase.toInt(),
            eccentricLoadIncreasePercent = cprog.eccentric_load_increase_percent?.toInt()
        )

    private fun mapPlannedSetToBackup(ps: PlannedSet): PlannedSetBackup =
        PlannedSetBackup(
            id = ps.id,
            routineExerciseId = ps.routine_exercise_id,
            setNumber = ps.set_number.toInt(),
            setType = ps.set_type,
            targetReps = ps.target_reps?.toInt(),
            targetWeightKg = ps.target_weight_kg?.toFloat(),
            targetRpe = ps.target_rpe?.toInt(),
            restSeconds = ps.rest_seconds?.toInt()
        )

    private fun mapCompletedSetToBackup(cs: CompletedSet): CompletedSetBackup =
        CompletedSetBackup(
            id = cs.id,
            sessionId = cs.session_id,
            plannedSetId = cs.planned_set_id,
            setNumber = cs.set_number.toInt(),
            setType = cs.set_type,
            actualReps = cs.actual_reps.toInt(),
            actualWeightKg = cs.actual_weight_kg.toFloat(),
            loggedRpe = cs.logged_rpe?.toInt(),
            isPr = cs.is_pr != 0L,
            completedAt = cs.completed_at
        )

    private fun mapProgressionEventToBackup(pe: ProgressionEvent): ProgressionEventBackup =
        ProgressionEventBackup(
            id = pe.id,
            exerciseId = pe.exercise_id,
            suggestedWeightKg = pe.suggested_weight_kg.toFloat(),
            previousWeightKg = pe.previous_weight_kg.toFloat(),
            reason = pe.reason,
            userResponse = pe.user_response,
            actualWeightKg = pe.actual_weight_kg?.toFloat(),
            timestamp = pe.timestamp
        )

    private fun mapEarnedBadgeToBackup(eb: EarnedBadge): EarnedBadgeBackup =
        EarnedBadgeBackup(
            id = eb.id,
            badgeId = eb.badgeId,
            earnedAt = eb.earnedAt,
            celebratedAt = eb.celebratedAt
        )

    private fun mapStreakHistoryToBackup(sh: StreakHistory): StreakHistoryBackup =
        StreakHistoryBackup(
            id = sh.id,
            startDate = sh.startDate,
            endDate = sh.endDate,
            length = sh.length.toInt()
        )

    private fun mapGamificationStatsToBackup(gs: GamificationStats): GamificationStatsBackup =
        GamificationStatsBackup(
            totalWorkouts = gs.totalWorkouts.toInt(),
            totalReps = gs.totalReps.toInt(),
            totalVolumeKg = gs.totalVolumeKg.toInt(),
            longestStreak = gs.longestStreak.toInt(),
            currentStreak = gs.currentStreak.toInt(),
            uniqueExercisesUsed = gs.uniqueExercisesUsed.toInt(),
            prsAchieved = gs.prsAchieved.toInt(),
            lastWorkoutDate = gs.lastWorkoutDate,
            streakStartDate = gs.streakStartDate,
            lastUpdated = gs.lastUpdated
        )

    private fun mapUserProfileToBackup(up: UserProfile): UserProfileBackup =
        UserProfileBackup(
            id = up.id,
            name = up.name,
            colorIndex = up.colorIndex.toInt(),
            createdAt = up.createdAt,
            isActive = up.isActive != 0L
        )
}
