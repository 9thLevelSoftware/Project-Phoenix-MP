package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.WarmupSet
import kotlin.math.roundToInt

/**
 * Applies one-shot Active Recovery or Heavy Deload transforms to a routine at launch time.
 *
 * The input routine is never mutated. Callers should resolve percent-of-PR routine weights
 * before invoking this use case so fallback weights are absolute kg values.
 *
 * Active Recovery weight semantics (Issue #882):
 * - Eligibility is driven by effective load, not [com.devil.phoenixproject.domain.model.Exercise.isBodyweight]
 *   metadata: any row with a positive scalar or per-set weight is scaled, so misclassified
 *   weighted rows (issue #635 class) are no longer skipped. Genuine zero-load rows
 *   (e.g. bodyweight exercises) keep their weights untouched.
 * - When a scoped baseline exists ([ResolveRoutineScalingBaselineUseCase], honoring
 *   [com.devil.phoenixproject.domain.model.RoutineExercise.effectiveScalingBasis]), weights are
 *   reduced to the selected percent of that baseline ("selected 1RM percentage" copy).
 *   The scalar lands exactly on percent-of-baseline and programmed per-set variation is
 *   preserved proportionally around it, with per-set half-kg rounding.
 * - When no baseline exists, each programmed load (scalar and per set) is reduced to the
 *   selected percent of itself. This is the only fallback; the 0.5 kg floor is never fabricated.
 */
class ApplyRoutineModifierUseCase(
    private val scalingBaselineResolver: ResolveRoutineScalingBaselineUseCase,
) {
    suspend operator fun invoke(
        routine: Routine,
        modifier: AppliedRoutineModifier,
        profileId: String = "default",
    ): Routine = when (modifier.type) {
        RoutineModifierType.ACTIVE_RECOVERY -> applyActiveRecovery(routine, modifier.percent, profileId)
        RoutineModifierType.HEAVY_DELOAD -> applyHeavyDeload(routine, modifier.percent)
    }

    private suspend fun applyActiveRecovery(routine: Routine, percent: Int, profileId: String): Routine = routine.copy(
        exercises = routine.exercises.map { exercise ->
            val loads = scaleActiveRecoveryLoads(exercise, percent, profileId)
            exercise.copy(
                weightPerCableKg = loads.scalar,
                setWeightsPerCableKg = loads.sets,
                warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent),
            )
        },
    )

    private data class ScaledLoads(val scalar: Float, val sets: List<Float>)

    private suspend fun scaleActiveRecoveryLoads(
        exercise: RoutineExercise,
        percent: Int,
        profileId: String,
    ): ScaledLoads {
        val scalarLoad = exercise.weightPerCableKg
        val setLoads = exercise.setWeightsPerCableKg
        // Issue #882: eligibility from effective load, not isBodyweight metadata.
        val hasPositiveLoad = scalarLoad > 0f || setLoads.any { it > 0f }
        if (!hasPositiveLoad) {
            return ScaledLoads(scalarLoad, setLoads)
        }

        val fraction = percent / 100f
        val baseline = exercise.exercise.id
            ?.let { exerciseId ->
                scalingBaselineResolver(
                    exerciseId = exerciseId,
                    mode = exercise.programMode,
                    profileId = profileId,
                    basis = exercise.effectiveScalingBasis,
                )
            }
            ?.weightPerCableKg
            ?.takeIf { it > 0f }

        return if (baseline != null) {
            // Documented percent-of-1RM semantics: the scalar lands exactly on the selected
            // percent of the baseline; programmed per-set variation is preserved proportionally.
            val targetScalar = roundToHalfKg(baseline * fraction)
            val setFactor = if (scalarLoad > 0f) targetScalar / scalarLoad else null
            val scaledSets = when {
                setLoads.isEmpty() -> emptyList()
                setFactor != null -> setLoads.map { roundToHalfKg(it * setFactor) }
                // Zero-scalar rows have no ratio to preserve: all sets take the baseline target.
                else -> List(setLoads.size) { targetScalar }
            }
            ScaledLoads(targetScalar, scaledSets)
        } else {
            // No-baseline fallback: the selected percent of each programmed set load,
            // preserving per-set variation. Never fabricate a floor weight.
            ScaledLoads(
                scalar = roundToHalfKg(scalarLoad * fraction),
                sets = setLoads.map { roundToHalfKg(it * fraction) },
            )
        }
    }

    private fun applyHeavyDeload(routine: Routine, percent: Int): Routine = routine.copy(
        exercises = routine.exercises.map { exercise ->
            val timedDuration = exercise.supportedTimedDurationSeconds
            exercise.copy(
                setReps = exercise.setReps.map { reps -> reps?.let { scaleReps(it, percent) } },
                duration = timedDuration?.let { scaleDurationSeconds(it, percent) } ?: exercise.duration,
                isLaunchAdjustedDuration = timedDuration != null,
                warmupSets = exercise.warmupSets.map { it.copy(reps = scaleReps(it.reps, percent)) },
            )
        },
    )

    private fun scaleFirstWarmupOnly(warmupSets: List<WarmupSet>, percent: Int): List<WarmupSet> = warmupSets
        .firstOrNull()
        ?.let { listOf(it.copy(reps = scaleReps(it.reps, percent))) }
        ?: emptyList()

    private fun scaleReps(reps: Int, percent: Int): Int = (reps * percent / 100f).roundToInt().coerceAtLeast(1)

    private fun scaleDurationSeconds(duration: Int, percent: Int): Int = if (duration > 0) {
        scaleReps(duration, percent)
    } else {
        duration
    }

    private fun roundToHalfKg(value: Float): Float = (value * 2f).roundToInt() / 2f
}
