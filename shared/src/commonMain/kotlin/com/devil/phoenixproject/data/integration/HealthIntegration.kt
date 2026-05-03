package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Aggregated data for a completed routine, used to write a single
 * workout entry to the platform health store instead of one per set.
 */
data class RoutineHealthData(
    val routineName: String,
    /** Routine start time as Unix epoch milliseconds. */
    val startTimeMs: Long,
    /** Total routine duration in milliseconds. */
    val durationMs: Long,
    /** Sum of estimated calories across all sets, or null if no set had calorie data. */
    val totalCalories: Float?,
    /** Deduplication key (routineSessionId). */
    val externalId: String,
)

/**
 * Platform-specific health integration.
 * Android: Google Health Connect
 * iOS: Apple HealthKit
 *
 * Write-only: pushes Phoenix workout data to the platform health store
 * after each completed workout.
 */
expect class HealthIntegration {
    suspend fun isAvailable(): Boolean
    suspend fun requestPermissions(): Boolean
    suspend fun hasPermissions(): Boolean

    /** Write a single set/exercise session (used for Just Lift / non-routine workouts). */
    suspend fun writeWorkout(session: WorkoutSession): Result<Unit>

    /**
     * Write a single aggregate workout for an entire routine.
     * Issue #395: Replaces per-set writes so Apple Health / Google Health
     * show one workout entry per routine instead of one per set.
     */
    suspend fun writeRoutineWorkout(data: RoutineHealthData): Result<Unit>
}
