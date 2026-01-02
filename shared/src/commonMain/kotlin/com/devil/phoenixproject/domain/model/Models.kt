package com.devil.phoenixproject.domain.model

/**
 * Vitruvian Hardware Model
 */
enum class VitruvianModel(val displayName: String) {
    VFormTrainer("V-Form Trainer"),
    TrainerPlus("Trainer+"),
    Unknown("Unknown Vitruvian Device")
}

/**
 * PR Type - distinguishes between weight-based and volume-based records
 */
enum class PRType {
    MAX_WEIGHT,  // Highest weight in a single rep (strength PR)
    MAX_VOLUME   // Highest weight × reps in a single set (volume PR)
}

/**
 * Personal record for an exercise
 */
data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: String,
    val exerciseName: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val oneRepMax: Float,
    val timestamp: Long,
    val workoutMode: String,
    val prType: PRType = PRType.MAX_WEIGHT,
    val volume: Float
)

/**
 * Connection state sealed class representing BLE connection states
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(
        val deviceName: String,
        val deviceAddress: String,
        val hardwareModel: VitruvianModel = VitruvianModel.Unknown
    ) : ConnectionState()
    data class Error(val message: String, val throwable: Throwable? = null) : ConnectionState()
}

/**
 * Workout state sealed class representing workout execution states
 */
sealed class WorkoutState {
    object Idle : WorkoutState()
    object Initializing : WorkoutState()
    data class Countdown(val secondsRemaining: Int) : WorkoutState()
    object Active : WorkoutState()
    data class SetSummary(
        val metrics: List<WorkoutMetric>,
        val peakPower: Float,
        val averagePower: Float,
        val repCount: Int,
        val durationMs: Long = 0L,
        val totalVolumeKg: Float = 0f,
        val heaviestLiftKgPerCable: Float = 0f,
        val peakForceConcentricA: Float = 0f,  // Peak during lifting (velocity > 0)
        val peakForceConcentricB: Float = 0f,
        val peakForceEccentricA: Float = 0f,   // Peak during lowering (velocity < 0)
        val peakForceEccentricB: Float = 0f,
        val avgForceConcentricA: Float = 0f,
        val avgForceConcentricB: Float = 0f,
        val avgForceEccentricA: Float = 0f,
        val avgForceEccentricB: Float = 0f,
        val estimatedCalories: Float = 0f,
        // Echo Mode Phase-Aware Metrics
        val isEchoMode: Boolean = false,
        val warmupReps: Int = 0,
        val workingReps: Int = 0,
        val burnoutReps: Int = 0,
        val warmupAvgWeightKg: Float = 0f,  // Average weight during warmup phase
        val workingAvgWeightKg: Float = 0f,  // Average weight at peak (working phase)
        val burnoutAvgWeightKg: Float = 0f,  // Average weight during burnout/eccentric phase
        val peakWeightKg: Float = 0f  // Highest weight achieved during set
    ) : WorkoutState()
    object Paused : WorkoutState()
    object Completed : WorkoutState()
    object ExerciseComplete : WorkoutState()
    object RoutineComplete : WorkoutState()
    data class Error(val message: String) : WorkoutState()
    data class Resting(
        val restSecondsRemaining: Int,
        val nextExerciseName: String,
        val isLastExercise: Boolean,
        val currentSet: Int,
        val totalSets: Int,
        val isSupersetTransition: Boolean = false,
        val supersetLabel: String? = null
    ) : WorkoutState()
}

/**
 * Program modes that use command 0x4F (96-byte frame)
 * Note: Official app uses 0x4F, NOT 0x04
 */
sealed class ProgramMode(val modeValue: Int, val displayName: String) {
    object OldSchool : ProgramMode(0, "Old School")
    object Pump : ProgramMode(2, "Pump")
    object TUT : ProgramMode(3, "TUT")
    object TUTBeast : ProgramMode(4, "TUT Beast")
    object EccentricOnly : ProgramMode(6, "Eccentric Only")

    companion object {
        @Suppress("unused")
        fun fromValue(value: Int): ProgramMode = when(value) {
            0 -> OldSchool
            2 -> Pump
            3 -> TUT
            4 -> TUTBeast
            6 -> EccentricOnly
            else -> OldSchool
        }
    }
}

