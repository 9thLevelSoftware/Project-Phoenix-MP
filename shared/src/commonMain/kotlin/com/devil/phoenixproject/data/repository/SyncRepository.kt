package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalSyncAdapter.CycleWithContext
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Repository interface for sync operations.
 * Provides methods to get local changes for push and merge remote changes from pull.
 */
interface SyncRepository {

    // === Push Operations (get local changes) ===

    /**
     * Get workout sessions modified since the given timestamp, scoped to profile
     */
    suspend fun getSessionsModifiedSince(timestamp: Long, profileId: String = "default"): List<WorkoutSessionSyncDto>

    /**
     * Get personal records modified since the given timestamp, scoped to profile
     */
    suspend fun getPRsModifiedSince(timestamp: Long, profileId: String = "default"): List<PersonalRecordSyncDto>

    /**
     * Get routines modified since the given timestamp, scoped to profile
     */
    suspend fun getRoutinesModifiedSince(timestamp: Long, profileId: String = "default"): List<RoutineSyncDto>

    /**
     * Get custom exercises modified since the given timestamp
     */
    suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto>

    /**
     * Get earned badges modified since the given timestamp, scoped to profile
     */
    suspend fun getBadgesModifiedSince(timestamp: Long, profileId: String): List<EarnedBadgeSyncDto>

    /**
     * Get current gamification stats for sync, scoped to profile
     */
    suspend fun getGamificationStatsForSync(profileId: String): GamificationStatsSyncDto?

    // === Portal Push Operations (full domain objects) ===

