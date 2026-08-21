package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel for exercise library.
 * Handles exercise listing, filtering, and demonstration-image retrieval.
 */
class ExerciseLibraryViewModel(private val exerciseRepository: ExerciseRepository) : ViewModel() {
    private val _exercises = MutableStateFlow<List<Exercise>>(emptyList())
    val exercises: StateFlow<List<Exercise>> = _exercises.asStateFlow()

    private val _filteredExercises = MutableStateFlow<List<Exercise>>(emptyList())
    val filteredExercises: StateFlow<List<Exercise>> = _filteredExercises.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedMuscleGroup = MutableStateFlow<String?>(null)
    val selectedMuscleGroup: StateFlow<String?> = _selectedMuscleGroup.asStateFlow()

    private val _exerciseImages = MutableStateFlow<Map<String, List<ExerciseImageEntity>>>(emptyMap())

    init {
        loadExercises()
        observeFilters()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            _isLoading.value = true
            exerciseRepository.getAllExercises()
                .catch { e ->
                    Logger.e("ExerciseLibraryVM", e) { "Failed to load exercises: ${e.message}" }
                    _isLoading.value = false
                }
                .collectLatest { exerciseList ->
                    _exercises.value = exerciseList
                    _isLoading.value = false
                }
        }
    }

    private fun observeFilters() {
        viewModelScope.launch {
            combine(
                _exercises,
                _searchQuery,
                _selectedMuscleGroup,
            ) { exercises, query, muscleGroup ->
                exercises.filter { exercise ->
                    val matchesQuery = query.isBlank() ||
                        exercise.name.contains(query, ignoreCase = true)
                    val matchesMuscleGroup = muscleGroup == null ||
                        exercise.muscleGroup.equals(muscleGroup, ignoreCase = true)
                    matchesQuery && matchesMuscleGroup
                }
            }.collect { filtered ->
                _filteredExercises.value = filtered
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setMuscleGroupFilter(muscleGroup: String?) {
        _selectedMuscleGroup.value = muscleGroup
    }

    /**
     * Get demonstration images for an exercise synchronously from cache.
     * Call loadImagesForExercise() first to populate the cache.
     */
    fun getImages(exerciseId: String): List<ExerciseImageEntity> = _exerciseImages.value[exerciseId] ?: emptyList()

    /**
     * Load demonstration images for an exercise asynchronously.
     * Results are cached and accessible via getImages().
     */
    fun loadImagesForExercise(exerciseId: String) {
        viewModelScope.launch {
            try {
                val images = exerciseRepository.getImages(exerciseId)
                _exerciseImages.value = _exerciseImages.value + (exerciseId to images)
            } catch (e: Exception) {
                Logger.w("ExerciseLibraryVM", e) { "Failed to load images for $exerciseId: ${e.message}" }
            }
        }
    }

    /**
     * Get demonstration images as a suspend function for direct async access.
     */
    suspend fun getImagesAsync(exerciseId: String): List<ExerciseImageEntity> = exerciseRepository.getImages(exerciseId)
}
