package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.AssessmentRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.assessment.AssessmentResult
import com.devil.phoenixproject.domain.assessment.AssessmentSetResult
import com.devil.phoenixproject.domain.assessment.LoadVelocityPoint
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sealed class representing each step of the assessment wizard.
 */
sealed class AssessmentStep {
    data class ExerciseSelection(
        val exercises: List<Exercise> = emptyList(),
        val searchQuery: String = ""
    ) : AssessmentStep()

    data class Instruction(
        val exercise: Exercise,
        val videos: List<ExerciseVideoEntity> = emptyList()
    ) : AssessmentStep()

    data class ProgressiveLoading(
        val currentSetNumber: Int = 1,
        val suggestedWeightKg: Float = 20f,
        val recordedSets: List<AssessmentSetResult> = emptyList(),
        val latestVelocity: Float? = null,
        val shouldStop: Boolean = false
    ) : AssessmentStep()

    data class Results(
        val estimatedOneRepMaxKg: Float,
        val r2: Float,
        val loadVelocityPoints: List<LoadVelocityPoint>,
        val overrideValueKg: String = ""
    ) : AssessmentStep()

    data object Saving : AssessmentStep()

    data class Complete(
        val finalOneRepMaxKg: Float,
        val exerciseName: String
    ) : AssessmentStep()
}

/**
 * ViewModel for the multi-step strength assessment wizard.
 *
 * Manages wizard state transitions: ExerciseSelection -> Instruction ->
 * ProgressiveLoading -> Results -> Saving -> Complete.
 *
 * The user physically performs sets on the Vitruvian machine and logs
 * weight + velocity after each set. The engine estimates 1RM from
 * the load-velocity profile.
 */
