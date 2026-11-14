package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.flow.Flow

/**
 * Still-image entity for exercise demonstrations.
 */
data class ExerciseImageEntity(
    val id: Long = 0,
    val exerciseId: String,
    val url: String,
    val sortOrder: Int = 0,
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
     * Get demonstration images for an exercise
     * @param exerciseId Exercise ID
     * @return List of image entities for the exercise
     */
    suspend fun getImages(exerciseId: String): List<ExerciseImageEntity>

    // ========== Exercise Media Rows (issue #1358, SD4) ==========
    // Row-level only: these methods never reference files, paths, or any
    // platform media type. The file store is a separate phoneMain collaborator.

    /**
     * Add one image row for an exercise.
     * @param url Row value (a local app-private file path for user photos)
     */
    suspend fun addImage(exerciseId: String, url: String, sortOrder: Int): Result<Unit>

    /**
     * Remove exactly the image row with this id (SD3 removal identity).
     */
    suspend fun removeImage(imageId: Long): Result<Unit>

    /**
     * Number of image rows currently attached to [exerciseId].
     */
    suspend fun getImageCount(exerciseId: String): Int

    /**
     * Apply additions and removals in ONE transaction (SD3): all succeed or
     * nothing changes. [additionUrls] are inserted after existing rows in order;
     * [removalIds] are deleted by row id.
     */
    suspend fun applyMediaDelta(
        exerciseId: String,
        additionUrls: List<String>,
        removalIds: List<Long>,
    ): Result<Unit>

    /**
     * All exercise ids with a row (including soft-deleted rows — conservative
     * input to the phone-only media reconciler, SD8).
     */
    suspend fun getAllExerciseIds(): List<String>

    /**
     * Every `ExerciseImage.url` value (input to the phone-only reconciler, SD8).
     */
    suspend fun getAllImageUrls(): List<String>

    /**
     * Import exercises from platform-specific source (e.g., assets, bundle)
     * Should only import if the database is empty
     * @return Result indicating success or failure
     */
    suspend fun importExercises(): Result<Unit>

    /**
     * Merge additional exercises from wger (CC-BY-SA). Never overwrites bundled rows.
     * @return Result with count of exercises inserted, or error
     */
    suspend fun updateFromWger(): Result<Int>

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
