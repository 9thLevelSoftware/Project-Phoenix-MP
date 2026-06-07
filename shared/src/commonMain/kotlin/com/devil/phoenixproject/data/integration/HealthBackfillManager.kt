package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WorkoutSession

private val healthBackfillLog = Logger.withTag("HealthBackfillManager")

data class HealthBackfillResult(
    val eligibleWorkouts: Int,
    val writtenWorkouts: Int,
    val skippedWorkouts: Int,
)

class HealthBackfillManager(
    private val workoutRepository: WorkoutRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val healthIntegration: HealthIntegration,
) {
    suspend fun syncPreviousWorkouts(profileId: String): Result<HealthBackfillResult> {
        if (!healthIntegration.isAvailable()) {
            return Result.failure(IllegalStateException("Health app is not available on this device"))
        }

        if (!healthIntegration.hasPermissions()) {
            return Result.failure(IllegalStateException("Health write permissions are not granted"))
        }

        val sessions = workoutRepository.getCompletedHealthExportCandidates(profileId)
        if (sessions.isEmpty()) {
            return Result.success(
                HealthBackfillResult(
                    eligibleWorkouts = 0,
                    writtenWorkouts = 0,
                    skippedWorkouts = 0,
                ),
            )
        }

        val completedSetsBySessionId = completedSetRepository
            .getCompletedSetsForSessions(sessions.map { it.id })
            .groupBy { it.sessionId }

        val workouts = buildWorkouts(sessions, completedSetsBySessionId)
        var written = 0
        var firstFailure: Throwable? = null

        workouts.forEach { workout ->
            healthIntegration.writeHealthWorkout(workout)
                .onSuccess {
                    written += 1
                    healthBackfillLog.d { "Backfilled health workout ${workout.externalId}" }
                }
                .onFailure { error ->
                    firstFailure = firstFailure ?: error
                    healthBackfillLog.w(error) { "Failed health backfill for ${workout.externalId}" }
                }
        }

        val result = HealthBackfillResult(
            eligibleWorkouts = workouts.size,
            writtenWorkouts = written,
            skippedWorkouts = (workouts.size - written).coerceAtLeast(0),
        )

        return firstFailure?.let { Result.failure(it) } ?: Result.success(result)
    }

    private fun buildWorkouts(
        sessions: List<WorkoutSession>,
        completedSetsBySessionId: Map<String, List<CompletedSet>>,
    ): List<HealthWorkoutData> {
        val routineWorkouts = sessions
            .filter { it.routineSessionId != null }
            .groupBy { it.routineSessionId.orEmpty() }
            .mapNotNull { (routineSessionId, routineSessions) ->
                HealthWorkoutExportBuilder.buildRoutineWorkout(
                    routineSessionId = routineSessionId,
                    sessions = routineSessions,
                    completedSetsBySessionId = completedSetsBySessionId,
                )
            }

        val standaloneWorkouts = sessions
            .filter { it.routineSessionId == null }
            .mapNotNull { session ->
                HealthWorkoutExportBuilder.buildStandaloneWorkout(
                    session = session,
                    completedSets = completedSetsBySessionId[session.id].orEmpty(),
                )
            }

        return (routineWorkouts + standaloneWorkouts).sortedBy { it.startTimeMs }
    }
}
