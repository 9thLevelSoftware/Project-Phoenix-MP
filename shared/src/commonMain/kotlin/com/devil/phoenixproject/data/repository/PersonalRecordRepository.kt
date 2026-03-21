package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutPhase
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
    ): Result<Boolean> {
        return updatePRsIfBetter(
            exerciseId = exerciseId,
            weightPRWeightPerCableKg = weightPerCableKg,
            volumePRWeightPerCableKg = weightPerCableKg,
            reps = reps,
            workoutMode = workoutMode,
            timestamp = timestamp
        ).fold(
            onSuccess = { Result.success(it.isNotEmpty()) },
            onFailure = { Result.failure(it) }
        )
    }

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
    ): Result<List<PRType>> {
        return updatePRsIfBetter(
            exerciseId = exerciseId,
            weightPRWeightPerCableKg = weightPerCableKg,
            volumePRWeightPerCableKg = weightPerCableKg,
            reps = reps,
            workoutMode = workoutMode,
            timestamp = timestamp
        )
    }

    /**
     * Update PRs if the new performance is better.
     * Uses separate weight inputs so weight PRs can reflect achieved load while
     * volume PRs continue to use the conservative programmed load.
     *
     * @param exerciseId Exercise ID
     * @param weightPRWeightPerCableKg Achieved load used for MAX_WEIGHT comparisons and 1RM sync
     * @param volumePRWeightPerCableKg Conservative load used for MAX_VOLUME comparisons
     * @param reps Number of reps completed
     * @param workoutMode Workout mode
     * @param timestamp Timestamp of the performance
     * @return List of PR types that were broken (can be empty, one, or both)
     */
    suspend fun updatePRsIfBetter(
        exerciseId: String,
        weightPRWeightPerCableKg: Float,
        volumePRWeightPerCableKg: Float,
        reps: Int,
        workoutMode: String,
        timestamp: Long
    ): Result<List<PRType>>

    // ========== Phase-specific PR Methods (Issue #111) ==========

    /**
     * Update phase-specific PRs (concentric and eccentric) if the new peak forces are better.
     * Called alongside the COMBINED PR check after each set completion.
     *
     * @param exerciseId Exercise ID
     * @param workoutMode Workout mode
     * @param timestamp Timestamp of the performance
     * @param reps Number of reps completed
     * @param peakConcentricForceKg Peak force during concentric phase (per cable, kg)
     * @param peakEccentricForceKg Peak force during eccentric phase (per cable, kg)
     * @return List of phases where PRs were broken (can be empty, one, or both)
     */
    suspend fun updatePhaseSpecificPRs(
        exerciseId: String,
        workoutMode: String,
        timestamp: Long,
        reps: Int,
        peakConcentricForceKg: Float,
        peakEccentricForceKg: Float
    ): Result<List<WorkoutPhase>>
}

fun normalizeWorkoutModeKey(workoutMode: String): String {
    val trimmed = workoutMode.trim()
    return when {
        trimmed.equals("OldSchool", ignoreCase = true) -> "Old School"
        trimmed.equals("TUTBeast", ignoreCase = true) -> "TUT Beast"
        trimmed.equals("EccentricOnly", ignoreCase = true) -> "Eccentric Only"
        trimmed.equals("Echo", ignoreCase = true) -> "Echo"
        trimmed.equals("Pump", ignoreCase = true) -> "Pump"
        trimmed.equals("TUT", ignoreCase = true) -> "TUT"
        trimmed.equals("Old School", ignoreCase = true) -> "Old School"
        trimmed.equals("TUT Beast", ignoreCase = true) -> "TUT Beast"
        trimmed.equals("Eccentric Only", ignoreCase = true) -> "Eccentric Only"
        else -> trimmed
    }
}
