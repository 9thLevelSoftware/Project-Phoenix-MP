package com.devil.phoenixproject.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Sanitize eccentric load values to prevent machine faults.
 * Machine hardware limit is 150% - values above this cause yellow light faults.
 */
fun Int.sanitizeEccentricLoad(): Int = this.coerceIn(0, 150)

/** Clamp an imported per-rep progression to the machine's command limit (defense in depth next to the command path). */
fun Float.sanitizeProgressionKg(): Float =
    if (isFinite()) coerceIn(-CommandLimits.MAX_PROGRESSION_KG, CommandLimits.MAX_PROGRESSION_KG) else 0f

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
    val displayMultiplier: Int? = null,
    val externalAddedLoadKg: Float = 0f,
    val counterweightKg: Float = 0f,
    val rackItemsJson: String = "[]",
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
    // Added v7: sync stamp and portal-origin marker. A pulled row restores as pulled so it is
    // never re-pushed; a local row keeps its stamp so the portal's LWW sees its real age.
    val updatedAt: Long? = null,
    val portalOrigin: Boolean = false,
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
    val deletedAt: Long? = null, // Preserve routine tombstones for telemetry FK integrity
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
    // Local equipment rack defaults (backup schema v4)
    val defaultRackItemIds: List<String> = emptyList(),
    // Per-exercise rack behavior overrides (Issues #521/#526)
    val rackBehaviorOverrides: String = "{}",
    // Velocity-based 1RM scaling basis (issue #517 Phase 3); enum name or null.
    // Default null keeps pre-existing backup files loadable.
    val scalingBasis: String? = null,
    // Explicit bodyweight classification (issue #635); null = derive from equipment.
    // Default null keeps pre-existing backup files loadable.
    val isBodyweight: Boolean? = null,
    val dropSetEnabled: Boolean = false,
    val dropSetMinWeightKg: Float? = null,
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
    val cableCount: Int? = null, // null for backward compat with pre-v0.9.0 backups
    val uuid: String? = null, // null for backward compat with pre-#634 backups
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
    val templateId: String? = null,
    val weekNumber: Int = 1,
    /** Portal cycle version (verbatim ISO) so a restored cycle keeps its sync base. Null in older backups. */
    val serverUpdatedAt: String? = null,
    val deletedAt: Long? = null,
    // Durable sync clocks added in backup v6. Null keeps v1-v5 readable.
    val updatedAt: Long? = null,
)

@Serializable
data class CycleSyncStateBackup(
    val cycleId: String,
    val profileId: String,
    val accountId: String? = null,
    val dirtyGeneration: Long,
    val acknowledgedGeneration: Long,
    val pendingDeleteUpdatedAt: Long? = null,
    val pendingDeleteGeneration: Long? = null,
)

/** Full user-created exercise row. Stock catalog rows remain supplied by the app. */
@Serializable
data class CustomExerciseBackup(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val created: Long = 0,
    val muscleGroup: String,
    val muscleGroups: String = muscleGroup,
    val muscles: String? = null,
    val equipment: String = "",
    val movement: String? = null,
    val sidedness: String? = null,
    val grip: String? = null,
    val gripWidth: String? = null,
    val minRepRange: Float? = null,
    val popularity: Float = 0f,
    val archived: Boolean = false,
    val isFavorite: Boolean = false,
    val timesPerformed: Int = 0,
    val lastPerformed: Long? = null,
    val aliases: String? = null,
    val defaultCableConfig: String = "DOUBLE",
    /** Legacy recovery source only; profileExerciseBaselines is authoritative in v6. */
    val legacyOneRepMaxKg: Float? = null,
    val updatedAt: Long? = null,
    val serverId: String? = null,
    val deletedAt: Long? = null,
    val mvtOverrideMs: Float? = null,
    val isBodyweight: Boolean? = null,
)

/** Explicit profile-scoped 1RM state. A null value is a meaningful cleared baseline. */
@Serializable
data class ProfileExerciseBaselineBackup(
    val profileId: String,
    val exerciseId: String,
    val oneRepMaxPerCableKg: Float? = null,
    val updatedAt: Long,
    val revision: Long,
)

/**
 * User-owned fields on a stock catalogue exercise (added v7). The catalogue itself ships with
 * the app; only favourites, the MVT override and the legacy 1RM input are user state.
 */
