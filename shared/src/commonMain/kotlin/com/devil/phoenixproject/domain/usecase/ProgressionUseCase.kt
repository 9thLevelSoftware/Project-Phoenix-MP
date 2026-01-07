package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ProgressionRepository
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionReason
import com.devil.phoenixproject.domain.model.ProgressionResponse
import com.devil.phoenixproject.domain.model.SetType

/**
 * Use case for calculating and managing weight progressions.
 * Analyzes workout history to suggest weight increases based on performance.
 */
class ProgressionUseCase(
    private val completedSetRepository: CompletedSetRepository,
    private val progressionRepository: ProgressionRepository
) {
    private val log = Logger.withTag("ProgressionUseCase")

    companion object {
        /** Number of consecutive sessions hitting target reps to trigger progression */
        const val SESSIONS_FOR_REP_PROGRESSION = 2

        /** RPE difference threshold to trigger progression (target - logged >= this) */
        const val RPE_DIFF_THRESHOLD = 2

        /** Default target RPE if not specified */
        const val DEFAULT_TARGET_RPE = 8

        /** Minimum sets in recent history to consider for progression */
        const val MIN_SETS_FOR_ANALYSIS = 3
    }

    /**
     * Check if a progression should be suggested for an exercise.
     * Returns null if no progression is warranted.
     *
     * @param exerciseId The exercise to check
     * @param targetReps The target reps for the exercise (for rep-based progression)
     * @param targetRpe The target RPE (for RPE-based progression)
     * @return ProgressionEvent if progression should be suggested, null otherwise
     */
    suspend fun checkForProgression(
        exerciseId: String,
        targetReps: Int? = null,
        targetRpe: Int = DEFAULT_TARGET_RPE
    ): ProgressionEvent? {
        // Check if there's already a pending progression for this exercise
        if (progressionRepository.hasPendingProgression(exerciseId)) {
            log.d { "Pending progression already exists for exercise $exerciseId" }
            return null
        }

        // Get recent completed sets for this exercise
        val recentSets = completedSetRepository.getRecentCompletedSetsForExercise(
            exerciseId = exerciseId,
            limit = 20 // Get enough history for analysis
        ).filter { it.setType != SetType.WARMUP } // Exclude warmup sets

        if (recentSets.size < MIN_SETS_FOR_ANALYSIS) {
            log.d { "Not enough history for exercise $exerciseId (${recentSets.size} sets)" }
            return null
        }

        // Get the most recent weight used
        val currentWeight = recentSets.maxOfOrNull { it.actualWeightKg } ?: return null

        // Check for RPE-based progression first (more immediate signal)
        val rpeProgression = checkRpeBasedProgression(recentSets, currentWeight, targetRpe)
        if (rpeProgression) {
            log.i { "RPE-based progression suggested for exercise $exerciseId" }
            return createProgressionEvent(exerciseId, currentWeight, ProgressionReason.LOW_RPE)
        }

        // Check for rep-based progression
        if (targetReps != null) {
            val repProgression = checkRepBasedProgression(recentSets, currentWeight, targetReps)
            if (repProgression) {
                log.i { "Rep-based progression suggested for exercise $exerciseId" }
                return createProgressionEvent(exerciseId, currentWeight, ProgressionReason.REPS_ACHIEVED)
            }
        }

        return null
    }

    /**
     * Check if recent RPE logs indicate the weight is too easy.
     * Triggers if average RPE is significantly below target.
     */
    private fun checkRpeBasedProgression(
        recentSets: List<CompletedSet>,
        currentWeight: Float,
        targetRpe: Int
    ): Boolean {
        // Get sets at current weight with RPE logged
        val setsWithRpe = recentSets
            .filter { it.actualWeightKg == currentWeight && it.loggedRpe != null }

        if (setsWithRpe.size < 2) {
            return false // Need at least 2 sets with RPE to make a decision
        }

        // Calculate average RPE
        val avgRpe = setsWithRpe.mapNotNull { it.loggedRpe }.average()

        // Check if consistently below target
        val rpeDiff = targetRpe - avgRpe
        val allBelowTarget = setsWithRpe.all { (it.loggedRpe ?: 10) < targetRpe }

        return rpeDiff >= RPE_DIFF_THRESHOLD && allBelowTarget
    }

    /**
     * Check if user has hit target reps consistently.
     * Triggers if target reps achieved for multiple consecutive sessions.
     */
    private fun checkRepBasedProgression(
        recentSets: List<CompletedSet>,
        currentWeight: Float,
        targetReps: Int
    ): Boolean {
        // Get sets at current weight
        val setsAtWeight = recentSets
            .filter { it.actualWeightKg == currentWeight }
            .sortedByDescending { it.completedAt }

        if (setsAtWeight.size < SESSIONS_FOR_REP_PROGRESSION) {
            return false
        }

        // Group by session (assuming sets within 2 hours are same session)
        val sessionGroups = groupBySession(setsAtWeight)

        if (sessionGroups.size < SESSIONS_FOR_REP_PROGRESSION) {
            return false
        }

        // Check if last N sessions all achieved target reps
        val recentSessions = sessionGroups.take(SESSIONS_FOR_REP_PROGRESSION)

        return recentSessions.all { session ->
            session.any { it.actualReps >= targetReps }
        }
    }

    /**
     * Group sets into sessions based on timestamp proximity.
     */
    private fun groupBySession(sets: List<CompletedSet>): List<List<CompletedSet>> {
        if (sets.isEmpty()) return emptyList()

        val sessionGapMs = 2 * 60 * 60 * 1000L // 2 hours
        val sessions = mutableListOf<MutableList<CompletedSet>>()
        var currentSession = mutableListOf<CompletedSet>()

        sets.sortedByDescending { it.completedAt }.forEach { set ->
            if (currentSession.isEmpty()) {
                currentSession.add(set)
            } else {
                val lastSet = currentSession.last()
                if (lastSet.completedAt - set.completedAt < sessionGapMs) {
                    currentSession.add(set)
                } else {
                    sessions.add(currentSession)
                    currentSession = mutableListOf(set)
                }
            }
        }

        if (currentSession.isNotEmpty()) {
            sessions.add(currentSession)
        }

        return sessions
    }

    /**
     * Create and save a progression event.
     */
    private suspend fun createProgressionEvent(
        exerciseId: String,
        currentWeight: Float,
        reason: ProgressionReason
    ): ProgressionEvent {
        val event = ProgressionEvent.create(
            exerciseId = exerciseId,
            previousWeightKg = currentWeight,
            reason = reason
        )

        progressionRepository.createProgressionSuggestion(event)
        return event
    }

    /**
     * Record user's response to a progression suggestion.
     */
    suspend fun respondToProgression(
        eventId: String,
        response: ProgressionResponse,
        actualWeight: Float? = null
    ) {
        progressionRepository.recordResponse(eventId, response, actualWeight)
        log.i { "Progression response recorded: $response for event $eventId" }
    }

    /**
     * Get the suggested weight for an exercise if there's a pending progression.
     */
    suspend fun getSuggestedWeight(exerciseId: String): Float? {
        val event = progressionRepository.getLatestProgressionEvent(exerciseId)
        return if (event?.isPending() == true) {
            event.suggestedWeightKg
        } else {
            null
        }
    }

    /**
     * Get all pending progressions.
     */
    suspend fun getPendingProgressions(): List<ProgressionEvent> {
        return progressionRepository.getPendingProgressions()
    }

    /**
     * Dismiss a pending progression without accepting it.
     */
    suspend fun dismissProgression(eventId: String) {
        progressionRepository.recordResponse(eventId, ProgressionResponse.REJECTED, null)
    }
}
