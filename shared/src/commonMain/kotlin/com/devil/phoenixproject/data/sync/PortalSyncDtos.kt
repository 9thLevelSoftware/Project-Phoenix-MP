package com.devil.phoenixproject.data.sync

import kotlinx.serialization.Serializable

/**
 * DTOs matching the portal's 3-tier database structure:
 *   workout_sessions → exercises → sets → rep_summaries / rep_telemetry
 *
 * These represent the wire format for mobile → portal sync.
 * The portal stores per-cable weight; the ×2 display multiplier is handled in the portal UI.
 *
 * IMPORTANT: These DTOs serialize as camelCase JSON to match the Edge Function's
 * TypeScript interfaces. The Edge Function handles camelCase→snake_case mapping
 * when inserting into the database.
 */

// ─── Top Level: Workout Session ─────────────────────────────────────

/**
 * Maps to portal's `workout_sessions` table.
 *
 * One portal session = one routine run (or one standalone exercise).
 * Multiple mobile WorkoutSessions with the same routineSessionId
 * are grouped into a single PortalWorkoutSessionDto.
 */
@Serializable
data class PortalWorkoutSessionDto(
    val id: String,
    val userId: String,
    val name: String? = null,
    val startedAt: String, // ISO 8601
    val durationSeconds: Int = 0,
    val totalVolume: Float = 0f, // per-cable kg
    val setCount: Int = 0,
    val exerciseCount: Int = 0,
    val prCount: Int = 0,
    val routineName: String? = null,
    val workoutMode: String? = null, // SCREAMING_SNAKE
    val routineSessionId: String? = null,
    val exercises: List<PortalExerciseDto> = emptyList()
)

// ─── Level 2: Exercise (within a session) ───────────────────────────

/**
 * Maps to portal's `exercises` table.
 * One entry per exercise performed in a workout session.
 */
@Serializable
data class PortalExerciseDto(
    val id: String,
    val sessionId: String,
    val name: String,
    val muscleGroup: String = "General",
    val orderIndex: Int = 0,
    val sets: List<PortalSetDto> = emptyList()
)

// ─── Level 3: Set (within an exercise) ──────────────────────────────

/**
 * Maps to portal's `sets` table.
 * One mobile WorkoutSession = one "set" in portal terms (since mobile
 * treats each exercise run as its own session).
 */
@Serializable
data class PortalSetDto(
    val id: String,
    val exerciseId: String,
    val setNumber: Int,
    val targetReps: Int? = null,
    val actualReps: Int = 0,
    val weightKg: Float = 0f, // per-cable
    val rpe: Int? = null,
    val isPr: Boolean = false,
    val notes: String? = null,
    val workoutMode: String? = null, // SCREAMING_SNAKE
    val repSummaries: List<PortalRepSummaryDto> = emptyList()
)

// ─── Level 4: Rep Summary ───────────────────────────────────────────

/**
 * Maps to portal's `rep_summaries` table.
 * Aggregated metrics for a single rep (derived from RepMetricData + RepBiomechanics).
 */
@Serializable
data class PortalRepSummaryDto(
    val id: String,
    val setId: String,
    val repNumber: Int,
    val meanVelocityMps: Float? = null,
    val peakVelocityMps: Float? = null,
    val meanForceN: Float? = null,
    val peakForceN: Float? = null,
    val powerWatts: Float? = null,
    val romMm: Float? = null,
    val tutMs: Int? = null, // concentric + eccentric duration
    val leftForceAvg: Float? = null, // cable A → left
    val rightForceAvg: Float? = null, // cable B → right
    val asymmetryPct: Float? = null,
    val vbtZone: String? = null // e.g., "EXPLOSIVE", "STRENGTH"
)

// ─── Level 4b: Rep Telemetry (raw force curves) ────────────────────

/**
 * Maps to portal's `rep_telemetry` table.
 * Raw time-series data points for force curve visualization.
 */
@Serializable
data class PortalRepTelemetryDto(
    val id: String,
    val setId: String,
    val timestampMs: Long,
    val forceN: Float? = null,
    val velocityMps: Float? = null,
    val positionMm: Float? = null,
    val cable: String? = null // "left" or "right"
)

// ─── Routine Sync DTOs ──────────────────────────────────────────────

