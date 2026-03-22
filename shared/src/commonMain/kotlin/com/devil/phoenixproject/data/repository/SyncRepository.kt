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
     * Get workout sessions modified since the given timestamp
     */
    suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto>

    /**
     * Get personal records modified since the given timestamp
     */
    suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto>

    /**
     * Get routines modified since the given timestamp
     */
    suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto>

    /**
     * Get custom exercises modified since the given timestamp
     */
    suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto>

    /**
     * Get earned badges modified since the given timestamp
     */
    suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto>

    /**
     * Get current gamification stats for sync
     */
    suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto?

    // === Portal Push Operations (full domain objects) ===

    /**
     * Get full WorkoutSession domain objects modified since timestamp.
     * Returns rich objects with routineSessionId, totalVolumeKg, etc. needed by PortalSyncAdapter.
     */
    suspend fun getWorkoutSessionsModifiedSince(timestamp: Long): List<WorkoutSession>

    /**
     * Get full Routine domain objects modified since timestamp.
     * Returns rich objects with exercises, supersets, etc. needed by PortalSyncAdapter.toPortalRoutine().
     */
    suspend fun getFullRoutinesModifiedSince(timestamp: Long): List<Routine>

    /**
     * Get all training cycles with their progress and progression context for push.
     * Returns all cycles (no delta — cycles lack updatedAt timestamps).
     */
    suspend fun getFullCyclesForSync(): List<CycleWithContext>

    /**
     * Get full PersonalRecord domain objects modified since timestamp.
     * Returns rich objects with prType, phase, and volume for PortalSyncAdapter PR metadata.
     */
    suspend fun getFullPRsModifiedSince(timestamp: Long): List<PersonalRecord>

    /**
     * Get phase statistics for the given session IDs.
     * Returns SQLDelight PhaseStatistics rows for conversion to portal DTOs.
     */
    suspend fun getPhaseStatisticsForSessions(
        sessionIds: List<String>
    ): List<com.devil.phoenixproject.database.PhaseStatistics>

    /**
     * Get all exercise signatures for sync push.
     */
    suspend fun getAllExerciseSignatures(): List<com.devil.phoenixproject.database.ExerciseSignature>

    /**
     * Get all VBT assessment results for sync push.
     */
    suspend fun getAllAssessments(profileId: String = "default"): List<com.devil.phoenixproject.database.AssessmentResult>

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
     * Merge badges from server
     */
    suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>)

    /**
     * Merge gamification stats from server
     */
    suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?)

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
}
