package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.collapseCompletedSetsToLatestLogicalAttempts
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake CompletedSetRepository for testing.
 * Stores planned and completed sets in memory with basic filtering support.
 */
open class FakeCompletedSetRepository : CompletedSetRepository {

    val saved = mutableListOf<CompletedSet>()

    /**
     * Every raw insert, including those [com.devil.phoenixproject.data.repository.WorkoutRepository.commitCompletedSet]
     * performs through this fake. Recorded before [beforeInsertCompletedSet], so a hook that
     * suspends or fails still shows the attempt.
     */
    val insertedCompletedSets = mutableListOf<CompletedSet>()
    var beforeInsertCompletedSet: suspend (CompletedSet) -> Unit = {}
    var beforeMarkAsPr: suspend (String) -> Unit = {}
    var afterInsertCompletedSet: suspend (CompletedSet) -> Unit = {}

    private val plannedSets = mutableMapOf<String, PlannedSet>()
    private val completedSets = mutableMapOf<String, CompletedSet>()
    private val plannedSetsByExercise = mutableMapOf<String, MutableList<String>>()
    val plannedSetReadRequests = mutableListOf<String>()
    var beforeAttemptDurabilityRead: suspend () -> Unit = {}
    var attemptDurabilityReadCount: Int = 0
    private val completedSetsBySession = mutableMapOf<String, MutableList<String>>()
    private val sessionExerciseIds = mutableMapOf<String, String>()
    private val sessionRoutineIds = mutableMapOf<String, String>()
    private val deletedSessionIds = mutableSetOf<String>()

    private val completedSetsFlows =
        mutableMapOf<String, MutableStateFlow<List<CompletedSet>>>()

    fun setSessionRoutine(sessionId: String, routineSessionId: String) {
        sessionRoutineIds[sessionId] = routineSessionId
    }

    fun softDeleteSession(sessionId: String) {
        deletedSessionIds += sessionId
    }

    // ==================== Planned Sets ====================

    override suspend fun getPlannedSets(routineExerciseId: String): List<PlannedSet> {
        plannedSetReadRequests += routineExerciseId
        return plannedSetsByExercise[routineExerciseId]
            ?.mapNotNull { plannedSets[it] }
            ?.sortedBy { it.setNumber }
            ?: emptyList()
    }

    override suspend fun savePlannedSet(set: PlannedSet) {
        plannedSets[set.id] = set
        plannedSetsByExercise.getOrPut(set.routineExerciseId) { mutableListOf() }
            .apply { if (!contains(set.id)) add(set.id) }
    }

    override suspend fun updatePlannedSet(set: PlannedSet) {
        plannedSets[set.id] = set
        plannedSetsByExercise.getOrPut(set.routineExerciseId) { mutableListOf() }
            .apply { if (!contains(set.id)) add(set.id) }
    }

    override suspend fun deletePlannedSet(setId: String) {
        val set = plannedSets.remove(setId) ?: return
        plannedSetsByExercise[set.routineExerciseId]?.remove(setId)
    }

    // ==================== Completed Sets ====================

    override suspend fun getCompletedSets(sessionId: String): List<CompletedSet> = completedSetsBySession[sessionId]
        ?.mapNotNull { completedSets[it] }
        ?.sortedBy { it.setNumber }
        ?: emptyList()

    open override suspend fun getCompletedSetsForSessions(sessionIds: List<String>): List<CompletedSet> = sessionIds
        .flatMap { sessionId -> getCompletedSets(sessionId) }

    override fun getCompletedSetsFlow(sessionId: String): Flow<List<CompletedSet>> {
        val flow = completedSetsFlows.getOrPut(sessionId) {
            MutableStateFlow(emptyList())
        }
        return flow.asStateFlow()
    }

    override suspend fun getCompletedSetsForExercise(exerciseId: String): List<CompletedSet> = completedSets.values
        .filter { sessionExerciseIds[it.sessionId] == exerciseId }
        .sortedByDescending { it.completedAt }

    // profileId is accepted to match the interface; this fake does not model
    // per-session profiles, so profile scoping is exercised by the SQL-backed
    // repository, not here.
    override suspend fun getRecentCompletedSetsForExercise(exerciseId: String, limit: Int, profileId: String): List<CompletedSet> {
        val recent = getCompletedSetsForExercise(exerciseId).filter { it.sessionId !in deletedSessionIds }
        return collapseCompletedSetsToLatestLogicalAttempts(recent, sessionRoutineIds::get).take(limit)
    }

