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
    val isRestDay: Boolean,
    val echoLevel: EchoLevel? = null,
    val eccentricLoadPercent: Int? = null,
    val weightProgressionPercent: Float? = null,
    val repModifier: Int? = null,
    val restTimeOverrideSeconds: Int? = null
) {
    companion object {
        fun create(
            id: String = generateUUID(),
            cycleId: String,
            dayNumber: Int,
            name: String? = null,
            routineId: String? = null,
            isRestDay: Boolean = false,
            echoLevel: EchoLevel? = null,
            eccentricLoadPercent: Int? = null,
            weightProgressionPercent: Float? = null,
            repModifier: Int? = null,
            restTimeOverrideSeconds: Int? = null
        ) = CycleDay(
            id = id,
            cycleId = cycleId,
            dayNumber = dayNumber,
            name = name,
            routineId = routineId,
            isRestDay = isRestDay,
            echoLevel = echoLevel,
            eccentricLoadPercent = eccentricLoadPercent,
            weightProgressionPercent = weightProgressionPercent,
            repModifier = repModifier,
            restTimeOverrideSeconds = restTimeOverrideSeconds
        )

        fun restDay(
            id: String = generateUUID(),
            cycleId: String,
            dayNumber: Int,
            name: String? = "Rest",
            echoLevel: EchoLevel? = null,
            eccentricLoadPercent: Int? = null,
            weightProgressionPercent: Float? = null,
            repModifier: Int? = null,
            restTimeOverrideSeconds: Int? = null
        ) = CycleDay(
            id = id,
            cycleId = cycleId,
            dayNumber = dayNumber,
            name = name,
            routineId = null,
            isRestDay = true,
            echoLevel = echoLevel,
            eccentricLoadPercent = eccentricLoadPercent,
            weightProgressionPercent = weightProgressionPercent,
            repModifier = repModifier,
            restTimeOverrideSeconds = restTimeOverrideSeconds
        )
    }
}

/**
 * UI-facing representation of a cycle day.
 * Sealed class forces distinct handling of workout vs rest days.
 */
sealed class CycleItem {
    abstract val id: String
    abstract val dayNumber: Int

    data class Workout(
        override val id: String,
        override val dayNumber: Int,
        val routineId: String,
        val routineName: String,
        val exerciseCount: Int,
        val estimatedMinutes: Int? = null
    ) : CycleItem()

    data class Rest(
        override val id: String,
        override val dayNumber: Int,
        val note: String? = null
    ) : CycleItem()

    companion object {
        /**
         * Convert a CycleDay to a CycleItem.
         * Requires routine info for workout days.
         */
        fun fromCycleDay(
            day: CycleDay,
            routineName: String?,
            exerciseCount: Int
        ): CycleItem {
            return if (day.isRestDay || day.routineId == null) {
                Rest(
                    id = day.id,
                    dayNumber = day.dayNumber,
                    note = day.name
                )
            } else {
                Workout(
                    id = day.id,
                    dayNumber = day.dayNumber,
                    routineId = day.routineId,
                    routineName = routineName ?: "Unknown Routine",
                    exerciseCount = exerciseCount
                )
            }
        }
    }
}

/**
 * Cycle-wide progression settings.
 * Applied every N cycle completions.
 */
data class CycleProgression(
    val cycleId: String,
    val frequencyCycles: Int = 2,
    val weightIncreasePercent: Float? = null,
    val echoLevelIncrease: Boolean = false,
    val eccentricLoadIncreasePercent: Int? = null
) {
    companion object {
        fun default(cycleId: String) = CycleProgression(cycleId = cycleId)
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
    val cycleStartDate: Long,
    val lastAdvancedAt: Long? = null,
    val completedDays: Set<Int> = emptySet(),
    val missedDays: Set<Int> = emptySet(),
    val rotationCount: Int = 0
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

    /**
     * Check if 24 hours have passed since last advance, triggering auto-advance.
     */
    fun shouldAutoAdvance(): Boolean {
        val advancedAt = lastAdvancedAt ?: return false
        val now = currentTimeMillis()
        val hoursSince = (now - advancedAt) / (60 * 60 * 1000L)
        return hoursSince >= 24
    }

    /**
     * Advance to the next day in the cycle.
     * @param totalDays Total number of days in the cycle
     * @param markMissed If true, mark current day as missed before advancing
     * @return New CycleProgress with updated values
     */
    fun advanceToNextDay(totalDays: Int, markMissed: Boolean = false): CycleProgress {
        val updatedMissedDays = if (markMissed) {
            missedDays + currentDayNumber
        } else {
            missedDays
        }

        val nextDay = if (currentDayNumber >= totalDays) 1 else currentDayNumber + 1
        val isNewRotation = nextDay == 1

        return copy(
            currentDayNumber = nextDay,
            lastAdvancedAt = currentTimeMillis(),
            completedDays = if (isNewRotation) emptySet() else completedDays,
            missedDays = if (isNewRotation) emptySet() else updatedMissedDays,
            rotationCount = if (isNewRotation) rotationCount + 1 else rotationCount
        )
    }

    /**
     * Mark a day as completed and advance to the next day.
     * Any days between currentDayNumber and dayNumber are marked as missed.
     * @param dayNumber The day number that was completed
     * @param totalDays Total number of days in the cycle
     * @return New CycleProgress with updated values
     */
    fun markDayCompleted(dayNumber: Int, totalDays: Int): CycleProgress {
        // Calculate skipped days (between current and completed day)
        val skippedDays = if (dayNumber > currentDayNumber) {
            (currentDayNumber until dayNumber).toSet()
        } else {
            emptySet()
        }

        val updatedMissedDays = missedDays + skippedDays
        val updatedCompletedDays = completedDays + dayNumber

        val nextDay = if (dayNumber >= totalDays) 1 else dayNumber + 1
        val isNewRotation = nextDay == 1
        val now = currentTimeMillis()

        return copy(
            currentDayNumber = nextDay,
            lastCompletedDate = now,
            lastAdvancedAt = now,
            completedDays = if (isNewRotation) emptySet() else updatedCompletedDays,
            missedDays = if (isNewRotation) emptySet() else updatedMissedDays,
            rotationCount = if (isNewRotation) rotationCount + 1 else rotationCount
        )
    }

    companion object {
        fun create(
            id: String = generateUUID(),
            cycleId: String,
            currentDayNumber: Int = 1,
            lastAdvancedAt: Long? = null,
            completedDays: Set<Int> = emptySet(),
            missedDays: Set<Int> = emptySet(),
            rotationCount: Int = 0
        ) = CycleProgress(
            id = id,
            cycleId = cycleId,
            currentDayNumber = currentDayNumber,
            lastCompletedDate = null,
            cycleStartDate = currentTimeMillis(),
            lastAdvancedAt = lastAdvancedAt,
            completedDays = completedDays,
            missedDays = missedDays,
            rotationCount = rotationCount
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
            routineExerciseId: String,
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
