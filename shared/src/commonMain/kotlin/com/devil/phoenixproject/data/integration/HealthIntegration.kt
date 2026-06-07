package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.displayLoadMultiplier

/**
 * Rich workout data for platform health stores.
 *
 * Android Health Connect can persist [segments] as exercise sets. Apple HealthKit's
 * public workout write API cannot store comparable per-set strength segments, so
 * iOS writes the aggregate workout fields and ignores [segments].
 */
data class HealthWorkoutData(
    val title: String,
    /** Deduplication key with a Phoenix namespace. */
    val externalId: String,
    /** Workout start time as Unix epoch milliseconds. */
    val startTimeMs: Long,
    /** Workout end time as Unix epoch milliseconds. */
    val endTimeMs: Long,
    /** Sum of estimated calories across included sessions, or null when unavailable. */
    val totalCalories: Float?,
    val segments: List<HealthWorkoutSegment>,
)

data class HealthWorkoutSegment(
    val sessionId: String,
    val exerciseId: String?,
    val exerciseName: String,
    val setIndex: Int,
    val setType: SetType,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val reps: Int,
    val weightKg: Float,
    val rpe: Int?,
)

object HealthWorkoutExportBuilder {
    fun buildRoutineWorkout(
        routineSessionId: String,
        sessions: List<WorkoutSession>,
        completedSetsBySessionId: Map<String, List<CompletedSet>>,
    ): HealthWorkoutData? {
        val routineSessions = sessions
            .filter { it.routineSessionId == routineSessionId }
            .sortedBy { it.timestamp }

        if (routineSessions.isEmpty()) return null

        val segments = buildSegments(routineSessions, completedSetsBySessionId)
        if (segments.isEmpty()) return null

        val title = routineSessions.firstNotNullOfOrNull { session ->
            session.routineName?.takeIf { it.isNotBlank() }
        } ?: "Phoenix Routine"

        return HealthWorkoutData(
            title = title,
            externalId = routineClientRecordId(routineSessionId),
            startTimeMs = segments.minOf { it.startTimeMs },
            endTimeMs = segments.maxOf { it.endTimeMs },
            totalCalories = routineSessions.sumPositiveCalories(),
            segments = segments,
        )
    }

    fun buildStandaloneWorkout(
        session: WorkoutSession,
        completedSets: List<CompletedSet>,
    ): HealthWorkoutData? {
        val segments = buildSegments(
            sessions = listOf(session),
            completedSetsBySessionId = mapOf(session.id to completedSets),
        )
        if (segments.isEmpty()) return null

        return HealthWorkoutData(
            title = session.exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout",
            externalId = sessionClientRecordId(session.id),
            startTimeMs = segments.minOf { it.startTimeMs },
            endTimeMs = segments.maxOf { it.endTimeMs },
            totalCalories = listOf(session).sumPositiveCalories(),
            segments = segments,
        )
    }

    fun routineClientRecordId(routineSessionId: String): String = "phoenix:routine:$routineSessionId"

    fun sessionClientRecordId(sessionId: String): String = "phoenix:session:$sessionId"

    fun calorieClientRecordId(externalId: String): String = "$externalId:calories"

    private fun buildSegments(
        sessions: List<WorkoutSession>,
        completedSetsBySessionId: Map<String, List<CompletedSet>>,
    ): List<HealthWorkoutSegment> = sessions
        .sortedBy { it.timestamp }
        .flatMap { session ->
            val completedSets = completedSetsBySessionId[session.id]
                .orEmpty()
                .sortedWith(compareBy<CompletedSet> { it.setNumber }.thenBy { it.completedAt })

            if (completedSets.isNotEmpty()) {
                completedSets.mapNotNull { completedSet ->
                    buildSegment(session, completedSet)
                }
            } else {
                buildSegment(session, completedSet = null)?.let(::listOf).orEmpty()
            }
        }

    private fun buildSegment(
        session: WorkoutSession,
        completedSet: CompletedSet?,
    ): HealthWorkoutSegment? {
        val reps = completedSet?.actualReps
            ?: session.workingReps.takeIf { it > 0 }
            ?: session.totalReps.takeIf { it > 0 }
            ?: return null
        if (reps <= 0) return null

        val (startTimeMs, endTimeMs) = segmentTimes(session, completedSet)
        val weightKg = session.weightPerCableKg * session.displayLoadMultiplier().toFloat()
        val exerciseName = session.exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"

        return HealthWorkoutSegment(
            sessionId = session.id,
            exerciseId = session.exerciseId,
            exerciseName = exerciseName,
            setIndex = completedSet?.setNumber?.coerceAtLeast(0) ?: 0,
            setType = completedSet?.setType ?: SetType.STANDARD,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            reps = reps,
            weightKg = weightKg.coerceAtLeast(0f),
            rpe = (completedSet?.loggedRpe ?: session.rpe)?.coerceIn(0, 10),
        )
    }

    private fun segmentTimes(
        session: WorkoutSession,
        completedSet: CompletedSet?,
    ): Pair<Long, Long> {
        val fallbackDurationMs = session.duration.coerceAtLeast(1000L)
        val fallbackStartMs = session.timestamp
        val fallbackEndMs = (fallbackStartMs + fallbackDurationMs).coerceAtLeast(fallbackStartMs + 1000L)
        val completedAtMs = completedSet?.completedAt ?: return fallbackStartMs to fallbackEndMs
        if (completedAtMs <= fallbackStartMs) return fallbackStartMs to fallbackEndMs

        val startMs = (completedAtMs - fallbackDurationMs).coerceAtLeast(fallbackStartMs)
        val endMs = completedAtMs.coerceAtLeast(startMs + 1000L)
        return startMs to endMs
    }

    private fun List<WorkoutSession>.sumPositiveCalories(): Float? {
        val total = mapNotNull { it.estimatedCalories?.takeIf { calories -> calories > 0f } }
            .sum()
        return total.takeIf { it > 0f }
    }
}

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
     * Write a health workout derived from Phoenix sessions and completed sets.
     * Android stores set-level [HealthWorkoutData.segments]; iOS stores the aggregate workout only.
     */
    suspend fun writeHealthWorkout(data: HealthWorkoutData): Result<Unit>
}
