package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow

/**
 * Personal record entity.
 */
data class PersonalRecordEntity(
    val id: Long = 0,
    val exerciseId: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val timestamp: Long,
    val workoutMode: String,
)

/**
 * Workout Repository interface.
 * Implemented by SqlDelightWorkoutRepository for type-safe database operations.
 */
interface WorkoutRepository {
    // Workout sessions
    fun getAllSessions(profileId: String): Flow<List<WorkoutSession>>
    suspend fun saveSession(session: WorkoutSession)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteAllSessions()

    /**
     * Get recent workout sessions
     * @param profileId Profile to filter by
     * @param limit Maximum number of sessions to return
     */
    fun getRecentSessions(profileId: String, limit: Int = 10): Flow<List<WorkoutSession>>

    /**
     * Get a specific workout session by ID
     */
    suspend fun getSession(sessionId: String): WorkoutSession?

    // Routines
    fun getAllRoutines(profileId: String): Flow<List<Routine>>
    suspend fun saveRoutine(routine: Routine)
    suspend fun updateRoutine(routine: Routine)
    suspend fun deleteRoutine(routineId: String)
    suspend fun moveRoutineToProfile(routineId: String, targetProfileId: String)
    suspend fun getRoutineById(routineId: String): Routine?

    /**
     * Mark routine as used (updates lastUsed and increments useCount)
     */
    suspend fun markRoutineUsed(routineId: String)

    // Personal records
    fun getAllPersonalRecords(profileId: String): Flow<List<PersonalRecordEntity>>
    suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String, profileId: String = "default")

    /**
     * Get average set duration in milliseconds for a specific exercise.
     * Returns null if no historical data is available.
     * Issue #225: Used by RoutineTimeEstimator.
     */
    suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long?

    // Metrics storage
    suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>)

    /**
     * Get metrics for a workout session
     */
    fun getMetricsForSession(sessionId: String): Flow<List<com.devil.phoenixproject.domain.model.WorkoutMetric>>

    /**
     * Get metrics for a workout session synchronously (for export)
     */
    suspend fun getMetricsForSessionSync(sessionId: String): List<com.devil.phoenixproject.domain.model.WorkoutMetric>

    /**
     * Get recent workout sessions synchronously (for export)
     */
    suspend fun getRecentSessionsSync(profileId: String, limit: Int = 10): List<WorkoutSession>

    // Phase Statistics (heuristic data from machine)

    /**
     * Save phase statistics for a workout session
     */
    suspend fun savePhaseStatistics(sessionId: String, stats: com.devil.phoenixproject.domain.model.HeuristicStatistics)

    /**
     * Get all phase statistics
     */
    fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>>
}

/**
 * Phase statistics data class for repository layer
 */
data class PhaseStatisticsData(
    val id: Long = 0,
    val sessionId: String,
    val concentricKgAvg: Float,
    val concentricKgMax: Float,
    val concentricVelAvg: Float,
    val concentricVelMax: Float,
    val concentricWattAvg: Float,
    val concentricWattMax: Float,
    val eccentricKgAvg: Float,
    val eccentricKgMax: Float,
    val eccentricVelAvg: Float,
    val eccentricVelMax: Float,
    val eccentricWattAvg: Float,
    val eccentricWattMax: Float,
    val timestamp: Long,
)
