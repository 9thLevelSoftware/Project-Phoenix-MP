package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.SyncRepository
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
import com.devil.phoenixproject.database.AssessmentResult
import com.devil.phoenixproject.database.ExerciseSignature
import com.devil.phoenixproject.database.PhaseStatistics
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Fake SyncRepository for testing SyncManager.
 * Provides configurable return values for push operations and captures merge calls from pull.
 */
class FakeSyncRepository : SyncRepository {

    // === Configurable return values for push ===

    var workoutSessionsToReturn: List<WorkoutSession> = emptyList()
    var prsToReturn: List<PersonalRecordSyncDto> = emptyList()
    var routinesToReturn: List<Routine> = emptyList()
    var gamificationStatsToReturn: GamificationStatsSyncDto? = null

    // Legacy push methods (not used by SyncManager portal flow)
    var sessionsToReturn: List<WorkoutSessionSyncDto> = emptyList()
    var legacyRoutinesToReturn: List<RoutineSyncDto> = emptyList()
    var customExercisesToReturn: List<CustomExerciseSyncDto> = emptyList()
    var badgesToReturn: List<EarnedBadgeSyncDto> = emptyList()

    // === Captured merge calls ===

    var mergedPortalRoutines: List<PullRoutineDto> = emptyList()
    var mergedPortalRoutinesLastSync: Long? = null
    var mergedBadges: List<EarnedBadgeSyncDto> = emptyList()
    var mergedGamificationStats: GamificationStatsSyncDto? = null
    var mergedSessions: List<WorkoutSessionSyncDto> = emptyList()
    var mergedPRs: List<PersonalRecordSyncDto> = emptyList()
    var mergedRoutines: List<RoutineSyncDto> = emptyList()
    var mergedCustomExercises: List<CustomExerciseSyncDto> = emptyList()
    var updatedIdMappings: IdMappings? = null

    // === Call counters ===

    var mergePortalRoutinesCallCount = 0
    var mergeBadgesCallCount = 0
    var mergeGamificationStatsCallCount = 0

    // === Push Operations ===

    override suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto> {
        return sessionsToReturn
    }

    override suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto> {
        return prsToReturn
    }

    override suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto> {
        return legacyRoutinesToReturn
    }

    override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> {
        return customExercisesToReturn
    }

    override suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto> {
        return badgesToReturn
    }

    override suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto? {
        return gamificationStatsToReturn
    }

    // === Portal Push Operations ===

    override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long): List<WorkoutSession> {
        return workoutSessionsToReturn
    }

    override suspend fun getFullRoutinesModifiedSince(timestamp: Long): List<Routine> {
        return routinesToReturn
    }

    // === ID Mapping ===

    override suspend fun updateServerIds(mappings: IdMappings) {
        updatedIdMappings = mappings
    }

    // === Pull Operations (merge) ===

    override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) {
        mergedSessions = sessions
    }

    override suspend fun mergePRs(records: List<PersonalRecordSyncDto>) {
        mergedPRs = records
    }

    override suspend fun mergeRoutines(routines: List<RoutineSyncDto>) {
        mergedRoutines = routines
    }

    override suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>) {
        mergedCustomExercises = exercises
    }

    override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>) {
        mergeBadgesCallCount++
        mergedBadges = badges
    }

    override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?) {
        mergeGamificationStatsCallCount++
        mergedGamificationStats = stats
    }

    override suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String) {
        mergePortalRoutinesCallCount++
        mergedPortalRoutines = routines
        mergedPortalRoutinesLastSync = lastSync
    }

    // === Stubs for new sync interface methods (added for cycle/PR/phase/signature/assessment sync) ===

    override suspend fun getFullCyclesForSync(): List<CycleWithContext> = emptyList()

    override suspend fun getFullPRsModifiedSince(timestamp: Long): List<PersonalRecord> = emptyList()

    override suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<PhaseStatistics> = emptyList()

    override suspend fun getAllExerciseSignatures(): List<ExerciseSignature> = emptyList()

    override suspend fun getAllAssessments(profileId: String): List<AssessmentResult> = emptyList()

    override suspend fun mergePortalCycles(cycles: List<PullTrainingCycleDto>, profileId: String) {
        // no-op for tests
    }

    var mergedPortalSessions: List<WorkoutSession> = emptyList()
    var mergePortalSessionsCallCount = 0

    override suspend fun mergePortalSessions(sessions: List<WorkoutSession>) {
        mergePortalSessionsCallCount++
        mergedPortalSessions = sessions
    }
}
