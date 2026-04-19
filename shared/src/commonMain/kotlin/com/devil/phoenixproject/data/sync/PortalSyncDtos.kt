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
    val exercises: List<PortalExerciseDto> = emptyList(),
    // --- Session enrichment (GAPs 3-6) ---
    // Biomechanics summary
    val avgVelocityMps: Float? = null,
    val avgAsymmetryPct: Float? = null,
    val velocityLossPct: Float? = null,
    val dominantSide: String? = null,
    val strengthProfile: String? = null,
    // Safety & form
    val formScore: Int? = null,
    val deloadWarnings: Int? = null,
    val romViolations: Int? = null,
    val spotterActivations: Int? = null,
    // Force metrics
    val peakForceN: Float? = null,
    val estimatedCalories: Float? = null,
    val heaviestLiftKg: Float? = null,
    // Configuration context
    val eccentricLoad: Int? = null,
    val echoLevel: Int? = null,
    val warmupReps: Int? = null,
    val workingReps: Int? = null,
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
    val sets: List<PortalSetDto> = emptyList(),
)

// ─── Level 3: Set (within an exercise) ──────────────────────────────

/**
 * Maps to portal's `sets` table.
 * One mobile WorkoutSession = one "set" in portal terms (since mobile
 * treats each exercise run as its own session).
 *
 * NOTE: `prType`, `prPhase`, and `prVolume` are SEND-ONLY derivation hints.
 * The portal `sets` table has no columns for them — the push handler reads
 * these fields from the incoming DTO and forwards them into the
 * `personal_records` table insert (see mobile-sync-push/index.ts around the
 * personal_records handling). PRs round-trip via PersonalRecord DTOs, not
 * through set-level columns. This split is intentional: keeping PR state
 * canonical in a single table avoids double-source-of-truth bugs.
 * Resolves audit item #3 (2026-04-19).
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
    // The following three fields are send-only PR derivation hints consumed
    // by the portal push handler to construct personal_records rows. They are
    // NOT persisted on the `sets` table. See doc comment above.
    val prType: String? = null, // "MAX_WEIGHT" or "MAX_VOLUME"
    val prPhase: String? = null, // "COMBINED", "CONCENTRIC", or "ECCENTRIC"
    val prVolume: Float? = null, // total volume (weight × reps) for volume PRs
    val notes: String? = null,
    val workoutMode: String? = null, // SCREAMING_SNAKE
    val repSummaries: List<PortalRepSummaryDto> = emptyList(),
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
    val vbtZone: String? = null, // e.g., "EXPLOSIVE", "STRENGTH"
)

// ─── Level 4b: Rep Telemetry (raw force curves) ────────────────────

/**
 * Maps to portal's `rep_telemetry` table.
 * Raw time-series data points for force curve visualization.
 *
 * Cable wire format: canonical "A" | "B" from BLE (A = left actuator,
 * B = right actuator). Portal schema (src/schemas/telemetry.ts) accepts
 * the same canonical values; the portal UI layer applies display mapping
 * via `cableDisplayName()`. Resolves audit item #4 (2026-04-19).
 */
@Serializable
data class PortalRepTelemetryDto(
    val id: String,
    val setId: String,
    val timestampMs: Long,
    val forceN: Float? = null,
    val velocityMps: Float? = null,
    val positionMm: Float? = null,
    /** Canonical BLE cable identifier: "A" (left actuator) or "B" (right actuator). */
    val cable: String? = null,
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
    val exercises: List<PortalRoutineExerciseSyncDto> = emptyList(),
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
    val isBodyweight: Boolean = false,
    val prPercentage: Float? = null,
    val repCountTiming: String? = null, // "TOP" or "BOTTOM"
    val stopAtPosition: String? = null, // "TOP" or "BOTTOM"
    val stallDetection: Boolean = true,
    val eccentricLoad: String? = null,
    val echoLevel: String? = null,
    val perSetEchoLevels: String? = null, // JSON array of echo level names
    val warmupSets: String? = null, // JSON array of {reps, percentOfWorking}
)

// ─── Training Cycle Sync DTOs ─────────────────────────────────────

/**
 * Maps to portal's `training_cycles` table.
 * Mobile TrainingCycle has a simpler schema — computed fields (durationWeeks,
 * workoutDays, restDays) are derived by the adapter from the days list.
 */
@Serializable
data class PortalTrainingCycleSyncDto(
    val id: String,
    val userId: String,
    val name: String,
    val description: String? = null,
    val durationWeeks: Int = 1,
    val workoutDays: Int = 0,
    val restDays: Int = 0,
    val currentWeek: Int = 1,
    val status: String = "draft",
    val startedAt: String? = null, // ISO 8601
    val lastUsedAt: String? = null, // ISO 8601
    val progressionSettings: String? = null, // JSON
    val deloadSettings: String? = null, // JSON
    val days: List<PortalCycleDaySyncDto> = emptyList(),
)

