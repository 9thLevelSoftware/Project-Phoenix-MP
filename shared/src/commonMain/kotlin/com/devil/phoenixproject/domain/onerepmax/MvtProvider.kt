package com.devil.phoenixproject.domain.onerepmax

/** Resolves the Minimum Velocity Threshold (m/s) for an exercise. */
class MvtProvider {
    fun resolve(
        exerciseName: String,
        muscleGroups: String,
        userOverrideMs: Float?,
        personalMvtMs: Float?,
        personalSampleCount: Int,
    ): Float {
        if (userOverrideMs != null && userOverrideMs > 0f) return userOverrideMs
        if (personalMvtMs != null && personalMvtMs > 0f && personalSampleCount >= MIN_PERSONAL_MVT_SAMPLES) {
            return personalMvtMs
        }
        return classifyMovementPattern(exerciseName, muscleGroups).defaultMvtMs
    }

    companion object {
        const val MIN_PERSONAL_MVT_SAMPLES = 3
    }
}
