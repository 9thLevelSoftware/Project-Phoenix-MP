package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Routine

/**
 * Estimates the total duration of a routine based on historical workout data
 * with a fallback calculation from configured rest times and estimated set durations.
 *
 * Issue #225
 */
class RoutineTimeEstimator(private val workoutRepository: WorkoutRepository) {
    /**
     * Estimate routine duration in seconds.
     * Uses historical average set duration per exercise when available,
     * falls back to calculation from rest times + estimated 45s per set.
     */
    suspend fun estimateRoutineDuration(routine: Routine): RoutineTimeEstimate {
        var totalHistoricalMs = 0L
        var historicalExerciseCount = 0
        var fallbackSeconds = 0

        for (exercise in routine.exercises) {
            val exerciseId = exercise.exercise.id
            if (exerciseId != null) {
                // Try to get average set duration from history
                val avgDurationMs = workoutRepository.getAverageSetDurationMs(
                    exerciseId,
                    profileId = "default",
                )
                if (avgDurationMs != null && avgDurationMs > 0) {
                    totalHistoricalMs += avgDurationMs * exercise.sets
                    historicalExerciseCount++
                    // Add rest times between sets
                    for (setIdx in 0 until exercise.sets - 1) {
                        fallbackSeconds += exercise.getRestForSet(setIdx)
                    }
                    continue
                }
            }
            // Fallback: estimate 45s per set + configured rest times
            fallbackSeconds += exercise.sets * 45
            for (setIdx in 0 until exercise.sets - 1) {
                fallbackSeconds += exercise.getRestForSet(setIdx)
            }
        }

        val totalSeconds = (totalHistoricalMs / 1000).toInt() + fallbackSeconds
        return RoutineTimeEstimate(
            totalSeconds = totalSeconds,
            isHistoryBased = historicalExerciseCount > 0,
            historicalExerciseCount = historicalExerciseCount,
            totalExerciseCount = routine.exercises.size,
        )
    }
}

data class RoutineTimeEstimate(
    val totalSeconds: Int,
    val isHistoryBased: Boolean,
    val historicalExerciseCount: Int,
    val totalExerciseCount: Int,
) {
    val formattedDuration: String
        get() {
            val minutes = totalSeconds / 60
            return if (minutes >= 60) {
                "${minutes / 60}h ${minutes % 60}m"
            } else {
                "${minutes}m"
            }
        }
}