@Serializable
data class StockExerciseUserFieldsBackup(
    val exerciseId: String,
    val isFavorite: Boolean = false,
    val mvtOverrideMs: Float? = null,
    /** Legacy recovery source only, like [CustomExerciseBackup.legacyOneRepMaxKg]. */
    val legacyOneRepMaxKg: Float? = null,
)

@Serializable
data class WorkoutDeletionBackup(
    val mutationId: String,
    val ownerUserId: String? = null,
    val profileId: String,
    val scope: String,
    val portalSessionId: String,
    val componentSessionId: String? = null,
    val deletedAt: Long,
    val acknowledgedAt: Long? = null,
    val source: String,
)

@Serializable
data class PendingProfileRecoveryBackup(
    val recoveryId: String,
    val kind: String,
    val sourceKey: String,
    val sourceProfileId: String? = null,
    val sourceProfileName: String,
    val ownerUserId: String? = null,
    val countsJson: String,
    val discoveredAt: Long,
    val resolvedAt: Long? = null,
)

@Serializable
data class OwnershipTransferBackup(
    val mutationId: String,
    val ownerUserId: String,
    val sourceProfileId: String? = null,
    val targetProfileId: String,
    val workoutSessionIdsJson: String,
    val routineIdsJson: String,
    val cycleIdsJson: String,
    val personalRecordIdsJson: String,
    val createdAt: Long,
    val acknowledgedAt: Long? = null,
)

@Serializable
data class AppliedOwnershipEventBackup(
    val ownerUserId: String,
    val mutationId: String,
    val canonicalBodyHash: String,
    val appliedAt: Long,
)

/** Retained account ownership for an entity whose transfer may arrive before its live row. */
@Serializable
data class LocalOwnershipClaimBackup(
    val ownerUserId: String,
    val entityType: String,
    val entityId: String,
    val mutationId: String,
    val sourceProfileId: String? = null,
    val targetProfileId: String,
    val transferredAt: Long,
)

