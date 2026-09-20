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
     * Get demonstration images for an exercise
     * @param exerciseId Exercise ID
     * @return List of image entities for the exercise
     */
    suspend fun getImages(exerciseId: String): List<ExerciseImageEntity>

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

    // ========== Training Max (stored 1RM) — per profile ==========
    //
    // The exercise catalogue is shared between profiles, so the training max is NOT a
    // property of the exercise: it lives in ExerciseTrainingMax, keyed by
    // (exercise, profile) (migration 49). Every read and write goes through these two
    // methods, and `Exercise` carries no 1RM field, so one household member's PRs can
    // never move another member's commanded load.

    /**
     * The profile's stored training max for an exercise, or null when it has none.
     * @param exerciseId Exercise ID
     * @param profileId Profile the value belongs to
     */
    suspend fun getTrainingMax(exerciseId: String, profileId: String): Float?

    /**
     * Write the profile's training max, or clear it with a null value.
     * @param source where the number came from, for diagnostics
     */
    suspend fun setTrainingMax(
        exerciseId: String,
        profileId: String,
        oneRepMaxKg: Float?,
        source: TrainingMaxSource,
    )

    /**
     * A pre-migration-49 stored 1RM whose owner could not be determined, and which no
     * profile has claimed yet. Null once any profile holds a training max for the
     * exercise. No baseline ever uses this value — it is only offered to the active
     * profile as a one-time claim prompt, because assigning it by guess is exactly the
     * cross-profile leak migration 49 exists to close.
     */
    suspend fun getUnassignedLegacyTrainingMax(exerciseId: String): Float?

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

/** Where a stored training max came from. Diagnostic only; no logic branches on it. */
enum class TrainingMaxSource {
    /** Typed by the user (5/3/1 setup, exercise config). */
    MANUAL,

    /** Written by a completed load-velocity assessment. */
    ASSESSMENT,

    /** The 5/3/1 week rollover's automatic training-max bump. */
    CYCLE_BUMP,

    /** A profile claimed an unattributable pre-migration-49 value. */
    CLAIMED_LEGACY,

    /** Copied from the legacy global column by migration 49 or its post-open repair. */
    LEGACY_MIGRATION,
}
