package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalMvtRepository
import com.devil.phoenixproject.domain.onerepmax.classifyMovementPattern

/**
 * Captures a personalized MVT sample from a completed set whose velocity has collapsed to
 * an RIR≈0 proxy (<= 1.1 × the pattern default). Maintains a rolling mean of recent samples.
 */
class RecordPersonalMvtSampleUseCase(private val personalMvtRepo: PersonalMvtRepository) {
    suspend operator fun invoke(
        exerciseId: String,
        profileId: String,
        exerciseName: String,
        muscleGroups: String,
        sessionMcvMmS: Float,
    ): Boolean {
        if (sessionMcvMmS <= 0f) return false
        val sampleMs = sessionMcvMmS / 1000f
        val patternDefault = classifyMovementPattern(exerciseName, muscleGroups).defaultMvtMs
        if (sampleMs > patternDefault * FAILURE_PROXY_FACTOR) return false

        val existing = personalMvtRepo.get(exerciseId, profileId)
        val prevCount = existing?.sampleCount ?: 0
        val prevMean = existing?.personalMvtMs ?: 0f
        // Incremental mean capped at MAX_SAMPLES weighting.
        val effectiveCount = minOf(prevCount, MAX_SAMPLES - 1)
        val newCount = prevCount + 1
        val newMean = (prevMean * effectiveCount + sampleMs) / (effectiveCount + 1)
        personalMvtRepo.upsert(exerciseId, profileId, newMean, newCount)
        return true
    }

    companion object {
        const val FAILURE_PROXY_FACTOR = 1.1f
        const val MAX_SAMPLES = 5
    }
}
