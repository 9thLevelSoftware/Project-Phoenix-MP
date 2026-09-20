package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalSyncAdapter.CycleWithContext
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.PulledWorkoutDeletionDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Outcome of [SyncRepository.applyServerDeletions]. Ids are the LOCAL row ids
 * that were removed; server ids the device never held are simply absent.
 */
data class ServerDeletionResult(
    val deletedRoutineIds: List<String> = emptyList(),
    val deletedCycleIds: List<String> = emptyList(),
    /** Deleted routines that carried a local edit newer than lastSync (discarded: delete wins). */
    val discardedRoutineEditIds: List<String> = emptyList(),
    /** Deleted cycles whose complete-graph clock was newer than lastSync (discarded: delete wins). */
    val discardedCycleEditIds: List<String> = emptyList(),
    /** Deleted cycles that were active or had progress (user-visible loss of the current program). */
    val deletedActiveCycleIds: List<String> = emptyList(),
    /** Local-only `cycle_routine_*` template routines removed with their deleted cycle. */
    val deletedTemplateRoutineIds: List<String> = emptyList(),
)

data class PhasePRBackfillResult(
    val changedRows: Int,
    val maxScannedSessionTimestamp: Long? = null,
)

data class WorkoutComponentSnapshot(
    val session: WorkoutSession,
    val portalSessionId: String,
    val localSyncGeneration: Long,
)

data class WorkoutSyncSnapshot(
    val components: List<WorkoutComponentSnapshot>,
    val repMetricsByComponentId: Map<String, List<RepMetricData>> = emptyMap(),
    val completedSetsByComponentId: Map<String, List<CompletedSet>> = emptyMap(),
    val phaseStatisticsByComponentId: Map<String, List<com.devil.phoenixproject.database.PhaseStatistics>> = emptyMap(),
    val sessionNotesByPortalId: Map<String, SessionNotesEntry> = emptyMap(),
) {
    val sessions: List<WorkoutSession> get() = components.map { it.session }
}

data class CycleComponentSnapshot(
    val context: CycleWithContext,
    val localSyncGeneration: Long,
)

