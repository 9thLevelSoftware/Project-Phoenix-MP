package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.OneRepMaxCalculator

enum class CurrentOneRepMaxSource {
    VELOCITY,
    ASSESSMENT,
    SESSION,
}

data class CurrentOneRepMax(
    val perCableKg: Float,
    val source: CurrentOneRepMaxSource,
    val measuredAt: Long,
)

fun WorkoutSession.estimatedOneRepMaxPerCableOrNull(): Float? {
    val load = weightPerCableKg.takeIf { it.isFinite() && it > 0f } ?: return null
    val reps = workingReps.takeIf { it > 0 }
        ?: totalReps.takeIf { it > 0 }
        ?: return null
    return OneRepMaxCalculator.estimate(load, reps)
        .takeIf { it.isFinite() && it > 0f }
}

private fun Float.validPositiveOrNull(): Float? =
    takeIf { it.isFinite() && it > 0f }

class ResolveCurrentOneRepMaxUseCase(
    private val velocityRepository: VelocityOneRepMaxRepository,
    private val assessmentRepository: AssessmentRepository,
    private val workoutRepository: WorkoutRepository,
) {
    suspend operator fun invoke(exerciseId: String, profileId: String): CurrentOneRepMax? {
        require(exerciseId.isNotBlank())
        require(profileId.isNotBlank())

        velocityRepository.getLatestPassing(exerciseId, profileId)
            ?.takeIf {
                it.exerciseId == exerciseId &&
                    it.profileId == profileId &&
                    it.passedQualityGate
            }
            ?.let { estimate ->
                estimate.estimatedPerCableKg.validPositiveOrNull()
                    ?.let { estimate to it }
            }
            ?.let {
                return CurrentOneRepMax(
                    perCableKg = it.second,
                    source = CurrentOneRepMaxSource.VELOCITY,
                    measuredAt = it.first.computedAt,
                )
            }

        assessmentRepository.getLatestAssessment(exerciseId, profileId)?.let { assessment ->
            if (assessment.exerciseId == exerciseId && assessment.profileId == profileId) {
                val validTotalKg = assessment.userOverrideKg?.validPositiveOrNull()
                    ?: assessment.estimatedOneRepMaxKg.validPositiveOrNull()
                val perCableKg = validTotalKg?.div(2f)?.validPositiveOrNull()
                if (perCableKg != null) {
                    return CurrentOneRepMax(
                        perCableKg = perCableKg,
                        source = CurrentOneRepMaxSource.ASSESSMENT,
                        measuredAt = assessment.createdAt,
                    )
                }
            }
        }

        workoutRepository.getRecentCompletedSessionsForExercise(
            exerciseId = exerciseId,
            profileId = profileId,
            limit = MAX_RECENT_EXERCISE_SESSIONS,
        ).forEach { session ->
            if (session.exerciseId == exerciseId && session.profileId == profileId) {
                val estimate = session.estimatedOneRepMaxPerCableOrNull()
                if (estimate != null) {
                    return CurrentOneRepMax(
                        perCableKg = estimate,
                        source = CurrentOneRepMaxSource.SESSION,
                        measuredAt = session.timestamp,
                    )
                }
            }
        }
        return null
    }
}
