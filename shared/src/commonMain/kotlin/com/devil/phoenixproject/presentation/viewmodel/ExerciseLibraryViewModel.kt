package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel for exercise library.
 * Handles exercise listing, filtering, and video retrieval.
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

    private val _exerciseVideos = MutableStateFlow<Map<String, List<ExerciseVideoEntity>>>(emptyMap())

    init {
        loadExercises()
        observeFilters()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            _isLoading.value = true
            exerciseRepository.getAllExercises().collectLatest { exerciseList ->
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
     * Get videos for an exercise synchronously from cache.
     * Call loadVideosForExercise() first to populate the cache.
     */
    fun getVideos(exerciseId: String): List<ExerciseVideoEntity> = _exerciseVideos.value[exerciseId] ?: emptyList()

    /**
     * Load videos for an exercise asynchronously.
     * Results are cached and accessible via getVideos().
     */
    fun loadVideosForExercise(exerciseId: String) {
        viewModelScope.launch {
            val videos = exerciseRepository.getVideos(exerciseId)
            _exerciseVideos.value = _exerciseVideos.value + (exerciseId to videos)
        }
    }

    /**
     * Get videos as a suspend function for direct async access.
     */
    suspend fun getVideosAsync(exerciseId: String): List<ExerciseVideoEntity> = exerciseRepository.getVideos(exerciseId)
}