/**
 * WorkoutMode - Legacy sealed class for UI compatibility
 * Maps to WorkoutType for protocol usage
 */
sealed class WorkoutMode(val displayName: String) {
    object OldSchool : WorkoutMode("Old School")
    object Pump : WorkoutMode("Pump")
    object TUT : WorkoutMode("TUT")
    object TUTBeast : WorkoutMode("TUT Beast")
    object EccentricOnly : WorkoutMode("Eccentric Only")
    data class Echo(val level: EchoLevel) : WorkoutMode("Echo")

    /**
     * Convert WorkoutMode to WorkoutType
     */
    fun toWorkoutType(eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100): WorkoutType = when (this) {
        is OldSchool -> WorkoutType.Program(ProgramMode.OldSchool)
        is Pump -> WorkoutType.Program(ProgramMode.Pump)
        is TUT -> WorkoutType.Program(ProgramMode.TUT)
        is TUTBeast -> WorkoutType.Program(ProgramMode.TUTBeast)
        is EccentricOnly -> WorkoutType.Program(ProgramMode.EccentricOnly)
        is Echo -> WorkoutType.Echo(level, eccentricLoad)
    }
}

/**
 * Workout type - either Program (0x04) or Echo (0x4E)
 */
sealed class WorkoutType {
    data class Program(val mode: ProgramMode) : WorkoutType()
    data class Echo(val level: EchoLevel, val eccentricLoad: EccentricLoad) : WorkoutType()

    val displayName: String get() = when (this) {
        is Program -> mode.displayName
        is Echo -> "Echo"
    }

    @Suppress("unused")
    val modeValue: Int get() = when (this) {
        is Program -> mode.modeValue
        is Echo -> 10
    }

    /**
     * Convert WorkoutType to WorkoutMode for UI compatibility
     */
    fun toWorkoutMode(): WorkoutMode = when (this) {
        is Program -> when (mode) {
            ProgramMode.OldSchool -> WorkoutMode.OldSchool
            ProgramMode.Pump -> WorkoutMode.Pump
            ProgramMode.TUT -> WorkoutMode.TUT
            ProgramMode.TUTBeast -> WorkoutMode.TUTBeast
            ProgramMode.EccentricOnly -> WorkoutMode.EccentricOnly
        }
        is Echo -> WorkoutMode.Echo(level)
    }
}

/**
 * Echo mode difficulty levels
 */
enum class EchoLevel(val levelValue: Int, val displayName: String) {
    HARD(0, "Hard"),
    HARDER(1, "Harder"),
    HARDEST(2, "Hardest"),
    EPIC(3, "Epic")
}

/**
 * Eccentric load percentage for Echo mode
 * Machine hardware limit: 150% maximum
 */
enum class EccentricLoad(val percentage: Int, val displayName: String) {
    LOAD_0(0, "0%"),
    LOAD_50(50, "50%"),
    LOAD_75(75, "75%"),
    LOAD_100(100, "100%"),
    LOAD_110(110, "110%"),
    LOAD_120(120, "120%"),
    LOAD_130(130, "130%"),
    LOAD_140(140, "140%"),
    LOAD_150(150, "150%")
}

/**
 * Weight unit preference
 */
enum class WeightUnit {
    KG, LB
}

/**
 * Workout parameters
 */
data class WorkoutParameters(
    val workoutType: WorkoutType,
    val reps: Int,
    val weightPerCableKg: Float = 0f,  // Only used for Program modes
    val progressionRegressionKg: Float = 0f,  // Only used for Program modes (not TUT/TUTBeast)
    val isJustLift: Boolean = false,
    val useAutoStart: Boolean = false, // true for Just Lift, false for others
    val stopAtTop: Boolean = false,  // false = stop at bottom (extended), true = stop at top (contracted)
    val warmupReps: Int = 3,
    val selectedExerciseId: String? = null,
    val isAMRAP: Boolean = false,  // AMRAP (As Many Reps As Possible) - disables auto-stop
    val lastUsedWeightKg: Float? = null,  // Last used weight for this exercise (for quick preset)
    val prWeightKg: Float? = null,  // Personal record weight for this exercise (for quick preset)
    val stallDetectionEnabled: Boolean = true  // Enable stall detection auto-stop for Just Lift/AMRAP modes
)