class AssessmentViewModel(
    private val exerciseRepository: ExerciseRepository,
    private val assessmentRepository: AssessmentRepository,
    private val assessmentEngine: AssessmentEngine
) : ViewModel() {

    private val _currentStep = MutableStateFlow<AssessmentStep>(AssessmentStep.ExerciseSelection())
    val currentStep: StateFlow<AssessmentStep> = _currentStep.asStateFlow()

    private val _exercises = MutableStateFlow<List<Exercise>>(emptyList())
    val exercises: StateFlow<List<Exercise>> = _exercises.asStateFlow()

    private var selectedExercise: Exercise? = null
    private var assessmentStartTimeMs: Long = 0L
    private var assessmentResult: AssessmentResult? = null

    init {
        loadExercises()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            exerciseRepository.getAllExercises().collectLatest { exerciseList ->
                _exercises.value = exerciseList
                // Update exercise selection step if we're on it
                val current = _currentStep.value
                if (current is AssessmentStep.ExerciseSelection) {
                    _currentStep.value = current.copy(exercises = exerciseList)
                }
            }
        }
    }

    /**
     * Update the search query and filter exercises in the selection step.
     */
    fun updateSearchQuery(query: String) {
        val current = _currentStep.value
        if (current is AssessmentStep.ExerciseSelection) {
            _currentStep.value = current.copy(searchQuery = query)
        }
    }

    /**
     * Select an exercise and transition to the Instruction step.
     * Loads exercise videos; if none found, skips to ProgressiveLoading.
     */
    fun selectExercise(exercise: Exercise) {
        selectedExercise = exercise
        viewModelScope.launch {
            val exerciseId = exercise.id
            val videos = if (exerciseId != null) {
                try {
                    exerciseRepository.getVideos(exerciseId)
                } catch (e: Exception) {
                    Logger.w("Failed to load videos for ${exercise.name}: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }

            if (videos.isEmpty()) {
                // No videos, skip instruction and go straight to loading
                startAssessmentInternal()
            } else {
                _currentStep.value = AssessmentStep.Instruction(
                    exercise = exercise,
                    videos = videos
                )
            }
        }
    }

    /**
     * Auto-select an exercise by ID (used when navigating from exercise detail).
     */
    fun selectExerciseById(exerciseId: String) {
        viewModelScope.launch {
            // Wait for exercises to be loaded, then find the match
            val allExercises = _exercises.value
            val exercise = allExercises.find { it.id == exerciseId }
            if (exercise != null) {
                selectExercise(exercise)
            } else {
                Logger.w("Exercise with ID $exerciseId not found in loaded exercises")
            }
        }
    }

    /**
     * Transition from Instruction to ProgressiveLoading step.
     */
    fun startAssessment() {
        startAssessmentInternal()
    }

    private fun startAssessmentInternal() {
        assessmentStartTimeMs = currentTimeMillis()
        val exercise = selectedExercise ?: return

        // Calculate initial suggested weight
        val existingOneRm = exercise.oneRepMaxKg
        val startingLoad = if (existingOneRm != null && existingOneRm > 0f) {
            existingOneRm * 0.4f
        } else {
            20f
        }

        // Use the engine to get a properly snapped suggestion
        val suggestedWeight = assessmentEngine.suggestNextWeight(
            currentLoadKg = startingLoad,
            currentVelocity = 1.2f // High velocity assumption for first set
        )

        _currentStep.value = AssessmentStep.ProgressiveLoading(
            currentSetNumber = 1,
            suggestedWeightKg = suggestedWeight,
            recordedSets = emptyList(),
            latestVelocity = null,
            shouldStop = false
        )
    }

    /**
     * Record a completed set during the progressive loading phase.
     *
     * Called AFTER the user physically performs reps on the machine and
     * enters the actual weight used and the mean velocity observed.
     *
     * @param loadKg Actual weight used for the set
     * @param reps Number of reps performed (typically 3)
     * @param meanVelocityMs Mean concentric velocity in m/s
     * @param peakVelocityMs Peak concentric velocity in m/s
     */
    fun recordSet(loadKg: Float, reps: Int, meanVelocityMs: Float, peakVelocityMs: Float) {
        val current = _currentStep.value
        if (current !is AssessmentStep.ProgressiveLoading) return

        val newSet = AssessmentSetResult(
            setNumber = current.currentSetNumber,
            loadKg = loadKg,
            reps = reps,
            meanVelocityMs = meanVelocityMs,
            peakVelocityMs = peakVelocityMs
        )

        val updatedSets = current.recordedSets + newSet
        val shouldStop = assessmentEngine.shouldStopAssessment(meanVelocityMs)

        // Check if we should finish: velocity threshold reached OR 5 sets recorded
        if (shouldStop || updatedSets.size >= 5) {
            // Try to estimate 1RM
            val points = updatedSets.map { LoadVelocityPoint(it.loadKg, it.meanVelocityMs) }
            val result = assessmentEngine.estimateOneRepMax(points)

            if (result != null) {
                assessmentResult = result
                _currentStep.value = AssessmentStep.Results(
                    estimatedOneRepMaxKg = result.estimatedOneRepMaxKg,
                    r2 = result.r2,
                    loadVelocityPoints = result.loadVelocityPoints
                )
            } else {
                // Not enough valid data for regression - show what we have with a fallback
                // Use the heaviest set as a rough estimate
                val heaviestLoad = updatedSets.maxOf { it.loadKg }
                _currentStep.value = AssessmentStep.Results(
                    estimatedOneRepMaxKg = heaviestLoad,
                    r2 = 0f,
                    loadVelocityPoints = points
                )
            }
        } else {
            // Continue with next set
            val nextWeight = assessmentEngine.suggestNextWeight(loadKg, meanVelocityMs)
            _currentStep.value = AssessmentStep.ProgressiveLoading(
                currentSetNumber = current.currentSetNumber + 1,
                suggestedWeightKg = nextWeight,
                recordedSets = updatedSets,
                latestVelocity = meanVelocityMs,
                shouldStop = false
            )
        }
    }

    /**
     * Accept the assessment result and save to database.
     *
     * @param overrideKg If non-null and > 0, replaces the estimated 1RM
     */
    fun acceptResult(overrideKg: Float? = null) {
        val exercise = selectedExercise ?: return
        val current = _currentStep.value
        if (current !is AssessmentStep.Results) return

        _currentStep.value = AssessmentStep.Saving

        viewModelScope.launch {
            try {
                val finalOneRm = if (overrideKg != null && overrideKg > 0f) {
                    overrideKg
                } else {
                    current.estimatedOneRepMaxKg
                }

                // Serialize load-velocity data as JSON
                val lvDataJson = Json.encodeToString(
                    current.loadVelocityPoints.map {
                        mapOf("loadKg" to it.loadKg.toString(), "velocityMs" to it.meanVelocityMs.toString())
                    }
                )

                val totalReps = assessmentResult?.let {
                    // Sum reps from recorded sets (accessible via the step history)
                    // Each set is typically 3 reps
                    current.loadVelocityPoints.size * 3
                } ?: (current.loadVelocityPoints.size * 3)

                val durationMs = currentTimeMillis() - assessmentStartTimeMs
                val avgWeight = current.loadVelocityPoints.map { it.loadKg }.average().toFloat()

                assessmentRepository.saveAssessmentSession(
                    exerciseId = exercise.id ?: exercise.name,
                    exerciseName = exercise.displayName,
                    estimatedOneRepMaxKg = current.estimatedOneRepMaxKg,
                    loadVelocityDataJson = lvDataJson,
                    userOverrideKg = if (overrideKg != null && overrideKg > 0f) overrideKg else null,
                    totalReps = totalReps,
                    durationMs = durationMs,
                    weightPerCableKg = avgWeight / 2f // Per cable = total / 2
                )

                _currentStep.value = AssessmentStep.Complete(
                    finalOneRepMaxKg = finalOneRm,
                    exerciseName = exercise.displayName
                )

                Logger.i("Assessment saved: ${exercise.displayName} -> $finalOneRm kg 1RM")
            } catch (e: Exception) {
                Logger.e("Failed to save assessment: ${e.message}")
                // Return to results so user can retry
                _currentStep.value = current
            }
        }
    }

    /**
     * Reset the wizard back to exercise selection.
     */
    fun reset() {
        selectedExercise = null
        assessmentResult = null
        assessmentStartTimeMs = 0L
        _currentStep.value = AssessmentStep.ExerciseSelection(
            exercises = _exercises.value
        )
    }
}
