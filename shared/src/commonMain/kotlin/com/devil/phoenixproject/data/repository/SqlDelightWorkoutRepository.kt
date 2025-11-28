package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.data.local.WeeklyProgramWithDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightWorkoutRepository(
    db: VitruvianDatabase
) : WorkoutRepository {

    private val queries = db.vitruvianDatabaseQueries

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

    private fun mapToRoutine(
        id: Long,
        name: String,
        description: String?,
        createdAt: Long,
        updatedAt: Long
    ): Routine {
        return Routine(
            id = id.toString(),
            name = name,
            description = description ?: "",
            exercises = emptyList() // TODO: Fetch exercises for routine
        )
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
        // TODO: Add delete query
    }

    override suspend fun deleteAllSessions() {
        // TODO: Add delete all query
    }

    override fun getAllRoutines(): Flow<List<Routine>> {
        return queries.selectAllRoutines(::mapToRoutine)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun saveRoutine(routine: Routine) {
        withContext(Dispatchers.IO) {
            queries.insertRoutine(
                name = routine.name,
                description = routine.description,
                createdAt = 0L, // TODO
                updatedAt = 0L
            )
        }
    }

    override suspend fun updateRoutine(routine: Routine) {
        // TODO
    }

    override suspend fun deleteRoutine(routineId: String) {
        // TODO
    }

    override suspend fun getRoutineById(routineId: String): Routine? {
        return withContext(Dispatchers.IO) {
            val idLong = routineId.toLongOrNull() ?: return@withContext null
            queries.selectRoutineById(idLong, ::mapToRoutine).executeAsOneOrNull()
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
