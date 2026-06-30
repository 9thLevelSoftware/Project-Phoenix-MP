package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise

/**
 * Use case for resolving PR percentage weights to absolute values at workout start time.
 *
 * When a routine exercise is configured to use a percentage of PR (usePercentOfPR = true),
 * this use case looks up the user's current baseline for that exercise and calculates the
 * actual weight based on the configured percentage. When no baseline exists, it falls back
 * to the exercise's saved absolute weights.
 */
class ResolveRoutineWeightsUseCase(
    private val prRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val scalingBaselineResolver: ResolveRoutineScalingBaselineUseCase = ResolveRoutineScalingBaselineUseCase(
        prRepository,
        exerciseRepository,
        velocityOneRepMaxRepository,
    ),
) {
    /**
     * Resolves RoutineExercise weights from PR percentages to absolute values.
     * Call this at workout start time to get current weights based on latest baselines.
     *
     * @param exercise The routine exercise to resolve weights for
     * @param mode The program mode to use for baseline lookup (defaults to exercise's programMode)
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

        val exerciseId = exercise.exercise.id ?: return ResolvedExerciseWeights(
            baseWeight = exercise.weightPerCableKg,
            setWeights = exercise.setWeightsPerCableKg,
            usedPR = null,
            percentOfPR = null,
            fallbackReason = "Exercise has no ID for PR lookup",
        )

        val baseline = scalingBaselineResolver(
            exerciseId = exerciseId,
            mode = mode,
            profileId = profileId,
            basis = exercise.effectiveScalingBasis,
        )

        return if (baseline != null) {
            ResolvedExerciseWeights(
                baseWeight = exercise.resolveWeight(baseline.weightPerCableKg),
                setWeights = exercise.resolveSetWeights(baseline.weightPerCableKg),
                usedPR = baseline.weightPerCableKg,
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
 * @param usedPR The baseline weight value that was used for percentage calculation, or null if not applicable
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
