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
 * from the exercise library. Implementations will handle platform-specific
 * data access (Android Room, iOS CoreData, Desktop SQLite, etc.)
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
}
