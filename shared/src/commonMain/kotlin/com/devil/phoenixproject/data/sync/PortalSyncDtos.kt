package com.devil.phoenixproject.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs matching the portal's 3-tier database structure:
 *   workout_sessions → exercises → sets → rep_summaries / rep_telemetry
 *
 * These represent the wire format for mobile → portal sync.
 * The portal stores per-cable weight; the ×2 display multiplier is handled in the portal UI.
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
    @SerialName("user_id") val userId: String,
    val name: String? = null,
    @SerialName("started_at") val startedAt: String, // ISO 8601
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("total_volume") val totalVolume: Float = 0f, // per-cable kg
    @SerialName("set_count") val setCount: Int = 0,
    @SerialName("exercise_count") val exerciseCount: Int = 0,
    @SerialName("pr_count") val prCount: Int = 0,
    @SerialName("routine_name") val routineName: String? = null,
    @SerialName("workout_mode") val workoutMode: String? = null, // SCREAMING_SNAKE
    @SerialName("routine_session_id") val routineSessionId: String? = null,
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
    @SerialName("session_id") val sessionId: String,
    val name: String,
    @SerialName("muscle_group") val muscleGroup: String = "General",
    @SerialName("order_index") val orderIndex: Int = 0,
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
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_number") val setNumber: Int,
    @SerialName("target_reps") val targetReps: Int? = null,
    @SerialName("actual_reps") val actualReps: Int = 0,
    @SerialName("weight_kg") val weightKg: Float = 0f, // per-cable
    val rpe: Int? = null,
    @SerialName("is_pr") val isPr: Boolean = false,
    val notes: String? = null,
    @SerialName("workout_mode") val workoutMode: String? = null, // SCREAMING_SNAKE
    @SerialName("rep_summaries") val repSummaries: List<PortalRepSummaryDto> = emptyList()
)

// ─── Level 4: Rep Summary ───────────────────────────────────────────

/**
 * Maps to portal's `rep_summaries` table.
 * Aggregated metrics for a single rep (derived from RepMetricData + RepBiomechanics).
 */
@Serializable
data class PortalRepSummaryDto(
    val id: String,
    @SerialName("set_id") val setId: String,
    @SerialName("rep_number") val repNumber: Int,
    @SerialName("mean_velocity_mps") val meanVelocityMps: Float? = null,
    @SerialName("peak_velocity_mps") val peakVelocityMps: Float? = null,
    @SerialName("mean_force_n") val meanForceN: Float? = null,
    @SerialName("peak_force_n") val peakForceN: Float? = null,
    @SerialName("power_watts") val powerWatts: Float? = null,
    @SerialName("rom_mm") val romMm: Float? = null,
    @SerialName("tut_ms") val tutMs: Int? = null, // concentric + eccentric duration
    @SerialName("left_force_avg") val leftForceAvg: Float? = null, // cable A → left
    @SerialName("right_force_avg") val rightForceAvg: Float? = null, // cable B → right
    @SerialName("asymmetry_pct") val asymmetryPct: Float? = null,
    @SerialName("vbt_zone") val vbtZone: String? = null // e.g., "EXPLOSIVE", "STRENGTH"
)

// ─── Level 4b: Rep Telemetry (raw force curves) ────────────────────

/**
 * Maps to portal's `rep_telemetry` table.
 * Raw time-series data points for force curve visualization.
 */
@Serializable
data class PortalRepTelemetryDto(
    val id: String,
    @SerialName("set_id") val setId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("force_n") val forceN: Float? = null,
    @SerialName("velocity_mps") val velocityMps: Float? = null,
    @SerialName("position_mm") val positionMm: Float? = null,
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
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String = "",
    @SerialName("exercise_count") val exerciseCount: Int = 0,
    @SerialName("estimated_duration") val estimatedDuration: Int = 0,
    @SerialName("times_completed") val timesCompleted: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    val exercises: List<PortalRoutineExerciseSyncDto> = emptyList()
)

@Serializable
data class PortalRoutineExerciseSyncDto(
    val id: String,
    @SerialName("routine_id") val routineId: String,
    val name: String,
    @SerialName("muscle_group") val muscleGroup: String = "General",
    val sets: Int = 3,
    val reps: Int = 10,
    val weight: Float = 0f, // per-cable kg
    @SerialName("rest_seconds") val restSeconds: Int = 90,
    val mode: String = "OLD_SCHOOL", // SCREAMING_SNAKE
    @SerialName("order_index") val orderIndex: Int = 0,
    // Superset support (new portal columns)
    @SerialName("superset_id") val supersetId: String? = null,
    @SerialName("superset_color") val supersetColor: String? = null,
    @SerialName("superset_order") val supersetOrder: Int? = null,
    // Per-set configuration (new portal columns)
    @SerialName("per_set_weights") val perSetWeights: String? = null, // JSON array
    @SerialName("per_set_rest") val perSetRest: String? = null, // JSON array
    @SerialName("is_amrap") val isAmrap: Boolean = false,
    @SerialName("pr_percentage") val prPercentage: Float? = null,
    @SerialName("rep_count_timing") val repCountTiming: String? = null, // "TOP" or "BOTTOM"
    @SerialName("stop_at_position") val stopAtPosition: String? = null, // "TOP" or "BOTTOM"
    @SerialName("stall_detection") val stallDetection: Boolean = true,
    @SerialName("eccentric_load") val eccentricLoad: String? = null,
    @SerialName("echo_level") val echoLevel: String? = null
)

// ─── RPG/Gamification Sync DTOs ─────────────────────────────────────

@Serializable
data class PortalRpgAttributesSyncDto(
    @SerialName("user_id") val userId: String,
    val strength: Int = 0,
    val power: Int = 0,
    val stamina: Int = 0,
    val consistency: Int = 0,
    val mastery: Int = 0,
    @SerialName("character_class") val characterClass: String? = null,
    val level: Int = 1,
    @SerialName("experience_points") val experiencePoints: Int = 0
)

@Serializable
data class PortalEarnedBadgeSyncDto(
    @SerialName("user_id") val userId: String,
    @SerialName("badge_id") val badgeId: String,
    @SerialName("badge_name") val badgeName: String,
    @SerialName("badge_description") val badgeDescription: String? = null,
    @SerialName("badge_tier") val badgeTier: String = "bronze",
    @SerialName("earned_at") val earnedAt: String // ISO 8601
)

@Serializable
data class PortalGamificationStatsSyncDto(
    @SerialName("user_id") val userId: String,
    @SerialName("total_workouts") val totalWorkouts: Int = 0,
    @SerialName("total_reps") val totalReps: Int = 0,
    @SerialName("total_volume_kg") val totalVolumeKg: Float = 0f,
    @SerialName("longest_streak") val longestStreak: Int = 0,
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("total_time_seconds") val totalTimeSeconds: Int = 0
)

// ─── Composite Sync Payload ─────────────────────────────────────────

/**
 * Full sync payload sent from mobile to portal.
 * Contains all data types in portal-compatible format.
 */
@Serializable
data class PortalSyncPayload(
    @SerialName("device_id") val deviceId: String,
    val platform: String = "android",
    @SerialName("last_sync") val lastSync: Long,
    val sessions: List<PortalWorkoutSessionDto> = emptyList(),
    val routines: List<PortalRoutineSyncDto> = emptyList(),
    @SerialName("rpg_attributes") val rpgAttributes: PortalRpgAttributesSyncDto? = null,
    val badges: List<PortalEarnedBadgeSyncDto> = emptyList(),
    @SerialName("gamification_stats") val gamificationStats: PortalGamificationStatsSyncDto? = null
)
