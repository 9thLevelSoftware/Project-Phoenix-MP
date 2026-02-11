package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightExerciseRepository(
    db: VitruvianDatabase,
    private val exerciseImporter: ExerciseImporter
) : ExerciseRepository {

    private val queries = db.vitruvianDatabaseQueries

    // Mapper function to convert database entity to Domain Model
    // Parameters match the column order in the Exercise table
    private fun mapToExercise(
        id: String,
        name: String,
        description: String?,
        created: Long,
        muscleGroup: String,
        muscleGroups: String,
        muscles: String?,
        equipment: String,
        movement: String?,
        sidedness: String?,
        grip: String?,
        gripWidth: String?,
        minRepRange: Double?,
        popularity: Double,
        archived: Long,
        isFavorite: Long,
        isCustom: Long,
        timesPerformed: Long,
        lastPerformed: Long?,
        aliases: String?,
        defaultCableConfig: String,
        one_rep_max_kg: Double?,
        // Sync fields (migration 6)
        updatedAt: Long?,
        serverId: String?,
        deletedAt: Long?
    ): Exercise {
        // Note: defaultCableConfig is stored in DB but no longer used
        return Exercise(
            id = id,
            name = name,
            muscleGroup = muscleGroup,
            muscleGroups = muscleGroups,
            equipment = equipment,
            isFavorite = isFavorite == 1L,
            isCustom = isCustom == 1L,
            timesPerformed = timesPerformed.toInt(),
            oneRepMaxKg = one_rep_max_kg?.toFloat()
        )
    }

    override fun getAllExercises(): Flow<List<Exercise>> {
        return queries.selectAllExercises(::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override fun searchExercises(query: String): Flow<List<Exercise>> {
        return queries.searchExercises(query, ::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>> {
        return queries.filterExercisesByMuscle(muscleGroup, ::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override fun filterByEquipment(equipment: String): Flow<List<Exercise>> {
        return queries.filterExercisesByEquipment(equipment, ::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override fun getFavorites(): Flow<List<Exercise>> {
        return queries.selectFavorites(::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun toggleFavorite(id: String) {
        withContext(Dispatchers.IO) {
            val exercise = queries.selectExerciseById(id).executeAsOneOrNull()
            if (exercise != null) {
                val newStatus = if (exercise.isFavorite == 1L) 0L else 1L
                queries.updateFavorite(newStatus, id)
            }
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? {
        return withContext(Dispatchers.IO) {
            queries.selectExerciseById(id, ::mapToExercise).executeAsOneOrNull()
        }
    }

    override suspend fun getVideos(exerciseId: String): List<ExerciseVideoEntity> {
        return withContext(Dispatchers.IO) {
            queries.selectVideosByExercise(exerciseId).executeAsList().map { 
                ExerciseVideoEntity(
                    id = it.id,
                    exerciseId = it.exerciseId,
                    angle = it.angle,
                    videoUrl = it.videoUrl,
                    thumbnailUrl = it.thumbnailUrl,
                    isTutorial = it.isTutorial == 1L
                )
            }
        }
    }

    override suspend fun importExercises(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Check if exercises are already imported
                val exerciseCount = queries.countExercises().executeAsOne()
                val videoCount = queries.countVideos().executeAsOne()

                // If exercises exist but no videos, we need to re-import (videos were added later)
                val needsReimport = exerciseCount > 0 && videoCount == 0L

                if (exerciseCount == 0L || needsReimport) {
                    if (needsReimport) {
                        Logger.d { "Exercises exist ($exerciseCount) but no videos found. Clearing and re-importing..." }
                        queries.deleteAllVideos()
                        queries.deleteAllExercises()
                    }
                    Logger.d { "Importing exercises from bundled JSON..." }
                    val result = exerciseImporter.importExercises()
                    if (result.isSuccess) {
                        val newVideoCount = queries.countVideos().executeAsOne()
                        Logger.d { "Successfully imported ${result.getOrNull()} exercises with $newVideoCount videos" }
                        Result.success(Unit)
                    } else {
                        result.exceptionOrNull()?.let { Result.failure(it) }
                            ?: Result.failure(Exception("Import failed"))
                    }
                } else {
                    Logger.d { "Exercises already imported (exercises: $exerciseCount, videos: $videoCount)" }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to import exercises" }
                Result.failure(e)
            }
        }
    }

    override suspend fun isExerciseLibraryEmpty(): Boolean {
        return withContext(Dispatchers.IO) {
            val count = queries.countExercises().executeAsOne()
            count == 0L
        }
    }

    override suspend fun updateFromGitHub(): Result<Int> {
        return exerciseImporter.updateFromGitHub()
    }

    // ========== Custom Exercise Management ==========

    override fun getCustomExercises(): Flow<List<Exercise>> {
        return queries.selectCustomExercises(::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun createCustomExercise(exercise: Exercise): Result<Exercise> {
        return withContext(Dispatchers.IO) {
            try {
                // Generate a unique ID for custom exercises
                val customId = "custom_${currentTimeMillis()}"

                queries.insertExercise(
                    id = customId,
                    name = exercise.name,
                    description = null, // Custom exercises start without description
                    created = currentTimeMillis(),
                    muscleGroup = exercise.muscleGroup,
                    muscleGroups = exercise.muscleGroups,
                    muscles = null,
                    equipment = exercise.equipment,
                    movement = null,
                    sidedness = null,
                    grip = null,
                    gripWidth = null,
                    minRepRange = null,
                    popularity = 0.0,
                    archived = 0L,
                    isFavorite = if (exercise.isFavorite) 1L else 0L,
                    isCustom = 1L, // Always mark as custom
                    timesPerformed = 0L,
                    lastPerformed = null,
                    aliases = null,
                    defaultCableConfig = "DOUBLE", // Legacy field - no longer used
                    one_rep_max_kg = exercise.oneRepMaxKg?.toDouble()
                )

                Logger.d { "Created custom exercise: ${exercise.name} with ID: $customId" }

                // Return the created exercise with the generated ID
                Result.success(exercise.copy(id = customId, isCustom = true))
            } catch (e: Exception) {
                Logger.e(e) { "Failed to create custom exercise: ${exercise.name}" }
                Result.failure(e)
            }
        }
    }

    override suspend fun updateCustomExercise(exercise: Exercise): Result<Exercise> {
        return withContext(Dispatchers.IO) {
            try {
                val exerciseId = exercise.id
                    ?: return@withContext Result.failure(IllegalArgumentException("Exercise ID is required for update"))

                // Verify it's a custom exercise
                val existing = queries.selectExerciseById(exerciseId).executeAsOneOrNull()
                if (existing == null) {
                    return@withContext Result.failure(IllegalArgumentException("Exercise not found: $exerciseId"))
                }
                if (existing.isCustom != 1L) {
                    return@withContext Result.failure(IllegalArgumentException("Cannot update non-custom exercise"))
                }

                queries.updateCustomExercise(
                    name = exercise.name,
                    description = null, // Custom exercise description (not in domain model yet)
                    muscleGroup = exercise.muscleGroup,
                    muscleGroups = exercise.muscleGroups,
                    muscles = null,
                    equipment = exercise.equipment,
                    movement = null,
                    sidedness = null,
                    grip = null,
                    gripWidth = null,
                    minRepRange = null,
                    aliases = null,
                    defaultCableConfig = "DOUBLE", // Legacy field - no longer used
                    one_rep_max_kg = exercise.oneRepMaxKg?.toDouble(),
                    id = exerciseId
                )

                Logger.d { "Updated custom exercise: ${exercise.name}" }
                Result.success(exercise)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to update custom exercise: ${exercise.name}" }
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteCustomExercise(exerciseId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Verify it's a custom exercise
                val existing = queries.selectExerciseById(exerciseId).executeAsOneOrNull()
                if (existing == null) {
                    return@withContext Result.failure(IllegalArgumentException("Exercise not found: $exerciseId"))
                }
                if (existing.isCustom != 1L) {
                    return@withContext Result.failure(IllegalArgumentException("Cannot delete non-custom exercise"))
                }

                queries.deleteCustomExercise(exerciseId)

                Logger.d { "Deleted custom exercise: $exerciseId" }
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to delete custom exercise: $exerciseId" }
                Result.failure(e)
            }
        }
    }

    // ========== One Rep Max Management ==========

    override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
        withContext(Dispatchers.IO) {
            queries.updateOneRepMax(oneRepMaxKg?.toDouble(), exerciseId)
        }
    }

    override fun getExercisesWithOneRepMax(): Flow<List<Exercise>> {
        return queries.getExercisesWithOneRepMax(::mapToExercise)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    override suspend fun findByName(name: String): Exercise? {
        return withContext(Dispatchers.IO) {
            queries.findExerciseByName(name, ::mapToExercise).executeAsOneOrNull()
        }
    }

    override suspend fun findByIdOrName(id: String?, name: String): Exercise? {
        return withContext(Dispatchers.IO) {
            // Strategy 1: Direct ID lookup (fastest, most reliable)
            if (id != null) {
                val byId = queries.selectExerciseById(id, ::mapToExercise).executeAsOneOrNull()
                if (byId != null) return@withContext byId
            }

            // Strategy 2: Exact name match (uses TRIM for trailing space tolerance)
            val byName = queries.findExerciseByName(name, ::mapToExercise).executeAsOneOrNull()
            if (byName != null) return@withContext byName

            // Strategy 3: Fuzzy search - take first result
            val searchResults = queries.searchExercises(name, ::mapToExercise).executeAsList()
            searchResults.firstOrNull()
        }
    }
}