/**
 * Maps to portal's `routines` + `routine_exercises` tables.
 * Includes superset and per-set config that the portal now supports.
 */
@Serializable
data class PortalRoutineSyncDto(
    val id: String,
    val userId: String,
    val name: String,
    val description: String = "",
    val exerciseCount: Int = 0,
    val estimatedDuration: Int = 0,
    val timesCompleted: Int = 0,
    val isFavorite: Boolean = false,
    val exercises: List<PortalRoutineExerciseSyncDto> = emptyList()
)

@Serializable
data class PortalRoutineExerciseSyncDto(
    val id: String,
    val routineId: String,
    val name: String,
    val muscleGroup: String = "General",
    val sets: Int = 3,
    val reps: Int = 10,
    val weight: Float = 0f, // per-cable kg
    val restSeconds: Int = 90,
    val mode: String = "OLD_SCHOOL", // SCREAMING_SNAKE
    val orderIndex: Int = 0,
    // Superset support (new portal columns)
    val supersetId: String? = null,
    val supersetColor: String? = null,
    val supersetOrder: Int? = null,
    // Per-set configuration (new portal columns)
    val perSetWeights: String? = null, // JSON array
    val perSetRest: String? = null, // JSON array
    val isAmrap: Boolean = false,
    val prPercentage: Float? = null,
    val repCountTiming: String? = null, // "TOP" or "BOTTOM"
    val stopAtPosition: String? = null, // "TOP" or "BOTTOM"
    val stallDetection: Boolean = true,
    val eccentricLoad: String? = null,
    val echoLevel: String? = null
)

// ─── RPG/Gamification Sync DTOs ─────────────────────────────────────

@Serializable
data class PortalRpgAttributesSyncDto(
    val userId: String,
    val strength: Int = 0,
    val power: Int = 0,
    val stamina: Int = 0,
    val consistency: Int = 0,
    val mastery: Int = 0,
    val characterClass: String? = null,
    val level: Int = 1,
    val experiencePoints: Int = 0
)

@Serializable
data class PortalEarnedBadgeSyncDto(
    val userId: String,
    val badgeId: String,
    val badgeName: String,
    val badgeDescription: String? = null,
    val badgeTier: String = "bronze",
    val earnedAt: String // ISO 8601
)

@Serializable
data class PortalGamificationStatsSyncDto(
    val userId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val totalTimeSeconds: Int = 0
)

// ─── Push Response ──────────────────────────────────────────────────

/**
 * Response from the mobile-sync-push Edge Function.
 * syncTime is ISO 8601 (not epoch millis like the legacy SyncPushResponse).
 * No idMappings — portal uses client-provided UUIDs.
 */
@Serializable
data class PortalSyncPushResponse(
    val syncTime: String,  // ISO 8601 from Edge Function
    val sessionsInserted: Int = 0,
    val exercisesInserted: Int = 0,
    val setsInserted: Int = 0,
    val repSummariesInserted: Int = 0,
    val routinesUpserted: Int = 0,
    val badgesUpserted: Int = 0,
    val exerciseProgressInserted: Int = 0,
    val personalRecordsInserted: Int = 0
)

// ─── Composite Sync Payload ─────────────────────────────────────────

/**
 * Full sync payload sent from mobile to portal.
 * Contains all data types in portal-compatible format.
 */
@Serializable
data class PortalSyncPayload(
    val deviceId: String,
    val platform: String = "android",
    val lastSync: Long,
    val sessions: List<PortalWorkoutSessionDto> = emptyList(),
    val routines: List<PortalRoutineSyncDto> = emptyList(),
    val rpgAttributes: PortalRpgAttributesSyncDto? = null,
    val badges: List<PortalEarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: PortalGamificationStatsSyncDto? = null
)

// ─── Pull Response DTOs (camelCase — NO @SerialName) ──────────────────
//
// The pull Edge Function returns camelCase JSON keys (e.g., "userId", "startedAt").
// These DTOs have property names matching the camelCase JSON directly.

/**
 * Response from the mobile-sync-pull Edge Function.
 * syncTime is epoch millis (Long), NOT ISO 8601 String like the push response.
 */
