package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.TrainingCycle
import kotlinx.coroutines.flow.Flow

/**
 * Repository for Training Cycle operations.
 * Handles the rolling schedule system that replaces WeeklyProgram.
 */
interface TrainingCycleRepository {

    // ==================== Training Cycles ====================

    /**
     * Get all training cycles ordered by creation date (newest first).
     */
    fun getAllCycles(): Flow<List<TrainingCycle>>

    /**
     * Get a specific training cycle by ID.
     */
    suspend fun getCycleById(cycleId: String): TrainingCycle?

    /**
     * Get the currently active training cycle (only one can be active).
     */
    fun getActiveCycle(): Flow<TrainingCycle?>

    /**
     * Get a training cycle with its progress information.
     */
    suspend fun getCycleWithProgress(cycleId: String): Pair<TrainingCycle, CycleProgress?>?

    /**
     * Save a new training cycle (including its days).
     */
    suspend fun saveCycle(cycle: TrainingCycle)

    /**
     * Update an existing training cycle.
     */
    suspend fun updateCycle(cycle: TrainingCycle)

    /**
     * Set a cycle as the active one (deactivates all others).
     */
    suspend fun setActiveCycle(cycleId: String)

    /**
     * Delete a training cycle and all its related data.
     */
    suspend fun deleteCycle(cycleId: String)

    // ==================== Cycle Days ====================

    /**
     * Get all days for a specific cycle, ordered by day number.
     */
    suspend fun getCycleDays(cycleId: String): List<CycleDay>

    /**
     * Add a day to a cycle.
     */
    suspend fun addCycleDay(day: CycleDay)

    /**
     * Update an existing cycle day.
     */
    suspend fun updateCycleDay(day: CycleDay)

    /**
     * Delete a cycle day.
     */
    suspend fun deleteCycleDay(dayId: String)

    /**
     * Reorder days in a cycle (updates day numbers).
     */
    suspend fun reorderCycleDays(cycleId: String, dayIds: List<String>)

    // ==================== Cycle Progress ====================

    /**
     * Get progress for a specific cycle.
     */
    suspend fun getCycleProgress(cycleId: String): CycleProgress?

    /**
     * Initialize progress for a cycle (called when cycle is first activated).
     */
    suspend fun initializeProgress(cycleId: String): CycleProgress

    /**
     * Advance to the next day in the cycle.
     * Wraps to day 1 if at the end.
     * @return The new current day number
     */
    suspend fun advanceToNextDay(cycleId: String): Int

    /**
     * Reset progress to day 1.
     */
    suspend fun resetProgress(cycleId: String)

    /**
     * Jump to a specific day in the cycle.
     */
    suspend fun jumpToDay(cycleId: String, dayNumber: Int)

    /**
     * Mark a day as completed (updates last completed date).
     */
    suspend fun markDayCompleted(cycleId: String)

    /**
     * Update cycle progress with a full CycleProgress object.
     * Used when using the CycleProgress model methods like markDayCompleted().
     */
    suspend fun updateCycleProgress(progress: CycleProgress)

    /**
     * Check if auto-advance is needed (24+ hours since last advance) and perform it.
     * If auto-advance is triggered, marks the current day as missed and advances to next day.
     * @return Current CycleProgress (possibly auto-advanced), or null if no progress exists
     */
    suspend fun checkAndAutoAdvance(cycleId: String): CycleProgress?
}