    /**
     * Test-only raw insert. Production writes a completed set through
     * [com.devil.phoenixproject.data.repository.WorkoutRepository.commitCompletedSet],
     * which reaches this fake the same way.
     */
    suspend fun insertCompletedSet(set: CompletedSet) {
        val canonicalSet = set.copy(attemptNumber = set.attemptNumber.coerceAtLeast(1))
        insertedCompletedSets += canonicalSet
        beforeInsertCompletedSet(canonicalSet)
        completedSets[canonicalSet.id] = canonicalSet
        completedSetsBySession.getOrPut(canonicalSet.sessionId) { mutableListOf() }
            .apply { if (!contains(canonicalSet.id)) add(canonicalSet.id) }
        updateCompletedFlow(canonicalSet.sessionId)
        saved += canonicalSet
        afterInsertCompletedSet(canonicalSet)
    }

    override suspend fun ensureCompletedSetForTaggedJustLift(session: WorkoutSession, isAmrap: Boolean): CompletedSet? {
        session.exerciseId?.let { exerciseId ->
            sessionExerciseIds[session.id] = exerciseId
        }

        getCompletedSets(session.id).firstOrNull()?.let { return it }

        val actualReps = (if (session.workingReps > 0) session.workingReps else session.totalReps)
            .coerceAtLeast(0)
        if (actualReps <= 0) return null

        val completedSet = CompletedSet(
            id = generateUUID(),
            sessionId = session.id,
            plannedSetId = null,
            setNumber = 0,
            setType = if (isAmrap) SetType.AMRAP else SetType.STANDARD,
            actualReps = actualReps,
            actualWeightKg = session.weightPerCableKg,
            loggedRpe = session.rpe,
            isPr = false,
            completedAt = session.timestamp + session.duration,
        )
        insertCompletedSet(completedSet)
        return completedSet
    }

    override suspend fun nextAttemptNumber(key: LogicalSetKey): Int = completedSets.values
        .asSequence()
        .filter { it.sessionId !in deletedSessionIds }
        .filter { sessionRoutineIds[it.sessionId] == key.routineSessionId }
        .filter { it.routineExerciseId == key.routineExerciseId }
        .filter { it.setNumber == key.setIndex }
        .filter { it.setType == key.setKind }
        .maxOfOrNull { it.attemptNumber.coerceAtLeast(1) }
        ?.plus(1)
        ?: 1

    override suspend fun isAttemptDurable(
        stableSessionId: String,
        key: LogicalSetKey,
        attemptNumber: Int,
    ): Boolean {
        attemptDurabilityReadCount++
        beforeAttemptDurabilityRead()
        return attemptNumber >= 1 &&
            stableSessionId !in deletedSessionIds &&
            sessionRoutineIds[stableSessionId] == key.routineSessionId &&
            completedSets.values.any {
                it.sessionId == stableSessionId &&
                    it.routineExerciseId == key.routineExerciseId &&
                    it.setNumber == key.setIndex &&
                    it.setType == key.setKind &&
                    it.attemptNumber.coerceAtLeast(1) == attemptNumber
            }
    }

    override suspend fun updateRpe(setId: String, rpe: Int) {
        val current = completedSets[setId] ?: return
        completedSets[setId] = current.copy(loggedRpe = rpe)
        updateCompletedFlow(current.sessionId)
    }

    override suspend fun markAsPr(setId: String) {
        beforeMarkAsPr(setId)
        val current = completedSets[setId] ?: return
        completedSets[setId] = current.copy(isPr = true)
        updateCompletedFlow(current.sessionId)
    }

    override suspend fun clearPr(setId: String) {
        val current = completedSets[setId] ?: return
        completedSets[setId] = current.copy(isPr = false)
        updateCompletedFlow(current.sessionId)
    }

    override suspend fun deleteCompletedSetsForSession(sessionId: String) {
        completedSetsBySession.remove(sessionId)?.forEach { completedSets.remove(it) }
        updateCompletedFlow(sessionId)
    }

    private fun updateCompletedFlow(sessionId: String) {
        val sets = completedSetsBySession[sessionId]
            ?.mapNotNull { completedSets[it] }
            ?.sortedBy { it.setNumber }
            ?: emptyList()
        completedSetsFlows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
            .value = sets
    }
}
