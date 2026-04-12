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

    override suspend fun getSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSessionSyncDto> = sessionsToReturn

    override suspend fun getPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecordSyncDto> = prsToReturn

    override suspend fun getRoutinesModifiedSince(timestamp: Long, profileId: String): List<RoutineSyncDto> = legacyRoutinesToReturn

    override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> = customExercisesToReturn

    override suspend fun getBadgesModifiedSince(timestamp: Long, profileId: String): List<EarnedBadgeSyncDto> = badgesToReturn

    override suspend fun getGamificationStatsForSync(profileId: String): GamificationStatsSyncDto? = gamificationStatsToReturn

    // === Portal Push Operations ===

    override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSession> = workoutSessionsToReturn

    override suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String): List<Routine> = routinesToReturn

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

    override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>, profileId: String) {
        mergeBadgesCallCount++
        mergedBadges = badges
    }

    override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?, profileId: String) {
        mergeGamificationStatsCallCount++
        mergedGamificationStats = stats
    }

    override suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String) {
        mergePortalRoutinesCallCount++
        mergedPortalRoutines = routines
        mergedPortalRoutinesLastSync = lastSync
    }

    // === Post-Push Stamping ===

    var updatedSessionTimestamps: MutableMap<String, Long> = mutableMapOf()

    override suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long) {
        updatedSessionTimestamps[sessionId] = timestamp
    }

    // === Stubs for new sync interface methods (added for cycle/PR/phase/signature/assessment sync) ===

    override suspend fun getFullCyclesForSync(profileId: String): List<CycleWithContext> = emptyList()

    override suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecord> = emptyList()

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

    var mergedPersonalRecords: List<PersonalRecordSyncDto> = emptyList()
    var mergePersonalRecordsCallCount = 0

    override suspend fun mergePersonalRecords(records: List<PersonalRecordSyncDto>, profileId: String) {
        mergePersonalRecordsCallCount++
        mergedPersonalRecords = records
    }

    // === Exercise Lookup for Pull ===

    /** Configurable exercise ID lookup results for testing pull adapter */
    var exerciseIdLookupResults: Map<String, String> = emptyMap()
    var findExerciseIdCallCount = 0

    override suspend fun findExerciseId(name: String, muscleGroup: String?): String? {
        findExerciseIdCallCount++
        // Try with muscle group key first, then name-only key
        val keyWithMuscle = "$name:$muscleGroup"
        return exerciseIdLookupResults[keyWithMuscle]
            ?: exerciseIdLookupResults[name]
    }

    // === Atomic Pull Merge ===

    /** Captured data from atomic merge calls */
    var atomicMergeCallCount = 0
    var lastAtomicMergeSessions: List<WorkoutSession> = emptyList()
    var lastAtomicMergeRoutines: List<PullRoutineDto> = emptyList()
    var lastAtomicMergeCycles: List<PullTrainingCycleDto> = emptyList()
    var lastAtomicMergeBadges: List<EarnedBadgeSyncDto> = emptyList()
    var lastAtomicMergeGamificationStats: GamificationStatsSyncDto? = null
    var lastAtomicMergePersonalRecords: List<PersonalRecordSyncDto> = emptyList()
    var lastAtomicMergeLastSync: Long = 0L
    var lastAtomicMergeProfileId: String = ""

    /** Set to throw an exception to simulate atomic merge failure */
    var atomicMergeShouldFail: Boolean = false

    override suspend fun mergeAllPullData(
        sessions: List<WorkoutSession>,
        routines: List<PullRoutineDto>,
        cycles: List<PullTrainingCycleDto>,
        badges: List<EarnedBadgeSyncDto>,
        gamificationStats: GamificationStatsSyncDto?,
        personalRecords: List<PersonalRecordSyncDto>,
        lastSync: Long,
        profileId: String,
    ) {
        if (atomicMergeShouldFail) {
            throw RuntimeException("Simulated atomic merge failure for testing rollback")
        }

        atomicMergeCallCount++
        lastAtomicMergeSessions = sessions
        lastAtomicMergeRoutines = routines
        lastAtomicMergeCycles = cycles
        lastAtomicMergeBadges = badges
        lastAtomicMergeGamificationStats = gamificationStats
        lastAtomicMergePersonalRecords = personalRecords
        lastAtomicMergeLastSync = lastSync
        lastAtomicMergeProfileId = profileId

        // Also update the individual merge trackers for backward compatibility with existing tests
        // that check the individual merge call counts and captured data.
        // Only increment counters when there's actual data to merge (matching real behavior).
        if (sessions.isNotEmpty()) {
            mergePortalSessionsCallCount++
            mergedPortalSessions = sessions
        }

        if (routines.isNotEmpty()) {
            mergePortalRoutinesCallCount++
            mergedPortalRoutines = routines
            mergedPortalRoutinesLastSync = lastSync
        }

        if (badges.isNotEmpty()) {
            mergeBadgesCallCount++
            mergedBadges = badges
        }

        if (gamificationStats != null) {
            mergeGamificationStatsCallCount++
            mergedGamificationStats = gamificationStats
        }

        if (personalRecords.isNotEmpty()) {
            mergePersonalRecordsCallCount++
            mergedPersonalRecords = personalRecords
        }
    }
}
