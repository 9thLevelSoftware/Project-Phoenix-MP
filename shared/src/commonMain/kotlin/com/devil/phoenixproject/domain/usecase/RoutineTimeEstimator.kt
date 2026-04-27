package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineItem

/**
 * Estimates the total duration of a routine based on historical workout data
 * with a fallback calculation from configured rest times and estimated set durations.
 *
 * Supports:
 * - Historical average set duration with minimum session threshold
 * - AMRAP sets with 1.5x duration multiplier (range output: 1.0x-2.0x)
 * - Warmup sets at 0.7x working set duration (no rest between warmup sets)
 * - Superset-aware traversal using Routine.getItems()
 * - 30s exercise transition time between top-level items
 * - Bodyweight exercise fallback (30s per set)
 *
 * Issue #225
 */
class RoutineTimeEstimator(private val workoutRepository: WorkoutRepository) {

    companion object {
        /** Fallback duration for a cable exercise set when no history exists */
        const val CABLE_SET_FALLBACK_SEC = 45

        /** Fallback duration for a bodyweight exercise set */
        const val BODYWEIGHT_SET_FALLBACK_SEC = 30

        /** Fallback duration for an AMRAP set when no history exists */
        const val AMRAP_FALLBACK_SEC = 90

        /** Multiplier applied to historical average for AMRAP sets (midpoint estimate) */
        const val AMRAP_DURATION_MULTIPLIER = 1.5

        /** Factor applied to warmup set duration relative to working set */
        const val WARMUP_DURATION_FACTOR = 0.7

        /** Transition time between top-level routine items (exercises/supersets) in ms */
        const val EXERCISE_TRANSITION_MS = 30_000L

        /** Minimum number of completed sessions before trusting historical averages */
        const val MIN_HISTORICAL_SESSIONS = 3
    }

    /**
     * Estimate routine duration.
     *
     * @param routine The routine to estimate
     * @param profileId The user profile ID (for querying historical data)
     * @return Estimated duration with range bounds for AMRAP routines
     */
    suspend fun estimateRoutineDuration(routine: Routine, profileId: String): RoutineTimeEstimate {
        if (routine.exercises.isEmpty()) {
            return RoutineTimeEstimate(
                totalSeconds = 0,
                lowerBoundSeconds = 0,
                upperBoundSeconds = 0,
                isHistoryBased = false,
                historicalExerciseCount = 0,
                totalExerciseCount = 0,
            )
        }

        var totalMidpointMs = 0L
        var totalLowerMs = 0L
        var totalUpperMs = 0L
        var historicalExerciseCount = 0
        var totalExerciseCount = 0
        var hasAmrap = false

        val items = routine.getItems()

        items.forEachIndexed { itemIndex, item ->
            when (item) {
                is RoutineItem.Single -> {
                    totalExerciseCount++
                    val exercise = item.exercise
                    val result = estimateExerciseMs(exercise, profileId)
                    totalMidpointMs += result.midpointMs
                    totalLowerMs += result.lowerMs
                    totalUpperMs += result.upperMs
                    if (result.isHistoryBased) historicalExerciseCount++
                    if (result.hasAmrap) hasAmrap = true
                }
                is RoutineItem.SupersetItem -> {
                    val superset = item.superset
                    val supersetExercises = superset.exercises

                    // A superset interleaves exercises: A1, B1, rest, A2, B2, rest, ...
                    val maxSets = supersetExercises.maxOfOrNull { it.sets } ?: 0

                    for (exercise in supersetExercises) {
                        totalExerciseCount++
                        val result = estimateExerciseMs(exercise, profileId, includeRest = false)
                        totalMidpointMs += result.midpointMs
                        totalLowerMs += result.lowerMs
                        totalUpperMs += result.upperMs
                        if (result.isHistoryBased) historicalExerciseCount++
                        if (result.hasAmrap) hasAmrap = true
                    }

                    // Rest between exercises within the superset (between each exercise in a round)
                    // For each round, there are (exerciseCount - 1) transitions within the superset
                    val restBetweenMs = superset.restBetweenSeconds * 1000L
                    val intraSupersetRestMs = restBetweenMs * (supersetExercises.size - 1).coerceAtLeast(0) * maxSets
                    totalMidpointMs += intraSupersetRestMs
                    totalLowerMs += intraSupersetRestMs
                    totalUpperMs += intraSupersetRestMs

                    // Rest between rounds (last exercise's rest time per set, except last round)
                    // Use the first exercise's rest times for between-round rest
                    val primaryExercise = supersetExercises.firstOrNull()
                    if (primaryExercise != null && maxSets > 1) {
                        for (setIdx in 0 until maxSets - 1) {
                            val restMs = primaryExercise.getRestForSet(setIdx) * 1000L
                            totalMidpointMs += restMs
                            totalLowerMs += restMs
                            totalUpperMs += restMs
                        }
                    }
                }
            }

            // Add transition time between top-level items (not after last)
            if (itemIndex < items.size - 1) {
                totalMidpointMs += EXERCISE_TRANSITION_MS
                totalLowerMs += EXERCISE_TRANSITION_MS
                totalUpperMs += EXERCISE_TRANSITION_MS
            }
        }

        val totalSeconds = (totalMidpointMs / 1000).toInt()
        val lowerSeconds = (totalLowerMs / 1000).toInt()
        val upperSeconds = (totalUpperMs / 1000).toInt()

        return RoutineTimeEstimate(
            totalSeconds = totalSeconds,
            lowerBoundSeconds = if (hasAmrap) lowerSeconds else totalSeconds,
            upperBoundSeconds = if (hasAmrap) upperSeconds else totalSeconds,
            isHistoryBased = historicalExerciseCount > 0,
            historicalExerciseCount = historicalExerciseCount,
            totalExerciseCount = totalExerciseCount,
        )
    }

