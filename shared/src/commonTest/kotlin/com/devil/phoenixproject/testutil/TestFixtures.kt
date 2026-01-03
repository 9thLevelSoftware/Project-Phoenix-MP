package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutType

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
        defaultCableConfig = CableConfiguration.DOUBLE,
        id = "bench-press-001",
        isFavorite = true
    )

    val bicepCurl = Exercise(
        name = "Bicep Curl",
        muscleGroup = "Biceps",
        muscleGroups = "Biceps",
        equipment = "SINGLE_HANDLE",
        defaultCableConfig = CableConfiguration.EITHER,
        id = "bicep-curl-001"
    )

    val squat = Exercise(
        name = "Squat",
        muscleGroup = "Legs",
        muscleGroups = "Legs,Glutes,Core",
        equipment = "BAR",
        defaultCableConfig = CableConfiguration.DOUBLE,
        id = "squat-001"
    )

    val deadlift = Exercise(
        name = "Deadlift",
        muscleGroup = "Back",
        muscleGroups = "Back,Legs,Glutes",
        equipment = "BAR",
        defaultCableConfig = CableConfiguration.DOUBLE,
        id = "deadlift-001"
    )

    val singleArmRow = Exercise(
        name = "Single Arm Row",
        muscleGroup = "Back",
        muscleGroups = "Back,Biceps",
        equipment = "SINGLE_HANDLE",
        defaultCableConfig = CableConfiguration.SINGLE,
        id = "single-arm-row-001"
    )

    val customExercise = Exercise(
        name = "Custom Exercise",
        muscleGroup = "Full Body",
        muscleGroups = "Full Body",
        equipment = "",
        defaultCableConfig = CableConfiguration.DOUBLE,
        id = "custom-001",
        isCustom = true
    )

    val allExercises = listOf(benchPress, bicepCurl, squat, deadlift, singleArmRow, customExercise)

    // ========== Workout Parameters ==========

    val oldSchoolParams = WorkoutParameters(
        workoutType = WorkoutType.Program(ProgramMode.OldSchool),
        reps = 10,
        weightPerCableKg = 25f,
        progressionRegressionKg = 0f,
        selectedExerciseId = benchPress.id
    )

    val echoParams = WorkoutParameters(
        workoutType = WorkoutType.Echo(EchoLevel.HARDER, EccentricLoad.LOAD_120),
        reps = 8,
        weightPerCableKg = 0f, // Echo mode doesn't use weight setting
        selectedExerciseId = squat.id
    )

    val justLiftParams = WorkoutParameters(
        workoutType = WorkoutType.Program(ProgramMode.OldSchool),
        reps = 0, // AMRAP
        weightPerCableKg = 30f,
        isJustLift = true,
        useAutoStart = true,
        isAMRAP = true,
        selectedExerciseId = deadlift.id
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
        timestamp: Long = System.currentTimeMillis()
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
        exerciseName = exerciseName
    )

    val sampleSession = createWorkoutSession()

    // ========== Personal Records ==========

    fun createPersonalRecord(
        exerciseId: String = benchPress.id!!,
        exerciseName: String = benchPress.name,
        weightPerCableKg: Float = 50f,
        reps: Int = 5,
        prType: PRType = PRType.MAX_WEIGHT,
        timestamp: Long = System.currentTimeMillis()
    ) = PersonalRecord(
        id = 0,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        weightPerCableKg = weightPerCableKg,
        reps = reps,
        oneRepMax = calculateOneRepMax(weightPerCableKg * 2, reps), // Total weight
        timestamp = timestamp,
        workoutMode = "OldSchool",
        prType = prType,
        volume = weightPerCableKg * 2 * reps
    )

    val samplePR = createPersonalRecord()

    // ========== Workout Metrics ==========

    fun createWorkoutMetric(
        loadA: Float = 25f,
        loadB: Float = 25f,
        positionA: Float = 500f,
        positionB: Float = 500f,
        velocityA: Double = 0.0,
        velocityB: Double = 0.0,
        timestamp: Long = System.currentTimeMillis()
    ) = WorkoutMetric(
        timestamp = timestamp,
        loadA = loadA,
        loadB = loadB,
        positionA = positionA,
        positionB = positionB,
        velocityA = velocityA,
        velocityB = velocityB
    )

    /**
     * Create a sequence of metrics simulating a rep.
     * Position goes from bottom (0) to top (800) and back.
     */
    fun createRepMetrics(
        repNumber: Int = 1,
        loadKg: Float = 25f,
        baseTimestamp: Long = System.currentTimeMillis()
    ): List<WorkoutMetric> {
        val metrics = mutableListOf<WorkoutMetric>()
        val positions = listOf(0f, 200f, 400f, 600f, 800f, 600f, 400f, 200f, 0f)

        positions.forEachIndexed { index, position ->
            metrics.add(
                createWorkoutMetric(
                    loadA = loadKg,
                    loadB = loadKg,
                    positionA = position,
                    positionB = position,
                    velocityA = if (index < 5) 100.0 else -100.0,
                    velocityB = if (index < 5) 100.0 else -100.0,
                    timestamp = baseTimestamp + (index * 100L)
                )
            )
        }

        return metrics
    }

    // ========== Rep Notifications ==========

    fun createRepNotification(
        topCounter: Int = 1,
        completeCounter: Int = 1,
        repsRomCount: Int = 0,
        repsSetCount: Int = 1,
        timestamp: Long = System.currentTimeMillis()
    ) = RepNotification(
        topCounter = topCounter,
        completeCounter = completeCounter,
        repsRomCount = repsRomCount,
        repsSetCount = repsSetCount,
        rangeTop = 800f,
        rangeBottom = 0f,
        rawData = ByteArray(24),
        timestamp = timestamp,
        isLegacyFormat = false
    )

    // ========== BLE Devices ==========

    val vFormDevice = ScannedDevice(
        name = "Vee_ABC123",
        address = "AA:BB:CC:DD:EE:01",
        rssi = -50
    )

    val trainerPlusDevice = ScannedDevice(
        name = "VIT_XYZ789",
        address = "AA:BB:CC:DD:EE:02",
        rssi = -60
    )

    // ========== Helper Functions ==========

    /**
     * Calculate one-rep max using Brzycki formula.
     * 1RM = weight × (36 / (37 - reps))
     */
    private fun calculateOneRepMax(weight: Float, reps: Int): Float {
        if (reps <= 0) return weight
        if (reps >= 37) return weight * 2 // Cap at reasonable value
        return weight * (36f / (37f - reps))
    }
}