/**
 * Maps to portal's `cycle_days` table.
 * Mobile CycleDay uses is_rest_day + echo/eccentric modifiers;
 * portal uses day_type + weight_adjustment + rest_type.
 */
@Serializable
data class PortalCycleDaySyncDto(
    val id: String,
    val cycleId: String,
    val dayNumber: Int,
    val dayType: String = "workout", // "workout" or "rest"
    val routineId: String? = null,
    val weightAdjustment: Float = 0f,
    val repModifier: Int = 0,
    val restOverride: Int? = null,
    val restType: String? = null,
    val notes: String? = null,
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
    val experiencePoints: Int = 0,
)

@Serializable
data class PortalEarnedBadgeSyncDto(
    val userId: String,
    val badgeId: String,
    val badgeName: String,
    val badgeDescription: String? = null,
    val badgeTier: String = "bronze",
    val earnedAt: String, // ISO 8601
)

@Serializable
data class PortalGamificationStatsSyncDto(
    val userId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val totalTimeSeconds: Int = 0,
)

// ─── Phase Statistics (GAP 7) ───────────────────────────────────────

/**
 * Maps to portal's `session_phase_statistics` table.
 * Concentric vs eccentric phase comparison metrics per session.
 */
@Serializable
data class PortalPhaseStatisticsDto(
    val id: String,
    val sessionId: String,
    val concentricKgAvg: Float = 0f,
    val concentricKgMax: Float = 0f,
    val concentricVelAvg: Float = 0f, // m/s (converted from mm/s)
    val concentricVelMax: Float = 0f,
    val concentricWattAvg: Float = 0f,
    val concentricWattMax: Float = 0f,
    val eccentricKgAvg: Float = 0f,
    val eccentricKgMax: Float = 0f,
    val eccentricVelAvg: Float = 0f, // m/s
    val eccentricVelMax: Float = 0f,
    val eccentricWattAvg: Float = 0f,
    val eccentricWattMax: Float = 0f,
)

// ─── Exercise Signatures (GAP 8) ───────────────────────────────────

/**
 * Maps to portal's `exercise_signatures` table.
 * Movement pattern signatures for exercise auto-detection and comparison.
 */
@Serializable
data class PortalExerciseSignatureDto(
    val id: String,
    val exerciseId: String,
    val romMm: Float = 0f,
    val durationMs: Long = 0,
    val symmetryRatio: Float = 0.5f,
    val velocityProfile: String = "LINEAR", // EXPLOSIVE_START, LINEAR, DECELERATING
    val cableConfig: String = "DUAL_SYMMETRIC",
    val sampleCount: Int = 1,
    val confidence: Float = 0f,
    val updatedAt: String? = null, // ISO 8601
)

// ─── VBT Assessment Results (GAP 9) ────────────────────────────────

/**
 * Maps to portal's `vbt_assessments` table.
 * VBT-derived 1RM estimates (more accurate than Brzycki formula).
 */
@Serializable
data class PortalAssessmentResultDto(
    val id: String,
    val exerciseId: String,
    val estimatedOneRepMaxKg: Float,
    val loadVelocityData: String, // JSON array of {loadKg, meanVelocityMs}
    val assessmentSessionId: String? = null,
    val userOverrideKg: Float? = null,
    val createdAt: String, // ISO 8601
)

// ─── Local Profile (profile data separation) ──────────────────────

/**
 * DTO for syncing local device profiles to the portal.
 * The portal stores these in a `local_profiles` table to enable
 * profile-scoped filtering in the web dashboard.
 */
@Serializable
data class LocalProfileDto(val id: String, val name: String, val colorIndex: Int)

// ─── Push Response ──────────────────────────────────────────────────

/**
 * Response from the mobile-sync-push Edge Function.
 * syncTime is ISO 8601 (not epoch millis like the legacy SyncPushResponse).
 * No idMappings — portal uses client-provided UUIDs.
 */
