package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.getBestVolumePRForWorkoutMode
import com.devil.phoenixproject.data.repository.getBestWeightPRForWorkoutMode
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis

/**
 * Use case for resolving PR percentage weights to absolute values at workout start time.
 *
 * When a routine exercise is configured to use a percentage of PR (usePercentOfPR = true),
 * this use case looks up the user's current PR for that exercise and calculates the
 * actual weight based on the configured percentage. When no PR exists, falls back to the
 * exercise's stored oneRepMaxKg (manual input or VBT assessment) before using absolute weights.
 */
class ResolveRoutineWeightsUseCase(
    private val prRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
) {
    /**
     * Resolves RoutineExercise weights from PR percentages to absolute values.
     * Call this at workout start time to get current weights based on latest PRs.
     *
     * @param exercise The routine exercise to resolve weights for
     * @param mode The program mode to use for PR lookup (defaults to exercise's programMode)
     * @return ResolvedExerciseWeights containing the absolute weight values
     */
    suspend operator fun invoke(
        exercise: RoutineExercise,
        mode: ProgramMode = exercise.programMode,
        profileId: String = "default",
    ): ResolvedExerciseWeights {
        if (!exercise.usePercentOfPR) {
            return ResolvedExerciseWeights(
                baseWeight = exercise.weightPerCableKg,
                setWeights = exercise.setWeightsPerCableKg,
                usedPR = null,
                percentOfPR = null,
            )
        }

        // Get exercise ID from the nested Exercise object
        val exerciseId = exercise.exercise.id ?: return ResolvedExerciseWeights(
            baseWeight = exercise.weightPerCableKg,
            setWeights = exercise.setWeightsPerCableKg,
            usedPR = null,
            percentOfPR = null,
            fallbackReason = "Exercise has no ID for PR lookup",
        )

        // Lookup scaling baseline for this exercise filtered by mode and profile
        val scalingWeight: Float? = when (exercise.effectiveScalingBasis) {
            ScalingBasis.MAX_WEIGHT_PR -> prRepository.getBestWeightPRForWorkoutMode(
                exerciseId,
                mode.displayName,
                profileId,
            )?.weightPerCableKg?.takeIf { it > 0 }

            ScalingBasis.MAX_VOLUME_PR -> prRepository.getBestVolumePRForWorkoutMode(
                exerciseId,
                mode.displayName,
                profileId,
            )?.weightPerCableKg?.takeIf { it > 0 }

            ScalingBasis.ESTIMATED_1RM -> velocityOneRepMaxRepository.getLatestPassing(
                exerciseId,
                profileId,
            )?.estimatedPerCableKg?.takeIf { it > 0 }
        } ?: exerciseRepository.getExerciseById(exerciseId)?.oneRepMaxKg?.takeIf { it > 0 }
            // Last-resort baseline for ESTIMATED_1RM only: when there is no velocity estimate
            // and no stored Exercise.oneRepMaxKg, fall back to the max-weight PR before going absolute.
            ?: (
                if (exercise.effectiveScalingBasis == ScalingBasis.ESTIMATED_1RM) {
                    prRepository.getBestWeightPRForWorkoutMode(
                        exerciseId,
                        mode.displayName,
                        profileId,
                    )?.weightPerCableKg?.takeIf { it > 0 }
                } else {
                    null
                }
                )

        return if (scalingWeight != null) {
            ResolvedExerciseWeights(
                baseWeight = exercise.resolveWeight(scalingWeight),
                setWeights = exercise.resolveSetWeights(scalingWeight),
                usedPR = scalingWeight,
                percentOfPR = exercise.weightPercentOfPR,
            )
        } else {
            ResolvedExerciseWeights(
                baseWeight = exercise.weightPerCableKg,
                setWeights = exercise.setWeightsPerCableKg,
                usedPR = null,
                percentOfPR = null,
                fallbackReason = "No PR or stored 1RM found for exercise",
            )
        }
    }
}

/**
 * Result of resolving routine exercise weights from PR percentages.
 *
 * @param baseWeight The resolved base weight per cable in kg
 * @param setWeights The resolved per-set weights in kg (may be empty if using baseWeight for all sets)
 * @param usedPR The PR weight value that was used for percentage calculation, or null if not applicable
 * @param percentOfPR The percentage of PR that was applied, or null if not applicable
 * @param fallbackReason If weights fell back to absolute values, the reason why (e.g., no PR found)
 */
data class ResolvedExerciseWeights(
    val baseWeight: Float,
    val setWeights: List<Float>,
    val usedPR: Float?,
    val percentOfPR: Int?,
    val fallbackReason: String? = null,
) {
    /**
     * True if weights were resolved from a PR percentage.
     * False if using fallback absolute weights.
     */
    val isFromPR: Boolean get() = usedPR != null && percentOfPR != null
}
