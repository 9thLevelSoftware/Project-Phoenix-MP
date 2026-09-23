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
 * Mirrors `RegenerateFiveThreeOneRoutinesUseCase`: days in day order; per routine the main
 * lift is the only [FiveThreeOneRoutineDetector.mainLiftId] match, or else the only match with
 * a known 5/3/1 set shape (ambiguous routines are skipped, as regeneration aborts on them); the
 * bump targets the first stored exercise id seen for each canonical lift. A lift is missing
 * when [baselinePerCableKg] returns null or a non-positive value for that id, which is when
 * `ProfileExerciseBaselineRepository.increment` returns null and the bump is skipped.
 */
fun missingFiveThreeOneTrainingMaxes(
    cycle: TrainingCycle,
    routines: List<Routine>,
    baselinePerCableKg: (exerciseId: String) -> Float?,
): List<MissingFiveThreeOneTrainingMax> {
    val routinesById = routines.associateBy { it.id }
    if (!cycle.isFiveThreeOneForTrainingMaxBump(routinesById)) return emptyList()
    val firstStoredLiftByCanonical = linkedMapOf<String, RoutineExercise>()
    for (day in cycle.days.sortedBy { it.dayNumber }) {
        if (day.isRestDay) continue
        val routine = day.routineId?.let(routinesById::get) ?: continue
        val matches = routine.exercises.filter { exercise ->
            FiveThreeOneRoutineDetector.mainLiftId(exercise) != null && exercise.exercise.id != null
        }
        if (matches.groupBy { FiveThreeOneRoutineDetector.mainLiftId(it) }.any { it.value.size > 1 }) continue
        val shaped = matches.filter { FiveThreeOneRoutineDetector.hasKnownSetShape(it) }
        val mainLift = when {
            matches.size == 1 -> matches.single()
            shaped.size == 1 -> shaped.single()
            else -> continue
        }
        val canonicalId = FiveThreeOneRoutineDetector.mainLiftId(mainLift) ?: continue
        if (canonicalId !in firstStoredLiftByCanonical) {
            firstStoredLiftByCanonical[canonicalId] = mainLift
        }
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

// Same value as ActiveSessionEngine.TEMPLATE_531_ID.
private const val TEMPLATE_531_ID = "template_531"

/** Mirrors ActiveSessionEngine.isFiveThreeOneCycleForProgress: only these cycles get the weekly bump. */
private fun TrainingCycle.isFiveThreeOneForTrainingMaxBump(routinesById: Map<String, Routine>): Boolean {
    if (templateId == TEMPLATE_531_ID) return true
    val matchedLiftIds = days.asSequence()
        .filterNot { it.isRestDay }
        .mapNotNull { day -> day.routineId?.let(routinesById::get) }
        .flatMap { it.exercises.asSequence() }
        .mapNotNull { FiveThreeOneRoutineDetector.knownShapeMainLiftId(it) }
        .toSet()
    return matchedLiftIds.containsAll(FiveThreeOneRoutineDetector.MAIN_LIFT_IDS)
}
