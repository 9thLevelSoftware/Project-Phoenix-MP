package com.devil.phoenixproject.domain.model

/**
 * Set types for workout tracking.
 * Each type has different rep handling behavior.
 */
enum class SetType {
    /** Fixed rep target - auto-stops at target reps */
    STANDARD,
    /** As Many Reps As Possible - records actual reps achieved */
    AMRAP,
    /** Reduce weight mid-set, continue - multiple sub-sets */
    DROP_SET,
    /** Lighter preparation set - excluded from volume tracking */
    WARMUP
}

/**
 * Training cycle - a rolling schedule of workout days.
 * Replaces the calendar-bound WeeklyProgram with flexible Day 1, Day 2, etc.
 */
data class TrainingCycle(
    val id: String,
    val name: String,
    val description: String?,
    val days: List<CycleDay>,
    val createdAt: Long,
    val isActive: Boolean,
    val progressionRule: ProgressionRule? = null,
    val weekNumber: Int = 1
) {
    companion object {
        fun create(
            id: String = generateUUID(),
            name: String,
            description: String? = null,
            days: List<CycleDay> = emptyList(),
            isActive: Boolean = false,
            progressionRule: ProgressionRule? = null,
            weekNumber: Int = 1
        ) = TrainingCycle(
            id = id,
            name = name,
            description = description,
            days = days,
            createdAt = currentTimeMillis(),
            isActive = isActive,
            progressionRule = progressionRule,
            weekNumber = weekNumber
        )
    }
}

/**
 * A single day within a training cycle.
 * Uses day number (1, 2, 3...) instead of weekday binding.
 */
data class CycleDay(
    val id: String,
    val cycleId: String,
    val dayNumber: Int,
    val name: String?,
    val routineId: String?,
    val isRestDay: Boolean
) {
    companion object {
        fun create(
            id: String = generateUUID(),
            cycleId: String,
            dayNumber: Int,
            name: String? = null,
            routineId: String? = null,
            isRestDay: Boolean = false
        ) = CycleDay(
            id = id,
            cycleId = cycleId,
            dayNumber = dayNumber,
            name = name,
            routineId = routineId,
            isRestDay = isRestDay
        )

        fun restDay(
            id: String = generateUUID(),
            cycleId: String,
            dayNumber: Int,
            name: String? = "Rest"
        ) = CycleDay(
            id = id,
            cycleId = cycleId,
            dayNumber = dayNumber,
            name = name,
            routineId = null,
            isRestDay = true
        )
    }
}

/**
 * Tracks user's current position in a training cycle.
 */
data class CycleProgress(
    val id: String,
    val cycleId: String,
    val currentDayNumber: Int,
    val lastCompletedDate: Long?,
    val cycleStartDate: Long
) {
    /**
     * Calculate days since last workout for gap detection.
     */
    fun daysSinceLastWorkout(): Int? {
        if (lastCompletedDate == null) return null
        val now = currentTimeMillis()
        return ((now - lastCompletedDate) / (24 * 60 * 60 * 1000L)).toInt()
    }

    /**
     * Check if user has been away long enough to trigger recovery dialog.
     */
    fun needsRecoveryDialog(): Boolean {
        val daysSince = daysSinceLastWorkout() ?: return false
        return daysSince >= 3
    }

    companion object {
        fun create(
            id: String = generateUUID(),
            cycleId: String,
            currentDayNumber: Int = 1
        ) = CycleProgress(
            id = id,
            cycleId = cycleId,
            currentDayNumber = currentDayNumber,
            lastCompletedDate = null,
            cycleStartDate = currentTimeMillis()
        )
    }
}

/**
 * A pre-planned set within a routine exercise.
 * Defines the target for what the user should do.
 */
