package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PlannedSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake CompletedSetRepository for testing.
 * Stores planned and completed sets in memory with basic filtering support.
 */
class FakeCompletedSetRepository : CompletedSetRepository {

    private val plannedSets = mutableMapOf<String, PlannedSet>()
    private val completedSets = mutableMapOf<String, CompletedSet>()
    private val plannedSetsByExercise = mutableMapOf<String, MutableList<String>>()
    private val completedSetsBySession = mutableMapOf<String, MutableList<String>>()
    private val sessionExerciseIds = mutableMapOf<String, String>()

    private val completedSetsFlows =
        mutableMapOf<String, MutableStateFlow<List<CompletedSet>>>()

    fun setSessionExercise(sessionId: String, exerciseId: String) {
        sessionExerciseIds[sessionId] = exerciseId
    }

    fun reset() {
        plannedSets.clear()
        completedSets.clear()
        plannedSetsByExercise.clear()
        completedSetsBySession.clear()
        sessionExerciseIds.clear()
        completedSetsFlows.clear()
    }

    // ==================== Planned Sets ====================

    override suspend fun getPlannedSets(routineExerciseId: String): List<PlannedSet> {
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

    override suspend fun savePlannedSets(sets: List<PlannedSet>) {
        sets.forEach { savePlannedSet(it) }
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

    override suspend fun deletePlannedSetsForExercise(routineExerciseId: String) {
        plannedSetsByExercise.remove(routineExerciseId)?.forEach { plannedSets.remove(it) }
    }

    // ==================== Completed Sets ====================

    override suspend fun getCompletedSets(sessionId: String): List<CompletedSet> {
        return completedSetsBySession[sessionId]
            ?.mapNotNull { completedSets[it] }
            ?.sortedBy { it.setNumber }
            ?: emptyList()
    }

    override fun getCompletedSetsFlow(sessionId: String): Flow<List<CompletedSet>> {
        val flow = completedSetsFlows.getOrPut(sessionId) {
            MutableStateFlow(emptyList())
        }
        return flow.asStateFlow()
    }

    override suspend fun getCompletedSetsForExercise(exerciseId: String): List<CompletedSet> {
        return completedSets.values
            .filter { sessionExerciseIds[it.sessionId] == exerciseId }
            .sortedByDescending { it.completedAt }
    }

    override suspend fun getRecentCompletedSetsForExercise(
        exerciseId: String,
        limit: Int
    ): List<CompletedSet> {
        return getCompletedSetsForExercise(exerciseId).take(limit)
    }

    override suspend fun saveCompletedSet(set: CompletedSet) {
        completedSets[set.id] = set
        completedSetsBySession.getOrPut(set.sessionId) { mutableListOf() }
            .apply { if (!contains(set.id)) add(set.id) }
        updateCompletedFlow(set.sessionId)
    }

    override suspend fun saveCompletedSets(sets: List<CompletedSet>) {
        sets.forEach { saveCompletedSet(it) }
    }

    override suspend fun updateRpe(setId: String, rpe: Int) {
        val current = completedSets[setId] ?: return
        completedSets[setId] = current.copy(loggedRpe = rpe)
        updateCompletedFlow(current.sessionId)
    }

    override suspend fun markAsPr(setId: String) {
        val current = completedSets[setId] ?: return
        completedSets[setId] = current.copy(isPr = true)
        updateCompletedFlow(current.sessionId)
    }

    override suspend fun deleteCompletedSet(setId: String) {
        val current = completedSets.remove(setId) ?: return
        completedSetsBySession[current.sessionId]?.remove(setId)
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
