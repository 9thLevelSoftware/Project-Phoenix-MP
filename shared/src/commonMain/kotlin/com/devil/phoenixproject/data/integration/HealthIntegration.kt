package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Latest eligible body-weight sample imported from a platform health store.
 *
 * Phoenix stores body weight in kg and keeps this one-way: platform health store -> Phoenix.
 */
data class HealthBodyWeightSample(
    val weightKg: Float,
    val measuredAtMs: Long,
    val externalId: String,
    val sourceName: String? = null,
    val deviceMetadata: Map<String, String> = emptyMap(),
    val rawMetadataJson: String? = null,
)

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
 * Workout export pushes Phoenix workout data to the platform health store
 * after each completed workout. Body-weight read support is one-way from
 * the platform health store into Phoenix.
 */
expect class HealthIntegration {
    suspend fun isAvailable(): Boolean
    suspend fun requestPermissions(): Boolean
    suspend fun hasPermissions(): Boolean
    suspend fun hasBodyWeightReadPermission(): Boolean
    suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?>

    /** Write a single set/exercise session (used for Just Lift / non-routine workouts). */
    suspend fun writeWorkout(session: WorkoutSession): Result<Unit>

    /**
     * Write a single aggregate workout for an entire routine.
     * Issue #395: Replaces per-set writes so Apple Health / Google Health
     * show one workout entry per routine instead of one per set.
     */
    suspend fun writeRoutineWorkout(data: RoutineHealthData): Result<Unit>
}

interface HealthBodyWeightReader {
    suspend fun isAvailable(): Boolean
    suspend fun hasBodyWeightReadPermission(): Boolean
    suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?>
}

class HealthIntegrationBodyWeightReader(
    private val healthIntegration: HealthIntegration,
) : HealthBodyWeightReader {
    override suspend fun isAvailable(): Boolean = healthIntegration.isAvailable()

    override suspend fun hasBodyWeightReadPermission(): Boolean = healthIntegration.hasBodyWeightReadPermission()

    override suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?> = healthIntegration.readLatestScaleBodyWeight()
}
