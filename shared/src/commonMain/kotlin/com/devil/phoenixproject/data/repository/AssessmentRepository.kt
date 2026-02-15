package com.devil.phoenixproject.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Entity representing an assessment result from the database.
 */
data class AssessmentResultEntity(
    val id: Long,
    val exerciseId: String,
    val estimatedOneRepMaxKg: Float,
    val loadVelocityData: String,
    val assessmentSessionId: String?,
    val userOverrideKg: Float?,
    val createdAt: Long
)

/**
 * Repository interface for VBT strength assessment operations.
 *
 * Bridges the domain assessment engine and the AssessmentResult database table.
 * Handles persisting assessment results, creating assessment WorkoutSessions
 * with the `__ASSESSMENT__` marker, and updating Exercise.oneRepMaxKg.
 */
interface AssessmentRepository {

    /**
     * Save a raw assessment result to the database.
     * @param exerciseId Exercise ID
     * @param estimatedOneRepMaxKg Estimated 1RM from load-velocity profiling
     * @param loadVelocityDataJson JSON blob of load-velocity data points
     * @param sessionId Optional workout session ID to link
     * @param userOverrideKg Optional user-provided override for the 1RM
     * @return Row ID of the inserted assessment result
     */
    suspend fun saveAssessment(
        exerciseId: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        sessionId: String?,
        userOverrideKg: Float? = null
    ): Long

    /**
     * Get all assessments for an exercise, ordered by most recent first.
     * @param exerciseId Exercise ID
     * @return Flow emitting list of assessment results
     */
    fun getAssessmentsByExercise(exerciseId: String): Flow<List<AssessmentResultEntity>>

    /**
     * Get the most recent assessment for an exercise.
     * @param exerciseId Exercise ID
     * @return Latest assessment result, or null if none exist
     */
    suspend fun getLatestAssessment(exerciseId: String): AssessmentResultEntity?

    /**
     * Delete an assessment result by ID.
     * @param id Assessment result row ID
     */
    suspend fun deleteAssessment(id: Long)

    /**
     * Save a complete assessment session: creates a WorkoutSession with
     * routineName = "__ASSESSMENT__", inserts the AssessmentResult, and
     * updates the exercise's oneRepMaxKg.
     *
     * @param exerciseId Exercise ID
     * @param exerciseName Exercise display name
     * @param estimatedOneRepMaxKg Estimated 1RM from load-velocity profiling
     * @param loadVelocityDataJson JSON blob of load-velocity data points
     * @param userOverrideKg Optional user-provided override for the 1RM
     * @param totalReps Total reps performed during assessment
     * @param durationMs Duration of assessment in milliseconds
     * @param weightPerCableKg Weight used per cable during assessment
     * @return Session ID of the created WorkoutSession
     */
    suspend fun saveAssessmentSession(
        exerciseId: String,
        exerciseName: String,
        estimatedOneRepMaxKg: Float,
        loadVelocityDataJson: String,
        userOverrideKg: Float?,
        totalReps: Int,
        durationMs: Long,
        weightPerCableKg: Float
    ): String
}
