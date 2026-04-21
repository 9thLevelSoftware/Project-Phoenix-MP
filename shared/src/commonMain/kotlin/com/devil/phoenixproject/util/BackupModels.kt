package com.devil.phoenixproject.util

import kotlinx.serialization.Serializable

/**
 * Sanitize eccentric load values to prevent machine faults.
 * Machine hardware limit is 150% - values above this cause yellow light faults.
 */
fun Int.sanitizeEccentricLoad(): Int = this.coerceIn(0, 150)

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
    val routineId: String? = null,
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
    val cableCount: Int? = null,
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null,
    // Biomechanics Summary (added in v0.5.0 Phase 13)
    val avgMcvMmS: Float? = null,
    val avgAsymmetryPercent: Float? = null,
    val totalVelocityLossPercent: Float? = null,
    val dominantSide: String? = null,
    val strengthProfile: String? = null,
    // Form Check score (added in v0.5.1 Phase 19 CV-06)
    val formScore: Long? = null,
    // Profile separation (profile data separation plan)
    val profileId: String? = null, // null for backward compat with pre-profile backups
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
    val status: Int = 0,
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
    val useCount: Int = 0,
    val profileId: String? = null, // null for backward compat with pre-profile backups
    val groupId: String? = null, // Routine group assignment (added v3)
)

/**
 * Backup representation of RoutineGroup (added in migration 27, v3 backup schema)
 */
@Serializable
data class RoutineGroupBackup(
    val id: String,
    val name: String,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val profileId: String = "default",
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
    val setEchoLevels: String = "",
    val perSetRestTime: Boolean = false,
    val isAMRAP: Boolean = false,
    // KMP extension: superset support (updated field names)
    val supersetId: String? = null,
    val orderInSuperset: Int = 0,
    // PR percentage scaling (Issue #57)
    val usePercentOfPR: Boolean = false,
    val weightPercentOfPR: Int = 80,
    val prTypeForScaling: String = "MAX_WEIGHT",
    val setWeightsPercentOfPR: String? = null, // JSON array as string
    // Per-exercise behavior overrides (PR #245)
    val stallDetectionEnabled: Boolean = true,
    val stopAtTop: Boolean = false,
    val repCountTiming: String = "TOP",
    // Variable warm-up sets (Phase 35C)
    val warmupSets: String = "",
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
    val volume: Float = 0f,
    val phase: String? = "COMBINED", // Nullable for backward compat with pre-v0.7.0 backups
    val profileId: String? = null, // null for backward compat with pre-profile backups
)

/**
 * Backup representation of Superset (groups exercises within a routine)
 */
@Serializable
data class SupersetBackup(
    val id: String,
    val routineId: String,
    val name: String,
    val colorIndex: Int = 0,
    val restBetweenSeconds: Int = 10,
    val orderIndex: Int = 0,
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
    val isActive: Boolean = false,
    val profileId: String? = null, // null for backward compat with pre-profile backups
)

/**
 * Backup representation of CycleDay (KMP extension)
 *
 * NOTE: Fields added in schema migrations 23+ are nullable here and default to null so
 * old (v1) backups continue to deserialize cleanly via kotlinx.serialization default values.
 */
@Serializable
data class CycleDayBackup(
    val id: String,
    val cycleId: String,
    val dayNumber: Int,
    val name: String? = null,
    val routineId: String? = null,
    val isRestDay: Boolean = false,
    // Per-day cycle progression overrides (added v2 backup schema)
    val echoLevel: String? = null,
    val eccentricLoadPercent: Int? = null,
    val weightProgressionPercent: Float? = null,
    val repModifier: Int? = null,
    val restTimeOverrideSeconds: Int? = null,
)

/**
 * Backup representation of UserProfile
 */
@Serializable
data class UserProfileBackup(val id: String, val name: String, val colorIndex: Int = 0, val createdAt: Long, val isActive: Boolean = false)

/**
 * Backup representation of CycleProgress (current position in training cycle)
 */
@Serializable
data class CycleProgressBackup(
    val id: String,
    val cycleId: String,
    val currentDayNumber: Int = 1,
    val lastCompletedDate: Long? = null,
    val cycleStartDate: Long,
    val lastAdvancedAt: Long? = null,
    val completedDays: String? = null,
    val missedDays: String? = null,
    val rotationCount: Int = 0,
)

/**
 * Backup representation of CycleProgression (auto-progression rules)
 */
