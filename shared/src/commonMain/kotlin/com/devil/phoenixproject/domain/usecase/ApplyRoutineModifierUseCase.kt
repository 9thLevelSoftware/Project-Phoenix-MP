package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineModifierType
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.util.roundToHalfKg
import kotlin.math.roundToInt

/**
 * Applies one-shot Active Recovery or Heavy Deload transforms to a routine at launch time.
 *
 * The input routine is never mutated. Callers should resolve percent-of-PR routine weights
 * before invoking this use case so fallback weights are absolute kg values.
 *
 * Heavy Deload (issue #1408) also converts AMRAP sets on eligible exercises into
 * session-only fixed-rep sets: the achieved reps of the most recent completed AMRAP set
 * for the same exercise (profile-scoped) are scaled by the deload percent (10 reps at
 * 60% -> 6 reps, weight unchanged). Without AMRAP history the set stays AMRAP and
 * unchanged. The routine template is never modified.
 *
 * Accepted limitations (signed off in the #1408 design review):
 * - Active Recovery AMRAP rows (typically lighter weight) may serve as the reference.
 * - History beyond the 50 most recent completed sets of that exercise is not consulted.
 */
class ApplyRoutineModifierUseCase(
    private val prRepository: PersonalRecordRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val completedSetRepository: CompletedSetRepository,
    // Issue #882: baseline lookup delegates to the canonical resolver so Active Recovery
    // honors effectiveScalingBasis (MAX_WEIGHT_PR / MAX_VOLUME_PR / ESTIMATED_1RM) and the
    // shared current-mode -> cross-mode -> stored-1RM precedence instead of a second chain.
    private val scalingBaselineResolver: ResolveRoutineScalingBaselineUseCase = ResolveRoutineScalingBaselineUseCase(
        prRepository,
        baselineRepository,
        velocityOneRepMaxRepository,
        completedSetRepository,
    ),
) {
    suspend operator fun invoke(
        routine: Routine,
        modifier: AppliedRoutineModifier,
        profileId: String = "default",
    ): Routine = when (modifier.type) {
        RoutineModifierType.ACTIVE_RECOVERY -> applyActiveRecovery(routine, modifier.percent, profileId)
        RoutineModifierType.HEAVY_DELOAD -> applyHeavyDeload(routine, modifier.percent, profileId)
    }

    private suspend fun applyActiveRecovery(routine: Routine, percent: Int, profileId: String): Routine = routine.copy(
        exercises = routine.exercises.map { exercise ->
            // Issue #882: load eligibility is a local predicate on positive programmed load,
            // not Exercise.isBodyweight. Catalogue-derived bodyweight rows (equipment
            // 'other'/'bands'/null) with real load must scale like any other loaded exercise;
            // only genuine zero-load rows keep their loads.
            if (!hasPositiveLoad(exercise)) {
                exercise.copy(
                    warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent),
                    isLaunchAdjustedByRoutineModifier = true,
                )
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
                        isLaunchAdjustedByRoutineModifier = true,
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
                        isLaunchAdjustedByRoutineModifier = true,
                    )
                }
            }
        },
    )

    /**
     * Heavy Deload (issue #1408): scales programmed rep counts by [percent] and, on
     * eligible exercises (not bodyweight, not timed-cable), converts each AMRAP set into
     * a session-only fixed-rep set at `scaleReps(R, percent)`, where R is the achieved
     * reps of the most recent completed AMRAP-typed set for the same exercise
     * (profile-scoped, latest logical attempt). Without AMRAP history the set stays AMRAP
     * and unchanged. The input routine is never mutated; the deloaded copy records as
     * STANDARD at execution, so completed history never feeds back into later deloads.
     */
    private suspend fun applyHeavyDeload(routine: Routine, percent: Int, profileId: String): Routine {
        // One history lookup per distinct exercise per invocation (Koin factory: no class state).
        val amrapHistoryCache = mutableMapOf<String, List<CompletedSet>>()
        return routine.copy(
            exercises = routine.exercises.map { exercise ->
                val timedDuration = exercise.supportedTimedDurationSeconds
                // Eligibility is evaluated on the input exercise; ineligible rows keep
                // today's exact output, including the last-set placeholder scaling.
                val amrapEligible = !exercise.exercise.isBodyweight && timedDuration == null
                val hasAmrapSets = exercise.setReps.indices.any { exercise.isAmrapSet(it) }
                val amrapHistory = if (amrapEligible && hasAmrapSets) {
                    val exerciseId = exercise.exercise.id
                    if (exerciseId == null) {
                        emptyList()
                    } else {
                        amrapHistoryCache.getOrPut(exerciseId) {
                            completedSetRepository
                                .getRecentCompletedSetsForExercise(exerciseId, AMRAP_HISTORY_LIMIT, profileId)
                                // Only achieved AMRAP reps are references; deloaded sets
                                // record as STANDARD, so no feedback loop is possible.
                                .filter { it.setType == SetType.AMRAP && it.actualReps > 0 }
                        }
                    }
                } else {
                    emptyList()
                }
                val adjustedSetReps = if (!amrapEligible) {
                    exercise.setReps.map { reps -> reps?.let { scaleReps(it, percent) } }
                } else {
                    exercise.setReps.mapIndexed { index, reps ->
                        if (exercise.isAmrapSet(index)) {
                            // With history: fixed-rep set at the scaled reference. Without:
                            // the entry stays as-is (null stays AMRAP, the last-set
                            // placeholder is not scaled while the set stays AMRAP).
                            resolveAmrapReferenceReps(exercise, index, amrapHistory)
                                ?.let { scaleReps(it, percent) }
                                ?: reps
                        } else {
                            reps?.let { scaleReps(it, percent) }
                        }
                    }
                }
                exercise.copy(
                    setReps = adjustedSetReps,
                    // The last-set AMRAP flag survives only while the set is still AMRAP.
                    isAMRAP = exercise.isAMRAP && amrapHistory.isEmpty(),
                    duration = timedDuration?.let { scaleDurationSeconds(it, percent) } ?: exercise.duration,
                    isLaunchAdjustedDuration = timedDuration != null,
                    warmupSets = exercise.warmupSets.map { it.copy(reps = scaleReps(it.reps, percent)) },
                    isLaunchAdjustedByRoutineModifier = true,
                )
            },
        )
    }

    /**
     * Reference achieved reps for an AMRAP set, newest attempt first (repository order):
     * same routine-exercise and set slot, then same routine-exercise, then any completed
     * AMRAP set of the exercise (routine or Just Lift), else null.
     */
    private fun resolveAmrapReferenceReps(
        exercise: RoutineExercise,
        setIndex: Int,
        amrapHistory: List<CompletedSet>,
    ): Int? {
        val reference = amrapHistory.firstOrNull { it.routineExerciseId == exercise.id && it.setNumber == setIndex }
            ?: amrapHistory.firstOrNull { it.routineExerciseId == exercise.id }
            ?: amrapHistory.firstOrNull()
        return reference?.actualReps
    }

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
        (loadKg * percent / 100f).roundToHalfKg().coerceAtLeast(MIN_WEIGHT_KG)

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

        /** Most recent completed sets consulted per exercise when resolving AMRAP reps. */
        const val AMRAP_HISTORY_LIMIT = 50
    }
}
