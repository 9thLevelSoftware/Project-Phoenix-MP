package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

const val MAX_RECENT_EXERCISE_SESSIONS = 5

/** Diagnostics for one Issue #1162 identity maintenance run. */
data class RoutineIdentityMaintenanceResult(
    val componentsScanned: Int = 0,
    val componentsReconciled: Int = 0,
    val aliasesRemoved: Int = 0,
    val snapshotsRetained: Int = 0,
    val tombstoneComponents: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
)

/**
 * Workout Repository interface.
 * Implemented by SqlDelightWorkoutRepository for type-safe database operations.
 */
interface WorkoutRepository {
    // Workout sessions
    fun getAllSessions(profileId: String): Flow<List<WorkoutSession>>
    suspend fun saveSession(session: WorkoutSession)

    /**
     * Commit one completed set — the session row, its raw metric samples, its
     * `CompletedSet`, its rep metrics and its rep biomechanics — in a SINGLE
     * transaction.
     *
     * Before this existed the six writes were six transactions, so a process
     * death or a concurrent portal push could see a session without its sets:
     * the push stamps the session as synced and the set that lands afterwards
     * is never re-pushed. Either the whole set is durable or none of it is.
     *
     * Idempotent, with the same guards the step-by-step save used: the session
     * is inserted only when absent, the `CompletedSet` only when its id is
     * absent, and the metric / rep-metric / rep-biomechanics rows are replaced
     * wholesale. Re-committing the same snapshot after a later step failed
     * therefore changes nothing — including an `is_pr` flag already set.
     */
    suspend fun commitCompletedSet(
        session: WorkoutSession,
        metrics: List<WorkoutMetric>,
        completedSet: CompletedSet?,
        repMetrics: List<RepMetricData>,
        repBiomechanics: List<BiomechanicsRepResult>,
    )
    suspend fun updateSessionExerciseTag(sessionId: String, exerciseId: String, exerciseName: String)

    /**
     * #972: remove a Just Lift exercise label. Writes SQL NULL to exerciseId/exerciseName
     * (never a blank string) and bumps local_sync_generation once so the correction syncs.
     * The workout row, its completed sets, and personal-record rows are kept.
     */
    suspend fun clearSessionExerciseTag(sessionId: String)
    /** User-facing deletion. Records a durable tombstone before hard-deleting local data. */
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteAllSessions(profileId: String)

