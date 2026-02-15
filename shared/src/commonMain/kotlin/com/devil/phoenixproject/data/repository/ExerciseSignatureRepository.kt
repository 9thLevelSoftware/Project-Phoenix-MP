package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.detection.ExerciseSignature

/**
 * Repository interface for exercise signature persistence.
 *
 * Manages CRUD operations for [ExerciseSignature] data used in
 * exercise auto-detection. Signatures are stored per exercise and
 * evolve over time as the user performs more reps.
 */
interface ExerciseSignatureRepository {

    /**
     * Get all signatures for a specific exercise, ordered by confidence descending.
     *
     * @param exerciseId The exercise ID to look up
     * @return List of signatures for the exercise (highest confidence first)
     */
    suspend fun getSignaturesByExercise(exerciseId: String): List<ExerciseSignature>

    /**
     * Get all signatures as a map for classifier history input.
     *
     * Groups by exerciseId and takes the highest-confidence signature per exercise.
     *
     * @return Map of exerciseId to best signature
     */
    suspend fun getAllSignaturesAsMap(): Map<String, ExerciseSignature>

    /**
     * Save a new signature for an exercise.
     *
     * @param exerciseId The exercise this signature belongs to
     * @param signature The signature to save
     */
    suspend fun saveSignature(exerciseId: String, signature: ExerciseSignature)

    /**
     * Update an existing signature by ID.
     *
     * @param id The database ID of the signature to update
     * @param signature The updated signature values
     */
    suspend fun updateSignature(id: Long, signature: ExerciseSignature)

    /**
     * Delete all signatures for an exercise.
     *
     * @param exerciseId The exercise ID whose signatures should be deleted
     */
    suspend fun deleteSignaturesByExercise(exerciseId: String)
}
