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

    private data class FiveThreeOneSetShape(
        val reps: List<Int?>,
        val isAmrap: Boolean,
    )
}
