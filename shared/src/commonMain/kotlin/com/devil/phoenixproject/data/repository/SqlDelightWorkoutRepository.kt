package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.VitruvianDatabase
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
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SqlDelightWorkoutRepository(private val db: VitruvianDatabase, private val exerciseRepository: ExerciseRepository) : WorkoutRepository {

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
        routineId: String?,
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
        cableCount: Long?,
        estimatedCalories: Double?,
        warmupAvgWeightKg: Double?,
        workingAvgWeightKg: Double?,
        burnoutAvgWeightKg: Double?,
        peakWeightKg: Double?,
        rpe: Long?,
        // Biomechanics summary (migration 15 - Phase 13 v16)
        avgMcvMmS: Double?,
        avgAsymmetryPercent: Double?,
        totalVelocityLossPercent: Double?,
        dominantSide: String?,
        strengthProfile: String?,
        // Form Check score (migration 16 - Phase 19 CV-06)
        formScore: Long?,
        // Sync fields (migration 6)
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
        cableCount = cableCount?.toInt(),
        estimatedCalories = estimatedCalories?.toFloat(),
        warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
        workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
        burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
        peakWeightKg = peakWeightKg?.toFloat(),
        rpe = rpe?.toInt(),
        // Biomechanics summary
        avgMcvMmS = avgMcvMmS?.toFloat(),
        avgAsymmetryPercent = avgAsymmetryPercent?.toFloat(),
        totalVelocityLossPercent = totalVelocityLossPercent?.toFloat(),
        dominantSide = dominantSide,
        strengthProfile = strengthProfile,
        // Form Check score
        formScore = formScore?.toInt(),
        // Multi-profile support
        profileId = profileId,
    )

    private fun mapToRoutineBasic(
        id: String,
        name: String,
        description: String,
        createdAt: Long,
        lastUsed: Long?,
        useCount: Long,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?,
        // Multi-profile support (migration 21)
        profileId: String,
    ): Routine = Routine(
        id = id,
        name = name,
        exercises = emptyList(),
        createdAt = createdAt,
        lastUsed = lastUsed,
        useCount = useCount.toInt(),
        profileId = profileId,
    )

    private suspend fun loadRoutineWithExercises(
        routineId: String,
        name: String,
        createdAt: Long,
        lastUsed: Long? = null,
        useCount: Int = 0,
        profileId: String = "default",
    ): Routine {
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
                orderIndex = row.orderIndex.toInt(),
            )
        }

        val exercises = exerciseRows.mapNotNull { row ->
            try {
                // Try to get exercise from library, or create from stored data
                val resolvedExercise = row.exerciseId?.let { exerciseId ->
                    exerciseRepository.getExerciseById(exerciseId)
                        ?: exerciseRepository.findByName(row.exerciseName)
                            ?.also { byName ->
                                Logger.w {
                                    "Routine exercise had stale exerciseId=$exerciseId; resolved by name '${row.exerciseName}' -> ${byName.id} and healing row ${row.id}"
                                }
                                queries.updateRoutineExerciseId(byName.id, row.id)
                            }
                } ?: exerciseRepository.findByName(row.exerciseName)
                    ?.also { byName ->
                        Logger.i {
                            "Routine exercise missing exerciseId for '${row.exerciseName}'; resolved to ${byName.id} and healing row ${row.id}"
                        }
                        queries.updateRoutineExerciseId(byName.id, row.id)
                    }

                val exercise = resolvedExercise ?: run {
                    // Self-healing: auto-create a custom exercise so exerciseId is never null.
                    // A null exerciseId breaks the entire PR tracking pipeline (#319).
                    val autoCreated = exerciseRepository.createCustomExercise(
                        Exercise(
                            name = row.exerciseName,
                            muscleGroup = row.exerciseMuscleGroup,
                            equipment = row.exerciseEquipment,
                            isCustom = true,
                        ),
                    ).getOrNull()

                    if (autoCreated != null) {
                        Logger.i {
                            "HEAL: Auto-created custom exercise '${row.exerciseName}' -> id=${autoCreated.id} " +
                                "(was null in RoutineExercise ${row.id})"
                        }
                        // Heal the DB row so subsequent loads don't repeat this
                        queries.updateRoutineExerciseId(autoCreated.id!!, row.id)
                        autoCreated
                    } else {
                        // Last resort: generate a synthetic ID to prevent null propagation
                        val syntheticId = generateUUID()
                        Logger.w {
                            "HEAL: createCustomExercise failed for '${row.exerciseName}', " +
                                "using synthetic ID=$syntheticId"
                        }
                        Exercise(
                            id = syntheticId,
                            name = row.exerciseName,
                            muscleGroup = row.exerciseMuscleGroup,
                            muscleGroups = row.exerciseMuscleGroup,
                            equipment = row.exerciseEquipment,
                            isFavorite = false,
                            isCustom = true,
                        )
                    }
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
                    if (row.setWeights.isBlank()) {
                        emptyList()
                    } else {
                        row.setWeights.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse setWeights '${row.setWeights}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val setRestSeconds: List<Int> = try {
                    json.decodeFromString<List<Int>>(row.setRestSeconds)
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setRestSeconds '${row.setRestSeconds}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                val setEchoLevels: List<EchoLevel?> = try {
                    if (row.setEchoLevels.isBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<Int?>>(row.setEchoLevels).map { ordinal ->
                            ordinal?.let { EchoLevel.entries.getOrNull(it) }
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setEchoLevels '${row.setEchoLevels}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                val warmupSets: List<com.devil.phoenixproject.domain.model.WarmupSet> = try {
                    if (row.warmupSets.isBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<com.devil.phoenixproject.domain.model.WarmupSet>>(row.warmupSets)
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to parse warmupSets '${row.warmupSets}' for exercise ${row.exerciseName}, using empty list" }
                    emptyList()
                }

                val eccentricLoad = mapEccentricLoadFromDb(row.eccentricLoad)
                val echoLevel = EchoLevel.entries.getOrNull(row.echoLevel.toInt()) ?: EchoLevel.HARDER

                val programMode = parseProgramMode(row.mode)

                // Parse PR percentage scaling fields
                val prTypeForScaling = try {
                    PRType.valueOf(row.prTypeForScaling)
                } catch (e: Exception) {
                    Logger.w(e) { "Invalid prTypeForScaling '${row.prTypeForScaling}' for exercise ${row.exerciseName}, using MAX_WEIGHT" }
                    PRType.MAX_WEIGHT
                }

                val setWeightsPercentOfPR: List<Int> = try {
                    if (row.setWeightsPercentOfPR.isNullOrBlank()) {
                        emptyList()
                    } else {
                        json.decodeFromString<List<Int>>(row.setWeightsPercentOfPR)
                    }
                } catch (e: Exception) {
                    Logger.w(e) {
                        "Failed to parse setWeightsPercentOfPR '${row.setWeightsPercentOfPR}' for exercise ${row.exerciseName}, using empty list"
                    }
                    emptyList()
                }

                RoutineExercise(
                    id = row.id,
                    exercise = exercise,
                    orderIndex = row.orderIndex.toInt(),
                    setReps = setReps,
                    weightPerCableKg = row.weightPerCableKg.toFloat(),
                    setWeightsPerCableKg = setWeights,
                    programMode = programMode,
                    eccentricLoad = eccentricLoad,
                    echoLevel = echoLevel,
                    progressionKg = row.progressionKg.toFloat(),
                    setRestSeconds = setRestSeconds,
                    setEchoLevels = setEchoLevels,
                    duration = row.duration?.toInt(),
                    isAMRAP = row.isAMRAP == 1L,
                    perSetRestTime = row.perSetRestTime == 1L,
                    // Per-exercise behavior overrides
                    stallDetectionEnabled = row.stallDetectionEnabled == 1L,
                    repCountTiming = try {
                        com.devil.phoenixproject.domain.model.RepCountTiming.valueOf(row.repCountTiming)
                    } catch (
                        _: Exception,
                    ) {
                        com.devil.phoenixproject.domain.model.RepCountTiming.TOP
                    },
                    stopAtTop = row.stopAtTop == 1L,
                    supersetId = row.supersetId,
                    orderInSuperset = row.orderInSuperset.toInt(),
                    // PR percentage scaling fields
                    usePercentOfPR = row.usePercentOfPR == 1L,
                    weightPercentOfPR = row.weightPercentOfPR.toInt(),
                    prTypeForScaling = prTypeForScaling,
                    setWeightsPercentOfPR = setWeightsPercentOfPR,
                    warmupSets = warmupSets,
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
            useCount = useCount,
            profileId = profileId,
        )
    }

    private fun mapEccentricLoadFromDb(dbValue: Long): EccentricLoad {
        // Defensive clamping: Machine hardware limit is 150% eccentric load
        // Values > 150% can cause machine faults (yellow light)
        val safeValue = dbValue.toInt().coerceIn(0, 150)
        if (dbValue.toInt() != safeValue) {
            Logger.w { "DB eccentric load $dbValue% clamped to $safeValue% (hardware limit 150%)" }
        }

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

            else -> {
                // Value is within 0-150 but not a standard enum value - find closest match
                Logger.w { "Non-standard eccentric load value: $safeValue%, finding closest match" }
                EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safeValue) }
                    ?: EccentricLoad.LOAD_100
            }
        }
    }

    private fun parseProgramMode(modeStr: String): ProgramMode = when {
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

        // Issue #7 Fix: Handle unprefixed legacy mode strings
        modeStr == "Pump" -> ProgramMode.Pump

        modeStr == "TUT" -> ProgramMode.TUT

        modeStr == "TUTBeast" -> ProgramMode.TUTBeast

        modeStr == "EccentricOnly" -> ProgramMode.EccentricOnly

        modeStr == "OldSchool" -> ProgramMode.OldSchool

        else -> ProgramMode.OldSchool
    }

    private fun serializeProgramMode(programMode: ProgramMode): String = when (programMode) {
        ProgramMode.OldSchool -> "Program:OldSchool"
        ProgramMode.Pump -> "Program:Pump"
        ProgramMode.TUT -> "Program:TUT"
        ProgramMode.TUTBeast -> "Program:TUTBeast"
        ProgramMode.EccentricOnly -> "Program:EccentricOnly"
        ProgramMode.Echo -> "Echo" // EchoLevel and EccentricLoad are stored separately in DB columns
    }

    override fun getAllSessions(profileId: String): Flow<List<WorkoutSession>> = queries.selectAllSessions(profileId = profileId, mapper = ::mapToSession)
        .asFlow()
        .mapToList(Dispatchers.IO)

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
                routineId = session.routineId,
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
                cableCount = session.cableCount?.toLong(),
                estimatedCalories = session.estimatedCalories?.toDouble(),
                warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                peakWeightKg = session.peakWeightKg?.toDouble(),
                rpe = session.rpe?.toLong(),
                // Biomechanics summary
                avgMcvMmS = session.avgMcvMmS?.toDouble(),
                avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
                totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
                dominantSide = session.dominantSide,
                strengthProfile = session.strengthProfile,
                // Form Check score
                formScore = session.formScore?.toLong(),
                // Multi-profile support
                profile_id = session.profileId,
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

    override fun getAllRoutines(profileId: String): Flow<List<Routine>> = queries.selectAllRoutines(profileId = profileId, mapper = ::mapToRoutineBasic)
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
                        routine.useCount,
                        routine.profileId,
                    )
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to load exercises for routine ${routine.id}" }
                    routine
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
                    useCount = routine.useCount.toLong(),
                    profile_id = routine.profileId,
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
            exerciseDefaultCableConfig = "DOUBLE", // Legacy field - no longer used
            exerciseId = exercise.exercise.id,
            cableConfig = "DOUBLE", // Legacy field - no longer used
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
            setWeightsPercentOfPR = if (exercise.setWeightsPercentOfPR.isEmpty()) {
                null
            } else {
                json.encodeToString(
                    exercise.setWeightsPercentOfPR,
                )
            },
            // Per-exercise behavior overrides
            stallDetectionEnabled = if (exercise.stallDetectionEnabled) 1L else 0L,
            stopAtTop = if (exercise.stopAtTop) 1L else 0L,
            repCountTiming = exercise.repCountTiming.name,
            // Per-set echo levels (stored as JSON array of nullable ordinals)
            setEchoLevels = if (exercise.setEchoLevels.isEmpty()) {
                ""
            } else {
                json.encodeToString(exercise.setEchoLevels.map { it?.ordinal })
            },
            // Variable warm-up sets (Phase 35C)
            warmupSets = if (exercise.warmupSets.isEmpty()) {
                ""
            } else {
                json.encodeToString(exercise.warmupSets)
            },
        )
    }

    private fun insertSuperset(routineId: String, superset: Superset) {
        queries.insertSuperset(
            id = superset.id,
            routineId = routineId,
            name = superset.name,
            colorIndex = superset.colorIndex.toLong(),
            restBetweenSeconds = superset.restBetweenSeconds.toLong(),
            orderIndex = superset.orderIndex.toLong(),
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
                    id = routineId,
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

            Logger.d {
                "Updated routine '${routine.name}' with ${routine.exercises.size} exercises and ${routine.supersets.size} supersets"
            }
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

    override suspend fun moveRoutineToProfile(routineId: String, targetProfileId: String) {
        withContext(Dispatchers.IO) {
            queries.adoptRoutineProfile(profileId = targetProfileId, id = routineId)
            Logger.d { "Moved routine $routineId to profile $targetProfileId" }
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
                basicRoutine.useCount,
                basicRoutine.profileId,
            )
        }
    }

    override suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long? = withContext(Dispatchers.IO) {
        queries.selectAverageSetDurationMs(exerciseId, profileId = profileId)
            .executeAsOneOrNull()
            ?.avgDurationMs
            ?.toLong()
    }

    override fun getAllPersonalRecords(profileId: String): Flow<List<PersonalRecordEntity>> = queries.selectAllRecords(profileId = profileId) {
            id,
            exerciseId,
            exerciseName,
            weight,
            reps,
            oneRepMax,
            achievedAt,
            workoutMode,
            prType,
            volume,
            phase,
            updatedAt,
            serverId,
            deletedAt,
            profileId_,
        ->
        PersonalRecordEntity(
            id = id,
            exerciseId = exerciseId,
            weightPerCableKg = weight.toFloat(),
            reps = reps.toInt(),
            timestamp = achievedAt,
            workoutMode = workoutMode,
        )
    }.asFlow().mapToList(Dispatchers.IO)

    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String, profileId: String) {
        withContext(Dispatchers.IO) {
            if (exerciseId.isBlank() || reps <= 0) return@withContext

            val timestamp = currentTimeMillis()
            val newVolume = weightKg * reps
            val exerciseName = exerciseRepository.getExerciseById(exerciseId)?.name ?: ""

            val combinedPhase = "COMBINED"

            val defaultProfileId = profileId.ifBlank { "default" }

            val currentWeightPR = queries.selectPR(
                exerciseId,
                mode,
                PRType.MAX_WEIGHT.name,
                combinedPhase,
                profileId = defaultProfileId,
            ).executeAsOneOrNull()

            val currentVolumePR = queries.selectPR(
                exerciseId,
                mode,
                PRType.MAX_VOLUME.name,
                combinedPhase,
                profileId = defaultProfileId,
            ).executeAsOneOrNull()

            val isNewWeightPR = currentWeightPR == null || weightKg > currentWeightPR.weight.toFloat()
            val currentVolume = (currentVolumePR?.weight?.toFloat() ?: 0f) * (currentVolumePR?.reps?.toInt() ?: 0)
            val isNewVolumePR = newVolume > currentVolume

            if (!isNewWeightPR && !isNewVolumePR) return@withContext

            val oneRepMax = OneRepMaxCalculator.epley(weightKg, reps)

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
                    volume = newVolume.toDouble(),
                    phase = combinedPhase,
                    profile_id = defaultProfileId,
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
                    volume = newVolume.toDouble(),
                    phase = combinedPhase,
                    profile_id = defaultProfileId,
                )
            }

            // Sync 1RM to Exercise table for %-based training features
            val currentExercise1RM = queries.selectExerciseById(exerciseId)
                .executeAsOneOrNull()?.one_rep_max_kg?.toFloat() ?: 0f
            if (oneRepMax > currentExercise1RM) {
                queries.updateOneRepMax(
                    one_rep_max_kg = oneRepMax.toDouble(),
                    id = exerciseId,
                )
            }
        }
    }

    override suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>) {
        withContext(Dispatchers.IO) {
            metrics.forEach { metric ->
                // Calculate power: P = (loadA + loadB) × v (combined force × velocity for dual-cable)
                val power = (metric.loadA + metric.loadB) * metric.velocityA.toFloat()
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
                    status = metric.status.toLong(),
                )
            }
        }
    }

    // ========== New methods for full parity ==========

    override fun getRecentSessions(profileId: String, limit: Int): Flow<List<WorkoutSession>> = queries.selectRecentSessions(profileId = profileId, limit = limit.toLong(), mapper = ::mapToSession)
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun getSession(sessionId: String): WorkoutSession? = withContext(Dispatchers.IO) {
        queries.selectSessionById(sessionId, ::mapToSession).executeAsOneOrNull()
    }

    override suspend fun markRoutineUsed(routineId: String) {
        withContext(Dispatchers.IO) {
            if (routineId.isBlank()) return@withContext
            queries.updateRoutineLastUsed(currentTimeMillis(), routineId)
            Logger.d { "Marked routine used: $routineId" }
        }
    }

    override fun getMetricsForSession(sessionId: String): Flow<List<com.devil.phoenixproject.domain.model.WorkoutMetric>> = queries.selectMetricsBySession(sessionId) {
            id,
            sessId,
            timestamp,
            position,
            positionB,
            velocity,
            velocityB,
            load,
            loadB,
            power,
            status,
        ->
        com.devil.phoenixproject.domain.model.WorkoutMetric(
            timestamp = timestamp,
            loadA = load?.toFloat() ?: 0f,
            loadB = loadB?.toFloat() ?: 0f,
            positionA = position?.toFloat() ?: 0f,
            positionB = positionB?.toFloat() ?: 0f,
            velocityA = velocity ?: 0.0,
            velocityB = velocityB ?: 0.0,
            status = status.toInt(),
        )
    }.asFlow().mapToList(Dispatchers.IO)

    override suspend fun getMetricsForSessionSync(sessionId: String): List<com.devil.phoenixproject.domain.model.WorkoutMetric> = withContext(Dispatchers.IO) {
        queries.selectMetricsBySession(sessionId) {
                id,
                sessId,
                timestamp,
                position,
                positionB,
                velocity,
                velocityB,
                load,
                loadB,
                power,
                status,
            ->
            com.devil.phoenixproject.domain.model.WorkoutMetric(
                timestamp = timestamp,
                loadA = load?.toFloat() ?: 0f,
                loadB = loadB?.toFloat() ?: 0f,
                positionA = position?.toFloat() ?: 0f,
                positionB = positionB?.toFloat() ?: 0f,
                velocityA = velocity ?: 0.0,
                velocityB = velocityB ?: 0.0,
                status = status.toInt(),
            )
        }.executeAsList()
    }

    override suspend fun getRecentSessionsSync(profileId: String, limit: Int): List<WorkoutSession> = withContext(Dispatchers.IO) {
        queries.selectRecentSessions(profileId = profileId, limit = limit.toLong(), mapper = ::mapToSession).executeAsList()
    }

    override suspend fun savePhaseStatistics(sessionId: String, stats: com.devil.phoenixproject.domain.model.HeuristicStatistics) {
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
                timestamp = stats.timestamp,
            )
            Logger.d { "Saved phase statistics for session $sessionId" }
        }
    }

    override fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>> = queries.selectAllPhaseStats {
            id,
            sessionId,
            concentricKgAvg,
            concentricKgMax,
            concentricVelAvg,
            concentricVelMax,
            concentricWattAvg,
            concentricWattMax,
            eccentricKgAvg,
            eccentricKgMax,
            eccentricVelAvg,
            eccentricVelMax,
            eccentricWattAvg,
            eccentricWattMax,
            timestamp,
        ->
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
            timestamp = timestamp,
        )
    }.asFlow().mapToList(Dispatchers.IO)
}