/**
 * Real-time workout metric data from the device
 *
 * Position values are in millimeters (mm), range -1000.0 to +1000.0
 * Raw BLE values are scaled by dividing by 10.0f (Issue #197)
 */
data class WorkoutMetric(
    val timestamp: Long = currentTimeMillis(),
    val loadA: Float,
    val loadB: Float,
    val positionA: Float,  // Position in mm (changed from Int in Issue #197)
    val positionB: Float,  // Position in mm (changed from Int in Issue #197)
    val ticks: Int = 0,
    val velocityA: Double = 0.0,  // Velocity for handle detection (official app protocol)
    val velocityB: Double = 0.0,   // Velocity for right handle detection (for single-handle exercises)
    val status: Int = 0 // Machine status flags (0x8000=Deload Occurred, 0x0040=Deload Warn)
) {
    val totalLoad: Float get() = loadA + loadB
}

/**
 * Rep count tracking
 */
data class RepCount(
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    val totalReps: Int = workingReps,  // Exclude warm-up reps from total count
    val isWarmupComplete: Boolean = false,
    val hasPendingRep: Boolean = false,  // True when at TOP (concentric peak), waiting for eccentric
    val pendingRepProgress: Float = 0f   // 0.0 at TOP, 1.0 at BOTTOM (fill progress)
)

/**
 * Rep event types
 */
enum class RepType {
    WARMUP_COMPLETED,  // Warmup rep done (no pending animation for warmup)
    WORKING_PENDING,   // At TOP during working - show grey number, waiting for eccentric
    WORKING_COMPLETED, // At BOTTOM during working - rep confirmed (colored)
    WARMUP_COMPLETE,   // All warmup reps done
    WORKOUT_COMPLETE   // All working reps done
}

/**
 * Rep event data
 */
data class RepEvent(
    val type: RepType,
    val warmupCount: Int,
    val workingCount: Int,
    val timestamp: Long = currentTimeMillis()
)

/**
 * Haptic feedback event types for workout notifications
 *
 * Implemented as a sealed class to support parameterized variants (REP_COUNT_ANNOUNCED).
 */
sealed class HapticEvent {
    /** Light haptic + beep sound */
    data object REP_COMPLETED : HapticEvent()

    /** Audio rep count announcement (1-25) - no haptic, just spoken number */
    data class REP_COUNT_ANNOUNCED(val repNumber: Int) : HapticEvent() {
        init {
            require(repNumber in 1..25) { "Rep number must be between 1 and 25" }
        }
    }

    /** Strong haptic + beepboop sound */
    data object WARMUP_COMPLETE : HapticEvent()

    /** Strong haptic + boopbeepbeep sound */
    data object WORKOUT_COMPLETE : HapticEvent()

    /** Light haptic + chirpchirp sound */
    data object WORKOUT_START : HapticEvent()

    /** Light haptic + chirpchirp sound */
    data object WORKOUT_END : HapticEvent()

    /** Strong haptic + restover sound (5 seconds left in rest timer) */
    data object REST_ENDING : HapticEvent()

    /** Strong haptic (no sound) */
    data object ERROR : HapticEvent()

    /** Easter egg celebration sound */
    data object DISCO_MODE_UNLOCKED : HapticEvent()

    /** Strong haptic + random badge celebration sound */
    data object BADGE_EARNED : HapticEvent()

    /** Strong haptic + random PR celebration sound */
    data object PERSONAL_RECORD : HapticEvent()
}

/**
 * Workout session data (simplified for database storage)
 */
