package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
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
    suspend fun updateSessionExerciseTag(sessionId: String, exerciseId: String, exerciseName: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteAllSessions()

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): delete every
     * WorkoutSession row that belongs to the given routine session id.
     * Used by the History "Delete All Sets" affordance so zero-rep /
     * ghost rows hidden by `getHistoryVisibleSessions` do not survive
     * the user-level deletion. This mirrors `deleteSession`'s local
     * hard-delete semantics; workout-session tombstone sync is not
     * currently implemented.
     */
    suspend fun deleteSessionsByRoutineSessionId(routineSessionId: String)

    /**
     * Get recent workout sessions
     * @param profileId Profile to filter by
     * @param limit Maximum number of sessions to return
     */
    fun getRecentSessions(profileId: String, limit: Int = 10): Flow<List<WorkoutSession>>

    /**
     * Issue #591: Workout sessions that should appear in the Analytics /
     * History UI. Excludes soft-deleted rows and rows with zero recorded
     * reps (workingReps == 0 AND totalReps == 0), which historically come
     * from pre-completion saves, routine restart mid-set, and zero-rep
     * import paths. Counting those as sets inflates routine set totals
     * (e.g. "Incline Fly 0 reps / 6 sets") and triggers the misleading
     * pre-v0.2.1 placeholder card in per-set drill-down.
     *
     * Mirrors the eligibility guards used by
     * `selectCompletedHealthExportCandidates` /
     * `selectSessionsByRoutineSessionId` / `selectSessionsForPhasePRBackfill`.
     */
    fun getHistoryVisibleSessions(profileId: String): Flow<List<WorkoutSession>>

    /**
     * Get a specific workout session by ID
     */
    suspend fun getSession(sessionId: String): WorkoutSession?

    /**
     * Get completed workout sessions belonging to a routine session.
     */
    suspend fun getSessionsForRoutineSession(profileId: String, routineSessionId: String): List<WorkoutSession>

    /**
     * Get completed local sessions that can be exported to platform health stores.
     */
    suspend fun getCompletedHealthExportCandidates(profileId: String): List<WorkoutSession>

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

    /**
     * Get the number of completed sessions for a specific exercise.
     * Issue #225: Used by RoutineTimeEstimator to enforce minimum session threshold.
     */
    suspend fun getSessionCountForExercise(exerciseId: String, profileId: String): Long

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

    /**
     * Issue #517: Velocity-based 1RM foundation.
     * Returns per-set [WorkoutVelocityPoint] records for a given exercise within the
     * time window [sinceTimestampMs, now]. Points with null MCV or zero working reps
     * are excluded. Load uses [workingAvgWeightKg] when captured, else [weightPerCableKg].
     */
    suspend fun getVelocityPointsForExercise(
        exerciseId: String,
        profileId: String,
        sinceTimestampMs: Long,
    ): List<WorkoutVelocityPoint>
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