@Serializable
data class CycleProgressionBackup(
    val cycleId: String,
    val frequencyCycles: Int = 2,
    val weightIncreasePercent: Float? = null,
    val echoLevelIncrease: Int = 0,
    val eccentricLoadIncreasePercent: Int? = null,
)

/**
 * Backup representation of PlannedSet
 */
@Serializable
data class PlannedSetBackup(
    val id: String,
    val routineExerciseId: String,
    val setNumber: Int,
    val setType: String = "STANDARD",
    val targetReps: Int? = null,
    val targetWeightKg: Float? = null,
    val targetRpe: Int? = null,
    val restSeconds: Int? = null,
)

/**
 * Backup representation of CompletedSet
 */
@Serializable
data class CompletedSetBackup(
    val id: String,
    val sessionId: String,
    val plannedSetId: String? = null,
    val setNumber: Int,
    val setType: String = "STANDARD",
    val actualReps: Int,
    val actualWeightKg: Float,
    val loggedRpe: Int? = null,
    val isPr: Boolean = false,
    val completedAt: Long,
)

/**
 * Backup representation of ProgressionEvent
 */
@Serializable
data class ProgressionEventBackup(
    val id: String,
    val exerciseId: String,
    val suggestedWeightKg: Float,
    val previousWeightKg: Float,
    val reason: String,
    val userResponse: String? = null,
    val actualWeightKg: Float? = null,
    val timestamp: Long,
    val profileId: String? = null, // null for backward compat with pre-profile backups
)

/**
 * Backup representation of EarnedBadge
 *
 * Sync fields (updatedAt, serverId, deletedAt) are preserved across backup round-trips
 * so a restored badge does not immediately re-push to the portal as a "new" badge.
 */
@Serializable
data class EarnedBadgeBackup(
    val id: Long = 0,
    val badgeId: String,
    val earnedAt: Long,
    val celebratedAt: Long? = null,
    val profileId: String = "default",
    // Sync fields (added v2 backup schema)
    val updatedAt: Long? = null,
    val serverId: String? = null,
    val deletedAt: Long? = null,
)

/**
 * Backup representation of StreakHistory
 */
@Serializable
data class StreakHistoryBackup(val id: Long = 0, val startDate: Long, val endDate: Long, val length: Int, val profileId: String = "default")

/**
 * Backup representation of GamificationStats
 */
@Serializable
data class GamificationStatsBackup(
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Int = 0,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val uniqueExercisesUsed: Int = 0,
    val prsAchieved: Int = 0,
    val lastWorkoutDate: Long? = null,
    val streakStartDate: Long? = null,
    val lastUpdated: Long,
    val profileId: String = "default",
    // Sync fields (added v2 backup schema) — preserve so restored row does not re-push
    val updatedAt: Long? = null,
    val serverId: String? = null,
)

/**
 * Backup representation of SessionNotes (added in migration 26)
 *
 * Portal-level notes attached to a routine session. Stored in its own table because
 * one portal session expands into N mobile WorkoutSession rows (keyed by
 * routineSessionId) and duplicating notes across rows would invite drift.
 */
@Serializable
data class SessionNotesBackup(
    val routineSessionId: String,
    val notes: String? = null,
    val updatedAt: Long? = null,
)

/**
 * Progress tracking for streaming backup export
 */
data class BackupProgress(val phase: BackupPhase, val current: Long, val total: Long)

/**
 * Statistics about session auto-backup files on disk.
 */
data class BackupStats(val fileCount: Int, val totalBytes: Long) {
    /**
     * Human-readable total size using binary units (e.g. "12.3 MB", "456 KB").
     * Uses 1024-based divisions to match platform file manager conventions.
     */
    val formattedSize: String
        get() = when {
            totalBytes >= 1_048_576 -> { // 1024 * 1024
                val tenths = (totalBytes * 10 / 1_048_576)
                "${tenths / 10}.${tenths % 10} MB"
            }

            totalBytes >= 1_024 -> {
                val tenths = (totalBytes * 10 / 1_024)
                "${tenths / 10}.${tenths % 10} KB"
            }

            else -> "$totalBytes B"
        }
}

/**
 * Phases of the streaming backup export process
 */
enum class BackupPhase(val displayName: String) {
    COUNTING("Calculating size..."),
    SESSIONS("Exporting sessions"),
    METRICS("Exporting metrics"),
    ROUTINES("Exporting routines"),
    OTHER("Exporting remaining data"),
    FINALIZING("Finalizing backup"),
}

