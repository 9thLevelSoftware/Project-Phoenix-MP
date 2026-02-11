package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.flow.Flow

/**
 * Video entity for exercise demonstrations
 * Represents a video demonstration from different angles or tutorial content
 */
data class ExerciseVideoEntity(
    val id: Long = 0,
    val exerciseId: String,
    val angle: String, // FRONT, SIDE, ISOMETRIC, or TUTORIAL
    val videoUrl: String,
    val thumbnailUrl: String,
    val isTutorial: Boolean = false // True for instructional videos, false for angle demonstrations
)

/**
 * Repository interface for exercise library management
 *
 * This interface defines the contract for accessing and managing exercises
 * from the exercise library. Implementations handle platform-specific data access.
 */
interface ExerciseRepository {
    /**
     * Get all exercises sorted by name
     * @return Flow emitting list of all exercises
     */
    fun getAllExercises(): Flow<List<Exercise>>

    /**
     * Search exercises by name, description, or muscles
     * @param query Search query string
     * @return Flow emitting filtered list of exercises
     */
    fun searchExercises(query: String): Flow<List<Exercise>>

    /**
     * Filter exercises by muscle group
     * @param muscleGroup Target muscle group (e.g., "Chest", "Back")
     * @return Flow emitting filtered exercises
     */
    fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>>

    /**
     * Filter exercises by equipment
     * @param equipment Required equipment (e.g., "Barbell", "Dumbbells")
     * @return Flow emitting filtered exercises
     */
    fun filterByEquipment(equipment: String): Flow<List<Exercise>>

    /**
     * Get favorite exercises
     * @return Flow emitting list of favorite exercises
     */
    fun getFavorites(): Flow<List<Exercise>>

    /**
     * Toggle favorite status for an exercise
     * @param id Exercise ID
     */
    suspend fun toggleFavorite(id: String)

    /**
     * Get exercise by ID
     * @param id Exercise ID
     * @return Exercise or null if not found
     */
    suspend fun getExerciseById(id: String): Exercise?

    /**
     * Get videos for an exercise
     * @param exerciseId Exercise ID
     * @return List of video entities for the exercise
     */
    suspend fun getVideos(exerciseId: String): List<ExerciseVideoEntity>

    /**
     * Import exercises from platform-specific source (e.g., assets, bundle)
     * Should only import if the database is empty
     * @return Result indicating success or failure
     */
    suspend fun importExercises(): Result<Unit>

    /**
     * Check if exercise library is empty
     * @return true if empty, false otherwise
     */
    suspend fun isExerciseLibraryEmpty(): Boolean

    /**
     * Update exercise library from GitHub
     * Fetches the latest exercise_dump.json from the repository and updates the database
     * @return Result with count of exercises updated, or error
     */
    suspend fun updateFromGitHub(): Result<Int>

    // ========== Custom Exercise Management ==========

    /**
     * Get all custom (user-created) exercises
     * @return Flow emitting list of custom exercises
     */
    fun getCustomExercises(): Flow<List<Exercise>>

    /**
     * Create a new custom exercise
     * @param exercise Exercise to create (isCustom will be set to true)
     * @return Result with created exercise or error
     */
    suspend fun createCustomExercise(exercise: Exercise): Result<Exercise>

    /**
     * Update an existing custom exercise
     * Only custom exercises can be updated
     * @param exercise Exercise with updated values
     * @return Result with updated exercise or error
     */
    suspend fun updateCustomExercise(exercise: Exercise): Result<Exercise>

    /**
     * Delete a custom exercise
     * Only custom exercises can be deleted
     * @param exerciseId ID of the exercise to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteCustomExercise(exerciseId: String): Result<Unit>

    // ========== One Rep Max Management ==========

    /**
     * Update the one-rep max for an exercise
     * @param exerciseId Exercise ID
     * @param oneRepMaxKg One-rep max in kg, or null to clear
     */
    suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?)

    /**
     * Get all exercises that have a one-rep max set
     * @return Flow emitting list of exercises with 1RM values
     */
    fun getExercisesWithOneRepMax(): Flow<List<Exercise>>

    /**
     * Find an exercise by its exact name
     * @param name Exercise name (case-sensitive)
     * @return Exercise or null if not found
     */
    suspend fun findByName(name: String): Exercise?

    /**
     * Find an exercise using multi-strategy resolution: ID first, then exact name, then fuzzy search.
     * Used by template conversion to reliably resolve exercises even when names change.
     * @param id Exercise ID (optional, tried first if non-null)
     * @param name Exercise name (tried as exact match, then fuzzy search)
     * @return Exercise or null if all strategies fail
     */
    suspend fun findByIdOrName(id: String?, name: String): Exercise?
}