    /**
     * Get full WorkoutSession domain objects modified since timestamp, scoped to profile.
     * Returns rich objects with routineSessionId, totalVolumeKg, etc. needed by PortalSyncAdapter.
     */
    suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String = "default"): List<WorkoutSession>

    /**
     * Get full Routine domain objects modified since timestamp, scoped to profile.
     * Returns rich objects with exercises, supersets, etc. needed by PortalSyncAdapter.toPortalRoutine().
     */
    suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String = "default"): List<Routine>

    /**
     * Get training cycles scoped to the given profile, with progress and progression context for push.
     * Returns all matching cycles (no delta — cycles lack updatedAt timestamps).
     */
    suspend fun getFullCyclesForSync(profileId: String = "default"): List<CycleWithContext>

    /**
     * Get full PersonalRecord domain objects modified since timestamp, scoped to profile.
     * Returns rich objects with prType, phase, and volume for PortalSyncAdapter PR metadata.
     */
    suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String = "default"): List<PersonalRecord>

    /**
     * Get phase statistics for the given session IDs.
     * Returns SQLDelight PhaseStatistics rows for conversion to portal DTOs.
     */
    suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<com.devil.phoenixproject.database.PhaseStatistics>

    /**
     * Get all exercise signatures for sync push.
     */
    suspend fun getAllExerciseSignatures(): List<com.devil.phoenixproject.database.ExerciseSignature>

    /**
     * Get all VBT assessment results for sync push.
     */
    suspend fun getAllAssessments(profileId: String = "default"): List<com.devil.phoenixproject.database.AssessmentResult>

    // === Parity Sync Operations (get local entity IDs for comparison) ===

    /**
     * Get all session IDs for the given profile.
     * Used for parity-based sync to determine which sessions already exist locally.
     */
    suspend fun getAllSessionIds(profileId: String = "default"): List<String>

    /**
     * Get all routine IDs for the given profile.
     */
    suspend fun getAllRoutineIds(profileId: String = "default"): List<String>

    /**
     * Get all training cycle IDs for the given profile.
     */
    suspend fun getAllCycleIds(profileId: String = "default"): List<String>

    /**
     * Get all earned badge IDs for the given profile.
     */
    suspend fun getAllBadgeIds(profileId: String = "default"): List<String>

    /**
     * Get all personal record IDs for the given profile.
     */
    suspend fun getAllPersonalRecordIds(profileId: String = "default"): List<String>

    // === Post-Push Stamping ===

    /**
     * Stamp pushed sessions with current timestamp so they are not re-sent on next sync.
     * Sessions with NULL updatedAt would otherwise match every delta query indefinitely.
     */
    suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long)

    // === ID Mapping (after push) ===

    /**
     * Update server IDs after successful push
     */
    suspend fun updateServerIds(mappings: IdMappings)

    // === Pull Operations (merge remote changes) ===

    /**
     * Merge sessions from server (upsert with conflict resolution)
     */
    suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>)

    /**
     * Merge personal records from server
     */
    suspend fun mergePRs(records: List<PersonalRecordSyncDto>)

    /**
     * Merge routines from server
     */
    suspend fun mergeRoutines(routines: List<RoutineSyncDto>)

    /**
     * Merge custom exercises from server
     */
    suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>)

    /**
     * Merge badges from server, scoped to profile
     */
    suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>, profileId: String = "default")

    /**
     * Merge gamification stats from server, scoped to profile
     */
    suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?, profileId: String = "default")

    /**
     * Merge portal routines with exercises. Handles full routine + exercise replacement.
     * Respects local modifications: if local updatedAt > lastSync, keeps local version.
     *
     * @param routines Portal routine DTOs with nested exercises
     * @param lastSync The lastSync timestamp — routines modified locally after this are preserved
     */
    suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String = "default")

    /**
     * Merge portal training cycles with days into local database.
     * Server wins: portal cycles overwrite local versions.
     * Uses delete-then-reinsert for cycle days (same pattern as portal edge function).
     */
    suspend fun mergePortalCycles(cycles: List<PullTrainingCycleDto>, profileId: String = "default")

    /**
     * Merge pulled workout sessions into local database.
     * Uses INSERT OR IGNORE — if a session with the same ID already exists locally,
     * it is NOT overwritten (local data wins for immutable sessions).
     */
    suspend fun mergePortalSessions(sessions: List<WorkoutSession>)

    /**
     * Merge personal records from portal pull response, scoped to profile.
     * Uses INSERT OR IGNORE — local PRs win on conflict.
     */
    suspend fun mergePersonalRecords(records: List<PersonalRecordSyncDto>, profileId: String = "default")

    // === Exercise Lookup for Pull ===

    /**
     * Find exercise ID by name and optionally muscle group for session enrichment during pull.
     * Lookup strategy:
     * 1. Exact match on name + muscle group (if muscle group provided)
     * 2. Exact match on name only
     * 3. Case-insensitive match on name
     *
     * @return Exercise ID if found, null otherwise
     */
    suspend fun findExerciseId(name: String, muscleGroup: String? = null): String?

    // === Atomic Pull Merge ===

    /**
     * Atomically merge all pulled entities in a single database transaction.
     *
     * This ensures that either ALL entities are merged successfully, or NONE are (rollback on failure).
     * Prevents partial state where some entity types are merged but others fail, which could
     * leave the database in an inconsistent state (e.g., sessions referencing routines that don't exist).
     *
     * IMPORTANT: This method handles ONLY SyncRepository-managed entities (sessions, routines, cycles,
     * badges, gamification stats, PRs). RPG attributes and external activities are managed by
     * separate repositories and must be handled outside this transaction.
     *
     * @param sessions WorkoutSession domain objects to merge (INSERT OR IGNORE)
     * @param routines Portal routine DTOs with nested exercises
     * @param cycles Training cycle DTOs with days
     * @param badges Earned badge DTOs
     * @param gamificationStats Optional gamification stats DTO
     * @param personalRecords Personal record DTOs
     * @param lastSync Timestamp for routine conflict resolution
     * @param profileId Target profile for all entities
     */
    suspend fun mergeAllPullData(
        sessions: List<WorkoutSession>,
        routines: List<PullRoutineDto>,
        cycles: List<PullTrainingCycleDto>,
        badges: List<EarnedBadgeSyncDto>,
        gamificationStats: GamificationStatsSyncDto?,
        personalRecords: List<PersonalRecordSyncDto>,
        lastSync: Long,
        profileId: String,
    )

    /**
     * Merge session-level notes from the portal pull (Phase 3.5, audit
     * item #2 mobile persistence). Notes are keyed on the portal's
     * `routineSessionId` because the mobile WorkoutSession is per-exercise
     * and a single portal workout expands into N mobile rows. The merge
     * uses LWW on `updatedAt` (millis since epoch); call sites should pass
     * the server-canonical timestamp from `PullWorkoutSessionDto.updatedAt`
     * (Phase 3.2). Default no-op so unrelated test fakes do not need to
     * implement immediately.
     */
    suspend fun mergeSessionNotes(
        notes: Map<String, SessionNotesEntry>,
    ) {
        // Default no-op for fakes / older implementations.
    }
}

/** Side-table entry for session notes (Phase 3.5). */
data class SessionNotesEntry(
    val notes: String?,
    val updatedAtMillis: Long,
)
