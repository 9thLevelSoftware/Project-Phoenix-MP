package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake ExerciseRepository for testing.
 * Provides in-memory storage and controllable behavior.
 */
class FakeExerciseRepository : ExerciseRepository {

    private val exercises = mutableMapOf<String, Exercise>()
    private val videos = mutableMapOf<String, List<ExerciseVideoEntity>>()
    private val _exercisesFlow = MutableStateFlow<List<Exercise>>(emptyList())

    // Test control
    var importResult: Result<Unit> = Result.success(Unit)
    var updateFromGitHubResult: Result<Int> = Result.success(0)

    // Test helper methods
    fun addExercise(exercise: Exercise) {
        val id = exercise.id ?: "exercise-${exercises.size}"
        exercises[id] = exercise.copy(id = id)
        updateFlow()
    }

    fun addVideos(exerciseId: String, videoList: List<ExerciseVideoEntity>) {
        videos[exerciseId] = videoList
    }

    fun reset() {
        exercises.clear()
        videos.clear()
        importResult = Result.success(Unit)
        updateFromGitHubResult = Result.success(0)
        updateFlow()
    }

    private fun updateFlow() {
        _exercisesFlow.value = exercises.values.sortedBy { it.name }
    }

    // ========== ExerciseRepository interface implementation ==========

    override fun getAllExercises(): Flow<List<Exercise>> = _exercisesFlow

    override fun searchExercises(query: String): Flow<List<Exercise>> {
        return _exercisesFlow.map { list ->
            list.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.muscleGroup.contains(query, ignoreCase = true)
            }
        }
    }

    override fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>> {
        return _exercisesFlow.map { list ->
            list.filter { it.muscleGroup.equals(muscleGroup, ignoreCase = true) }
        }
    }

    override fun filterByEquipment(equipment: String): Flow<List<Exercise>> {
        return _exercisesFlow.map { list ->
            list.filter { it.equipment.contains(equipment, ignoreCase = true) }
        }
    }

    override fun getFavorites(): Flow<List<Exercise>> {
        return _exercisesFlow.map { list -> list.filter { it.isFavorite } }
    }

    override suspend fun toggleFavorite(id: String) {
        exercises[id]?.let { exercise ->
            exercises[id] = exercise.copy(isFavorite = !exercise.isFavorite)
            updateFlow()
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? = exercises[id]

    override suspend fun getVideos(exerciseId: String): List<ExerciseVideoEntity> {
        return videos[exerciseId] ?: emptyList()
    }

    override suspend fun importExercises(): Result<Unit> = importResult

    override suspend fun isExerciseLibraryEmpty(): Boolean = exercises.isEmpty()

    override suspend fun updateFromGitHub(): Result<Int> = updateFromGitHubResult

    override fun getCustomExercises(): Flow<List<Exercise>> {
        return _exercisesFlow.map { list -> list.filter { it.isCustom } }
    }

    override suspend fun createCustomExercise(exercise: Exercise): Result<Exercise> {
        val id = exercise.id ?: "custom-${exercises.size}"
        val newExercise = exercise.copy(id = id, isCustom = true)
        exercises[id] = newExercise
        updateFlow()
        return Result.success(newExercise)
    }

    override suspend fun updateCustomExercise(exercise: Exercise): Result<Exercise> {
        val id = exercise.id ?: return Result.failure(Exception("No ID"))
        if (exercises[id]?.isCustom != true) {
            return Result.failure(Exception("Cannot update non-custom exercise"))
        }
        exercises[id] = exercise
        updateFlow()
        return Result.success(exercise)
    }

    override suspend fun deleteCustomExercise(exerciseId: String): Result<Unit> {
        if (exercises[exerciseId]?.isCustom != true) {
            return Result.failure(Exception("Cannot delete non-custom exercise"))
        }
        exercises.remove(exerciseId)
        updateFlow()
        return Result.success(Unit)
    }

    override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
        exercises[exerciseId]?.let { exercise ->
            exercises[exerciseId] = exercise.copy(oneRepMaxKg = oneRepMaxKg)
            updateFlow()
        }
    }

    override fun getExercisesWithOneRepMax(): Flow<List<Exercise>> {
        return _exercisesFlow.map { list -> list.filter { it.oneRepMaxKg != null } }
    }

    override suspend fun findByName(name: String): Exercise? {
        return exercises.values.find { it.name == name }
    }
}