/**
 * Root backup data structure containing all exportable data.
 *
 * Schema versions:
 * - v1: initial backup format (pre-2026-04-19)
 * - v2: adds SessionNotes, EarnedBadge/GamificationStats sync fields, CycleDay per-day
 *       progression overrides. Older (v1) backups remain importable — new fields default
 *       to null via kotlinx.serialization default values.
 * - v3: adds RoutineGroup table and Routine.groupId field for routine grouping.
 */
@Serializable
data class BackupData(val version: Int = CURRENT_BACKUP_VERSION, val exportedAt: String, val appVersion: String, val data: BackupContent)

/**
 * Highest backup schema version this build can produce.
 * Bump whenever BackupContent gains/loses entities or a backup field type changes.
 */
const val CURRENT_BACKUP_VERSION: Int = 3

/**
 * Container for all backup data entities
 */
@Serializable
data class BackupContent(
    val workoutSessions: List<WorkoutSessionBackup> = emptyList(),
    val metricSamples: List<MetricSampleBackup> = emptyList(),
    val routines: List<RoutineBackup> = emptyList(),
    val routineExercises: List<RoutineExerciseBackup> = emptyList(),
    val supersets: List<SupersetBackup> = emptyList(),
    val personalRecords: List<PersonalRecordBackup> = emptyList(),
    // KMP extensions
    val trainingCycles: List<TrainingCycleBackup> = emptyList(),
    val cycleDays: List<CycleDayBackup> = emptyList(),
    val cycleProgress: List<CycleProgressBackup> = emptyList(),
    val cycleProgressions: List<CycleProgressionBackup> = emptyList(),
    val plannedSets: List<PlannedSetBackup> = emptyList(),
    val completedSets: List<CompletedSetBackup> = emptyList(),
    val progressionEvents: List<ProgressionEventBackup> = emptyList(),
    val earnedBadges: List<EarnedBadgeBackup> = emptyList(),
    val streakHistory: List<StreakHistoryBackup> = emptyList(),
    val gamificationStats: GamificationStatsBackup? = null,
    val userProfiles: List<UserProfileBackup> = emptyList(),
    // Added v2: portal session notes (migration 26)
    val sessionNotes: List<SessionNotesBackup> = emptyList(),
    // Added v3: routine groups (migration 27)
    val routineGroups: List<RoutineGroupBackup> = emptyList(),
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
    val supersetsImported: Int = 0,
    val supersetsSkipped: Int = 0,
    val personalRecordsImported: Int,
    val personalRecordsSkipped: Int,
    val trainingCyclesImported: Int = 0,
    val trainingCyclesSkipped: Int = 0,
    val cycleDaysImported: Int = 0,
    val cycleProgressImported: Int = 0,
    val cycleProgressionsImported: Int = 0,
    val plannedSetsImported: Int = 0,
    val completedSetsImported: Int = 0,
    val progressionEventsImported: Int = 0,
    val earnedBadgesImported: Int = 0,
    val streakHistoryImported: Int = 0,
    val gamificationStatsImported: Boolean = false,
    val userProfilesImported: Int = 0,
    val userProfilesSkipped: Int = 0,
    val sessionNotesImported: Int = 0,
    val sessionNotesSkipped: Int = 0,
    val routineGroupsImported: Int = 0,
    val routineGroupsSkipped: Int = 0,
    /**
     * Count of individual entity rows that threw during import and were skipped.
     * Non-zero here means the backup contained malformed rows — the import still
     * succeeded overall, but data was partially dropped. Surfacing this lets the UI
     * warn users rather than silently swallow corruption.
     */
    val entitiesWithErrors: Int = 0,
) {
    val totalImported: Int
        get() = sessionsImported + metricsImported + routinesImported +
            routineExercisesImported + supersetsImported + personalRecordsImported +
            trainingCyclesImported + cycleDaysImported + cycleProgressImported +
            cycleProgressionsImported + plannedSetsImported + completedSetsImported +
            progressionEventsImported + earnedBadgesImported + streakHistoryImported +
            (if (gamificationStatsImported) 1 else 0) + userProfilesImported +
            sessionNotesImported + routineGroupsImported

    val totalSkipped: Int
        get() = sessionsSkipped + routinesSkipped + supersetsSkipped + personalRecordsSkipped +
            trainingCyclesSkipped + userProfilesSkipped + sessionNotesSkipped +
            routineGroupsSkipped
}
