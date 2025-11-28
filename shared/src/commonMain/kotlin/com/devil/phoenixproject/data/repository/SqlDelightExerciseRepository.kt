package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CableConfiguration
import com.devil.phoenixproject.domain.model.Exercise
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
    private fun mapToExercise(
        id: String,
        name: String,
        muscleGroup: String,
        muscleGroups: String,
        equipment: String,
        defaultCableConfig: String,
        isFavorite: Long,
        isCustom: Long
    ): Exercise {
        return Exercise(
            id = id,
            name = name,
            muscleGroup = muscleGroup,
            muscleGroups = muscleGroups,
            equipment = equipment,
            defaultCableConfig = try {
                CableConfiguration.valueOf(defaultCableConfig)
            } catch (e: Exception) {
                CableConfiguration.DOUBLE
            }
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
}
