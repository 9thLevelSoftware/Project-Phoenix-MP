package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionResponse
import kotlinx.coroutines.flow.Flow

/**
 * Repository for auto-progression tracking.
 * Manages weight increase suggestions and user responses.
 */
interface ProgressionRepository {

    /**
     * Get all progression events for an exercise, ordered by timestamp (newest first).
     */
    suspend fun getProgressionEvents(exerciseId: String): List<ProgressionEvent>

    /**
     * Get progression events as a Flow for reactive updates.
     */
    fun getProgressionEventsFlow(exerciseId: String): Flow<List<ProgressionEvent>>

    /**
     * Get the most recent progression event for an exercise.
     */
    suspend fun getLatestProgressionEvent(exerciseId: String): ProgressionEvent?

    /**
     * Get all pending progression suggestions (user hasn't responded yet).
     */
    suspend fun getPendingProgressions(): List<ProgressionEvent>

    /**
     * Get pending progression as a Flow for reactive updates.
     */
    fun getPendingProgressionsFlow(): Flow<List<ProgressionEvent>>

    /**
     * Check if there's a pending progression for an exercise.
     */
    suspend fun hasPendingProgression(exerciseId: String): Boolean

    /**
     * Create a new progression suggestion.
     */
    suspend fun createProgressionSuggestion(event: ProgressionEvent)

    /**
     * Record user's response to a progression suggestion.
     * @param eventId The progression event ID
     * @param response User's response (ACCEPTED, MODIFIED, REJECTED)
     * @param actualWeight The weight the user actually used (may differ from suggested)
     */
    suspend fun recordResponse(
        eventId: String,
        response: ProgressionResponse,
        actualWeight: Float?
    )

    /**
     * Delete a progression event.
     */
    suspend fun deleteProgressionEvent(eventId: String)

    /**
     * Delete all progression events for an exercise.
     */
    suspend fun deleteProgressionEventsForExercise(exerciseId: String)
}
