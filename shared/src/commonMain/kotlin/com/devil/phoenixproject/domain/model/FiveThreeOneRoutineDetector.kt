package com.devil.phoenixproject.domain.model

object FiveThreeOneRoutineDetector {
    const val BENCH_ID = "Barbell_Bench_Press_-_Medium_Grip"
    const val SHOULDER_PRESS_ID = "Barbell_Shoulder_Press"
    const val SQUAT_ID = "Barbell_Squat"
    const val DEADLIFT_ID = "Barbell_Deadlift"

    val UPPER_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID)
    val MAIN_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID, SQUAT_ID, DEADLIFT_ID)

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

    fun mainLiftId(exercise: RoutineExercise): String? {
        val exerciseId = exercise.exercise.id ?: return null
        return if (exercise.usePercentOfPR && exerciseId in MAIN_LIFT_IDS) exerciseId else null
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
