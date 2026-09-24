package com.devil.phoenixproject.domain.model

/** A 5/3/1 main lift whose training max (per-cable baseline) is not set for the cycle's profile. */
data class MissingFiveThreeOneTrainingMax(
    val exerciseId: String,
    val exerciseName: String,
)

/**
 * The 5/3/1 main lifts in [cycle] whose weekly training-max bump would be skipped because
 * the cycle's profile has no baseline for them (carryover R-24).
 *
 * Uses the same [FiveThreeOneRoutineDetector.isFiveThreeOneCycle] gate and
 * [FiveThreeOneRoutineDetector.resolveMainLift] as `RegenerateFiveThreeOneRoutinesUseCase`,
 * so the notice lists exactly the lifts the bump targets: days in day order, ambiguous
 * routines skipped (regeneration aborts on them), the first stored id per canonical lift.
 * A lift is missing when [baselinePerCableKg] returns null or a non-positive value for that
 * id, which is when `ProfileExerciseBaselineRepository.increment` returns null.
 */
fun missingFiveThreeOneTrainingMaxes(
    cycle: TrainingCycle,
    routines: List<Routine>,
    baselinePerCableKg: (exerciseId: String) -> Float?,
): List<MissingFiveThreeOneTrainingMax> {
    val routinesById = routines.associateBy { it.id }
    val trainingRoutines = cycle.days.sortedBy { it.dayNumber }
        .filterNot { it.isRestDay }
        .mapNotNull { day -> day.routineId?.let(routinesById::get) }
    if (!FiveThreeOneRoutineDetector.isFiveThreeOneCycle(cycle.templateId, trainingRoutines)) return emptyList()

    val firstStoredLiftByCanonical = linkedMapOf<String, RoutineExercise>()
    for (routine in trainingRoutines) {
        val mainLift = FiveThreeOneRoutineDetector.resolveMainLift(routine.exercises)
            as? FiveThreeOneRoutineDetector.MainLiftResolution.Found ?: continue
        firstStoredLiftByCanonical.getOrPut(mainLift.canonicalId) { routine.exercises[mainLift.index] }
    }
    return firstStoredLiftByCanonical.values.mapNotNull { exercise ->
        val exerciseId = exercise.exercise.id ?: return@mapNotNull null
        val baseline = baselinePerCableKg(exerciseId)
        if (baseline != null && baseline > 0f) {
            null
        } else {
            MissingFiveThreeOneTrainingMax(exerciseId = exerciseId, exerciseName = exercise.exercise.name)
        }
    }
}