    /**
     * Estimate total duration for a single exercise (working sets + warmup sets + optional rest).
     *
     * @param includeRest Whether to include rest between working sets. Set to false for
     *   exercises within a superset, where rest timing is managed at the superset level.
     */
    private suspend fun estimateExerciseMs(
        exercise: com.devil.phoenixproject.domain.model.RoutineExercise,
        profileId: String,
        includeRest: Boolean = true,
    ): ExerciseEstimateResult {
        val exerciseId = exercise.exercise.id
        val isBodyweight = !exercise.exercise.hasCableAccessory

        // Try to get historical average set duration
        var historicalAvgMs: Long? = null
        var isHistoryBased = false

        if (exerciseId != null) {
            val sessionCount = workoutRepository.getSessionCountForExercise(exerciseId, profileId)
            if (sessionCount >= MIN_HISTORICAL_SESSIONS) {
                val avgMs = workoutRepository.getAverageSetDurationMs(exerciseId, profileId)
                if (avgMs != null && avgMs > 0) {
                    historicalAvgMs = avgMs
                    isHistoryBased = true
                }
            }
        }

        var midpointMs = 0L
        var lowerMs = 0L
        var upperMs = 0L
        var hasAmrap = false

        // === Warmup sets ===
        val warmupSetCount = exercise.warmupSets.size
        if (warmupSetCount > 0) {
            val baseWarmupDurationMs = if (historicalAvgMs != null) {
                (historicalAvgMs * WARMUP_DURATION_FACTOR).toLong()
            } else {
                val fallbackSec = if (isBodyweight) BODYWEIGHT_SET_FALLBACK_SEC else CABLE_SET_FALLBACK_SEC
                (fallbackSec * 1000L * WARMUP_DURATION_FACTOR).toLong()
            }
            // No rest between warmup sets as per spec
            val warmupTotalMs = baseWarmupDurationMs * warmupSetCount
            midpointMs += warmupTotalMs
            lowerMs += warmupTotalMs
            upperMs += warmupTotalMs
        }

        // === Working sets ===
        for (setIdx in 0 until exercise.sets) {
            val reps = exercise.setReps.getOrNull(setIdx)
            val isAmrapSet = reps == null // null reps = AMRAP indicator

            if (isAmrapSet) {
                hasAmrap = true
                if (historicalAvgMs != null) {
                    // AMRAP with history: use multiplier range
                    lowerMs += (historicalAvgMs * 1.0).toLong()
                    midpointMs += (historicalAvgMs * AMRAP_DURATION_MULTIPLIER).toLong()
                    upperMs += (historicalAvgMs * 2.0).toLong()
                } else {
                    // AMRAP without history: use AMRAP fallback
                    val amrapFallbackMs = AMRAP_FALLBACK_SEC * 1000L
                    lowerMs += (amrapFallbackMs * 1.0 / AMRAP_DURATION_MULTIPLIER).toLong()
                    midpointMs += amrapFallbackMs
                    upperMs += (amrapFallbackMs * 2.0 / AMRAP_DURATION_MULTIPLIER).toLong()
                }
            } else {
                // Fixed rep set
                val setDurationMs = historicalAvgMs
                    ?: (if (isBodyweight) BODYWEIGHT_SET_FALLBACK_SEC else CABLE_SET_FALLBACK_SEC) * 1000L
                midpointMs += setDurationMs
                lowerMs += setDurationMs
                upperMs += setDurationMs
            }

            // Rest between working sets (not after last set)
            // Skip when exercise is in a superset (rest managed at superset level)
            if (includeRest && setIdx < exercise.sets - 1) {
                val restMs = exercise.getRestForSet(setIdx) * 1000L
                midpointMs += restMs
                lowerMs += restMs
                upperMs += restMs
            }
        }

        return ExerciseEstimateResult(
            midpointMs = midpointMs,
            lowerMs = lowerMs,
            upperMs = upperMs,
            isHistoryBased = isHistoryBased,
            hasAmrap = hasAmrap,
        )
    }

