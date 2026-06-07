package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.getBestWeightPRForWorkoutMode
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
 */
class ApplyRoutineModifierUseCase(
    private val prRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
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
            if (exercise.exercise.isBodyweight) {
                exercise.copy(warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent))
            } else {
                val baseline = resolveBaselineOneRepMax(exercise, profileId)
                val adjustedWeight = roundToHalfKg(baseline * percent / 100f).coerceAtLeast(MIN_WEIGHT_KG)
                val adjustedSetWeights = if (exercise.setWeightsPerCableKg.isNotEmpty()) {
                    List(exercise.setWeightsPerCableKg.size) { adjustedWeight }
                } else {
                    emptyList()
                }

                exercise.copy(
                    weightPerCableKg = adjustedWeight,
                    setWeightsPerCableKg = adjustedSetWeights,
                    warmupSets = scaleFirstWarmupOnly(exercise.warmupSets, percent),
                )
            }
        },
    )

    private fun applyHeavyDeload(routine: Routine, percent: Int): Routine = routine.copy(
        exercises = routine.exercises.map { exercise ->
            exercise.copy(
                setReps = exercise.setReps.map { reps -> reps?.let { scaleReps(it, percent) } },
                duration = exercise.duration?.let { scaleDurationSeconds(it, percent) },
                warmupSets = exercise.warmupSets.map { it.copy(reps = scaleReps(it.reps, percent)) },
            )
        },
    )

    private suspend fun resolveBaselineOneRepMax(exercise: RoutineExercise, profileId: String): Float {
        val exerciseId = exercise.exercise.id
        val weightPrOneRepMax = exerciseId
            ?.let { prRepository.getBestWeightPRForWorkoutMode(it, exercise.programMode.displayName, profileId) }
            ?.oneRepMax
            ?.takeIf { it > 0 }

        if (weightPrOneRepMax != null) return weightPrOneRepMax

        val storedOneRepMax = exerciseId
            ?.let { exerciseRepository.getExerciseById(it)?.oneRepMaxKg }
            ?.takeIf { it > 0 }
            ?: exercise.exercise.oneRepMaxKg?.takeIf { it > 0 }

        return storedOneRepMax ?: exercise.weightPerCableKg.takeIf { it > 0 } ?: MIN_WEIGHT_KG
    }

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

    private companion object {
        const val MIN_WEIGHT_KG = 0.5f
    }
}
