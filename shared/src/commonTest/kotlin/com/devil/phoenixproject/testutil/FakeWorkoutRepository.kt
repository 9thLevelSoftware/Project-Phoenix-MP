package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.PersonalRecordEntity
import com.devil.phoenixproject.data.repository.PhaseStatisticsData
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake WorkoutRepository for testing.
 * Provides in-memory storage for sessions, routines, and metrics.
 */
class FakeWorkoutRepository : WorkoutRepository {

    data class RecentCompletedRequest(
        val exerciseId: String,
        val profileId: String,
        val limit: Int,
    )

    private val sessions = mutableMapOf<String, WorkoutSession>()
    private val routines = mutableMapOf<String, Routine>()
    private val metrics = mutableMapOf<String, List<WorkoutMetric>>()
    private val personalRecords = mutableMapOf<String, PersonalRecordEntity>()
    private val phaseStatistics = mutableMapOf<String, PhaseStatisticsData>()

    private val _sessionsFlow = MutableStateFlow<List<WorkoutSession>>(emptyList())
    private val _routinesFlow = MutableStateFlow<List<Routine>>(emptyList())
    private val _personalRecordsFlow = MutableStateFlow<List<PersonalRecordEntity>>(emptyList())
    private val _phaseStatisticsFlow = MutableStateFlow<List<PhaseStatisticsData>>(emptyList())

    val recentCompletedRequests = mutableListOf<RecentCompletedRequest>()
    var recentCompletedFailure: Throwable? = null
    var mostRecentCompletedExerciseFailure: Throwable? = null

    // Test control methods
    fun addSession(session: WorkoutSession) {
        sessions[session.id] = session
        updateSessionsFlow()
    }

    fun addSessions(sessionList: List<WorkoutSession>) {
        sessionList.forEach { sessions[it.id] = it }
        updateSessionsFlow()
    }

    /**
     * Issue #591 follow-up test helper: snapshot every session the
     * fake holds, including zero-rep / ghost rows that the production
     * `getHistoryVisibleSessions` would filter out. Used by
     * `Issue591DeleteRoutineGroupTest` to assert the routine-group
     * delete cleans up hidden rows too.
     */
    fun allSessions(): List<WorkoutSession> = sessions.values.toList()

    fun addRoutine(routine: Routine) {
        routines[routine.id] = routine
        updateRoutinesFlow()
    }

    fun reset() {
        sessions.clear()
        routines.clear()
        metrics.clear()
        personalRecords.clear()
        phaseStatistics.clear()
        recentCompletedRequests.clear()
        recentCompletedFailure = null
        mostRecentCompletedExerciseFailure = null
        updateSessionsFlow()
        updateRoutinesFlow()
        updatePersonalRecordsFlow()
        updatePhaseStatisticsFlow()
    }

    private fun updateSessionsFlow() {
        _sessionsFlow.value = sessions.values.sortedByDescending { it.timestamp }
    }

    private fun updateRoutinesFlow() {
        _routinesFlow.value = routines.values.toList()
    }

    private fun updatePersonalRecordsFlow() {
        _personalRecordsFlow.value = personalRecords.values.toList()
    }

    private fun updatePhaseStatisticsFlow() {
        _phaseStatisticsFlow.value = phaseStatistics.values.toList()
    }

    // ========== WorkoutRepository interface implementation ==========

    override fun getAllSessions(profileId: String): Flow<List<WorkoutSession>> = _sessionsFlow

    // Issue #591: filter zero-rep / soft-deleted rows from Analytics view.
    // The Fake mirrors the SQL eligibility guard
    //   deletedAt IS NULL AND (workingReps > 0 OR totalReps > 0)
    // so unit tests using this fake exercise the same data the
    // production SqlDelightWorkoutRepository returns.
    override fun getHistoryVisibleSessions(profileId: String): Flow<List<WorkoutSession>> = _sessionsFlow.map { all ->
        all.filter { session ->
            // No deletedAt field on WorkoutSession today; when soft
            // delete lands, gate on it here too. For now the in-memory
            // fake never stores deleted rows, so only the profile and
            // positive-rep guards are required to match the SQL behavior.
            session.profileId == profileId &&
                (session.workingReps > 0 || session.totalReps > 0)
        }
    }