/** Opaque cycle graph retained when a server rejection would otherwise overwrite local work. */
@Serializable
data class CycleConflictDraftBackup(
    val id: String,
    val cycleId: String,
    val originalProfileId: String,
    val rejectedUpdatedAt: Long,
    val payloadJson: String,
    val createdAt: Long,
    val resolution: String? = null,
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
data class UserProfileBackup(
    val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long,
    val isActive: Boolean = false,
    /** Stable cloud account identity only. Auth credentials and session state are never exported. */
    val supabaseUserId: String? = null,
)

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
    val setEndReason: String = "UNKNOWN",
    val routineExerciseId: String? = null,
    val attemptNumber: Int = 1,
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
 * - v4: adds legacy global equipment rack definitions and per-routine-exercise rack defaults.
 * - v5: replaces the global rack payload with independently restorable profile training
 *       preference sections. Local safety/consent state and sync bookkeeping are excluded.
 * - v6: adds optional custom exercises, profile baselines, cycle clocks/drafts, workout
 *       deletion ledgers, and ownership/recovery operations.
 * - v7: adds per-profile gamification stats, stock-exercise user fields, and the session
 *       sync stamp + portal-origin marker. Raw telemetry (metricSamples) is opt-in.
 */
@Serializable
data class BackupData(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAt: String,
    val appVersion: String,
    val data: BackupContent,
    val privacy: BackupPrivacyMetadata = BackupPrivacyMetadata(),
)

@Serializable
enum class BackupDataClassification {
    FULL_PERSONAL_DATA,
    REDACTED_DIAGNOSTICS,
}

@Serializable
data class BackupPrivacyMetadata(
    val classification: BackupDataClassification = BackupDataClassification.FULL_PERSONAL_DATA,
    val containsWorkoutHistory: Boolean = true,
    val containsUserProfiles: Boolean = true,
    val containsSessionNotes: Boolean = true,
    val containsAuthTokens: Boolean = false,
    val containsRuntimeSecrets: Boolean = false,
    val containsRawTelemetry: Boolean = false,
    val userFacingSummary: String = backupPrivacySummary(containsRawTelemetry),
)

/** Lists what a full backup contains, so the privacy summary never over- or under-states it. */
fun backupPrivacySummary(includesRawTelemetry: Boolean): String =
    "Personal-data export: includes workout history, routines, custom exercises and exercise favourites, " +
        "profiles, profile training preferences, training baselines, personal records, badges and stats, and notes. " +
        (
            if (includesRawTelemetry) {
                "Includes raw per-sample force and position telemetry. "
            } else {
                "Does not include raw per-sample telemetry (turn on \"Include raw telemetry\" to add it). "
            }
            ) +
        "Does not include auth tokens, runtime secrets, preference sync bookkeeping, voice phrase or calibration, " +
        "or adult consent and prompt state."

/**
 * Highest backup schema version this build can produce.
 * Bump whenever BackupContent gains/loses entities or a backup field type changes.
 */
const val CURRENT_BACKUP_VERSION: Int = 7

/**
 * Profile-scoped preference payload. Raw JSON sections intentionally isolate malformed or
 * forward-version values so one bad section cannot prevent valid siblings from restoring.
 * Sync metadata and local-only safety state are deliberately absent from this wire type.
 */
@Serializable
data class ProfilePreferencesBackup(
    val profileId: String,
    val core: JsonElement? = null,
    val rack: JsonElement? = null,
    val workout: JsonElement? = null,
    val led: JsonElement? = null,
    val vbt: JsonElement? = null,
)

/**
 * Container for all backup data entities
 */
@Serializable
data class BackupContent(
    // Added v6. Optional so v1-v5 imports continue to decode as an empty catalog delta.
    val customExercises: List<CustomExerciseBackup> = emptyList(),
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
    // v1-v6 single object. v7 writes gamificationStatsByProfile instead; both still import.
    val gamificationStats: GamificationStatsBackup? = null,
    val gamificationStatsByProfile: List<GamificationStatsBackup> = emptyList(),
    val stockExerciseUserFields: List<StockExerciseUserFieldsBackup> = emptyList(),
    val userProfiles: List<UserProfileBackup> = emptyList(),
    // Added v5: profile-scoped training preferences; sections decode independently.
    val profilePreferences: List<ProfilePreferencesBackup> = emptyList(),
    // Added v4 and retained only for deterministic v4 import compatibility.
    @SerialName("equipmentRackItems")
    val legacyEquipmentRackItems: JsonElement? = null,
    // Added v2: portal session notes (migration 26)
    val sessionNotes: List<SessionNotesBackup> = emptyList(),
    // Added v3: routine groups (migration 27)
    val routineGroups: List<RoutineGroupBackup> = emptyList(),
    // Added v6 durable/scoped state.
    val profileExerciseBaselines: List<ProfileExerciseBaselineBackup> = emptyList(),
    val workoutDeletions: List<WorkoutDeletionBackup> = emptyList(),
    val pendingProfileRecoveries: List<PendingProfileRecoveryBackup> = emptyList(),
    val ownershipTransfers: List<OwnershipTransferBackup> = emptyList(),
    val appliedOwnershipEvents: List<AppliedOwnershipEventBackup> = emptyList(),
    val localOwnershipClaims: List<LocalOwnershipClaimBackup> = emptyList(),
    val cycleConflictDrafts: List<CycleConflictDraftBackup> = emptyList(),
    val cycleSyncStates: List<CycleSyncStateBackup> = emptyList(),
)

/**
 * Result of an import operation
 */
data class ImportResult(
    val sessionsImported: Int,
    val sessionsSkipped: Int,
    val metricsImported: Int,
    val metricsSkipped: Int = 0,
    val routinesImported: Int,
    val routinesSkipped: Int,
    val routineExercisesImported: Int,
    val routineExercisesSkipped: Int = 0,
    val supersetsImported: Int = 0,
    val supersetsSkipped: Int = 0,
    val personalRecordsImported: Int,
    val personalRecordsSkipped: Int,
    val trainingCyclesImported: Int = 0,
    val trainingCyclesSkipped: Int = 0,
    val cycleDaysImported: Int = 0,
    val cycleDaysSkipped: Int = 0,
    val cycleProgressImported: Int = 0,
    val cycleProgressSkipped: Int = 0,
    val cycleProgressionsImported: Int = 0,
    val cycleProgressionsSkipped: Int = 0,
    val plannedSetsImported: Int = 0,
    val plannedSetsSkipped: Int = 0,
    val completedSetsImported: Int = 0,
    val completedSetsSkipped: Int = 0,
    val progressionEventsImported: Int = 0,
    val progressionEventsSkipped: Int = 0,
    val earnedBadgesImported: Int = 0,
    val earnedBadgesSkipped: Int = 0,
    val streakHistoryImported: Int = 0,
    val streakHistorySkipped: Int = 0,
    val gamificationStatsImported: Boolean = false,
    val gamificationStatsSkipped: Boolean = false,
    val userProfilesImported: Int = 0,
    val userProfilesSkipped: Int = 0,
    val sessionNotesImported: Int = 0,
    val sessionNotesSkipped: Int = 0,
    val routineGroupsImported: Int = 0,
    val routineGroupsSkipped: Int = 0,
    val customExercisesImported: Int = 0,
    val customExercisesSkipped: Int = 0,
    val stockExerciseUserFieldsImported: Int = 0,
    val stockExerciseUserFieldsSkipped: Int = 0,
    val profileExerciseBaselinesImported: Int = 0,
    val profileExerciseBaselinesSkipped: Int = 0,
    val workoutDeletionsImported: Int = 0,
    val workoutDeletionsSkipped: Int = 0,
    val pendingProfileRecoveriesImported: Int = 0,
    val pendingProfileRecoveriesSkipped: Int = 0,
    val ownershipTransfersImported: Int = 0,
    val ownershipTransfersSkipped: Int = 0,
    val appliedOwnershipEventsImported: Int = 0,
    val appliedOwnershipEventsSkipped: Int = 0,
    val localOwnershipClaimsImported: Int = 0,
    val localOwnershipClaimsSkipped: Int = 0,
    val cycleConflictDraftsImported: Int = 0,
    val cycleConflictDraftsSkipped: Int = 0,
    val cycleSyncStatesImported: Int = 0,
    val cycleSyncStatesSkipped: Int = 0,
    val repairedReferences: Int = 0,
    /**
     * Count of individual entity rows that threw during import and were skipped.
     * Non-zero here means the backup contained malformed rows — the import still
     * succeeded overall, but data was partially dropped. Surfacing this lets the UI
     * warn users rather than silently swallow corruption.
     */
    val entitiesWithErrors: Int = 0,
) {
    /** Rows that could not be restored. Kept separate from idempotent duplicates. */
    val entitiesFailed: Int get() = entitiesWithErrors

    val hasPartialFailure: Boolean get() = entitiesFailed > 0

    val totalImported: Int
        get() = sessionsImported + metricsImported + routinesImported +
            routineExercisesImported + supersetsImported + personalRecordsImported +
            trainingCyclesImported + cycleDaysImported + cycleProgressImported +
            cycleProgressionsImported + plannedSetsImported + completedSetsImported +
            progressionEventsImported + earnedBadgesImported + streakHistoryImported +
            (if (gamificationStatsImported) 1 else 0) + userProfilesImported +
            sessionNotesImported + routineGroupsImported + customExercisesImported +
            stockExerciseUserFieldsImported +
            profileExerciseBaselinesImported + workoutDeletionsImported +
            pendingProfileRecoveriesImported + ownershipTransfersImported +
            appliedOwnershipEventsImported + localOwnershipClaimsImported +
            cycleConflictDraftsImported + cycleSyncStatesImported

    val totalSkipped: Int
        get() = sessionsSkipped + metricsSkipped + routinesSkipped + supersetsSkipped + personalRecordsSkipped +
            routineExercisesSkipped + trainingCyclesSkipped + cycleDaysSkipped + cycleProgressSkipped +
            cycleProgressionsSkipped + plannedSetsSkipped + completedSetsSkipped + progressionEventsSkipped +
            earnedBadgesSkipped + streakHistorySkipped + (if (gamificationStatsSkipped) 1 else 0) +
            userProfilesSkipped + sessionNotesSkipped +
            routineGroupsSkipped + customExercisesSkipped + stockExerciseUserFieldsSkipped +
            profileExerciseBaselinesSkipped +
            workoutDeletionsSkipped + pendingProfileRecoveriesSkipped +
            ownershipTransfersSkipped + appliedOwnershipEventsSkipped + cycleConflictDraftsSkipped +
            localOwnershipClaimsSkipped + cycleSyncStatesSkipped
}
