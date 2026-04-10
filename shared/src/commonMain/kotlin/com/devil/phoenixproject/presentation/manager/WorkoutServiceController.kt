package com.devil.phoenixproject.presentation.manager

enum class WorkoutServicePhase {
    INITIALIZING,
    COUNTDOWN,
    ACTIVE,
    SET_SUMMARY,
    RESTING,
    JUST_LIFT_REST,
    PAUSED,
}

data class WorkoutServiceSnapshot(
    val phase: WorkoutServicePhase,
    val workoutModeName: String,
    val exerciseName: String? = null,
    val nextExerciseName: String? = null,
    val currentSet: Int? = null,
    val totalSets: Int? = null,
    val completedReps: Int? = null,
    val targetReps: Int? = null,
    val secondsRemaining: Int? = null,
)

interface WorkoutServiceController {
    fun showOrUpdate(snapshot: WorkoutServiceSnapshot)

    fun stop()
}

object NoOpWorkoutServiceController : WorkoutServiceController {
    override fun showOrUpdate(snapshot: WorkoutServiceSnapshot) = Unit

    override fun stop() = Unit
}

object WorkoutServiceProtocol {
    const val ACTION_SYNC = "com.devil.phoenixproject.WORKOUT_SERVICE_SYNC"
    const val ACTION_STOP = "com.devil.phoenixproject.WORKOUT_SERVICE_STOP"

    const val EXTRA_PHASE = "phase"
    const val EXTRA_WORKOUT_MODE = "workout_mode"
    const val EXTRA_EXERCISE_NAME = "exercise_name"
    const val EXTRA_NEXT_EXERCISE_NAME = "next_exercise_name"
    const val EXTRA_CURRENT_SET = "current_set"
    const val EXTRA_TOTAL_SETS = "total_sets"
    const val EXTRA_COMPLETED_REPS = "completed_reps"
    const val EXTRA_TARGET_REPS = "target_reps"
    const val EXTRA_SECONDS_REMAINING = "seconds_remaining"
}
