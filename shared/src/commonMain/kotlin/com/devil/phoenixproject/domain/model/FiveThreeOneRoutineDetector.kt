package com.devil.phoenixproject.domain.model

object FiveThreeOneRoutineDetector {
    const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
    const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
    const val SQUAT_ID = "UjIGHxCav-lS9B2I"
    const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"

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
