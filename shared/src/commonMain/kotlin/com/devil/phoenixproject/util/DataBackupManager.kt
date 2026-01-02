package com.devil.phoenixproject.util

import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
     * Import data from a file path
     */
    suspend fun importFromFile(filePath: String): Result<ImportResult>

    /**
     * Get shareable content (JSON string) for sharing via platform share sheet
     */
    suspend fun getShareableContent(): String
}

/**
 * Common implementation that handles database operations.
 * Platform implementations extend this and add file I/O.
 */
abstract class BaseDataBackupManager(
    private val database: VitruvianDatabase
) : DataBackupManager {

    protected val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true  // Forward compatibility
        encodeDefaults = true
    }

    private val queries get() = database.vitruvianDatabaseQueries

    override suspend fun exportAllData(): BackupData = withContext(Dispatchers.IO) {
        val sessions = queries.selectAllSessionsSync().executeAsList()
        val metrics = queries.selectAllMetricsSync().executeAsList()
        val routines = queries.selectAllRoutinesSync().executeAsList()
        val routineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
        val personalRecords = queries.selectAllRecords { id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume ->
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
        val trainingCycles = queries.selectAllTrainingCycles().executeAsList()
        val cycleDays = trainingCycles.flatMap { cycle ->
            queries.selectCycleDaysByCycle(cycle.id).executeAsList()
        }

        BackupData(
            version = 1,
            exportedAt = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd") + "T" +
                    KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss") + "Z",
            appVersion = Constants.APP_VERSION,
            data = BackupContent(
                workoutSessions = sessions.map { session ->
                    WorkoutSessionBackup(
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
                        routineSessionId = session.routineSessionId,
                        routineName = session.routineName,
                        safetyFlags = session.safetyFlags.toInt(),
                        deloadWarningCount = session.deloadWarningCount.toInt(),
                        romViolationCount = session.romViolationCount.toInt(),
                        spotterActivations = session.spotterActivations.toInt(),
                        // New summary metrics
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
                },
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
                routines = routines.map { routine ->
                    RoutineBackup(
                        id = routine.id,
                        name = routine.name,
                        description = routine.description,
                        createdAt = routine.createdAt,
                        lastUsed = routine.lastUsed,
                        useCount = routine.useCount.toInt()
                    )
                },
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
                        orderInSuperset = exercise.orderInSuperset.toInt()
                    )
                },
                personalRecords = personalRecords,
                trainingCycles = trainingCycles.map { cycle ->
                    TrainingCycleBackup(
                        id = cycle.id,
                        name = cycle.name,
                        description = cycle.description,
                        createdAt = cycle.created_at,
                        isActive = cycle.is_active != 0L
                    )
                },
                cycleDays = cycleDays.map { day ->
                    CycleDayBackup(
                        id = day.id,
                        cycleId = day.cycle_id,
                        dayNumber = day.day_number.toInt(),
                        name = day.name,
                        routineId = day.routine_id,
                        isRestDay = day.is_rest_day != 0L
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
                // Log warning but continue - forward compatibility via ignoreUnknownKeys
            }

            // Get existing IDs for duplicate detection
            val existingSessionIds = queries.selectAllSessionIds().executeAsList().toSet()
            val existingRoutineIds = queries.selectAllRoutineIds().executeAsList().toSet()
            val existingPRIds = queries.selectAllPRIds().executeAsList().toSet()

            var sessionsImported = 0
            var sessionsSkipped = 0
            backup.data.workoutSessions.forEach { session ->
                if (session.id !in existingSessionIds) {
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
                        eccentricLoad = session.eccentricLoad.toLong(),
                        echoLevel = session.echoLevel.toLong(),
                        exerciseId = session.exerciseId,
                        exerciseName = session.exerciseName,
                        routineSessionId = session.routineSessionId,
                        routineName = session.routineName,
                        safetyFlags = session.safetyFlags.toLong(),
                        deloadWarningCount = session.deloadWarningCount.toLong(),
                        romViolationCount = session.romViolationCount.toLong(),
                        spotterActivations = session.spotterActivations.toLong(),
                        // New summary metrics
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
            var metricsImported = 0
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
            var routinesImported = 0
            var routinesSkipped = 0
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

            // Import routine exercises (only for imported routines)
            var routineExercisesImported = 0
            val importedRoutineIds = backup.data.routines
                .filter { it.id !in existingRoutineIds }
                .map { it.id }
                .toSet()

            backup.data.routineExercises.forEach { exercise ->
                if (exercise.routineId in importedRoutineIds) {
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
                        eccentricLoad = exercise.eccentricLoad.toLong(),
                        echoLevel = exercise.echoLevel.toLong(),
                        progressionKg = exercise.progressionKg.toDouble(),
                        restSeconds = exercise.restSeconds.toLong(),
                        duration = exercise.duration?.toLong(),
                        setRestSeconds = exercise.setRestSeconds,
                        perSetRestTime = if (exercise.perSetRestTime) 1L else 0L,
                        isAMRAP = if (exercise.isAMRAP) 1L else 0L,
                        supersetId = exercise.supersetId,
                        orderInSuperset = exercise.orderInSuperset.toLong()
                    )
                    routineExercisesImported++
                }
            }

            // Import personal records
            var personalRecordsImported = 0
            var personalRecordsSkipped = 0
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
            var trainingCyclesImported = 0
            var trainingCyclesSkipped = 0
            val existingCycleIds = queries.selectAllTrainingCycles().executeAsList().map { it.id }.toSet()

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
            var cycleDaysImported = 0
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

            Result.success(
                ImportResult(
                    sessionsImported = sessionsImported,
                    sessionsSkipped = sessionsSkipped,
                    metricsImported = metricsImported,
                    routinesImported = routinesImported,
                    routinesSkipped = routinesSkipped,
                    routineExercisesImported = routineExercisesImported,
                    personalRecordsImported = personalRecordsImported,
                    personalRecordsSkipped = personalRecordsSkipped,
                    trainingCyclesImported = trainingCyclesImported,
                    trainingCyclesSkipped = trainingCyclesSkipped,
                    cycleDaysImported = cycleDaysImported
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getShareableContent(): String = exportToJson()
}
