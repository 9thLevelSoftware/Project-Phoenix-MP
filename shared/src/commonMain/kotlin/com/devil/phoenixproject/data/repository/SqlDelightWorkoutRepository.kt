package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightWorkoutRepository(
    private val db: VitruvianDatabase,
    private val exerciseRepository: ExerciseRepository
) : WorkoutRepository {

    private val queries = db.vitruvianDatabaseQueries

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun mapToSession(
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
        safetyFlags: Long,
        deloadWarningCount: Long,
        romViolationCount: Long,
        spotterActivations: Long,
        // New summary metrics
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
        estimatedCalories: Double?,
        warmupAvgWeightKg: Double?,
        workingAvgWeightKg: Double?,
        burnoutAvgWeightKg: Double?,
        peakWeightKg: Double?,
        rpe: Long?
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
            safetyFlags = safetyFlags.toInt(),
            deloadWarningCount = deloadWarningCount.toInt(),
            romViolationCount = romViolationCount.toInt(),
            spotterActivations = spotterActivations.toInt(),
            // New summary metrics
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
            estimatedCalories = estimatedCalories?.toFloat(),
            warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
            workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
            burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
            peakWeightKg = peakWeightKg?.toFloat(),
            rpe = rpe?.toInt()
        )
    }

    private fun mapToRoutineBasic(
        id: String,
        name: String,
        description: String,
        createdAt: Long,
        lastUsed: Long?,
        useCount: Long
    ): Routine {
        return Routine(
            id = id,
            name = name,
            exercises = emptyList(),
            createdAt = createdAt,
            lastUsed = lastUsed,
            useCount = useCount.toInt()
        )
    }

    private suspend fun loadRoutineWithExercises(routineId: String, name: String, createdAt: Long, lastUsed: Long? = null, useCount: Int = 0): Routine {
        val exerciseRows = queries.selectExercisesByRoutine(routineId).executeAsList()
        val supersetRows = queries.selectSupersetsByRoutine(routineId).executeAsList()

        // Load supersets from database
        val supersets = supersetRows.map { row ->
            Superset(
                id = row.id,
                routineId = row.routineId,
                name = row.name,
                colorIndex = row.colorIndex.toInt(),
                restBetweenSeconds = row.restBetweenSeconds.toInt(),
                orderIndex = row.orderIndex.toInt()
            )
        }

        val exercises = exerciseRows.mapNotNull { row ->
            try {
                // Try to get exercise from library, or create from stored data
                val exercise = row.exerciseId?.let { exerciseId ->
                    exerciseRepository.getExerciseById(exerciseId) ?: run {
                        Logger.w { "Exercise not found in database: $exerciseId (${row.exerciseName}), using stored data" }
                        Exercise(
                            id = row.exerciseId,
                            name = row.exerciseName,
                            muscleGroup = row.exerciseMuscleGroup,
                            muscleGroups = row.exerciseMuscleGroup,
                            equipment = row.exerciseEquipment,
                            defaultCableConfig = try {
                                CableConfiguration.valueOf(row.exerciseDefaultCableConfig)
                            } catch (e: Exception) {
                                Logger.w(e) { "Invalid defaultCableConfig '${row.exerciseDefaultCableConfig}' for exercise ${row.exerciseName}, using DOUBLE" }
                                CableConfiguration.DOUBLE
                            },
                            isFavorite = false,
                            isCustom = false
                        )
                    }
                } ?: run {
                    Logger.d { "No exerciseId stored for routine exercise ${row.exerciseName}, using stored data" }
                    Exercise(
                        id = row.exerciseId,
                        name = row.exerciseName,
                        muscleGroup = row.exerciseMuscleGroup,
                        muscleGroups = row.exerciseMuscleGroup,
                        equipment = row.exerciseEquipment,
                        defaultCableConfig = try {
                            CableConfiguration.valueOf(row.exerciseDefaultCableConfig)
                        } catch (e: Exception) {
                            Logger.w(e) { "Invalid defaultCableConfig '${row.exerciseDefaultCableConfig}' for exercise ${row.exerciseName}, using DOUBLE" }
                            CableConfiguration.DOUBLE
                        },
                        isFavorite = false,
                        isCustom = false
                    )
                }

                // Parse comma-separated setReps (supports "AMRAP" as null marker)
                val setReps: List<Int?> = try {
                    row.setReps.split(",").map { value ->
                        val trimmed = value.trim()
                        if (trimmed.equals("AMRAP", ignoreCase = true)) null else trimmed.toIntOrNull()
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setReps '${row.setReps}' for exercise ${row.exerciseName}, using default [10]" }
                    listOf(10)
                }

                val setWeights: List<Float> = try {
                    if (row.setWeights.isBlank()) emptyList()
                    else row.setWeights.split(",").mapNotNull { it.trim().toFloatOrNull() }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setWeights '${row.setWeights}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val setRestSeconds: List<Int> = try {
                    json.decodeFromString<List<Int>>(row.setRestSeconds)
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setRestSeconds '${row.setRestSeconds}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val cableConfig = try {
                    CableConfiguration.valueOf(row.cableConfig)
                } catch (e: Exception) {
                    Logger.w(e) { "Invalid cable config '${row.cableConfig}' for exercise ${row.exerciseName}, using DOUBLE" }
                    CableConfiguration.DOUBLE
                }

                val eccentricLoad = mapEccentricLoadFromDb(row.eccentricLoad)
                val echoLevel = EchoLevel.values().getOrNull(row.echoLevel.toInt()) ?: EchoLevel.HARDER

                val programMode = parseProgramMode(row.mode)

                // Parse PR percentage scaling fields
                val prTypeForScaling = try {
                    PRType.valueOf(row.prTypeForScaling)
                } catch (e: Exception) {
                    Logger.w(e) { "Invalid prTypeForScaling '${row.prTypeForScaling}' for exercise ${row.exerciseName}, using MAX_WEIGHT" }
                    PRType.MAX_WEIGHT
                }

                val setWeightsPercentOfPR: List<Int> = try {
                    if (row.setWeightsPercentOfPR.isNullOrBlank()) emptyList()
                    else json.decodeFromString<List<Int>>(row.setWeightsPercentOfPR)
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setWeightsPercentOfPR '${row.setWeightsPercentOfPR}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                RoutineExercise(
                    id = row.id,
                    exercise = exercise,
                    cableConfig = cableConfig,
                    orderIndex = row.orderIndex.toInt(),
                    setReps = setReps,
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    setWeightsPerCableKg = setWeights,
                    programMode = programMode,
                    eccentricLoad = eccentricLoad,
                    echoLevel = echoLevel,
                    progressionKg = row.progressionKg.toFloat(),
                    setRestSeconds = setRestSeconds,
                    duration = row.duration?.toInt(),
                    isAMRAP = row.isAMRAP == 1L,
                    perSetRestTime = row.perSetRestTime == 1L,
                    supersetId = row.supersetId,
                    orderInSuperset = row.orderInSuperset.toInt(),
                    // PR percentage scaling fields
                    usePercentOfPR = row.usePercentOfPR == 1L,
                    weightPercentOfPR = row.weightPercentOfPR.toInt(),
                    prTypeForScaling = prTypeForScaling,
                    setWeightsPercentOfPR = setWeightsPercentOfPR
                )
            } catch (e: Exception) {
                Logger.e(e) { "Failed to map routine exercise: ${row.exerciseId}" }
                null
            }
        }

        return Routine(
            id = routineId,
            name = name,
            exercises = exercises,
            supersets = supersets,
            createdAt = createdAt,
            lastUsed = lastUsed,
            useCount = useCount
        )
    }

    private fun mapEccentricLoadFromDb(dbValue: Long): EccentricLoad {
        return when (dbValue.toInt()) {
            0 -> EccentricLoad.LOAD_0
            50 -> EccentricLoad.LOAD_50
            75 -> EccentricLoad.LOAD_75
            100 -> EccentricLoad.LOAD_100
            110 -> EccentricLoad.LOAD_110
            120 -> EccentricLoad.LOAD_120
            130 -> EccentricLoad.LOAD_130
            140 -> EccentricLoad.LOAD_140
            150 -> EccentricLoad.LOAD_150
            else -> {
                Logger.w { "Unknown eccentric load value: $dbValue, defaulting to 100%" }
                EccentricLoad.LOAD_100
            }
        }
    }

    private fun parseProgramMode(modeStr: String): ProgramMode {
        return when {
            modeStr.startsWith("Program:") -> {
                val programModeName = modeStr.removePrefix("Program:")
                when (programModeName) {
                    "OldSchool" -> ProgramMode.OldSchool
                    "Pump" -> ProgramMode.Pump
                    "TUT" -> ProgramMode.TUT
                    "TUTBeast" -> ProgramMode.TUTBeast
                    "EccentricOnly" -> ProgramMode.EccentricOnly
                    "Echo" -> ProgramMode.Echo
                    else -> ProgramMode.OldSchool
                }
            }
            modeStr == "Echo" || modeStr.startsWith("Echo") -> {
                ProgramMode.Echo
            }
            else -> ProgramMode.OldSchool
        }
    }

    private fun serializeProgramMode(programMode: ProgramMode): String {
        return when (programMode) {
            ProgramMode.OldSchool -> "Program:OldSchool"
            ProgramMode.Pump -> "Program:Pump"
            ProgramMode.TUT -> "Program:TUT"
            ProgramMode.TUTBeast -> "Program:TUTBeast"
            ProgramMode.EccentricOnly -> "Program:EccentricOnly"
            ProgramMode.Echo -> "Echo"  // EchoLevel and EccentricLoad are stored separately in DB columns
        }
    }

    override fun getAllSessions(): Flow<List<WorkoutSession>> {
        return queries.selectAllSessions(::mapToSession)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun saveSession(session: WorkoutSession) {
        withContext(Dispatchers.IO) {
            queries.insertSession(
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
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteSession(sessionId)
        }
    }

    override suspend fun deleteAllSessions() {
        withContext(Dispatchers.IO) {
            queries.deleteAllSessions()
        }
    }

    override fun getAllRoutines(): Flow<List<Routine>> {
        return queries.selectAllRoutines(::mapToRoutineBasic)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { basicRoutines ->
                // Load exercises for each routine
                basicRoutines.map { routine ->
                    try {
                        loadRoutineWithExercises(
                            routine.id,
                            routine.name,
                            routine.createdAt,
                            routine.lastUsed,
                            routine.useCount
                        )
                    } catch (e: Exception) {
                        Logger.e(e) { "Failed to load exercises for routine ${routine.id}" }
                        routine
                    }
                }
            }
    }

    override suspend fun saveRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            // Generate a UUID for the routine if not provided
            val routineId = routine.id.takeIf { it.isNotBlank() } ?: generateUUID()

            db.transaction {
                // Upsert the routine (handles both new and existing routines)
                queries.upsertRoutine(
                    id = routineId,
                    name = routine.name,
                    description = "", // Default empty description
                    createdAt = routine.createdAt,
                    lastUsed = routine.lastUsed,
                    useCount = routine.useCount.toLong()
                )

                // Delete existing supersets and exercises before re-inserting
                queries.deleteSupersetsByRoutine(routineId)
                queries.deleteRoutineExercises(routineId)

                // Insert all supersets
                routine.supersets.forEach { superset ->
                    insertSuperset(routineId, superset)
                }

                // Insert all exercises
                routine.exercises.forEachIndexed { index, exercise ->
                    insertRoutineExercise(routineId, exercise, index)
                }
            }

            Logger.d { "Saved routine '${routine.name}' with ${routine.exercises.size} exercises and ${routine.supersets.size} supersets" }
        }
    }

    private fun insertRoutineExercise(routineId: String, exercise: RoutineExercise, index: Int) {
        // Generate a UUID for the exercise if not provided
        val exerciseRowId = exercise.id.takeIf { it.isNotBlank() } ?: generateUUID()

        queries.insertRoutineExercise(
            id = exerciseRowId,
            routineId = routineId,
            exerciseName = exercise.exercise.name,
            exerciseMuscleGroup = exercise.exercise.muscleGroup,
            exerciseEquipment = exercise.exercise.equipment,
            exerciseDefaultCableConfig = exercise.exercise.defaultCableConfig.name,
            exerciseId = exercise.exercise.id,
            cableConfig = exercise.cableConfig.name,
            orderIndex = index.toLong(),
            setReps = exercise.setReps.joinToString(",") { it?.toString() ?: "AMRAP" },
            weightPerCableKg = exercise.weightPerCableKg.toDouble(),
            setWeights = exercise.setWeightsPerCableKg.joinToString(","),
            mode = serializeProgramMode(exercise.programMode),
            eccentricLoad = exercise.eccentricLoad.percentage.toLong(),
            echoLevel = exercise.echoLevel.ordinal.toLong(),
            progressionKg = exercise.progressionKg.toDouble(),
            restSeconds = exercise.setRestSeconds.firstOrNull()?.toLong() ?: 60L,
            duration = exercise.duration?.toLong(),
            setRestSeconds = json.encodeToString(exercise.setRestSeconds),
            perSetRestTime = if (exercise.perSetRestTime) 1L else 0L,
            isAMRAP = if (exercise.isAMRAP) 1L else 0L,
            supersetId = exercise.supersetId,
            orderInSuperset = exercise.orderInSuperset.toLong(),
            // PR percentage scaling fields
            usePercentOfPR = if (exercise.usePercentOfPR) 1L else 0L,
            weightPercentOfPR = exercise.weightPercentOfPR.toLong(),
            prTypeForScaling = exercise.prTypeForScaling.name,
            setWeightsPercentOfPR = if (exercise.setWeightsPercentOfPR.isEmpty()) null else json.encodeToString(exercise.setWeightsPercentOfPR)
        )
    }

    private fun insertSuperset(routineId: String, superset: Superset) {
        queries.insertSuperset(
            id = superset.id,
            routineId = routineId,
            name = superset.name,
            colorIndex = superset.colorIndex.toLong(),
            restBetweenSeconds = superset.restBetweenSeconds.toLong(),
            orderIndex = superset.orderIndex.toLong()
        )
    }

    override suspend fun updateRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            val routineId = routine.id.takeIf { it.isNotBlank() } ?: return@withContext

            db.transaction {
                // Update the routine
                queries.updateRoutineById(
                    name = routine.name,
                    description = "", // Keep description empty for now
                    id = routineId
                )

                // Delete existing supersets and exercises, then re-insert
                queries.deleteSupersetsByRoutine(routineId)
                queries.deleteRoutineExercises(routineId)

                // Insert all supersets
                routine.supersets.forEach { superset ->
                    insertSuperset(routineId, superset)
                }

                // Insert all exercises
                routine.exercises.forEachIndexed { index, exercise ->
                    insertRoutineExercise(routineId, exercise, index)
                }
            }

            Logger.d { "Updated routine '${routine.name}' with ${routine.exercises.size} exercises and ${routine.supersets.size} supersets" }
        }
    }

    override suspend fun deleteRoutine(routineId: String) {
        withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext

            db.transaction {
                // Delete exercises and supersets first (foreign key cascade should handle this, but be explicit)
                queries.deleteRoutineExercises(routineId)
                queries.deleteSupersetsByRoutine(routineId)
                queries.deleteRoutineById(routineId)
            }

            Logger.d { "Deleted routine $routineId" }
        }
    }

    override suspend fun getRoutineById(routineId: String): Routine? {
        return withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext null
            val basicRoutine = queries.selectRoutineById(routineId, ::mapToRoutineBasic).executeAsOneOrNull()
                ?: return@withContext null

            loadRoutineWithExercises(
                routineId,
                basicRoutine.name,
                basicRoutine.createdAt,
                basicRoutine.lastUsed,
                basicRoutine.useCount
            )
        }
    }

    override fun getAllPersonalRecords(): Flow<List<PersonalRecordEntity>> {
        return queries.selectAllRecords { id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode, prType, volume ->
            PersonalRecordEntity(
                id = id,
                exerciseId = exerciseId,
                weightPerCableKg = weight.toFloat(),
                reps = reps.toInt(),
                timestamp = achievedAt,
                workoutMode = workoutMode
            )
        }.asFlow().mapToList(Dispatchers.IO)
    }

    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String) {
        withContext(Dispatchers.IO) {
            if (exerciseId.isBlank() || reps <= 0) return@withContext

            val timestamp = currentTimeMillis()
            val newVolume = weightKg * reps
            val exerciseName = exerciseRepository.getExerciseById(exerciseId)?.name ?: ""

            val currentWeightPR = queries.selectPR(
                exerciseId,
                mode,
                PRType.MAX_WEIGHT.name
            ).executeAsOneOrNull()

            val currentVolumePR = queries.selectPR(
                exerciseId,
                mode,
                PRType.MAX_VOLUME.name
            ).executeAsOneOrNull()

            val isNewWeightPR = currentWeightPR == null || weightKg > currentWeightPR.weight.toFloat()
            val currentVolume = (currentVolumePR?.weight?.toFloat() ?: 0f) * (currentVolumePR?.reps?.toInt() ?: 0)
            val isNewVolumePR = newVolume > currentVolume

            if (!isNewWeightPR && !isNewVolumePR) return@withContext

            val oneRepMax = if (reps == 1) {
                weightKg
            } else {
                weightKg * (1 + reps / 30f)
            }

            if (isNewWeightPR) {
                queries.upsertPR(
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    weight = weightKg.toDouble(),
                    reps = reps.toLong(),
                    oneRepMax = oneRepMax.toDouble(),
                    achievedAt = timestamp,
                    workoutMode = mode,
                    prType = PRType.MAX_WEIGHT.name,
                    volume = newVolume.toDouble()
                )

                queries.updateOneRepMax(
                    one_rep_max_kg = oneRepMax.toDouble(),
                    id = exerciseId
                )
            }

            if (isNewVolumePR && !isNewWeightPR) {
                queries.upsertPR(
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    weight = weightKg.toDouble(),
                    reps = reps.toLong(),
                    oneRepMax = oneRepMax.toDouble(),
                    achievedAt = timestamp,
                    workoutMode = mode,
                    prType = PRType.MAX_VOLUME.name,
                    volume = newVolume.toDouble()
                )
            }
        }
    }

    override suspend fun saveMetrics(
        sessionId: String,
        metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>
    ) {
        withContext(Dispatchers.IO) {
            metrics.forEach { metric ->
                // Calculate power: P = F × v (force × velocity)
                val power = metric.loadA * metric.velocityA.toFloat()
                queries.insertMetric(
                    sessionId = sessionId,
                    timestamp = metric.timestamp,
                    position = metric.positionA.toDouble(),
                    positionB = metric.positionB.toDouble(),
                    velocity = metric.velocityA,
                    velocityB = metric.velocityB,
                    load = metric.loadA.toDouble(),
                    loadB = metric.loadB.toDouble(),
                    power = power.toDouble(),
                    status = metric.status.toLong()
                )
            }
        }
    }

    // ========== New methods for full parity ==========

    override fun getRecentSessions(limit: Int): Flow<List<WorkoutSession>> {
        return queries.selectRecentSessions(limit.toLong(), ::mapToSession)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun getSession(sessionId: String): WorkoutSession? {
        return withContext(Dispatchers.IO) {
            queries.selectSessionById(sessionId, ::mapToSession).executeAsOneOrNull()
        }
    }

    override suspend fun markRoutineUsed(routineId: String) {
        withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext
            queries.updateRoutineLastUsed(currentTimeMillis(), routineId)
            Logger.d { "Marked routine used: $routineId" }
        }
    }

    override fun getMetricsForSession(sessionId: String): Flow<List<com.devil.phoenixproject.domain.model.WorkoutMetric>> {
        return queries.selectMetricsBySession(sessionId) { id, sessId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status ->
            com.devil.phoenixproject.domain.model.WorkoutMetric(
                timestamp = timestamp,
                loadA = load?.toFloat() ?: 0f,
                loadB = loadB?.toFloat() ?: 0f,
                positionA = position?.toFloat() ?: 0f,
                positionB = positionB?.toFloat() ?: 0f,
                velocityA = velocity ?: 0.0,
                velocityB = velocityB ?: 0.0,
                status = status?.toInt() ?: 0
            )
        }.asFlow().mapToList(Dispatchers.IO)
    }

    override suspend fun getMetricsForSessionSync(sessionId: String): List<com.devil.phoenixproject.domain.model.WorkoutMetric> {
        return withContext(Dispatchers.IO) {
            queries.selectMetricsBySession(sessionId) { id, sessId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status ->
                com.devil.phoenixproject.domain.model.WorkoutMetric(
                    timestamp = timestamp,
                    loadA = load?.toFloat() ?: 0f,
                    loadB = loadB?.toFloat() ?: 0f,
                    positionA = position?.toFloat() ?: 0f,
                    positionB = positionB?.toFloat() ?: 0f,
                    velocityA = velocity ?: 0.0,
                    velocityB = velocityB ?: 0.0,
                    status = status?.toInt() ?: 0
                )
            }.executeAsList()
        }
    }

    override suspend fun getRecentSessionsSync(limit: Int): List<WorkoutSession> {
        return withContext(Dispatchers.IO) {
            queries.selectRecentSessions(limit.toLong(), ::mapToSession).executeAsList()
        }
    }

    override suspend fun savePhaseStatistics(
        sessionId: String,
        stats: com.devil.phoenixproject.domain.model.HeuristicStatistics
    ) {
        withContext(Dispatchers.IO) {
            queries.insertPhaseStatistics(
                sessionId = sessionId,
                concentricKgAvg = stats.concentric.kgAvg.toDouble(),
                concentricKgMax = stats.concentric.kgMax.toDouble(),
                concentricVelAvg = stats.concentric.velAvg.toDouble(),
                concentricVelMax = stats.concentric.velMax.toDouble(),
                concentricWattAvg = stats.concentric.wattAvg.toDouble(),
                concentricWattMax = stats.concentric.wattMax.toDouble(),
                eccentricKgAvg = stats.eccentric.kgAvg.toDouble(),
                eccentricKgMax = stats.eccentric.kgMax.toDouble(),
                eccentricVelAvg = stats.eccentric.velAvg.toDouble(),
                eccentricVelMax = stats.eccentric.velMax.toDouble(),
                eccentricWattAvg = stats.eccentric.wattAvg.toDouble(),
                eccentricWattMax = stats.eccentric.wattMax.toDouble(),
                timestamp = stats.timestamp
            )
            Logger.d { "Saved phase statistics for session $sessionId" }
        }
    }

    override fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>> {
        return queries.selectAllPhaseStats { id, sessionId, concentricKgAvg, concentricKgMax, concentricVelAvg, concentricVelMax, concentricWattAvg, concentricWattMax, eccentricKgAvg, eccentricKgMax, eccentricVelAvg, eccentricVelMax, eccentricWattAvg, eccentricWattMax, timestamp ->
            PhaseStatisticsData(
                id = id,
                sessionId = sessionId,
                concentricKgAvg = concentricKgAvg.toFloat(),
                concentricKgMax = concentricKgMax.toFloat(),
                concentricVelAvg = concentricVelAvg.toFloat(),
                concentricVelMax = concentricVelMax.toFloat(),
                concentricWattAvg = concentricWattAvg.toFloat(),
                concentricWattMax = concentricWattMax.toFloat(),
                eccentricKgAvg = eccentricKgAvg.toFloat(),
                eccentricKgMax = eccentricKgMax.toFloat(),
                eccentricVelAvg = eccentricVelAvg.toFloat(),
                eccentricVelMax = eccentricVelMax.toFloat(),
                eccentricWattAvg = eccentricWattAvg.toFloat(),
                eccentricWattMax = eccentricWattMax.toFloat(),
                timestamp = timestamp
            )
        }.asFlow().mapToList(Dispatchers.IO)
    }
}