    /** Internal rollback/compensation path. Never creates a user deletion tombstone. */
    suspend fun discardSessionInternal(sessionId: String)

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): delete every
     * WorkoutSession row that belongs to the given profile and routine session id.
     * Used by the History "Delete All Sets" affordance so zero-rep /
     * ghost rows hidden by `getHistoryVisibleSessions` do not survive
     * the user-level deletion. The repository records one durable workout
     * tombstone before hard-deleting the complete group.
     */
    suspend fun deleteSessionsByRoutineSessionId(profileId: String, routineSessionId: String)

    /**
     * Get the newest user-visible workout sessions (soft-deleted rows excluded, newest first),
     * observed as a flow. Same rows and order as the head of [getAllSessions], without loading
     * the rest of the history (F-034).
     * @param profileId Profile to filter by
     * @param limit Maximum number of sessions to return
     */
    fun getRecentSessions(profileId: String, limit: Int = 10): Flow<List<WorkoutSession>>

    /**
     * Per-cable weight of the newest user-visible session of [exerciseId] in [profileId],
     * or null when the profile has never done it (F-034: one indexed row, not the history).
     */
    suspend fun getLastWeightForExercise(profileId: String, exerciseId: String): Float?

    /**
     * Issue #591: Workout sessions that should appear in the Analytics /
     * History UI. Excludes soft-deleted rows and rows with zero recorded
     * reps (workingReps == 0 AND totalReps == 0), which historically come
     * from pre-completion saves, routine restart mid-set, and zero-rep
     * import paths. Counting those as sets inflates routine set totals
     * (e.g. "Incline Fly 0 reps / 6 sets") and triggers the misleading
     * pre-v0.2.1 placeholder card in per-set drill-down.
     *
     * Mirrors the eligibility guards used by
     * `selectCompletedHealthExportCandidates` /
     * `selectSessionsByRoutineSessionId` / `selectSessionsForPhasePRBackfill`.
     */
    fun getHistoryVisibleSessions(profileId: String): Flow<List<WorkoutSession>>

    suspend fun getRecentCompletedSessionsForExercise(
        exerciseId: String,
        profileId: String,
        limit: Int = MAX_RECENT_EXERCISE_SESSIONS,
    ): List<WorkoutSession>

    suspend fun getMostRecentCompletedExerciseId(profileId: String): String?

    /**
     * Get a specific workout session by ID
     */
    suspend fun getSession(sessionId: String): WorkoutSession?

    /**
     * Get completed workout sessions belonging to a routine session.
     */
    suspend fun getSessionsForRoutineSession(profileId: String, routineSessionId: String): List<WorkoutSession>

    /**
     * Get completed local sessions that can be exported to platform health stores.
     */
    suspend fun getCompletedHealthExportCandidates(profileId: String): List<WorkoutSession>

    // Routines
    fun getAllRoutines(profileId: String): Flow<List<Routine>>
    suspend fun saveRoutine(routine: Routine)
    suspend fun updateRoutine(routine: Routine)
    suspend fun deleteRoutine(routineId: String)
    suspend fun moveRoutineToProfile(routineId: String, targetProfileId: String)
    suspend fun getRoutineById(routineId: String): Routine?

    /**
     * Issue #1162: one-shot owner/profile-scoped routine identity maintenance,
     * run transactionally before the first routine-list emission for [profileId]
     * (offline launch and profile switch included), independent of named writes
     * and network pulls. Already-split alias rows reconcile into one card; a
     * failure rolls back and leaves the original rows visible. Idempotent.
     */
    suspend fun runRoutineIdentityMaintenance(profileId: String): RoutineIdentityMaintenanceResult =
        RoutineIdentityMaintenanceResult()

    /**
     * Issue #1162: routines retained in the local-only recovery store before a
     * destructive coalesce or server hard delete, shown only for the signed-in
     * [portalUserId] and the active [profileId]. Authorization is resolved at
     * execution time against the AUTHENTICATED portal identity and the active
     * profile (never stored linkage or caller-supplied strings alone); an
     * unauthorized scope sees an empty list, and anonymous snapshots stay
     * retained but hidden.
     */
    suspend fun listRoutineRecoveries(profileId: String, portalUserId: String): List<RoutineRecoveryItem> =
        emptyList()

    /**
     * Issue #1162 (final audit R5): observes the recoverable routines of one
     * signed-in [portalUserId] and active [profileId], re-emitting when locally
     * retained snapshots change so an already-mounted screen discovers new
     * recoveries without a remount. Re-authorized at every emission; an
     * unauthorized scope emits an empty list.
     */
    fun observeRoutineRecoveries(profileId: String, portalUserId: String): Flow<List<RoutineRecoveryItem>> =
        emptyFlow()

    /**
     * Issue #1162: explicit restore-as-copy of one retained routine graph — fresh
     * routine and child UUIDs in [profileId], never the server-deleted identity,
     * never a silent cycle or history reconnect. Returns the new routine id.
     */
    suspend fun restoreRoutineRecoveryAsCopy(
        recoveryId: String,
        graphIndex: Int,
        profileId: String,
        portalUserId: String,
    ): String? = null

    /**
     * The profile's live routines without their exercises (#772). Unlike [getAllRoutines] and
     * [getRoutineById], this never writes: it runs no exercise-id repair.
     */
    suspend fun getRoutineHeaders(profileId: String): List<Routine>

    /** The profile's routine groups, read once (#772). */
    suspend fun getRoutineGroupsSnapshot(profileId: String): List<RoutineGroup>

    /**
     * Stores a confirmed CSV import (#772) in one transaction: [newGroups], then every routine
     * in [routines] with its supersets and exercises. Routines in [overwriteRoutineIds] are
     * replaced in place and must still be live routines of [profileId]; otherwise nothing is
     * written and [RoutineCsvImportConflictException] is thrown.
     */
    suspend fun commitRoutineCsvImport(
        profileId: String,
        newGroups: List<RoutineGroup>,
        routines: List<Routine>,
        overwriteRoutineIds: Set<String>,
    )

    /**
     * Get average set duration in milliseconds for a specific exercise.
     * Returns null if no historical data is available.
     * Issue #225: Used by RoutineTimeEstimator.
     */
    suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long?

    /**
     * Get the number of completed sessions for a specific exercise.
     * Issue #225: Used by RoutineTimeEstimator to enforce minimum session threshold.
     */
    suspend fun getSessionCountForExercise(exerciseId: String, profileId: String): Long

    /**
     * Get recent workout sessions synchronously (for export / import de-duplication).
     * Unlike [getRecentSessions] this includes soft-deleted rows.
     */
    suspend fun getRecentSessionsSync(profileId: String, limit: Int = 10): List<WorkoutSession>

    /**
     * Issue #517: Velocity-based 1RM foundation.
     * Returns per-set [WorkoutVelocityPoint] records for a given exercise within the
     * time window [sinceTimestampMs, now]. Points with null MCV or zero working reps
     * are excluded. Load uses [workingAvgWeightKg] when captured, else [weightPerCableKg].
     */
    suspend fun getVelocityPointsForExercise(
        exerciseId: String,
        profileId: String,
        sinceTimestampMs: Long,
    ): List<WorkoutVelocityPoint>

    /**
     * Issue #517 Phase 5 T1: enumerate distinct exercise IDs that have at least one
     * non-deleted set with a captured MCV value and at least one working rep, for the
     * given profile. Used by the velocity-1RM pipeline to decide which exercises are
     * ready to estimate.
     */
    suspend fun getExerciseIdsWithVelocityData(profileId: String): List<String>
}

/** A routine a CSV import was going to overwrite was deleted or moved to another profile (#772). */
class RoutineCsvImportConflictException(routineId: String) :
    IllegalStateException("Routine $routineId changed since the import was previewed")
