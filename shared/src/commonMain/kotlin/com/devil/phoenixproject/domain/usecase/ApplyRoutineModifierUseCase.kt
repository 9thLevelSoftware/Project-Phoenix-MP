package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.roundToInt

/**
 * Applies one-shot Active Recovery or Heavy Deload transforms to a routine at launch time.
 *
 * The input routine is never mutated. Callers should resolve percent-of-PR routine weights
 * before invoking this use case so fallback weights are absolute kg values.
 */
class ApplyRoutineModifierUseCase(
    private val prRepository: PersonalRecordRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    // Issue #882: baseline lookup delegates to the canonical resolver so Active Recovery
    // honors effectiveScalingBasis (MAX_WEIGHT_PR / MAX_VOLUME_PR / ESTIMATED_1RM) and the
    // shared current-mode -> cross-mode -> stored-1RM precedence instead of a second chain.
    private val scalingBaselineResolver: ResolveRoutineScalingBaselineUseCase = ResolveRoutineScalingBaselineUseCase(
        prRepository,
        baselineRepository,
        velocityOneRepMaxRepository,
    ),
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
            // Issue #882: load eligibility is a local predicate on positive programmed load,
            // not Exercise.isBodyweight. Catalogue-derived bodyweight rows (equipment
            // 'other'/'bands'/null) with real load must scale like any other loaded exercise;
            // only genuine zero-load rows keep their loads.
            if (!hasPositiveLoad(exercise)) {
                exercise.copy(warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent))
            } else {
                val baselineKg = resolveBaselineKg(exercise, profileId)
                if (baselineKg != null) {
                    // 1RM-baseline branch: the selected percent of the resolved baseline.
                    // Flat per-set output is shipped behaviour (kept for compatibility).
                    val adjustedWeight = scaleLoad(baselineKg, percent)
                    exercise.copy(
                        weightPerCableKg = adjustedWeight,
                        setWeightsPerCableKg = if (exercise.setWeightsPerCableKg.isNotEmpty()) {
                            List(exercise.setWeightsPerCableKg.size) { adjustedWeight }
                        } else {
                            emptyList()
                        },
                        warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent),
                    )
                } else {
                    // No PR/stored-1RM baseline: scale each already-resolved programmed load
                    // by the selected percent. The caller resolved usePercentOfPR rows to
                    // absolute kg before this use case ran, so those values are scaled exactly
                    // once here - never re-derive them through weightPercentOfPR /
                    // setWeightsPercentOfPR (that would double-apply percentages).
                    val adjustedSetWeights = exercise.setWeightsPerCableKg.map { setLoad ->
                        // Per-set load wins; the scalar is the fallback only when a set has no
                        // positive weight. Positive per-set-only loads are never floored just
                        // because the scalar is zero; genuine zero sets stay zero.
                        val load = setLoad.takeIf { it > 0f } ?: exercise.weightPerCableKg.takeIf { it > 0f }
                        if (load == null) setLoad else scaleLoad(load, percent)
                    }
                    val adjustedBaseWeight = exercise.weightPerCableKg
                        .takeIf { it > 0f }
                        ?.let { scaleLoad(it, percent) }
                        // Scalar-only rows scale from the scalar; per-set-only rows mirror the
                        // first adjusted set so the base weight never collapses to the floor.
                        ?: adjustedSetWeights.firstOrNull { it > 0f }
                        ?: exercise.weightPerCableKg

                    exercise.copy(
                        weightPerCableKg = adjustedBaseWeight,
                        setWeightsPerCableKg = adjustedSetWeights,
                        warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent),
                    )
                }
            }
        },
    )

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

    private suspend fun resolveBaselineKg(exercise: RoutineExercise, profileId: String): Float? {
        val exerciseId = exercise.exercise.id ?: return null
        return scalingBaselineResolver(
            exerciseId = exerciseId,
            mode = exercise.programMode,
            profileId = profileId,
            basis = exercise.effectiveScalingBasis,
        )?.weightPerCableKg?.takeIf { it > 0f }
    }

    private fun hasPositiveLoad(exercise: RoutineExercise): Boolean =
        exercise.weightPerCableKg > 0f || exercise.setWeightsPerCableKg.any { it > 0f }

    private fun scaleLoad(loadKg: Float, percent: Int): Float =
        UnitConverter.roundToMachineIncrement(loadKg * percent / 100f).coerceAtLeast(MIN_WEIGHT_KG)

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

    private companion object {
        const val MIN_WEIGHT_KG = 0.5f
    }
}