@Serializable
data class PortalSyncPushResponse(
    val syncTime: String, // ISO 8601 from Edge Function
    val sessionsInserted: Int = 0,
    val exercisesInserted: Int = 0,
    val setsInserted: Int = 0,
    val repSummariesInserted: Int = 0,
    val telemetryInserted: Int = 0,
    val routinesUpserted: Int = 0,
    val cyclesUpserted: Int = 0,
    val badgesUpserted: Int = 0,
    val exerciseProgressInserted: Int = 0,
    val personalRecordsInserted: Int = 0,
    val phaseStatisticsInserted: Int = 0,
    val exerciseSignaturesUpserted: Int = 0,
    val assessmentsInserted: Int = 0,
    val externalActivitiesUpserted: Int = 0,
    /**
     * DEPRECATED: prefer `externalActivityKeys` which now carries per-ack
     * localId/serverId/updatedAt metadata. Retained for one release so older
     * adapters can continue to decode the response. Will be removed alongside
     * Phase 3 LWW rollout.
     */
    @Deprecated(
        message = "Use externalActivityKeys which now carries full ack metadata",
        replaceWith = ReplaceWith("externalActivityKeys")
    )
    val externalActivityIds: List<String> = emptyList(),
    val externalActivityKeys: List<ExternalActivityAckDto> = emptyList(),
)

/**
 * Ack returned by mobile-sync-push for each upserted external activity.
 *
 * `localId` and `serverId` are both the client-minted UUID in steady state.
 * They are modeled as separate fields so a future server-side id remap would
 * not require another wire break. `updatedAt` is the server-canonical
 * timestamp used to seed LWW convergence on the mobile side.
 *
 * Older clients that only read `externalId`/`provider` still deserialize
 * correctly because new fields carry defaults. The deprecated
 * `PortalSyncPushResponse.externalActivityIds` alias is retained for one
 * release for callers that relied on it.
 *
 * Resolves audit items #5 and #10 (2026-04-19).
 */
@Serializable
data class ExternalActivityAckDto(
    val externalId: String,
    val provider: String,
    val localId: String? = null,
    val serverId: String? = null,
    val updatedAt: String? = null,
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
    val telemetry: List<PortalRepTelemetryDto> = emptyList(),
    val routines: List<PortalRoutineSyncDto> = emptyList(),
    val cycles: List<PortalTrainingCycleSyncDto> = emptyList(),
    val rpgAttributes: PortalRpgAttributesSyncDto? = null,
    val badges: List<PortalEarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: PortalGamificationStatsSyncDto? = null,
    // Phase 3: Extended metrics
    val phaseStatistics: List<PortalPhaseStatisticsDto> = emptyList(),
    val exerciseSignatures: List<PortalExerciseSignatureDto> = emptyList(),
    val assessments: List<PortalAssessmentResultDto> = emptyList(),
    // Profile data separation: active profile tagging + full profile snapshot
    val profileId: String? = null,
    val profileName: String? = null,
    val allProfiles: List<LocalProfileDto>? = null,
    // External integration activities (paid users only)
    val externalActivities: List<ExternalActivitySyncDto> = emptyList(),
)

// ─── External Activities (Integration sync) ──────────────────────────

@Serializable
data class ExternalActivitySyncDto(
    val id: String,
    val externalId: String,
    val provider: String,
    val name: String,
    val activityType: String = "strength",
    val startedAt: String, // ISO-8601
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
    val rawData: String? = null,
    val syncedAt: String, // ISO-8601
)

// ─── Pull Response DTOs (camelCase — NO @SerialName) ──────────────────
//
// The pull Edge Function returns camelCase JSON keys (e.g., "userId", "startedAt").
// These DTOs have property names matching the camelCase JSON directly.

/**
 * Entity IDs known to the client, used for parity-based sync.
 * Server returns entities NOT in these lists.
 */
@Serializable
data class KnownEntityIds(
    val sessionIds: List<String> = emptyList(),
    val routineIds: List<String> = emptyList(),
    val cycleIds: List<String> = emptyList(),
    val badgeIds: List<String> = emptyList(),
    val personalRecordIds: List<String> = emptyList(),
)

/**
 * Request body for the mobile-sync-pull Edge Function.
 * Uses a typed DTO instead of Map<String, Any> to avoid Kotlinx Serialization
 * "different element types" error with heterogeneous maps.
 *
 * Pagination parameters (Plan 03-05):
 * - cursor: resume from previous response's nextCursor; null for first page
 * - pageSize: entities per page; null uses server default (100)
 */
@Serializable
data class PortalSyncPullRequest(
    val deviceId: String,
    /** @deprecated Use knownEntityIds for parity-based sync. Kept for backward compatibility. */
    val lastSync: Long = 0,
    val profileId: String? = null,
    val cursor: String? = null,
    val pageSize: Int? = null,
    /** Entity IDs client already has. Server returns entities NOT in these lists. */
    val knownEntityIds: KnownEntityIds? = null,
)

/**
 * Response from the mobile-sync-pull Edge Function.
 * syncTime is epoch millis (Long), NOT ISO 8601 String like the push response.
 *
 * Pagination fields (added in Plan 03-05):
 * - nextCursor: opaque cursor to pass in next request; null when no more pages
 * - hasMore: true if more pages remain; client should loop until false
 *
 * Backward compatibility: nextCursor and hasMore are optional with defaults.
 */
