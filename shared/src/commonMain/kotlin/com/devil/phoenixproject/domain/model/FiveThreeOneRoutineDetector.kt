package com.devil.phoenixproject.domain.model

object FiveThreeOneRoutineDetector {
    const val BENCH_ID = "Barbell_Bench_Press_-_Medium_Grip"
    const val SHOULDER_PRESS_ID = "Barbell_Shoulder_Press"
    const val SQUAT_ID = "Barbell_Squat"
    const val DEADLIFT_ID = "Barbell_Deadlift"

    const val LEGACY_BENCH_ID = "ZZ92N8QsBdp6HCh3"
    const val LEGACY_SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
    const val LEGACY_SQUAT_ID = "UjIGHxCav-lS9B2I"
    const val LEGACY_DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"

    val UPPER_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID)
    val MAIN_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID, SQUAT_ID, DEADLIFT_ID)

    private val canonicalLiftById = mapOf(
        BENCH_ID to BENCH_ID,
        SHOULDER_PRESS_ID to SHOULDER_PRESS_ID,
        SQUAT_ID to SQUAT_ID,
        DEADLIFT_ID to DEADLIFT_ID,
        LEGACY_BENCH_ID to BENCH_ID,
        LEGACY_SHOULDER_PRESS_ID to SHOULDER_PRESS_ID,
        LEGACY_SQUAT_ID to SQUAT_ID,
        LEGACY_DEADLIFT_ID to DEADLIFT_ID,
    )

    private val knownSetShapes = listOf(
        FiveThreeOneWeeks.WEEK_1,
        FiveThreeOneWeeks.WEEK_2,
        FiveThreeOneWeeks.WEEK_3,
        FiveThreeOneWeeks.WEEK_4_DELOAD,
    ).map { sets ->
        FiveThreeOneSetShape(
            reps = sets.map { it.targetReps },
            isAmrap = sets.any { it.isAmrap },
        )
    }

    fun canonicalMainLiftId(exerciseId: String?): String? = exerciseId?.let(canonicalLiftById::get)

    fun mainLiftId(exercise: RoutineExercise): String? {
        if (!exercise.usePercentOfPR) return null
        return canonicalMainLiftId(exercise.exercise.id)
    }

    fun knownShapeMainLiftId(exercise: RoutineExercise): String? =
        mainLiftId(exercise)?.takeIf { hasKnownSetShape(exercise) }

    fun hasKnownSetShape(exercise: RoutineExercise): Boolean = knownSetShapes.any { shape ->
        exercise.setReps == shape.reps && exercise.isAMRAP == shape.isAmrap
    }

    /**
     * The routine's single 5/3/1 main lift: the only [mainLiftId] match, or else the only
     * match with a known 5/3/1 set shape. Shared by weekly regeneration and the cycle
     * screen's missing-training-max notice so the two can never disagree.
     */
    fun resolveMainLift(exercises: List<RoutineExercise>): MainLiftResolution {
        val matches = exercises.mapIndexedNotNull { index, exercise ->
            val liftId = mainLiftId(exercise) ?: return@mapIndexedNotNull null
            val storedId = exercise.exercise.id ?: return@mapIndexedNotNull null
            MainLiftResolution.Found(index, liftId, storedId, hasKnownSetShape(exercise))
        }
        if (matches.isEmpty()) return MainLiftResolution.None
        matches.groupBy { it.canonicalId }.entries.firstOrNull { it.value.size > 1 }?.let { (liftId, duplicates) ->
            return MainLiftResolution.DuplicateLift(liftId, duplicates.map { it.index })
        }
        val shaped = matches.filter { it.hasFiveThreeOneSetShape }
        return when {
            matches.size == 1 -> matches.single()
            shaped.size == 1 -> shaped.single()
            else -> MainLiftResolution.MultipleCandidates(matches)
        }
    }

    /** 5/3/1 cycles get the weekly training-max bump: the 5/3/1 template, or all four main lifts present. */
    fun isFiveThreeOneCycle(templateId: String?, routines: List<Routine>): Boolean {
        if (templateId == TEMPLATE_531_ID) return true
        val matchedLiftIds = routines.asSequence()
            .flatMap { it.exercises.asSequence() }
            .mapNotNull { knownShapeMainLiftId(it) }
            .toSet()
        return matchedLiftIds.containsAll(MAIN_LIFT_IDS)
    }

    const val TEMPLATE_531_ID = "template_531"

    sealed interface MainLiftResolution {
        data object None : MainLiftResolution

        data class Found(
            val index: Int,
            val canonicalId: String,
            val storedId: String,
            val hasFiveThreeOneSetShape: Boolean,
        ) : MainLiftResolution

        data class DuplicateLift(val canonicalId: String, val indexes: List<Int>) : MainLiftResolution

        data class MultipleCandidates(val candidates: List<Found>) : MainLiftResolution
    }

    private data class FiveThreeOneSetShape(
        val reps: List<Int?>,
        val isAmrap: Boolean,
    )
}
