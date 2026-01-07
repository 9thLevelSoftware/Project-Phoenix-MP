package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing personal records (PRs)
 *
 * This interface defines the contract for accessing and managing personal records
 * for exercises. Implementations handle platform-specific data access.
 */
interface PersonalRecordRepository {
    /**
     * Get the latest PR for an exercise in a specific workout mode
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode (e.g., "OldSchool", "Pump", "TUT")
     * @return PersonalRecord or null if no PR exists
     */
    suspend fun getLatestPR(exerciseId: String, workoutMode: String): PersonalRecord?

    /**
     * Get all PRs for an exercise across all workout modes
     * @param exerciseId Exercise ID
     * @return Flow emitting list of personal records
     */
    fun getPRsForExercise(exerciseId: String): Flow<List<PersonalRecord>>

    /**
     * Get the best PR for an exercise across all modes
     * Returns the record with the highest weight * reps (volume)
     * @param exerciseId Exercise ID
     * @return PersonalRecord or null if no PR exists
     */
    suspend fun getBestPR(exerciseId: String): PersonalRecord?

    /**
     * Get all personal records
     * @return Flow emitting list of all personal records
     */
    fun getAllPRs(): Flow<List<PersonalRecord>>

    /**
     * Get all personal records grouped by exercise (for analytics)
     * Returns one record per exercise (the best one)
     * @return Flow emitting list of personal records
     */
    fun getAllPRsGrouped(): Flow<List<PersonalRecord>>

    /**
     * Update PR if the new performance is better
     * Compares the new weight and reps with the existing PR for the exercise/mode combination
     * and updates if the new performance is better (higher volume = weight * reps)
     *
     * @param exerciseId Exercise ID
     * @param weightPerCableKg Weight per cable in kg
     * @param reps Number of reps completed
     * @param workoutMode Workout mode
     * @param timestamp Timestamp of the performance
     * @return Result.success(true) if a new PR was set, Result.success(false) otherwise, or Result.failure on error
     */
    suspend fun updatePRIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<Boolean>

    // ========== Volume/Weight PR Methods (parity with parent) ==========

    /**
     * Get the weight PR for an exercise in a specific workout mode
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode
     * @return PersonalRecord or null if no weight PR exists
     */
    suspend fun getWeightPR(exerciseId: String, workoutMode: String): PersonalRecord?

    /**
     * Get the volume PR for an exercise in a specific workout mode
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode
     * @return PersonalRecord or null if no volume PR exists
     */
    suspend fun getVolumePR(exerciseId: String, workoutMode: String): PersonalRecord?

    /**
     * Get the best weight PR for an exercise across all modes
     * @param exerciseId Exercise ID
     * @return PersonalRecord with highest weight, or null if no PR exists
     */
    suspend fun getBestWeightPR(exerciseId: String): PersonalRecord?

    /**
     * Get the best volume PR for an exercise across all modes
     * @param exerciseId Exercise ID
     * @return PersonalRecord with highest volume (weight × reps), or null if no PR exists
     */
    suspend fun getBestVolumePR(exerciseId: String): PersonalRecord?

    /**
     * Get the best weight PR for an exercise in a specific mode
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode (e.g., "OldSchool", "Pump", "TUT")
     * @return PersonalRecord with highest weight for this mode, or null if no PR exists
     */
    suspend fun getBestWeightPR(exerciseId: String, workoutMode: String): PersonalRecord?

    /**
     * Get the best volume PR for an exercise in a specific mode
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode (e.g., "OldSchool", "Pump", "TUT")
     * @return PersonalRecord with highest volume for this mode, or null if no PR exists
     */
    suspend fun getBestVolumePR(exerciseId: String, workoutMode: String): PersonalRecord?

    /**
     * Get all PRs for an exercise (all modes) for display
     * @param exerciseId Exercise ID
     * @return List of all PRs for the exercise, ordered by mode, type, and date
     */
    suspend fun getAllPRsForExercise(exerciseId: String): List<PersonalRecord>

    /**
     * Update PRs if the new performance is better
     * Checks both weight and volume PRs and updates each if beaten
     *
     * @param exerciseId Exercise ID
     * @param weightPerCableKg Weight per cable in kg
     * @param reps Number of reps completed
     * @param workoutMode Workout mode
     * @param timestamp Timestamp of the performance
     * @return List of PR types that were broken (can be empty, one, or both)
     */
    suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<List<PRType>>
}