data class PlannedSet(
    val id: String,
    val routineExerciseId: String,
    val setNumber: Int,
    val setType: SetType,
    val targetReps: Int?,
    val targetWeightKg: Float?,
    val targetRpe: Int?,
    val restSeconds: Int?
) {
    companion object {
        fun standard(
            id: String = generateUUID(),
            routineExerciseId: String,
            setNumber: Int,
            targetReps: Int,
            targetWeightKg: Float,
            targetRpe: Int? = null,
            restSeconds: Int? = null
        ) = PlannedSet(
            id = id,
            routineExerciseId = routineExerciseId,
            setNumber = setNumber,
            setType = SetType.STANDARD,
            targetReps = targetReps,
            targetWeightKg = targetWeightKg,
            targetRpe = targetRpe,
            restSeconds = restSeconds
        )

        fun amrap(
            id: String = generateUUID(),
            routineExerciseId: String,
            setNumber: Int,
            targetWeightKg: Float,
            targetRpe: Int? = null,
            restSeconds: Int? = null
        ) = PlannedSet(
            id = id,
            routineExerciseId = routineExerciseId,
            setNumber = setNumber,
            setType = SetType.AMRAP,
            targetReps = null,
            targetWeightKg = targetWeightKg,
            targetRpe = targetRpe,
            restSeconds = restSeconds
        )

        fun warmup(
            id: String = generateUUID(),
            routineExerciseId: Long,
            setNumber: Int,
            targetReps: Int,
            targetWeightKg: Float
        ) = PlannedSet(
            id = id,
            routineExerciseId = routineExerciseId,
            setNumber = setNumber,
            setType = SetType.WARMUP,
            targetReps = targetReps,
            targetWeightKg = targetWeightKg,
            targetRpe = null,
            restSeconds = null
        )
    }
}

/**
 * A completed set with actual performance data.
 * Records what the user actually did.
 */
data class CompletedSet(
    val id: String,
    val sessionId: String,
    val plannedSetId: String?,
    val setNumber: Int,
    val setType: SetType,
    val actualReps: Int,
    val actualWeightKg: Float,
    val loggedRpe: Int?,
    val isPr: Boolean,
    val completedAt: Long
) {
    /**
     * Calculate estimated 1RM using Epley formula.
     */
    fun estimatedOneRepMax(): Float {
        if (actualReps <= 0) return actualWeightKg
        if (actualReps == 1) return actualWeightKg
        return actualWeightKg * (1 + 0.0333f * actualReps)
    }

    /**
     * Calculate volume (weight × reps).
     */
    fun volume(): Float = actualWeightKg * actualReps

    companion object {
        fun create(
            id: String = generateUUID(),
            sessionId: String,
            plannedSetId: String? = null,
            setNumber: Int,
            setType: SetType = SetType.STANDARD,
            actualReps: Int,
            actualWeightKg: Float,
            loggedRpe: Int? = null,
            isPr: Boolean = false
        ) = CompletedSet(
            id = id,
            sessionId = sessionId,
            plannedSetId = plannedSetId,
            setNumber = setNumber,
            setType = setType,
            actualReps = actualReps,
            actualWeightKg = actualWeightKg,
            loggedRpe = loggedRpe,
            isPr = isPr,
            completedAt = currentTimeMillis()
        )
    }
}

/**
 * Reasons for suggesting a weight progression.
 */
enum class ProgressionReason {
    /** User hit target reps for consecutive sessions */
    REPS_ACHIEVED,
    /** User logged RPE below target */
    LOW_RPE
}

/**
 * User's response to a progression suggestion.
 */
enum class ProgressionResponse {
    /** User accepted the suggested weight */
    ACCEPTED,
    /** User modified the suggested weight */
    MODIFIED,
    /** User rejected the suggestion (kept old weight) */
    REJECTED
}

/**
 * Tracks a weight progression suggestion and user response.
 */
