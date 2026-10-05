package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis

/**
 * Pre-built test fixtures for use in tests.
 * Provides consistent, reusable test data.
 */
object TestFixtures {

    // ========== Exercises ==========

    val benchPress = Exercise(
        name = "Bench Press",
        muscleGroup = "Chest",
        muscleGroups = "Chest,Triceps,Shoulders",
        equipment = "BAR",
        id = "bench-press-001",
        isFavorite = true,
    )

    val bicepCurl = Exercise(
        name = "Bicep Curl",
        muscleGroup = "Biceps",
        muscleGroups = "Biceps",
        equipment = "SINGLE_HANDLE",
        id = "bicep-curl-001",
    )

    val squat = Exercise(
        name = "Squat",
        muscleGroup = "Legs",
        muscleGroups = "Legs,Glutes,Core",
        equipment = "BAR",
        id = "squat-001",
    )

    val deadlift = Exercise(
        name = "Deadlift",
        muscleGroup = "Back",
        muscleGroups = "Back,Legs,Glutes",
        equipment = "BAR",
        id = "deadlift-001",
    )

    val singleArmRow = Exercise(
        name = "Single Arm Row",
        muscleGroup = "Back",
        muscleGroups = "Back,Biceps",
        equipment = "SINGLE_HANDLE",
        id = "single-arm-row-001",
    )

    val customExercise = Exercise(
        name = "Custom Exercise",
        muscleGroup = "Full Body",
        muscleGroups = "Full Body",
        equipment = "",
        id = "custom-001",
        isCustom = true,
    )

    val allExercises = listOf(benchPress, bicepCurl, squat, deadlift, singleArmRow, customExercise)

    val justLiftParams = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 0, // AMRAP
        weightPerCableKg = 30f,
        isJustLift = true,
        useAutoStart = true,
        isAMRAP = true,
        selectedExerciseId = deadlift.id,
    )

    // ========== Workout Sessions ==========

    fun createWorkoutSession(
        id: String = "session-001",
        exerciseId: String = benchPress.id!!,
        exerciseName: String = benchPress.name,
        weightPerCableKg: Float = 25f,
        totalReps: Int = 10,
        workingReps: Int = 10,
        warmupReps: Int = 0,
        mode: String = "OldSchool",
        timestamp: Long = currentTimeMillis(),
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = totalReps,
        weightPerCableKg = weightPerCableKg,
        totalReps = totalReps,
        workingReps = workingReps,
        warmupReps = warmupReps,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
    )
}