data class CycleSyncSnapshot(
    val components: List<CycleComponentSnapshot>,
) {
    val cycles: List<CycleWithContext> get() = components.map { it.context }
}

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

    /** Atomically expands every dirty portal parent to all of its live component rows. */
    suspend fun getDirtyWorkoutSnapshot(profileId: String): WorkoutSyncSnapshot = WorkoutSyncSnapshot(
        getWorkoutSessionsModifiedSince(0L, profileId).map { session ->
            WorkoutComponentSnapshot(
                session = session,
                portalSessionId = session.routineSessionId?.takeIf { it.isNotBlank() } ?: session.id,
                localSyncGeneration = 0L,
            )
        },
    )

    /** Clears only unchanged component generations belonging to accepted portal parents. */
    suspend fun acknowledgeWorkoutSnapshot(
        snapshot: WorkoutSyncSnapshot,
        acceptedPortalSessionIds: Set<String>,
    ) = Unit

    /**
     * Every live WorkoutSession row belonging to the given routine groups, whether or
     * not it is in the push delta.
     *
     * The portal replaces a workout's children wholesale on every accepted push, so a
     * routine workout may only be sent complete. Whenever one row of a group changes,
     * the push re-gathers the whole group through this call.
     */
    suspend fun getWorkoutSessionsByRoutineSessionIds(
        routineSessionIds: Collection<String>,
        profileId: String = "default",
    ): List<WorkoutSession> = emptyList()

    /**
     * Routine groups that must NOT be pushed, mapped to the sibling ids that block
     * them: rows this device only ever pulled and holds no MetricSample / RepMetric /
     * CompletedSet for. Pushing the group would delete that sibling's portal exercise
     * and sets, or overwrite them with an empty exercise.
     */
    suspend fun getBlockedRoutineGroupSiblings(
        routineSessionIds: Collection<String>,
        profileId: String = "default",
    ): Map<String, List<String>> = emptyMap()

    /**
     * Routine groups whose portal copy the pre-fix per-set push truncated: at least two
     * live rows and at least one already-stamped row. Newest first, walked backwards
     * with [beforeTimestamp] (the previous batch's oldest group timestamp).
     *
     * @return routineSessionId → newest member timestamp, newest first.
     */
    suspend fun getRoutineGroupRepairCandidates(
        beforeTimestamp: Long,
        limit: Int,
        profileId: String = "default",
    ): List<Pair<String, Long>> = emptyList()

    /**
     * Locally stored portal session notes for the given portal session ids
     * (`routineSessionId ?: id`), so an accepted push re-sends the note the portal
     * already holds instead of nulling it (the portal writes `notes = EXCLUDED.notes`
     * on every accepted session upsert).
     */
    suspend fun getSessionNotesForIds(portalSessionIds: Collection<String>): Map<String, String?> = emptyMap()

    /**
     * Get full Routine domain objects modified since timestamp, scoped to profile.
     * Returns rich objects with exercises, supersets, etc. needed by PortalSyncAdapter.toPortalRoutine().
     */
    suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String = "default"): List<Routine>

    /**
     * Get IDs of soft-deleted routines since timestamp, for sync push tombstone propagation.
     * Returns server IDs where available, falling back to client IDs.
     */
    suspend fun getDeletedRoutineIdsSince(timestamp: Long, profileId: String = "default"): List<String>

    /**
     * Get IDs of training cycles that were soft-deleted since [timestamp].
     * Used to propagate deletion tombstones to the server on push.
     */
    suspend fun getDeletedCycleIdsSince(timestamp: Long, profileId: String = "default"): List<String>

    /**
     * Get training cycles scoped to the given profile, with progress and progression context for push.
     * Returns all matching cycles (no delta — cycles lack updatedAt timestamps).
     */
    suspend fun getFullCyclesForSync(profileId: String = "default"): List<CycleWithContext>

    /** Atomically snapshots complete cycle aggregates whose generation is dirty. */
    suspend fun getDirtyCycleSnapshot(profileId: String = "default"): CycleSyncSnapshot = CycleSyncSnapshot(
        getFullCyclesForSync(profileId).map { context ->
            CycleComponentSnapshot(context = context, localSyncGeneration = 0L)
        },
    )

    /** Clears only unchanged generations for cycle IDs accepted by the portal. */
    suspend fun acknowledgeCycleSnapshot(
        snapshot: CycleSyncSnapshot,
        acceptedCycleIds: Set<String>,
    ) = Unit

    /**
     * Store the portal versions acknowledged by a successful push (`cycleVersions`)
     * as each cycle's base for the next push. Cycles not in [versions] keep their base.
     */
    suspend fun updateCycleServerVersions(versions: Map<String, String>)

    /**
     * Get full PersonalRecord domain objects modified since timestamp, scoped to profile.
     * Returns rich objects with prType, phase, and volume for PortalSyncAdapter PR metadata.
     */
    suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String = "default"): List<PersonalRecord>

    /**
     * Idempotently backfill phase-specific PRs from historical sessions that
     * have tagged exercises and saved concentric/eccentric force metrics.
     *
     * @return phase PR change count and the newest scanned session timestamp for checkpointing.
     */
    suspend fun backfillPhaseSpecificPRs(
        profileId: String = "default",
        fromSessionTimestamp: Long = 0L,
    ): PhasePRBackfillResult = PhasePRBackfillResult(changedRows = 0)

    /**
     * Resolve local workout session IDs for dedicated PR rows whose source
     * sessions may not be included in the current modified-since push batch.
     */
    suspend fun findSessionIdsForPersonalRecords(
        records: List<PersonalRecord>,
        profileId: String = "default",
    ): Map<String, String> = emptyMap()

    /**
     * Get phase statistics for the given session IDs.
     * Returns SQLDelight PhaseStatistics rows for conversion to portal DTOs.
     */
    suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<com.devil.phoenixproject.database.PhaseStatistics>

    /**
     * Get all VBT assessment results for sync push.
     */
    suspend fun getAllAssessments(profileId: String = "default"): List<com.devil.phoenixproject.database.AssessmentResult>

    // === Parity Reconciliation (hard-delete entities removed on server) ===

    /**
     * Hard-delete cycles by IDs. Used during parity reconciliation when
     * server confirms these cycles no longer exist (portal deletion).
     */
    suspend fun hardDeleteCyclesByIds(ids: List<String>)

    /**
     * Hard-delete routines by IDs. Used during parity reconciliation when
     * server confirms these routines no longer exist (portal deletion).
     */
    suspend fun hardDeleteRoutinesByIds(ids: List<String>)

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

    /** Routine IDs whose legacy exercises still need an authoritative portal duration. */
    suspend fun getRoutineIdsNeedingDurationBackfill(profileId: String = "default"): List<String> = emptyList()

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

    /**
     * Stamp exactly the session rows the portal accepted, in one transaction.
     *
     * A row edited or inserted after [gatherStartedAt] (device time captured just
     * before the push gathered its payload) keeps its newer `updatedAt` and is left
     * for the next sync: the portal never saw that edit.
     *
     * @return the number of rows actually stamped.
     */
    suspend fun updateSessionTimestamps(
        sessionIds: Collection<String>,
        timestamp: Long,
        gatherStartedAt: Long,
    ): Int = 0

    /**
     * Stamp pushed personal records with the same timestamp used for
     * [updateSessionTimestamp] so they are not re-sent on every subsequent
     * push. PRs with NULL `updatedAt` would otherwise match every
     * `getFullPRsModifiedSince(lastSync, ...)` delta query indefinitely.
     *
     * Issue #528: without this, pre-fix PRs that already exist on the
     * portal are re-shipped on every sync because the push path only
     * stamped WorkoutSession rows.
     */
    suspend fun updatePersonalRecordTimestamp(prIds: List<Long>, timestamp: Long)

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
    suspend fun findExerciseId(name: String, muscleGroup: String? = null, exerciseId: String? = null): String?

    /**
     * Resolves the catalog muscle group for a performed exercise during push.
     *
     * WorkoutSession rows do not carry a muscle group of their own, so the push
     * path must look it up from the exercise catalog. Lookup strategy:
     * 1. Direct catalog ID match (unambiguous)
     * 2. Exact name match
     * 3. Case-insensitive name match
     *
     * @return The catalog muscle group, or null when the exercise is not in the
     *         catalog (ad-hoc / unknown movement). Callers default to "General".
     */
    suspend fun getExerciseMuscleGroup(exerciseId: String?, name: String?): String?

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
     * @param serverWinsRoutineIds Routines whose push the server rejected under LWW: the
     *   portal version is applied even if the local row was modified after [lastSync]
     */
    suspend fun mergeAllPullData(
        ownerUserId: String = "",
        workoutDeletions: List<PulledWorkoutDeletionDto> = emptyList(),
        sessions: List<WorkoutSession>,
        routines: List<PullRoutineDto>,
        cycles: List<PullTrainingCycleDto>,
        badges: List<EarnedBadgeSyncDto>,
        gamificationStats: GamificationStatsSyncDto?,
        personalRecords: List<PersonalRecordSyncDto>,
        lastSync: Long,
        profileId: String,
        serverWinsRoutineIds: Set<String> = emptySet(),
        sessionNotes: Map<String, SessionNotesEntry> = emptyMap(),
        sessionUpdatedAtById: Map<String, Long> = emptyMap(),
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

    /** Saves a local notes edit and dirties every live component in its portal parent. */
    suspend fun saveLocalSessionNotes(
        portalSessionId: String,
        notes: String?,
        updatedAtMillis: Long,
    ) {
        // Default no-op for fakes / older implementations.
    }

    suspend fun getSessionNotesForPortalParents(
        portalSessionIds: List<String>,
    ): Map<String, SessionNotesEntry> = emptyMap()

    /**
     * Hard-delete routines and cycles the server reports as deleted
     * (`deletedRoutineIds` / `deletedCycleIds` on pull, `skippedDeleted` on
     * push), together with their children. "Delete if present": unknown ids
     * are ignored. No local tombstone is left behind, so nothing is pushed
     * back (the server already knows). Delete wins over unsynced local edits
     * (`updatedAt > lastSync` for either entity); those are reported in the
     * result so the caller can notify the user. Cycle days that referenced a deleted routine
     * keep the day with `routine_id = NULL`, mirroring the server FK.
     * Local-only `cycle_routine_*` template routines used only by a deleted
     * cycle are removed with it. Discarded edits are not classified when
     * `lastSync == 0` (no sync base).
     *
     * [syncProfileId] is the profile captured for this sync. An unbound profile
     * may be mutated only when its id exactly matches this value. Profiles bound
     * to another account and other unbound profiles are always preserved.
     *
     * Default no-op so unrelated test fakes do not need to implement.
     */
    suspend fun applyServerDeletions(
        ownerUserId: String,
        routineIds: List<String>,
        cycleIds: List<String>,
        lastSync: Long,
        syncProfileId: String? = null,
    ): ServerDeletionResult = ServerDeletionResult()

    /**
     * Phase 3.3 (audit item #1): LWW pull merge for WorkoutSession rows.
     *
     * Replaces the legacy INSERT OR IGNORE behavior (`mergeAllPullData`)
     * which silently dropped server-newer rows. For each session, the
     * implementation reads the existing local `updatedAt`, compares to
     * `updatedAtBySessionId[session.id]`, and overwrites only when the
     * incoming timestamp is newer-or-equal. NULL existing or absent map
     * entry is treated as older (accept incoming) so first-time pulls
     * always write.
     *
     * `updatedAtBySessionId` is the authoritative server timestamp that
     * portal-sync-pull returns on `PullWorkoutSessionDto.updatedAt`. It
     * is keyed on the per-exercise WorkoutSession.id (which equals the
     * portal exercise id; one portal session expands to N mobile rows).
     *
     * Default no-op so unrelated test fakes do not need to implement.
     */
    suspend fun mergeSessionsLww(
        sessions: List<WorkoutSession>,
        updatedAtBySessionId: Map<String, Long>,
    ) {
        // Default no-op for fakes / older implementations.
    }
}

/** Side-table entry for session notes (Phase 3.5). */
data class SessionNotesEntry(
    val notes: String?,
    val updatedAtMillis: Long,
)
