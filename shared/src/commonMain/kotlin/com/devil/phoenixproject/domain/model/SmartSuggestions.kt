package com.devil.phoenixproject.domain.model

/**
 * Movement category for push/pull/legs classification.
 * Used by SmartSuggestionsEngine to analyze training balance.
 */
enum class MovementCategory { PUSH, PULL, LEGS, CORE }

/**
 * Flattened session data used as input for the SmartSuggestionsEngine.
 * Represents one exercise entry from a completed workout session.
 */
data class SessionSummary(
    val exerciseId: String,
    val exerciseName: String,
    val muscleGroup: String,
    val timestamp: Long,
    val weightPerCableKg: Float,
    val totalReps: Int,
    val workingReps: Int
)

// SUGG-01: Volume per muscle group

data class MuscleGroupVolume(
    val muscleGroup: String,
    val sets: Int,
    val reps: Int,
    val totalKg: Float
)

data class WeeklyVolumeReport(
    val weekStartTimestamp: Long,
    val volumes: List<MuscleGroupVolume>
)

// SUGG-02: Balance analysis

data class BalanceAnalysis(
    val pushVolume: Float,
    val pullVolume: Float,
    val legsVolume: Float,
    val imbalances: List<BalanceImbalance>
)

data class BalanceImbalance(
    val category: MovementCategory,
    val ratio: Float,
    val suggestion: String
)

// SUGG-03: Neglected exercises

data class NeglectedExercise(
    val exerciseId: String,
    val exerciseName: String,
    val daysSinceLastPerformed: Int,
    val muscleGroup: String
)

// SUGG-04: Plateau detection

data class PlateauDetection(
    val exerciseId: String,
    val exerciseName: String,
    val currentWeightKg: Float,
    val sessionCount: Int,
    val suggestion: String
)

// SUGG-05: Time-of-day analysis

enum class TimeWindow { EARLY_MORNING, MORNING, AFTERNOON, EVENING, NIGHT }

data class TimeOfDayAnalysis(
    val windowVolumes: Map<TimeWindow, Float>,
    val windowCounts: Map<TimeWindow, Int>,
    val optimalWindow: TimeWindow?,
    val suggestion: String
)
