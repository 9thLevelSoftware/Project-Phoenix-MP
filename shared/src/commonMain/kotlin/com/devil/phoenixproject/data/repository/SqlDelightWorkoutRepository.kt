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
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutType
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.data.local.WeeklyProgramWithDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightWorkoutRepository(
    db: VitruvianDatabase,
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
        routineName: String?
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
            routineName = routineName
        )
    }

    private fun mapToRoutineBasic(
        id: Long,
        name: String,
        createdAt: Long,
        updatedAt: Long
    ): Routine {
        return Routine(
            id = id.toString(),
            name = name,
            exercises = emptyList(),
            createdAt = createdAt,
            lastUsed = null,
            useCount = 0
        )
    }

    private suspend fun loadRoutineWithExercises(routineId: Long, name: String, createdAt: Long): Routine {
        val exerciseRows = queries.selectExercisesByRoutine(routineId).executeAsList()
        val exercises = exerciseRows.mapNotNull { row ->
            try {
                val exercise = exerciseRepository.getExerciseById(row.exerciseId) ?: return@mapNotNull null

                // Parse JSON fields
                val setReps: List<Int?> = try {
                    json.decodeFromString<List<Int?>>(row.setRepsJson)
                } catch (e: Exception) {
                    listOf(row.targetReps.toInt())
                }

                val setWeights: List<Float> = try {
                    json.decodeFromString<List<Float>>(row.setWeightsJson)
                } catch (e: Exception) {
                    emptyList()
                }

                val setRestSeconds: List<Int> = try {
                    json.decodeFromString<List<Int>>(row.setRestSecondsJson)
                } catch (e: Exception) {
                    emptyList()
                }

                val cableConfig = try {
                    CableConfiguration.valueOf(row.cableConfig)
                } catch (e: Exception) {
                    CableConfiguration.DOUBLE
                }

                val eccentricLoad = EccentricLoad.values().getOrNull(row.eccentricLoad.toInt() / 25) ?: EccentricLoad.LOAD_100
                val echoLevel = EchoLevel.values().getOrNull(row.echoLevel.toInt()) ?: EchoLevel.HARDER

                val workoutType = parseWorkoutType(row.mode, eccentricLoad, echoLevel)

                RoutineExercise(
                    id = row.id.toString(),
                    exercise = exercise,
                    cableConfig = cableConfig,
                    orderIndex = row.orderIndex.toInt(),
                    setReps = setReps,
                    weightPerCableKg = row.targetWeight.toFloat(),
                    setWeightsPerCableKg = setWeights,
                    workoutType = workoutType,
                    eccentricLoad = eccentricLoad,
                    echoLevel = echoLevel,
                    progressionKg = row.progressionKg.toFloat(),
                    setRestSeconds = setRestSeconds,
                    duration = row.duration?.toInt(),
                    isAMRAP = row.isAMRAP == 1L,
                    perSetRestTime = row.perSetRestTime == 1L,
                    supersetGroupId = row.supersetGroupId,
                    supersetOrder = row.supersetOrder.toInt(),
                    supersetRestSeconds = row.supersetRestSeconds.toInt()
                )
            } catch (e: Exception) {
                Logger.e(e) { "Failed to map routine exercise: ${row.exerciseId}" }
                null
            }
        }

        return Routine(
            id = routineId.toString(),
            name = name,
            exercises = exercises,
            createdAt = createdAt,
            lastUsed = null,
            useCount = 0
        )
    }

    private fun parseWorkoutType(modeStr: String, eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100, echoLevel: EchoLevel = EchoLevel.HARDER): WorkoutType {
        return when {
            modeStr.startsWith("Program:") -> {
                val programModeName = modeStr.removePrefix("Program:")
                val programMode = when (programModeName) {
                    "OldSchool" -> ProgramMode.OldSchool
                    "Pump" -> ProgramMode.Pump
                    "TUT" -> ProgramMode.TUT
                    "TUTBeast" -> ProgramMode.TUTBeast
                    "EccentricOnly" -> ProgramMode.EccentricOnly
                    else -> ProgramMode.OldSchool
                }
                WorkoutType.Program(programMode)
            }
            modeStr == "Echo" || modeStr.startsWith("Echo") -> {
                WorkoutType.Echo(echoLevel, eccentricLoad)
            }
            else -> WorkoutType.Program(ProgramMode.OldSchool)
        }
    }

    private fun serializeWorkoutType(workoutType: WorkoutType): String {
        return when (workoutType) {
            is WorkoutType.Program -> {
                val modeName = when (workoutType.mode) {
                    ProgramMode.OldSchool -> "OldSchool"
                    ProgramMode.Pump -> "Pump"
                    ProgramMode.TUT -> "TUT"
                    ProgramMode.TUTBeast -> "TUTBeast"
                    ProgramMode.EccentricOnly -> "EccentricOnly"
                }
                "Program:$modeName"
            }
            is WorkoutType.Echo -> "Echo"  // EchoLevel and EccentricLoad are stored separately in DB columns
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
                routineName = session.routineName
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
                            routine.id.toLong(),
                            routine.name,
                            routine.createdAt
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
            val now = currentTimeMillis()

            // Insert the routine
            queries.insertRoutine(
                name = routine.name,
                createdAt = routine.createdAt,
                updatedAt = now
            )

            // Get the inserted routine ID
            val routineId = queries.getLastInsertedRoutineId().executeAsOne()

            // Insert all exercises
            routine.exercises.forEachIndexed { index, exercise ->
                insertRoutineExercise(routineId, exercise, index)
            }

            Logger.d { "Saved routine '${routine.name}' with ${routine.exercises.size} exercises" }
        }
    }

    private fun insertRoutineExercise(routineId: Long, exercise: RoutineExercise, index: Int) {
        queries.insertRoutineExercise(
            routineId = routineId,
            exerciseId = exercise.exercise.id ?: exercise.exercise.name.replace(" ", "_").lowercase(),
            exerciseName = exercise.exercise.name,
            orderIndex = index.toLong(),
            targetWeight = exercise.weightPerCableKg.toDouble(),
            targetReps = exercise.reps.toLong(),
            targetSets = exercise.sets.toLong(),
            mode = serializeWorkoutType(exercise.workoutType),
            cableConfig = exercise.cableConfig.name,
            setRepsJson = json.encodeToString(exercise.setReps),
            setWeightsJson = json.encodeToString(exercise.setWeightsPerCableKg),
            setRestSecondsJson = json.encodeToString(exercise.setRestSeconds),
            eccentricLoad = exercise.eccentricLoad.percentage.toLong(),
            echoLevel = exercise.echoLevel.ordinal.toLong(),
            progressionKg = exercise.progressionKg.toDouble(),
            duration = exercise.duration?.toLong(),
            isAMRAP = if (exercise.isAMRAP) 1L else 0L,
            perSetRestTime = if (exercise.perSetRestTime) 1L else 0L,
            supersetGroupId = exercise.supersetGroupId,
            supersetOrder = exercise.supersetOrder.toLong(),
            supersetRestSeconds = exercise.supersetRestSeconds.toLong()
        )
    }

    override suspend fun updateRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            val routineId = routine.id.toLongOrNull() ?: return@withContext
            val now = currentTimeMillis()

            // Update the routine
            queries.updateRoutineById(
                name = routine.name,
                updatedAt = now,
                id = routineId
            )

            // Delete existing exercises and re-insert
            queries.deleteRoutineExercises(routineId)

            // Insert all exercises
            routine.exercises.forEachIndexed { index, exercise ->
                insertRoutineExercise(routineId, exercise, index)
            }

            Logger.d { "Updated routine '${routine.name}' with ${routine.exercises.size} exercises" }
        }
    }

    override suspend fun deleteRoutine(routineId: String) {
        withContext(Dispatchers.IO) {
            val idLong = routineId.toLongOrNull() ?: return@withContext

            // Delete exercises first (foreign key cascade should handle this, but be explicit)
            queries.deleteRoutineExercises(idLong)
            queries.deleteRoutineById(idLong)

            Logger.d { "Deleted routine $routineId" }
        }
    }

    override suspend fun getRoutineById(routineId: String): Routine? {
        return withContext(Dispatchers.IO) {
            val idLong = routineId.toLongOrNull() ?: return@withContext null
            val basicRoutine = queries.selectRoutineById(idLong, ::mapToRoutineBasic).executeAsOneOrNull()
                ?: return@withContext null

            loadRoutineWithExercises(idLong, basicRoutine.name, basicRoutine.createdAt)
        }
    }

    // In-memory storage for programs (until SQLDelight schema is extended)
    private val _programs = kotlinx.coroutines.flow.MutableStateFlow<List<WeeklyProgramWithDays>>(emptyList())

    override fun getAllPrograms(): Flow<List<WeeklyProgramWithDays>> = _programs

    override fun getActiveProgram(): Flow<WeeklyProgramWithDays?> = _programs.map { programs ->
        programs.find { it.program.isActive }
    }

    override fun getProgramById(programId: String): Flow<WeeklyProgramWithDays?> = _programs.map { programs ->
        programs.find { it.program.id == programId }
    }

    override suspend fun saveProgram(program: WeeklyProgramWithDays) {
        val existingIndex = _programs.value.indexOfFirst { it.program.id == program.program.id }
        _programs.value = if (existingIndex >= 0) {
            _programs.value.toMutableList().apply { this[existingIndex] = program }
        } else {
            _programs.value + program
        }
    }

    override suspend fun activateProgram(programId: String) {
        _programs.value = _programs.value.map { pwDays ->
            pwDays.copy(program = pwDays.program.copy(isActive = pwDays.program.id == programId))
        }
    }

    override suspend fun deleteProgram(programId: String) {
        _programs.value = _programs.value.filter { it.program.id != programId }
    }

    override fun getAllPersonalRecords(): Flow<List<PersonalRecordEntity>> {
        return queries.selectAllRecords { id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt, workoutMode ->
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
        // Logic to check PR and insert
    }

    override suspend fun saveMetrics(
        sessionId: String,
        metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>
    ) {
        // TODO: Add MetricSample table queries when schema is extended
        // For now, metrics are not persisted
    }
}