    override suspend fun getRecentCompletedSessionsForExercise(
        exerciseId: String,
        profileId: String,
        limit: Int,
    ): List<WorkoutSession> {
        require(exerciseId.isNotBlank())
        require(profileId.isNotBlank())
        require(limit in 1..MAX_RECENT_EXERCISE_SESSIONS)
        recentCompletedRequests += RecentCompletedRequest(exerciseId, profileId, limit)
        recentCompletedFailure?.let { throw it }
        return sessions.values
            .asSequence()
            .filter { it.profileId == profileId && it.exerciseId == exerciseId }
            .filter { it.workingReps > 0 || it.totalReps > 0 }
            .sortedWith(
                compareByDescending<WorkoutSession> { it.timestamp }
                    .thenByDescending { it.id },
            )
            .take(limit)
            .toList()
    }

    override suspend fun getMostRecentCompletedExerciseId(profileId: String): String? {
        require(profileId.isNotBlank())
        mostRecentCompletedExerciseFailure?.let { throw it }
        return sessions.values
            .asSequence()
            .filter { it.profileId == profileId }
            .filter { it.workingReps > 0 || it.totalReps > 0 }
            .filter { !it.exerciseId.isNullOrBlank() }
            .sortedWith(
                compareByDescending<WorkoutSession> { it.timestamp }
                    .thenByDescending { it.id },
            )
            .firstOrNull()
            ?.exerciseId
    }

    override suspend fun getSessionCountForExercise(exerciseId: String, profileId: String): Long = sessions.values.count { it.exerciseId == exerciseId }.toLong()

    override suspend fun saveSession(session: WorkoutSession) {
        sessions[session.id] = session
        updateSessionsFlow()
    }

    override suspend fun updateSessionExerciseTag(sessionId: String, exerciseId: String, exerciseName: String) {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = session.copy(
                exerciseId = exerciseId,
                exerciseName = exerciseName,
            )
            updateSessionsFlow()
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        metrics.remove(sessionId)
        updateSessionsFlow()
    }

    override suspend fun deleteAllSessions() {
        sessions.clear()
        metrics.clear()
        updateSessionsFlow()
    }

    override suspend fun deleteSessionsByRoutineSessionId(routineSessionId: String) {
        // Issue #591 follow-up: remove every session belonging to the
        // routine session id, including any zero-rep / ghost rows that
        // `getHistoryVisibleSessions` would have filtered out. Mirrors
        // the production soft-delete intent (zero-rep cleanup at the
        // group level) without the SQL deletedAt bookkeeping the
        // production repository maintains.
        val matchingIds = sessions.entries
            .filter { (_, session) -> session.routineSessionId == routineSessionId }
            .map { it.key }
        matchingIds.forEach { id ->
            sessions.remove(id)
            metrics.remove(id)
        }
        updateSessionsFlow()
    }

    override fun getRecentSessions(profileId: String, limit: Int): Flow<List<WorkoutSession>> = _sessionsFlow.map { it.take(limit) }

    override suspend fun getSession(sessionId: String): WorkoutSession? = sessions[sessionId]

    override suspend fun getSessionsForRoutineSession(
        profileId: String,
        routineSessionId: String,
    ): List<WorkoutSession> = sessions.values
        .filter {
            it.profileId == profileId &&
                it.routineSessionId == routineSessionId &&
                (it.workingReps > 0 || it.totalReps > 0)
        }
        .sortedBy { it.timestamp }

    override suspend fun getCompletedHealthExportCandidates(profileId: String): List<WorkoutSession> = sessions.values
        .filter { it.profileId == profileId && (it.workingReps > 0 || it.totalReps > 0) }
        .sortedBy { it.timestamp }

    override fun getAllRoutines(profileId: String): Flow<List<Routine>> = _routinesFlow

    override suspend fun saveRoutine(routine: Routine) {
        routines[routine.id] = routine
        updateRoutinesFlow()
    }

    override suspend fun updateRoutine(routine: Routine) {
        routines[routine.id] = routine
        updateRoutinesFlow()
    }

    override suspend fun deleteRoutine(routineId: String) {
        routines.remove(routineId)
        updateRoutinesFlow()
    }

