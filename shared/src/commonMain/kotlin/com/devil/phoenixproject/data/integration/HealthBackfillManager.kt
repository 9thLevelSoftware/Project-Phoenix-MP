package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.IntegrationProvider
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
    private val cursorRepository: IntegrationSyncCursorRepository,
    private val healthWriter: HealthWorkoutWriter,
) {
    suspend fun syncPreviousWorkouts(
        provider: IntegrationProvider,
        profileId: String,
    ): Result<HealthBackfillResult> {
        if (!healthWriter.isAvailable()) {
            return Result.failure(IllegalStateException("Health app is not available on this device"))
        }

        if (!healthWriter.hasPermissions()) {
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
            .getCompletedSetsForSessionsChunked(sessions.map { it.id })
            .groupBy { it.sessionId }

        val workouts = buildWorkouts(sessions, completedSetsBySessionId)
            .filterNot { workout ->
                HealthExportMarkers.isExported(
                    cursorRepository = cursorRepository,
                    provider = provider,
                    profileId = profileId,
                    externalId = workout.externalId,
                )
            }
        var written = 0
        var firstFailure: Throwable? = null

        workouts.forEach { workout ->
            healthWriter.writeHealthWorkout(workout)
                .onSuccess {
                    written += 1
                    HealthExportMarkers.markExported(
                        cursorRepository = cursorRepository,
                        provider = provider,
                        profileId = profileId,
                        externalId = workout.externalId,
                    )
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

        return if (written == 0 && workouts.isNotEmpty()) {
            Result.failure(firstFailure ?: IllegalStateException("All backfill writes failed"))
        } else {
            Result.success(result)
        }
    }

    private suspend fun CompletedSetRepository.getCompletedSetsForSessionsChunked(
        sessionIds: List<String>,
    ): List<CompletedSet> = sessionIds
        .chunked(500)
        .flatMap { chunk -> getCompletedSetsForSessions(chunk) }

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