data class ProgressionEvent(
    val id: String,
    val exerciseId: String,
    val suggestedWeightKg: Float,
    val previousWeightKg: Float,
    val reason: ProgressionReason,
    val userResponse: ProgressionResponse?,
    val actualWeightKg: Float?,
    val timestamp: Long
) {
    /**
     * Calculate the suggested increment.
     */
    fun increment(): Float = suggestedWeightKg - previousWeightKg

    /**
     * Check if this suggestion is still pending.
     */
    fun isPending(): Boolean = userResponse == null

    companion object {
        fun create(
            id: String = generateUUID(),
            exerciseId: String,
            previousWeightKg: Float,
            reason: ProgressionReason
        ): ProgressionEvent {
            val suggestedWeight = calculateProgressionWeight(previousWeightKg)
            return ProgressionEvent(
                id = id,
                exerciseId = exerciseId,
                suggestedWeightKg = suggestedWeight,
                previousWeightKg = previousWeightKg,
                reason = reason,
                userResponse = null,
                actualWeightKg = null,
                timestamp = currentTimeMillis()
            )
        }

        /**
         * Calculate progression weight: 2.5% increase, rounded to 0.5kg, minimum 0.5kg increment.
         */
        fun calculateProgressionWeight(currentWeight: Float): Float {
            val rawIncrease = currentWeight * 0.025f
            val increment = maxOf(0.5f, (rawIncrease * 2).toInt() / 2f)
            return currentWeight + increment
        }
    }
}

// Extension functions for collections

/**
 * Get compact string representation of sets.
 * Example: "80kg × 10, 10, 8"
 */
fun List<CompletedSet>.toCompactString(formatWeight: (Float) -> String): String {
    if (isEmpty()) return ""

    val byWeight = groupBy { it.actualWeightKg }

    return if (byWeight.size == 1) {
        val weight = formatWeight(first().actualWeightKg)
        val reps = sortedBy { it.setNumber }.joinToString(", ") { it.actualReps.toString() }
        "$weight × $reps"
    } else {
        sortedBy { it.setNumber }
            .joinToString(", ") { "${formatWeight(it.actualWeightKg)} × ${it.actualReps}" }
    }
}

/**
 * Get best estimated 1RM from a list of sets.
 */
fun List<CompletedSet>.bestOneRepMax(): Float? {
    return mapNotNull { set ->
        if (set.actualReps > 0) set.estimatedOneRepMax() else null
    }.maxOrNull()
}

/**
 * Calculate total volume from a list of sets.
 */
fun List<CompletedSet>.totalVolume(): Float {
    return sumOf { it.volume().toDouble() }.toFloat()
}

/**
 * Filter to only working sets (exclude warmups).
 */
fun List<CompletedSet>.workingSets(): List<CompletedSet> {
    return filter { it.setType != SetType.WARMUP }
}

/**
 * Types of progression strategies for training cycles.
 */
enum class ProgressionType {
    /** Increase weight by percentage (e.g., +2.5%) */
    PERCENTAGE,
    /** Increase weight by fixed amount (e.g., +2.5kg) */
    FIXED_WEIGHT,
    /** No automatic progression suggestions */
    MANUAL
}

/**
 * Defines how weight progression works for a training cycle.
 */
data class ProgressionRule(
    val type: ProgressionType,
    val incrementPercent: Float? = null,
    val incrementKgUpper: Float? = null,
    val incrementKgLower: Float? = null,
    val triggerCondition: String? = null,
    val cycleWeeks: Int? = null
) {
    companion object {
        /** Standard percentage-based progression (+2.5% when all sets completed) */
        fun percentage(percent: Float = 2.5f) = ProgressionRule(
            type = ProgressionType.PERCENTAGE,
            incrementPercent = percent,
            triggerCondition = "all_sets_completed"
        )

        /** 5/3/1 style fixed weight progression */
        fun fiveThreeOne() = ProgressionRule(
            type = ProgressionType.FIXED_WEIGHT,
            incrementKgUpper = 2.5f,
            incrementKgLower = 5.0f,
            triggerCondition = "cycle_complete",
            cycleWeeks = 4
        )

        /** No automatic progression */
        fun manual() = ProgressionRule(type = ProgressionType.MANUAL)
    }
}
