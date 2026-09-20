package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import com.devil.phoenixproject.data.repository.TrainingMaxSource
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
    private val images = mutableMapOf<String, List<ExerciseImageEntity>>()
    private val _exercisesFlow = MutableStateFlow<List<Exercise>>(emptyList())

    // Test control
    var importResult: Result<Unit> = Result.success(Unit)
    var updateFromWgerResult: Result<Int> = Result.success(0)

    // Test helper methods
    fun addExercise(exercise: Exercise) {
        val id = exercise.id ?: "exercise-${exercises.size}"
        exercises[id] = exercise.copy(id = id)
        updateFlow()
    }

    fun addImages(exerciseId: String, imageList: List<ExerciseImageEntity>) {
        images[exerciseId] = imageList
    }

    fun reset() {
        exercises.clear()
        images.clear()
        trainingMaxes.clear()
        unassignedLegacyTrainingMaxes.clear()
        importResult = Result.success(Unit)
        updateFromWgerResult = Result.success(0)
        updateFlow()
    }

    private fun updateFlow() {
        _exercisesFlow.value = exercises.values.sortedBy { it.name }
    }

    // ========== ExerciseRepository interface implementation ==========

    override fun getAllExercises(): Flow<List<Exercise>> = _exercisesFlow

    override fun searchExercises(query: String): Flow<List<Exercise>> = _exercisesFlow.map { list ->
        list.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.muscleGroup.contains(query, ignoreCase = true)
        }
    }

    override fun filterByMuscleGroup(muscleGroup: String): Flow<List<Exercise>> = _exercisesFlow.map { list ->
        list.filter { it.muscleGroup.equals(muscleGroup, ignoreCase = true) }
    }

    override fun filterByEquipment(equipment: String): Flow<List<Exercise>> = _exercisesFlow.map { list ->
        list.filter { it.equipment.contains(equipment, ignoreCase = true) }
    }

    override fun getFavorites(): Flow<List<Exercise>> = _exercisesFlow.map { list -> list.filter { it.isFavorite } }

    override suspend fun toggleFavorite(id: String) {
        exercises[id]?.let { exercise ->
            exercises[id] = exercise.copy(isFavorite = !exercise.isFavorite)
            updateFlow()
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? = exercises[id]

    override suspend fun getImages(exerciseId: String): List<ExerciseImageEntity> = images[exerciseId] ?: emptyList()

    override suspend fun importExercises(): Result<Unit> = importResult

    override suspend fun isExerciseLibraryEmpty(): Boolean = exercises.isEmpty()

    override suspend fun updateFromWger(): Result<Int> = updateFromWgerResult

    override fun getCustomExercises(): Flow<List<Exercise>> = _exercisesFlow.map { list -> list.filter { it.isCustom } }

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

    // Per-profile training maxes (migration 49), keyed the same way the table is.
    private val trainingMaxes = mutableMapOf<Pair<String, String>, Float>()

    /** Seed a legacy, unattributed `Exercise.one_rep_max_kg` for claim-prompt tests. */
    var unassignedLegacyTrainingMaxes: MutableMap<String, Float> = mutableMapOf()

    fun setTrainingMaxDirectly(exerciseId: String, profileId: String, oneRepMaxKg: Float) {
        trainingMaxes[exerciseId to profileId] = oneRepMaxKg
    }

    override suspend fun getTrainingMax(exerciseId: String, profileId: String): Float? =
        trainingMaxes[exerciseId to profileId]

    override suspend fun setTrainingMax(
        exerciseId: String,
        profileId: String,
        oneRepMaxKg: Float?,
        source: TrainingMaxSource,
    ) {
        if (oneRepMaxKg == null || oneRepMaxKg <= 0f) {
            trainingMaxes.remove(exerciseId to profileId)
        } else {
            trainingMaxes[exerciseId to profileId] = oneRepMaxKg
            // A claimed or written value stops being unassigned for everyone, exactly as
            // selectUnassignedLegacyTrainingMax's NOT EXISTS clause does.
            unassignedLegacyTrainingMaxes.remove(exerciseId)
        }
    }

    override suspend fun getUnassignedLegacyTrainingMax(exerciseId: String): Float? =
        unassignedLegacyTrainingMaxes[exerciseId]

    override suspend fun findByName(name: String): Exercise? = exercises.values.find { it.name == name }

    override suspend fun findByIdOrName(id: String?, name: String): Exercise? {
        // Mirrors SqlDelightExerciseRepository's 3-strategy resolution:
        // 1) direct ID lookup, 2) exact name (trim-tolerant), 3) fuzzy contains-search.
        if (id != null) {
            exercises[id]?.let { return it }
        }
        val trimmed = name.trim()
        // A blank name would fuzzy-match every exercise (everything contains "") —
        // return null instead of an arbitrary first match.
        if (trimmed.isEmpty()) return null
        exercises.values.find { it.name.trim() == trimmed }?.let { return it }
        return exercises.values.find { it.name.contains(trimmed, ignoreCase = true) }
    }
}
