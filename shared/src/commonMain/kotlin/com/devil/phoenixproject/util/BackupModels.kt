package com.devil.phoenixproject.util

import kotlinx.serialization.Serializable

/**
 * Serializable backup data classes for export/import functionality.
 * These mirror the SQLDelight table structure but use kotlinx.serialization for JSON.
 *
 * Design rationale:
 * - Separate from SQLDelight generated classes for clean serialization
 * - Uses primitive types (String, Int, Long, Float, Boolean) for JSON compatibility
 * - Platform-agnostic - works on Android and iOS
 */

/**
 * Backup representation of WorkoutSession
 */
@Serializable
data class WorkoutSessionBackup(
    val id: String,
    val timestamp: Long,
    val mode: String,
    val targetReps: Int,
    val weightPerCableKg: Float,
    val progressionKg: Float,
    val duration: Long,
    val totalReps: Int,
    val warmupReps: Int,
    val workingReps: Int,
    val isJustLift: Boolean,
    val stopAtTop: Boolean,
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 1,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val routineSessionId: String? = null,
    val routineName: String? = null,
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
)

/**
 * Backup representation of MetricSample
 */
@Serializable
data class MetricSampleBackup(
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val position: Float?,
    val positionB: Float? = null,
    val velocity: Float?,
    val velocityB: Float? = null,
    val load: Float?,
    val loadB: Float? = null,
    val power: Float?,
    val status: Int = 0
)

/**
 * Backup representation of Routine
 */
@Serializable
data class RoutineBackup(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long,
    val lastUsed: Long? = null,
    val useCount: Int = 0
)

/**
 * Backup representation of RoutineExercise
 */
@Serializable
data class RoutineExerciseBackup(
    val id: String,
    val routineId: String,
    val exerciseName: String,
    val exerciseMuscleGroup: String,
    val exerciseEquipment: String = "",
    val exerciseDefaultCableConfig: String,
    val exerciseId: String? = null,
    val cableConfig: String,
    val orderIndex: Int,
    val setReps: String,
    val weightPerCableKg: Float,
    val setWeights: String = "",
    val mode: String = "OldSchool",
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 1,
    val progressionKg: Float = 0f,
    val restSeconds: Int = 60,
    val duration: Int? = null,
    val setRestSeconds: String = "[]",
    val perSetRestTime: Boolean = false,
    val isAMRAP: Boolean = false,
    // KMP extension: superset support (updated field names)
    val supersetId: String? = null,
    val orderInSuperset: Int = 0,
    // PR percentage scaling (Issue #57)
    val usePercentOfPR: Boolean = false,
    val weightPercentOfPR: Int = 80,
    val prTypeForScaling: String = "MAX_WEIGHT",
    val setWeightsPercentOfPR: String? = null  // JSON array as string
)

/**
 * Backup representation of PersonalRecord
 */
@Serializable
data class PersonalRecordBackup(
    val id: Long = 0,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Float,
    val reps: Int,
    val oneRepMax: Float,
    val achievedAt: Long,
    val workoutMode: String,
    val prType: String = "MAX_WEIGHT",
    val volume: Float = 0f
)

/**
 * Backup representation of TrainingCycle (KMP extension)
 */
@Serializable
data class TrainingCycleBackup(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long,
    val isActive: Boolean = false
)

/**
 * Backup representation of CycleDay (KMP extension)
 */
@Serializable
data class CycleDayBackup(
    val id: String,
    val cycleId: String,
    val dayNumber: Int,
    val name: String? = null,
    val routineId: String? = null,
    val isRestDay: Boolean = false
)

/**
 * Root backup data structure containing all exportable data
 */
@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: String,
    val appVersion: String,
    val data: BackupContent
)

/**
 * Container for all backup data entities
 */
@Serializable
data class BackupContent(
    val workoutSessions: List<WorkoutSessionBackup> = emptyList(),
    val metricSamples: List<MetricSampleBackup> = emptyList(),
    val routines: List<RoutineBackup> = emptyList(),
    val routineExercises: List<RoutineExerciseBackup> = emptyList(),
    val personalRecords: List<PersonalRecordBackup> = emptyList(),
    // KMP extensions
    val trainingCycles: List<TrainingCycleBackup> = emptyList(),
    val cycleDays: List<CycleDayBackup> = emptyList()
)

/**
 * Result of an import operation
 */
data class ImportResult(
    val sessionsImported: Int,
    val sessionsSkipped: Int,
    val metricsImported: Int,
    val routinesImported: Int,
    val routinesSkipped: Int,
    val routineExercisesImported: Int,
    val personalRecordsImported: Int,
    val personalRecordsSkipped: Int,
    val trainingCyclesImported: Int = 0,
    val trainingCyclesSkipped: Int = 0,
    val cycleDaysImported: Int = 0
) {
    val totalImported: Int
        get() = sessionsImported + metricsImported + routinesImported +
                routineExercisesImported + personalRecordsImported +
                trainingCyclesImported + cycleDaysImported

    val totalSkipped: Int
        get() = sessionsSkipped + routinesSkipped + personalRecordsSkipped +
                trainingCyclesSkipped
}
