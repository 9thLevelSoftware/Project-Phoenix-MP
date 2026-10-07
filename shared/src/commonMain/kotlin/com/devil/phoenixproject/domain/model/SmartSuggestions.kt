package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver

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
    val workingReps: Int,
    val cableCount: Int? = null, // null for legacy data without cable metadata
    // Issue #1182: Echo analytics must never fall back to the configured Echo seed.
    val isEcho: Boolean = false,
    /** Recorded measured peak per cable (the row's measured column), if any. */
    val measuredPeakKg: Float? = null,
    /** Already-stored measured summary volume (v0.2.1+), if any. */
    val measuredTotalVolumeKg: Float? = null,
    /** True when the row carries independent telemetry evidence (recorded peak forces). */
    val hasForceTelemetry: Boolean = false,
)

/** Effective cable multiplier: 2 for known dual-cable, 1 otherwise (safe default for unknown). */
val SessionSummary.cableMultiplier: Int get() = if (cableCount == 2) 2 else 1

/**
 * Volume contribution of one session for smart-suggestion analytics.
 *
 * Issue #1182: Echo volume is the ALREADY-STORED measured summary volume, gated on the
 * row's measured-load provenance (the same rule as [EchoAchievedLoadResolver]). It is
 * never recomputed from the configured Echo seed and never as an invented peak x reps
 * formula; an Echo session with no accepted working telemetry has NO volume claim and is
 * suppressed. Non-Echo keeps the existing programmed-load formula unchanged.
 */
val SessionSummary.volumeKg: Float?
    get() {
        if (isEcho) {
            if (!EchoAchievedLoadResolver.hasMeasuredLoad(measuredPeakKg, weightPerCableKg, hasForceTelemetry)) return null
            return measuredTotalVolumeKg?.takeIf { it.isFinite() && it > 0f }
        }
        return weightPerCableKg * cableMultiplier * workingReps
    }

// SUGG-01: Volume per muscle group

data class MuscleGroupVolume(val muscleGroup: String, val sets: Int, val reps: Int, val totalKg: Float)

data class WeeklyVolumeReport(val volumes: List<MuscleGroupVolume>)

// SUGG-02: Balance analysis

data class BalanceAnalysis(val pushVolume: Float, val pullVolume: Float, val legsVolume: Float, val imbalances: List<BalanceImbalance>)

data class BalanceImbalance(val category: MovementCategory, val ratio: Float, val suggestion: String)

// SUGG-03: Neglected exercises

data class NeglectedExercise(val exerciseId: String, val exerciseName: String, val daysSinceLastPerformed: Int, val muscleGroup: String)

// SUGG-04: Plateau detection

data class PlateauDetection(
    val exerciseId: String,
    val exerciseName: String,
    val currentWeightKg: Float,
    val workoutDayCount: Int,
    val suggestion: String,
)

// SUGG-05: Time-of-day analysis

enum class TimeWindow { EARLY_MORNING, MORNING, AFTERNOON, EVENING, NIGHT }

data class TimeOfDayAnalysis(
    val windowVolumes: Map<TimeWindow, Float>,
    val windowCounts: Map<TimeWindow, Int>,
    val optimalWindow: TimeWindow?,
    val suggestion: String,
)
