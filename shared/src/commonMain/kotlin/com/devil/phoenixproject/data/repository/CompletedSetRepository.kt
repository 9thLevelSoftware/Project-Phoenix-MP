package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PlannedSet
import kotlinx.coroutines.flow.Flow

/**
 * Repository for set-level workout data.
 * Handles both planned sets (templates) and completed sets (actual performance).
 */
interface CompletedSetRepository {

    // ==================== Planned Sets ====================

    /**
     * Get all planned sets for a routine exercise, ordered by set number.
     */
    suspend fun getPlannedSets(routineExerciseId: Long): List<PlannedSet>

    /**
     * Save a planned set.
     */
    suspend fun savePlannedSet(set: PlannedSet)

    /**
     * Save multiple planned sets at once.
     */
    suspend fun savePlannedSets(sets: List<PlannedSet>)

    /**
     * Update a planned set.
     */
    suspend fun updatePlannedSet(set: PlannedSet)

    /**
     * Delete a planned set.
     */
    suspend fun deletePlannedSet(setId: String)

    /**
     * Delete all planned sets for a routine exercise.
     */
    suspend fun deletePlannedSetsForExercise(routineExerciseId: Long)

    // ==================== Completed Sets ====================

    /**
     * Get all completed sets for a workout session, ordered by set number.
     */
    suspend fun getCompletedSets(sessionId: String): List<CompletedSet>

    /**
     * Get completed sets as a Flow for reactive updates.
     */
    fun getCompletedSetsFlow(sessionId: String): Flow<List<CompletedSet>>

    /**
     * Get all completed sets for a specific exercise across all sessions.
     */
    suspend fun getCompletedSetsForExercise(exerciseId: String): List<CompletedSet>

    /**
     * Get recent completed sets for an exercise (for progression analysis).
     * @param limit Maximum number of sets to return
     */
    suspend fun getRecentCompletedSetsForExercise(exerciseId: String, limit: Int): List<CompletedSet>

    /**
     * Save a completed set.
     */
    suspend fun saveCompletedSet(set: CompletedSet)

    /**
     * Save multiple completed sets at once.
     */
    suspend fun saveCompletedSets(sets: List<CompletedSet>)

    /**
     * Update RPE for a completed set (user logs after the fact).
     */
    suspend fun updateRpe(setId: String, rpe: Int)

    /**
     * Mark a completed set as a personal record.
     */
    suspend fun markAsPr(setId: String)

    /**
     * Delete a completed set.
     */
    suspend fun deleteCompletedSet(setId: String)

    /**
     * Delete all completed sets for a session.
     */
    suspend fun deleteCompletedSetsForSession(sessionId: String)
}