@Serializable
data class PortalSyncPullResponse(
    val syncTime: Long,
    val sessions: List<PullWorkoutSessionDto> = emptyList(), // Skipped during merge (push-only)
    val routines: List<PullRoutineDto> = emptyList(),
    val rpgAttributes: PullRpgAttributesDto? = null,
    val badges: List<PullBadgeDto> = emptyList(),
    val gamificationStats: PullGamificationStatsDto? = null
)

/**
 * Pulled workout session — included in response but SKIPPED during merge.
 * Sessions are immutable/push-only per PULL-03.
 * Minimal fields to allow deserialization without error.
 */
@Serializable
data class PullWorkoutSessionDto(
    val id: String = "",
    val userId: String = "",
    val name: String? = null,
    val startedAt: String? = null,
    val durationSeconds: Int = 0,
    val totalVolume: Float = 0f,
    val setCount: Int = 0,
    val exerciseCount: Int = 0,
    val prCount: Int = 0,
    val routineName: String? = null,
    val workoutMode: String? = null,
    val routineSessionId: String? = null,
    val exercises: List<PullExerciseDto> = emptyList()
)

@Serializable
data class PullExerciseDto(
    val id: String = "",
    val sessionId: String = "",
    val name: String = "",
    val muscleGroup: String = "General",
    val orderIndex: Int = 0,
    val sets: List<PullSetDto> = emptyList()
)

@Serializable
data class PullSetDto(
    val id: String = "",
    val exerciseId: String = "",
    val setNumber: Int = 0,
    val targetReps: Int? = null,
    val actualReps: Int = 0,
    val weightKg: Float = 0f,
    val rpe: Int? = null,
    val isPr: Boolean = false,
    val notes: String? = null,
    val workoutMode: String? = null,
    val repSummaries: List<PullRepSummaryDto> = emptyList()
)

@Serializable
data class PullRepSummaryDto(
    val id: String = "",
    val setId: String = "",
    val repNumber: Int = 0,
    val meanVelocityMps: Float? = null,
    val peakVelocityMps: Float? = null,
    val meanForceN: Float? = null,
    val peakForceN: Float? = null,
    val powerWatts: Float? = null,
    val romMm: Float? = null,
    val tutMs: Int? = null,
    val leftForceAvg: Float? = null,
    val rightForceAvg: Float? = null,
    val asymmetryPct: Float? = null,
    val vbtZone: String? = null
)

/**
 * Pulled routine with nested exercises.
 */
@Serializable
data class PullRoutineDto(
    val id: String,
    val userId: String = "",
    val name: String,
    val description: String = "",
    val exerciseCount: Int = 0,
    val estimatedDuration: Int = 0,
    val timesCompleted: Int = 0,
    val isFavorite: Boolean = false,
    val exercises: List<PullRoutineExerciseDto> = emptyList()
)

@Serializable
data class PullRoutineExerciseDto(
    val id: String,
    val routineId: String = "",
    val name: String = "",
    val muscleGroup: String = "General",
    val sets: Int = 3,
    val reps: Int = 10,
    val weight: Float = 0f,
    val restSeconds: Int = 90,
    val mode: String = "OLD_SCHOOL",
    val orderIndex: Int = 0,
    val supersetId: String? = null,
    val supersetColor: String? = null,
    val supersetOrder: Int? = null,
    val perSetWeights: String? = null,
    val perSetRest: String? = null,
    val isAmrap: Boolean = false,
    val prPercentage: Float? = null,
    val repCountTiming: String? = null,
    val stopAtPosition: String? = null,
    val stallDetection: Boolean = true,
    val eccentricLoad: String? = null,
    val echoLevel: String? = null
)

@Serializable
data class PullRpgAttributesDto(
    val userId: String = "",
    val strength: Int = 0,
    val power: Int = 0,
    val stamina: Int = 0,
    val consistency: Int = 0,
    val mastery: Int = 0,
    val characterClass: String? = null,
    val level: Int = 1,
    val experiencePoints: Int = 0
)

@Serializable
data class PullBadgeDto(
    val userId: String = "",
    val badgeId: String,
    val badgeName: String = "",
    val badgeDescription: String? = null,
    val badgeTier: String = "bronze",
    val earnedAt: String = "" // ISO 8601
)

@Serializable
data class PullGamificationStatsDto(
    val userId: String = "",
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val totalTimeSeconds: Int = 0
)
