package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.ProgressionRepository
import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake ProgressionRepository for testing.
 * Stores progression events in memory with flow support.
 */
class FakeProgressionRepository : ProgressionRepository {

    private val events = mutableMapOf<String, ProgressionEvent>()
    private val eventsByExercise = mutableMapOf<String, MutableList<String>>()
    private val eventsFlowByExercise = mutableMapOf<String, MutableStateFlow<List<ProgressionEvent>>>()
    private val pendingFlow = MutableStateFlow<List<ProgressionEvent>>(emptyList())

    fun reset() {
        events.clear()
        eventsByExercise.clear()
        eventsFlowByExercise.clear()
        pendingFlow.value = emptyList()
    }

    override suspend fun getProgressionEvents(exerciseId: String): List<ProgressionEvent> {
        return eventsByExercise[exerciseId]
            ?.mapNotNull { events[it] }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    override fun getProgressionEventsFlow(exerciseId: String): Flow<List<ProgressionEvent>> {
        val flow = eventsFlowByExercise.getOrPut(exerciseId) {
            MutableStateFlow(emptyList())
        }
        return flow.asStateFlow()
    }

    override suspend fun getLatestProgressionEvent(exerciseId: String): ProgressionEvent? {
        return getProgressionEvents(exerciseId).firstOrNull()
    }

    override suspend fun getPendingProgressions(): List<ProgressionEvent> {
        return events.values.filter { it.isPending() }.sortedByDescending { it.timestamp }
    }

    override fun getPendingProgressionsFlow(): Flow<List<ProgressionEvent>> {
        return pendingFlow.asStateFlow()
    }

    override suspend fun hasPendingProgression(exerciseId: String): Boolean {
        return eventsByExercise[exerciseId]
            ?.mapNotNull { events[it] }
            ?.any { it.isPending() }
            ?: false
    }

    override suspend fun createProgressionSuggestion(event: ProgressionEvent) {
        events[event.id] = event
        eventsByExercise.getOrPut(event.exerciseId) { mutableListOf() }
            .apply { if (!contains(event.id)) add(event.id) }
        updateExerciseFlow(event.exerciseId)
        updatePendingFlow()
    }

    override suspend fun recordResponse(
        eventId: String,
        response: ProgressionResponse,
        actualWeight: Float?
    ) {
        val current = events[eventId] ?: return
        events[eventId] = current.copy(userResponse = response, actualWeightKg = actualWeight)
        updateExerciseFlow(current.exerciseId)
        updatePendingFlow()
    }

    override suspend fun deleteProgressionEvent(eventId: String) {
        val current = events.remove(eventId) ?: return
        eventsByExercise[current.exerciseId]?.remove(eventId)
        updateExerciseFlow(current.exerciseId)
        updatePendingFlow()
    }

    override suspend fun deleteProgressionEventsForExercise(exerciseId: String) {
        eventsByExercise.remove(exerciseId)?.forEach { events.remove(it) }
        updateExerciseFlow(exerciseId)
        updatePendingFlow()
    }

    private fun updateExerciseFlow(exerciseId: String) {
        eventsFlowByExercise.getOrPut(exerciseId) { MutableStateFlow(emptyList()) }
            .value = run {
                eventsByExercise[exerciseId]
                    ?.mapNotNull { events[it] }
                    ?.sortedByDescending { it.timestamp }
                    ?: emptyList()
            }
    }

    private fun updatePendingFlow() {
        pendingFlow.value = events.values
            .filter { it.isPending() }
            .sortedByDescending { it.timestamp }
    }
}