    private data class ExerciseEstimateResult(
        val midpointMs: Long,
        val lowerMs: Long,
        val upperMs: Long,
        val isHistoryBased: Boolean,
        val hasAmrap: Boolean,
    )
}

/**
 * Result of routine time estimation.
 *
 * For fixed-rep routines: lowerBoundSeconds == upperBoundSeconds == totalSeconds.
 * For AMRAP routines: provides a range where totalSeconds is the midpoint (1.5x).
 */
data class RoutineTimeEstimate(
    /** Midpoint estimate in seconds */
    val totalSeconds: Int,
    /** Lower bound estimate in seconds (1.0x for AMRAP sets) */
    val lowerBoundSeconds: Int,
    /** Upper bound estimate in seconds (2.0x for AMRAP sets) */
    val upperBoundSeconds: Int,
    val isHistoryBased: Boolean,
    val historicalExerciseCount: Int,
    val totalExerciseCount: Int,
) {
    /** Whether this estimate contains AMRAP sets (range differs from point estimate) */
    val hasRange: Boolean get() = lowerBoundSeconds != upperBoundSeconds

    /** Whether the estimate is entirely fallback-based (no historical data at all) */
    val isEntirelyFallback: Boolean get() = !isHistoryBased

    val formattedDuration: String
        get() {
            val minutes = totalSeconds / 60
            return if (minutes >= 60) {
                "${minutes / 60}h ${minutes % 60}m"
            } else {
                "${minutes}m"
            }
        }

    /**
     * Formatted range string for display.
     * Fixed-rep: "42 min"
     * AMRAP: "35-50 min"
     */
    val formattedRange: String
        get() {
            if (!hasRange) return formattedDuration
            val lowerMin = lowerBoundSeconds / 60
            val upperMin = upperBoundSeconds / 60
            return if (upperMin >= 60) {
                val lowerH = lowerMin / 60
                val lowerM = lowerMin % 60
                val upperH = upperMin / 60
                val upperM = upperMin % 60
                "${lowerH}h ${lowerM}m - ${upperH}h ${upperM}m"
            } else {
                "$lowerMin-${upperMin}m"
            }
        }
}
