package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseSignatureRepository
import com.devil.phoenixproject.domain.detection.ExerciseClassification
import com.devil.phoenixproject.domain.detection.ExerciseClassifier
import com.devil.phoenixproject.domain.detection.ExerciseSignature
import com.devil.phoenixproject.domain.detection.SignatureExtractor
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State for exercise auto-detection UI.
 */
data class DetectionState(
    /** Whether detection sheet should show */
    val isActive: Boolean = false,
    /** Classification result from the detector */
    val classification: ExerciseClassification? = null,
    /** Current extracted signature for potential storage */
    val signature: ExerciseSignature? = null,
    /** User dismissed without confirming (prevents re-trigger for this set) */
    val isDismissed: Boolean = false
)

/**
 * Orchestrates exercise auto-detection during active workouts.
 *
 * After a configurable number of working reps, this manager:
 * 1. Extracts a movement signature from collected metrics
 * 2. Classifies the exercise using history matching or rule-based detection
 * 3. Presents the suggestion via DetectionState for UI rendering
 * 4. Stores/evolves signatures when the user confirms
 *
 * Per DETECT-03, DETECT-04, DETECT-06 specifications.
 */
class ExerciseDetectionManager(
    private val signatureExtractor: SignatureExtractor,
    private val exerciseClassifier: ExerciseClassifier,
    private val signatureRepository: ExerciseSignatureRepository,
    private val exerciseRepository: ExerciseRepository
) {
    companion object {
        /** Minimum working reps before triggering detection */
        const val MIN_REPS_FOR_DETECTION = 3
    }

    private val _detectionState = MutableStateFlow(DetectionState())
    val detectionState: StateFlow<DetectionState> = _detectionState

    /** Flag to prevent multiple triggers per set */
    private var hasTriggeredThisSet = false

    /**
     * Called after each working rep completes.
     *
     * Triggers detection after MIN_REPS_FOR_DETECTION reps if:
     * - Detection hasn't already triggered for this set
     * - User hasn't dismissed the sheet for this set
     * - The current workout doesn't already have an exercise assigned
     *
     * @param repNumber Current working rep count
     * @param metrics Collected WorkoutMetric samples for this set
     * @param scope CoroutineScope to launch detection work
     * @param hasExerciseAssigned True if workout already has an exercise selected
     */
    fun onRepCompleted(
        repNumber: Int,
        metrics: List<WorkoutMetric>,
        scope: CoroutineScope,
        hasExerciseAssigned: Boolean = false
    ) {
        // Skip if already triggered this set or user dismissed
        if (hasTriggeredThisSet || _detectionState.value.isDismissed) {
            return
        }

        // Skip if exercise already assigned (routine mode or user already selected)
        if (hasExerciseAssigned) {
            return
        }

        // Only trigger at the threshold rep count
        if (repNumber < MIN_REPS_FOR_DETECTION) {
            return
        }

        hasTriggeredThisSet = true

        scope.launch(Dispatchers.Default) {
            try {
                // Extract signature from metrics
                val signature = signatureExtractor.extractSignature(metrics)
                if (signature == null) {
                    Logger.d("ExerciseDetectionManager") { "Insufficient data for signature extraction" }
                    return@launch
                }

                // Load user's exercise history for matching
                val history = signatureRepository.getAllSignaturesAsMap()

                // Classify the exercise
                val classification = exerciseClassifier.classify(signature, history)

                Logger.d("ExerciseDetectionManager") {
                    "Exercise detected: ${classification.exerciseName} (${(classification.confidence * 100).toInt()}% confidence)"
                }

                // Update state to show the detection sheet
                _detectionState.value = DetectionState(
                    isActive = true,
                    classification = classification,
                    signature = signature,
                    isDismissed = false
                )
            } catch (e: Exception) {
                Logger.e("ExerciseDetectionManager", e) { "Detection failed" }
            }
        }
    }

    /**
     * Called when user confirms the detected or selected exercise.
     *
     * Stores the signature for new exercises or evolves existing signatures
     * using EMA for improved future matching.
     *
     * @param exerciseId The exercise ID to associate with the signature
     * @param exerciseName Human-readable name for logging
     * @return The confirmed exerciseId
     */
    suspend fun onExerciseConfirmed(exerciseId: String, exerciseName: String): String {
        val currentSignature = _detectionState.value.signature
        if (currentSignature == null) {
            Logger.w("ExerciseDetectionManager") { "No signature to save for confirmed exercise" }
            clearDetectionState()
            return exerciseId
        }

        try {
            // Check for existing signature
            val existingSignatures = signatureRepository.getSignaturesByExercise(exerciseId)

            if (existingSignatures.isNotEmpty()) {
                // Evolve existing signature with EMA
                val existing = existingSignatures.first()
                val evolved = exerciseClassifier.evolveSignature(existing, currentSignature)

                // Note: updateSignature needs the database ID, but we don't have it exposed
                // For now, we'll delete and re-save. This is a simplification that works.
                signatureRepository.deleteSignaturesByExercise(exerciseId)
                signatureRepository.saveSignature(exerciseId, evolved)

                Logger.d("ExerciseDetectionManager") {
                    "Evolved signature for $exerciseName (sample count: ${evolved.sampleCount})"
                }
            } else {
                // Save new signature
                signatureRepository.saveSignature(exerciseId, currentSignature)
                Logger.d("ExerciseDetectionManager") { "Saved new signature for $exerciseName" }
            }
        } catch (e: Exception) {
            Logger.e("ExerciseDetectionManager", e) { "Failed to save/evolve signature" }
        }

        clearDetectionState()
        return exerciseId
    }

    /**
     * Called when user dismisses the detection sheet without confirming.
     * Prevents re-triggering for the remainder of this set.
     */
    fun onDetectionDismissed() {
        _detectionState.value = DetectionState(isDismissed = true)
    }

    /**
     * Reset detection state for a new set.
     * Called when transitioning between sets or starting a new workout.
     */
    fun resetForNewSet() {
        hasTriggeredThisSet = false
        _detectionState.value = DetectionState()
    }

    /**
     * Clear detection state (internal helper).
     */
    private fun clearDetectionState() {
        _detectionState.value = DetectionState()
    }
}