@Serializable
data class PortalSyncPullResponse(
    val syncTime: Long,
    // Pagination metadata (Plan 03-05)
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    // Entity data
    val sessions: List<PullWorkoutSessionDto> = emptyList(), // Merged via INSERT OR IGNORE (local wins)
    val routines: List<PullRoutineDto> = emptyList(),
    val cycles: List<PullTrainingCycleDto> = emptyList(),
    val personalRecords: List<PullPersonalRecordDto> = emptyList(),
    val rpgAttributes: PullRpgAttributesDto? = null,
    val badges: List<PullBadgeDto> = emptyList(),
    val gamificationStats: PullGamificationStatsDto? = null,
    // Profile data separation: profile list from portal (round-trip)
    val localProfiles: List<LocalProfileDto>? = null,
    // External integration activities (paid users only)
    val externalActivities: List<ExternalActivitySyncDto> = emptyList(),
)

/**
 * Pulled workout session -- merged into local DB via INSERT OR IGNORE.
 * Local data wins on conflict (existing sessions are not overwritten).
 * Multi-device scenario: sessions from device A appear on device B after pull.
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
    /**
     * Session-level notes from portal. Added 2026-04-19 (audit item #2).
     *
     * Wire contract only — mobile does not yet persist session-level notes
     * locally because the mobile WorkoutSession model is per-exercise (portal
     * session → N mobile rows grouped by routineSessionId). A SessionNotes
     * table keyed on routineSessionId is the planned persistence surface and
     * ships with the Phase 3 LWW migration; until then, this field round-trips
     * through the wire but is ignored when merging into SQLDelight.
     */
    val notes: String? = null,
    val exercises: List<PullExerciseDto> = emptyList(),
)

@Serializable
data class PullExerciseDto(
    val id: String = "",
    val sessionId: String = "",
    val name: String = "",
    val muscleGroup: String = "General",
    val orderIndex: Int = 0,
    val sets: List<PullSetDto> = emptyList(),
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
    val repSummaries: List<PullRepSummaryDto> = emptyList(),
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
    val vbtZone: String? = null,
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
    val exercises: List<PullRoutineExerciseDto> = emptyList(),
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
    val isBodyweight: Boolean = false,
    val prPercentage: Float? = null,
    val repCountTiming: String? = null,
    val stopAtPosition: String? = null,
    val stallDetection: Boolean = true,
    val eccentricLoad: String? = null,
    val echoLevel: String? = null,
    val perSetEchoLevels: String? = null, // JSON array of echo level names
    val warmupSets: String? = null, // JSON array of {reps, percentOfWorking}
)

/**
 * Pulled training cycle with nested days.
 */
@Serializable
data class PullTrainingCycleDto(
    val id: String,
    val userId: String = "",
    val name: String,
    val description: String? = null,
    val durationWeeks: Int = 1,
    val workoutDays: Int = 0,
    val restDays: Int = 0,
    val currentWeek: Int = 1,
    val status: String = "draft",
    val startedAt: String? = null,
    val lastUsedAt: String? = null,
    val progressionSettings: String? = null,
    val deloadSettings: String? = null,
    val days: List<PullCycleDayDto> = emptyList(),
)

@Serializable
data class PullCycleDayDto(
    val id: String,
    val cycleId: String = "",
    val dayNumber: Int = 1,
    val dayType: String = "workout",
    val routineId: String? = null,
    val weightAdjustment: Float = 0f,
    val repModifier: Int = 0,
    val restOverride: Int? = null,
    val restType: String? = null,
    val notes: String? = null,
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
    val experiencePoints: Int = 0,
)

@Serializable
data class PullBadgeDto(
    val userId: String = "",
    val badgeId: String,
    val badgeName: String = "",
    val badgeDescription: String? = null,
    val badgeTier: String = "bronze",
    val earnedAt: String = "", // ISO 8601
)

@Serializable
data class PullGamificationStatsDto(
    val userId: String = "",
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Float = 0f,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val totalTimeSeconds: Int = 0,
)

/**
 * Personal record from pull response.
 * Maps from portal's personal_records table (snake_case in DB, camelCase in response).
 */
@Serializable
data class PullPersonalRecordDto(
    val id: String = "",
    val userId: String = "",
    val exerciseName: String = "",
    val muscleGroup: String = "General",
    val recordType: String = "1RM",
    val value: Double = 0.0,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val workoutPhase: String? = "COMBINED",
    val sessionId: String? = null,
    val achievedAt: String? = null,
    val updatedAt: String? = null,
)
