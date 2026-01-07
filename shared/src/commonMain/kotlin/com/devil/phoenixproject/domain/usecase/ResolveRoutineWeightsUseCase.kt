package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise

/**
 * Use case for resolving PR percentage weights to absolute values at workout start time.
 *
 * When a routine exercise is configured to use a percentage of PR (usePercentOfPR = true),
 * this use case looks up the user's current PR for that exercise and calculates the
 * actual weight based on the configured percentage.
 */
class ResolveRoutineWeightsUseCase(
    private val prRepository: PersonalRecordRepository
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
        mode: ProgramMode = exercise.programMode
    ): ResolvedExerciseWeights {
        if (!exercise.usePercentOfPR) {
            return ResolvedExerciseWeights(
                baseWeight = exercise.weightPerCableKg,
                setWeights = exercise.setWeightsPerCableKg,
                usedPR = null,
                percentOfPR = null
            )
        }

        // Get exercise ID from the nested Exercise object
        val exerciseId = exercise.exercise.id ?: return ResolvedExerciseWeights(
            baseWeight = exercise.weightPerCableKg,
            setWeights = exercise.setWeightsPerCableKg,
            usedPR = null,
            percentOfPR = null,
            fallbackReason = "Exercise has no ID for PR lookup"
        )

        // Lookup PR for this exercise and mode
        val pr = when (exercise.prTypeForScaling) {
            PRType.MAX_WEIGHT -> prRepository.getBestWeightPR(exerciseId, mode.displayName)
            PRType.MAX_VOLUME -> prRepository.getBestVolumePR(exerciseId, mode.displayName)
        }

        val prWeight = pr?.weightPerCableKg

        return if (prWeight != null && prWeight > 0) {
            ResolvedExerciseWeights(
                baseWeight = exercise.resolveWeight(prWeight),
                setWeights = exercise.resolveSetWeights(prWeight),
                usedPR = prWeight,
                percentOfPR = exercise.weightPercentOfPR
            )
        } else {
            // No PR found - fall back to absolute weight
            ResolvedExerciseWeights(
                baseWeight = exercise.weightPerCableKg,
                setWeights = exercise.setWeightsPerCableKg,
                usedPR = null,
                percentOfPR = null,
                fallbackReason = "No PR found for exercise"
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
    val fallbackReason: String? = null
) {
    /**
     * True if weights were resolved from a PR percentage.
     * False if using fallback absolute weights.
     */
    val isFromPR: Boolean get() = usedPR != null && percentOfPR != null
}
