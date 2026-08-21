package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.WorkoutSession
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
    suspend fun getPlannedSets(routineExerciseId: String): List<PlannedSet>

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
    suspend fun deletePlannedSetsForExercise(routineExerciseId: String)

    // ==================== Completed Sets ====================

    /**
     * Get all completed sets for a workout session, ordered by set number.
     */
    suspend fun getCompletedSets(sessionId: String): List<CompletedSet>

    /**
     * Get completed sets for multiple workout sessions, ordered by session and set number.
     */
    suspend fun getCompletedSetsForSessions(sessionIds: List<String>): List<CompletedSet>

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
     * @param profileId Profile to scope the query to — prevents another profile's
     *   sets (and soft-deleted sessions) leaking into progression/deload analysis.
     */
    suspend fun getRecentCompletedSetsForExercise(exerciseId: String, limit: Int, profileId: String): List<CompletedSet>

    /**
     * Save a completed set.
     */
    suspend fun saveCompletedSet(set: CompletedSet)

    /**
     * Ensure a completed-set row exists for a Just Lift session after post-set exercise tagging.
     * Returns the existing row when one is already present so retagging does not duplicate stats.
     */
    suspend fun ensureCompletedSetForTaggedJustLift(session: WorkoutSession, isAmrap: Boolean): CompletedSet?

    /**
     * Save multiple completed sets at once.
     */
    suspend fun saveCompletedSets(sets: List<CompletedSet>)

    /** Return the next durable attempt number for this exact logical routine set. */
    suspend fun nextAttemptNumber(key: LogicalSetKey): Int

    /**
     * Whether the exact attempt is durably stored under the stable workout-session id.
     * Soft-deleted workout sessions are not authoritative.
     */
    suspend fun isAttemptDurable(stableSessionId: String, key: LogicalSetKey, attemptNumber: Int): Boolean

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

internal fun collapseCompletedSetsToLatestLogicalAttempts(
    sets: List<CompletedSet>,
    routineSessionIdFor: (sessionId: String) -> String?,
): List<CompletedSet> {
    data class LogicalKey(
        val routineSessionId: String,
        val routineExerciseId: String,
        val setNumber: Int,
    )
    val selected = LinkedHashMap<Any, CompletedSet>()
    for (set in sets) {
        val routineSessionId = routineSessionIdFor(set.sessionId)
        val routineExerciseId = set.routineExerciseId
        val key: Any = if (routineSessionId.isNullOrBlank() || routineExerciseId.isNullOrBlank()) {
            set.id
        } else {
            LogicalKey(routineSessionId, routineExerciseId, set.setNumber)
        }
        val existing = selected[key]
        if (existing == null) {
            selected[key] = set
            continue
        }
        val existingAttempt = existing.attemptNumber.coerceAtLeast(1)
        val candidateAttempt = set.attemptNumber.coerceAtLeast(1)
        if (candidateAttempt > existingAttempt ||
            (candidateAttempt == existingAttempt && set.completedAt > existing.completedAt)
        ) {
            selected[key] = set
        }
    }
    return selected.values.sortedByDescending { it.completedAt }
}
