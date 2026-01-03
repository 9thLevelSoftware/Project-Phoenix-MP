package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.PersonalRecordEntity
import com.devil.phoenixproject.data.repository.PhaseStatisticsData
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake WorkoutRepository for testing.
 * Provides in-memory storage for sessions, routines, and metrics.
 */
class FakeWorkoutRepository : WorkoutRepository {

    private val sessions = mutableMapOf<String, WorkoutSession>()
    private val routines = mutableMapOf<String, Routine>()
    private val metrics = mutableMapOf<String, List<WorkoutMetric>>()
    private val personalRecords = mutableMapOf<String, PersonalRecordEntity>()
    private val phaseStatistics = mutableMapOf<String, PhaseStatisticsData>()

    private val _sessionsFlow = MutableStateFlow<List<WorkoutSession>>(emptyList())
    private val _routinesFlow = MutableStateFlow<List<Routine>>(emptyList())
    private val _personalRecordsFlow = MutableStateFlow<List<PersonalRecordEntity>>(emptyList())
    private val _phaseStatisticsFlow = MutableStateFlow<List<PhaseStatisticsData>>(emptyList())

    // Test control methods
    fun addSession(session: WorkoutSession) {
        val id = session.id ?: "session-${sessions.size}"
        sessions[id] = session.copy(id = id)
        updateSessionsFlow()
    }

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

    override fun getAllSessions(): Flow<List<WorkoutSession>> = _sessionsFlow

    override suspend fun saveSession(session: WorkoutSession) {
        val id = session.id ?: "session-${sessions.size}"
        sessions[id] = session.copy(id = id)
        updateSessionsFlow()
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

    override fun getRecentSessions(limit: Int): Flow<List<WorkoutSession>> =
        _sessionsFlow.map { it.take(limit) }

    override suspend fun getSession(sessionId: String): WorkoutSession? = sessions[sessionId]

    override fun getAllRoutines(): Flow<List<Routine>> = _routinesFlow

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

    override suspend fun getRoutineById(routineId: String): Routine? = routines[routineId]

    override suspend fun markRoutineUsed(routineId: String) {
        routines[routineId]?.let { routine ->
            routines[routineId] = routine.copy(
                lastUsed = currentTimeMillis(),
                useCount = routine.useCount + 1
            )
            updateRoutinesFlow()
        }
    }

    override fun getAllPersonalRecords(): Flow<List<PersonalRecordEntity>> = _personalRecordsFlow

    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String) {
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
                workoutMode = mode
            )
            updatePersonalRecordsFlow()
        }
    }

    override suspend fun saveMetrics(sessionId: String, metrics: List<WorkoutMetric>) {
        this.metrics[sessionId] = metrics
    }

    override fun getMetricsForSession(sessionId: String): Flow<List<WorkoutMetric>> {
        return MutableStateFlow(metrics[sessionId] ?: emptyList())
    }

    override suspend fun getMetricsForSessionSync(sessionId: String): List<WorkoutMetric> {
        return metrics[sessionId] ?: emptyList()
    }

    override suspend fun getRecentSessionsSync(limit: Int): List<WorkoutSession> {
        return sessions.values.sortedByDescending { it.timestamp }.take(limit)
    }

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
            timestamp = currentTimeMillis()
        )
        updatePhaseStatisticsFlow()
    }

    override fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>> = _phaseStatisticsFlow
}