    override suspend fun moveRoutineToProfile(routineId: String, targetProfileId: String) {
        routines[routineId]?.let { routine ->
            routines[routineId] = routine.copy(profileId = targetProfileId)
            updateRoutinesFlow()
        }
    }

    override suspend fun getRoutineById(routineId: String): Routine? = routines[routineId]

    override suspend fun markRoutineUsed(routineId: String) {
        routines[routineId]?.let { routine ->
            routines[routineId] = routine.copy(
                lastUsed = currentTimeMillis(),
                useCount = routine.useCount + 1,
            )
            updateRoutinesFlow()
        }
    }

    override suspend fun getAverageSetDurationMs(exerciseId: String, profileId: String): Long? = null

    override fun getAllPersonalRecords(profileId: String): Flow<List<PersonalRecordEntity>> = _personalRecordsFlow

    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String, profileId: String) {
        val key = "$exerciseId-$mode"
        val existing = personalRecords[key]
        val newVolume = weightKg * reps

        if (existing == null || newVolume > existing.weightPerCableKg * existing.reps) {
            personalRecords[key] = PersonalRecordEntity(
                id = existing?.id ?: personalRecords.size.toLong(),
                exerciseId = exerciseId,
                weightPerCableKg = weightKg,
                reps = reps,
                timestamp = currentTimeMillis(),
                workoutMode = mode,
            )
            updatePersonalRecordsFlow()
        }
    }

    override suspend fun saveMetrics(sessionId: String, metrics: List<WorkoutMetric>) {
        this.metrics[sessionId] = metrics
    }

    override fun getMetricsForSession(sessionId: String): Flow<List<WorkoutMetric>> = MutableStateFlow(metrics[sessionId] ?: emptyList())

    override suspend fun getMetricsForSessionSync(sessionId: String): List<WorkoutMetric> = metrics[sessionId] ?: emptyList()

    override suspend fun getRecentSessionsSync(profileId: String, limit: Int): List<WorkoutSession> = sessions.values.sortedByDescending {
        it.timestamp
    }.take(limit)

    override suspend fun savePhaseStatistics(sessionId: String, stats: HeuristicStatistics) {
        phaseStatistics[sessionId] = PhaseStatisticsData(
            id = phaseStatistics.size.toLong(),
            sessionId = sessionId,
            concentricKgAvg = stats.concentric.kgAvg,
            concentricKgMax = stats.concentric.kgMax,
            concentricVelAvg = stats.concentric.velAvg,
            concentricVelMax = stats.concentric.velMax,
            concentricWattAvg = stats.concentric.wattAvg,
            concentricWattMax = stats.concentric.wattMax,
            eccentricKgAvg = stats.eccentric.kgAvg,
            eccentricKgMax = stats.eccentric.kgMax,
            eccentricVelAvg = stats.eccentric.velAvg,
            eccentricVelMax = stats.eccentric.velMax,
            eccentricWattAvg = stats.eccentric.wattAvg,
            eccentricWattMax = stats.eccentric.wattMax,
            timestamp = currentTimeMillis(),
        )
        updatePhaseStatisticsFlow()
    }

    override fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>> = _phaseStatisticsFlow

    override suspend fun getVelocityPointsForExercise(
        exerciseId: String,
        profileId: String,
        sinceTimestampMs: Long,
    ): List<WorkoutVelocityPoint> = sessions.values
        .filter { s ->
            s.exerciseId == exerciseId &&
                s.profileId == profileId &&
                s.timestamp >= sinceTimestampMs &&
                s.avgMcvMmS != null &&
                s.workingReps > 0
        }
        .sortedByDescending { it.timestamp }
        .map { s ->
            WorkoutVelocityPoint(
                loadPerCableKg = s.workingAvgWeightKg ?: s.weightPerCableKg,
                mcvMmS = s.avgMcvMmS ?: 0f, // non-null guaranteed by the avgMcvMmS != null filter above
                timestampMs = s.timestamp,
                workingReps = s.workingReps,
            )
        }

    override suspend fun getExerciseIdsWithVelocityData(profileId: String): List<String> = sessions.values
        .filter { s ->
            s.profileId == profileId &&
                s.avgMcvMmS != null &&
                s.workingReps > 0 &&
                s.exerciseId != null
        }
        .map { it.exerciseId!! }
        .distinct()
}