data class WorkoutSession(
    val id: String = generateUUID(),
    val timestamp: Long = currentTimeMillis(),
    val mode: String = "OldSchool",
    val reps: Int = 10,
    val weightPerCableKg: Float = 10f,
    val progressionKg: Float = 0f,
    val duration: Long = 0,
    val totalReps: Int = 0,
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    val isJustLift: Boolean = false,
    val stopAtTop: Boolean = false,
    // Echo mode configuration
    val eccentricLoad: Int = 100,  // Percentage (0, 50, 75, 100, 125, 150)
    val echoLevel: Int = 2,  // 1=Hard, 2=Harder, 3=Hardest, 4=Epic
    // Exercise tracking
    val exerciseId: String? = null,  // Exercise library ID for PR tracking
    val exerciseName: String? = null,  // Exercise name for display (avoids DB lookups)
    // Routine tracking (for grouping sets from the same routine)
    val routineSessionId: String? = null,  // Unique ID for this routine session
    val routineName: String? = null,  // Name of the routine being performed
    // Safety tracking (parity with parent repo v23)
    val safetyFlags: Int = 0,
    val deloadWarningCount: Int = 0,
    val romViolationCount: Int = 0,
    val spotterActivations: Int = 0,
    // Set Summary Metrics (added in v0.2.1)
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null
) {
    /** True if this session has detailed summary metrics (v0.2.1+) */
    val hasSummaryMetrics: Boolean
        get() = peakForceConcentricA != null || peakForceConcentricB != null
}

/**
 * Convert WorkoutSession to SetSummary for display in history.
 * Returns null if session doesn't have summary metrics (pre-v0.2.1).
 */
fun WorkoutSession.toSetSummary(): WorkoutState.SetSummary? {
    if (!hasSummaryMetrics) return null

    return WorkoutState.SetSummary(
        metrics = emptyList(),
        peakPower = 0f,
        averagePower = 0f,
        repCount = totalReps,
        durationMs = duration,
        totalVolumeKg = totalVolumeKg ?: 0f,
        heaviestLiftKgPerCable = heaviestLiftKg ?: 0f,
        peakForceConcentricA = peakForceConcentricA ?: 0f,
        peakForceConcentricB = peakForceConcentricB ?: 0f,
        peakForceEccentricA = peakForceEccentricA ?: 0f,
        peakForceEccentricB = peakForceEccentricB ?: 0f,
        avgForceConcentricA = avgForceConcentricA ?: 0f,
        avgForceConcentricB = avgForceConcentricB ?: 0f,
        avgForceEccentricA = avgForceEccentricA ?: 0f,
        avgForceEccentricB = avgForceEccentricB ?: 0f,
        estimatedCalories = estimatedCalories ?: 0f,
        isEchoMode = mode.contains("Echo", ignoreCase = true),
        warmupReps = warmupReps,
        workingReps = workingReps,
        burnoutReps = (totalReps - warmupReps - workingReps).coerceAtLeast(0),
        warmupAvgWeightKg = warmupAvgWeightKg ?: 0f,
        workingAvgWeightKg = workingAvgWeightKg ?: 0f,
        burnoutAvgWeightKg = burnoutAvgWeightKg ?: 0f,
        peakWeightKg = peakWeightKg ?: 0f
    )
}

expect fun generateUUID(): String

/**
 * Chart data point for visualization
 * Position values are in millimeters (mm), range -1000.0 to +1000.0 (Issue #197)
 */
@Suppress("unused")
data class ChartDataPoint(
    val timestamp: Long,
    val totalLoad: Float,
    val loadA: Float,
    val loadB: Float,
    val positionA: Float,  // Position in mm (changed from Int in Issue #197)
    val positionB: Float   // Position in mm (changed from Int in Issue #197)
)

/**
 * Chart event markers
 */
sealed class ChartEvent(val timestamp: Long, val label: String) {
    @Suppress("unused")
    class RepStart(timestamp: Long, repNumber: Int) : ChartEvent(timestamp, "Rep $repNumber")
    @Suppress("unused")
    class RepComplete(timestamp: Long, repNumber: Int) : ChartEvent(timestamp, "Rep $repNumber Complete")
    @Suppress("unused")
    class WarmupComplete(timestamp: Long) : ChartEvent(timestamp, "Warmup Complete")
}

/**
 * PR Celebration Event - Triggered when user achieves a new Personal Record
 */
data class PRCelebrationEvent(
    val exerciseName: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val workoutMode: String,
    val brokenPRTypes: List<PRType> = listOf(PRType.MAX_WEIGHT)
) {
    val isWeightPR: Boolean get() = PRType.MAX_WEIGHT in brokenPRTypes
    val isVolumePR: Boolean get() = PRType.MAX_VOLUME in brokenPRTypes
    val isBothPRs: Boolean get() = brokenPRTypes.size == 2
}
