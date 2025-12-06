package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionReason
import com.devil.phoenixproject.domain.model.ProgressionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of ProgressionRepository for auto-progression tracking.
 * Manages weight increase suggestions and user responses using the database.
 */
class SqlDelightProgressionRepository(
    db: VitruvianDatabase
) : ProgressionRepository {

    private val queries = db.vitruvianDatabaseQueries

    /**
     * Maps a database row to a ProgressionEvent domain model.
     */
    private fun mapToProgressionEvent(
        id: String,
        exerciseId: String,
        suggestedWeightKg: Double,
        previousWeightKg: Double,
        reason: String,
        userResponse: String?,
        actualWeightKg: Double?,
        timestamp: Long
    ): ProgressionEvent {
        return ProgressionEvent(
            id = id,
            exerciseId = exerciseId,
            suggestedWeightKg = suggestedWeightKg.toFloat(),
            previousWeightKg = previousWeightKg.toFloat(),
            reason = ProgressionReason.valueOf(reason),
            userResponse = userResponse?.let { ProgressionResponse.valueOf(it) },
            actualWeightKg = actualWeightKg?.toFloat(),
            timestamp = timestamp
        )
    }

    override suspend fun getProgressionEvents(exerciseId: String): List<ProgressionEvent> {
        return withContext(Dispatchers.IO) {
            queries.selectProgressionEventsByExercise(exerciseId, ::mapToProgressionEvent).executeAsList()
        }
    }

    override fun getProgressionEventsFlow(exerciseId: String): Flow<List<ProgressionEvent>> {
        return queries.selectProgressionEventsByExercise(exerciseId, ::mapToProgressionEvent)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun getLatestProgressionEvent(exerciseId: String): ProgressionEvent? {
        return withContext(Dispatchers.IO) {
            queries.selectRecentProgressionEvent(exerciseId, ::mapToProgressionEvent).executeAsOneOrNull()
        }
    }

    override suspend fun getPendingProgressions(): List<ProgressionEvent> {
        return withContext(Dispatchers.IO) {
            queries.selectPendingProgressionEvents(::mapToProgressionEvent).executeAsList()
        }
    }

    override fun getPendingProgressionsFlow(): Flow<List<ProgressionEvent>> {
        return queries.selectPendingProgressionEvents(::mapToProgressionEvent)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun hasPendingProgression(exerciseId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val latestEvent = queries.selectRecentProgressionEvent(exerciseId, ::mapToProgressionEvent)
                .executeAsOneOrNull()
            latestEvent?.isPending() == true
        }
    }

    override suspend fun createProgressionSuggestion(event: ProgressionEvent) {
        withContext(Dispatchers.IO) {
            queries.insertProgressionEvent(
                id = event.id,
                exercise_id = event.exerciseId,
                suggested_weight_kg = event.suggestedWeightKg.toDouble(),
                previous_weight_kg = event.previousWeightKg.toDouble(),
                reason = event.reason.name,
                user_response = event.userResponse?.name,
                actual_weight_kg = event.actualWeightKg?.toDouble(),
                timestamp = event.timestamp
            )
        }
    }

    override suspend fun recordResponse(
        eventId: String,
        response: ProgressionResponse,
        actualWeight: Float?
    ) {
        withContext(Dispatchers.IO) {
            queries.updateProgressionEventResponse(
                user_response = response.name,
                actual_weight_kg = actualWeight?.toDouble(),
                id = eventId
            )
        }
    }

    override suspend fun deleteProgressionEvent(eventId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteProgressionEvent(id = eventId)
        }
    }

    override suspend fun deleteProgressionEventsForExercise(exerciseId: String) {
        withContext(Dispatchers.IO) {
            val events = queries.selectProgressionEventsByExercise(exerciseId, ::mapToProgressionEvent)
                .executeAsList()
            events.forEach { event ->
                queries.deleteProgressionEvent(event.id)
            }
        }
    }
}
