package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.data.local.WeeklyProgramWithDays
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Personal record entity - stub for database
 */
data class PersonalRecordEntity(
    val id: Long = 0,
    val exerciseId: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val timestamp: Long,
    val workoutMode: String
)

/**
 * Workout Repository interface.
 * Implemented by SqlDelightWorkoutRepository for type-safe database operations.
 */
interface WorkoutRepository {
    // Workout sessions
    fun getAllSessions(): Flow<List<WorkoutSession>>
    suspend fun saveSession(session: WorkoutSession)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteAllSessions()

    // Routines
    fun getAllRoutines(): Flow<List<Routine>>
    suspend fun saveRoutine(routine: Routine)
    suspend fun updateRoutine(routine: Routine)
    suspend fun deleteRoutine(routineId: String)
    suspend fun getRoutineById(routineId: String): Routine?

    // Weekly programs
    fun getAllPrograms(): Flow<List<WeeklyProgramWithDays>>
    fun getActiveProgram(): Flow<WeeklyProgramWithDays?>
    fun getProgramById(programId: String): Flow<WeeklyProgramWithDays?>
    suspend fun saveProgram(program: WeeklyProgramWithDays)
    suspend fun activateProgram(programId: String)
    suspend fun deleteProgram(programId: String)

    // Personal records
    fun getAllPersonalRecords(): Flow<List<PersonalRecordEntity>>
    suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String)

    // Metrics storage
    suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>)
}

/**
 * Stub Workout Repository for compilation
 */
class StubWorkoutRepository : WorkoutRepository {
    override fun getAllSessions() = flowOf(emptyList<WorkoutSession>())
    override suspend fun saveSession(session: WorkoutSession) {}
    override suspend fun deleteSession(sessionId: String) {}
    override suspend fun deleteAllSessions() {}

    override fun getAllRoutines() = flowOf(emptyList<Routine>())
    override suspend fun saveRoutine(routine: Routine) {}
    override suspend fun updateRoutine(routine: Routine) {}
    override suspend fun deleteRoutine(routineId: String) {}
    override suspend fun getRoutineById(routineId: String): Routine? = null

    private val _programs = MutableStateFlow<List<WeeklyProgramWithDays>>(emptyList())

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

    override fun getAllPersonalRecords() = flowOf(emptyList<PersonalRecordEntity>())
    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String) {}
    override suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>) {}
}
