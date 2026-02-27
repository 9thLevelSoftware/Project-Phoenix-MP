package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Pure computation engine for training insight suggestions.
 * Stateless object with pure functions - no DB or DI dependencies.
 * All time-dependent functions accept nowMs parameter for testability.
 */
object SmartSuggestionsEngine {

    private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L
    private const val FOUR_WEEKS_MS = 28 * 24 * 60 * 60 * 1000L
    private const val FOURTEEN_DAYS_MS = 14 * 24 * 60 * 60 * 1000L
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    private const val IMBALANCE_LOW_THRESHOLD = 0.25f
    private const val IMBALANCE_HIGH_THRESHOLD = 0.45f

    private const val PLATEAU_MIN_SESSIONS = 5
    private const val PLATEAU_MIN_CONSECUTIVE = 4
    private const val PLATEAU_WEIGHT_TOLERANCE = 0.5f

    private const val MIN_SESSIONS_FOR_OPTIMAL = 3

    /**
     * SUGG-01: Compute weekly volume per muscle group.
     * Filters sessions from current 7-day window (nowMs - 7 days to nowMs).
     * totalKg = sum of (weightPerCableKg * 2 * workingReps) per session.
     */
    fun computeWeeklyVolume(sessions: List<SessionSummary>, nowMs: Long): WeeklyVolumeReport {
        val weekStart = nowMs - SEVEN_DAYS_MS
        val weekSessions = sessions.filter { it.timestamp in weekStart..nowMs }

        val volumes = weekSessions
            .groupBy { it.muscleGroup }
            .map { (group, groupSessions) ->
                MuscleGroupVolume(
                    muscleGroup = group,
                    sets = groupSessions.size,
                    reps = groupSessions.sumOf { it.workingReps },
                    totalKg = groupSessions.sumOf { (it.weightPerCableKg * 2 * it.workingReps).toDouble() }.toFloat()
                )
            }

        return WeeklyVolumeReport(
            weekStartTimestamp = weekStart,
            volumes = volumes
        )
    }

    /**
     * SUGG-02: Analyze push/pull/legs balance over last 4 weeks.
     * Ideal ratio: ~33% each (excluding core).
     * Imbalance threshold: any category < 25% or > 45% of non-core total.
     */
    fun analyzeBalance(sessions: List<SessionSummary>, nowMs: Long): BalanceAnalysis {
        val fourWeeksAgo = nowMs - FOUR_WEEKS_MS
        val recentSessions = sessions.filter { it.timestamp in fourWeeksAgo..nowMs }

        var pushVol = 0f
        var pullVol = 0f
        var legsVol = 0f

        for (s in recentSessions) {
            val vol = s.weightPerCableKg * 2 * s.workingReps
            when (classifyMuscleGroup(s.muscleGroup)) {
                MovementCategory.PUSH -> pushVol += vol
                MovementCategory.PULL -> pullVol += vol
                MovementCategory.LEGS -> legsVol += vol
                MovementCategory.CORE -> { /* excluded from ratio */ }
            }
        }

        val total = pushVol + pullVol + legsVol
        val imbalances = mutableListOf<BalanceImbalance>()

        if (total > 0f) {
            val pushRatio = pushVol / total
            val pullRatio = pullVol / total
            val legsRatio = legsVol / total

            if (pushRatio < IMBALANCE_LOW_THRESHOLD || pushRatio > IMBALANCE_HIGH_THRESHOLD) {
                imbalances.add(BalanceImbalance(
                    category = MovementCategory.PUSH,
                    ratio = pushRatio,
                    suggestion = if (pushRatio < IMBALANCE_LOW_THRESHOLD)
                        "Add more push exercises (chest, shoulders, triceps) to balance your training"
                    else
                        "Consider reducing push volume and adding more pull/leg exercises"
                ))
            }
            if (pullRatio < IMBALANCE_LOW_THRESHOLD || pullRatio > IMBALANCE_HIGH_THRESHOLD) {
                imbalances.add(BalanceImbalance(
                    category = MovementCategory.PULL,
                    ratio = pullRatio,
                    suggestion = if (pullRatio < IMBALANCE_LOW_THRESHOLD)
                        "Add more pull exercises (back, biceps) to balance your push:pull ratio"
                    else
                        "Consider reducing pull volume and adding more push/leg exercises"
                ))
            }
            if (legsRatio < IMBALANCE_LOW_THRESHOLD || legsRatio > IMBALANCE_HIGH_THRESHOLD) {
                imbalances.add(BalanceImbalance(
                    category = MovementCategory.LEGS,
                    ratio = legsRatio,
                    suggestion = if (legsRatio < IMBALANCE_LOW_THRESHOLD)
                        "Add more leg exercises (squats, lunges) to balance your training"
                    else
                        "Consider reducing leg volume and adding more upper body exercises"
                ))
            }
        }

        return BalanceAnalysis(
            pushVolume = pushVol,
            pullVolume = pullVol,
            legsVolume = legsVol,
            imbalances = imbalances
        )
    }

    /**
     * SUGG-03: Find exercises not performed in the last 14 days.
     * Returns exercises sorted by days since last performed (descending).
     */
    fun findNeglectedExercises(sessions: List<SessionSummary>, nowMs: Long): List<NeglectedExercise> {
        // For each exercise, find the most recent session
        val latestByExercise = sessions
            .groupBy { it.exerciseId }
            .mapValues { (_, exerciseSessions) -> exerciseSessions.maxBy { it.timestamp } }

        return latestByExercise.values
            .map { s ->
                val daysSince = ((nowMs - s.timestamp) / ONE_DAY_MS).toInt()
                NeglectedExercise(
                    exerciseId = s.exerciseId,
                    exerciseName = s.exerciseName,
                    daysSinceLastPerformed = daysSince,
                    muscleGroup = s.muscleGroup
                )
            }
            .filter { it.daysSinceLastPerformed > 14 }
            .sortedByDescending { it.daysSinceLastPerformed }
    }

    /**
     * SUGG-04: Detect exercises where weight hasn't changed across recent sessions.
     * Plateau = weight hasn't changed by more than 0.5kg across last 4+ consecutive sessions.
     * Requires at least 5 total sessions for the exercise.
     */
    fun detectPlateaus(sessions: List<SessionSummary>): List<PlateauDetection> {
        return sessions
            .groupBy { it.exerciseId }
            .mapNotNull { (_, exerciseSessions) ->
                if (exerciseSessions.size < PLATEAU_MIN_SESSIONS) return@mapNotNull null

                val sorted = exerciseSessions.sortedBy { it.timestamp }
                val latest = sorted.last()

                // Count consecutive sessions from end with weight within tolerance
                var consecutiveCount = 1
                val referenceWeight = latest.weightPerCableKg
                for (i in sorted.size - 2 downTo 0) {
                    val diff = kotlin.math.abs(sorted[i].weightPerCableKg - referenceWeight)
                    if (diff <= PLATEAU_WEIGHT_TOLERANCE) {
                        consecutiveCount++
                    } else {
                        break
                    }
                }

                if (consecutiveCount >= PLATEAU_MIN_CONSECUTIVE) {
                    PlateauDetection(
                        exerciseId = latest.exerciseId,
                        exerciseName = latest.exerciseName,
                        currentWeightKg = referenceWeight,
                        sessionCount = consecutiveCount,
                        suggestion = "You've been at ${referenceWeight}kg for $consecutiveCount sessions. " +
                                "Try eccentric overload, drop sets, or changing rep ranges to break through."
                    )
                } else null
            }
    }

    /**
     * SUGG-05: Analyze training performance by time of day.
     * Maps sessions to time windows and finds optimal training time.
     * Requires minimum 3 sessions in a window to consider it for optimal.
     */
    fun analyzeTimeOfDay(
        sessions: List<SessionSummary>,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): TimeOfDayAnalysis {
        if (sessions.isEmpty()) {
            return TimeOfDayAnalysis(
                windowVolumes = emptyMap(),
                windowCounts = emptyMap(),
                optimalWindow = null,
                suggestion = "Not enough data to determine your optimal training time."
            )
        }

        // Group sessions by time window
        val byWindow = sessions.groupBy { classifyTimeWindow(it.timestamp, timeZone) }

        val windowCounts = mutableMapOf<TimeWindow, Int>()
        val windowAvgVolumes = mutableMapOf<TimeWindow, Float>()

        for ((window, windowSessions) in byWindow) {
            windowCounts[window] = windowSessions.size
            val totalVolume = windowSessions.sumOf {
                (it.weightPerCableKg * 2 * it.workingReps).toDouble()
            }.toFloat()
            windowAvgVolumes[window] = totalVolume / windowSessions.size
        }

        // Find optimal: window with highest avg volume, minimum 3 sessions
        val optimal = windowAvgVolumes
            .filter { (window, _) -> (windowCounts[window] ?: 0) >= MIN_SESSIONS_FOR_OPTIMAL }
            .maxByOrNull { it.value }
            ?.key

        val suggestion = if (optimal != null) {
            "Your best performance is during ${formatTimeWindow(optimal)} sessions. " +
                    "Try to schedule your key workouts during this time."
        } else {
            "Train at more consistent times to identify your optimal training window."
        }

        return TimeOfDayAnalysis(
            windowVolumes = windowAvgVolumes,
            windowCounts = windowCounts,
            optimalWindow = optimal,
            suggestion = suggestion
        )
    }

    /**
     * Maps a muscle group string to a MovementCategory for balance analysis.
     * Case-insensitive. Unknown groups default to CORE.
     */
    internal fun classifyMuscleGroup(muscleGroup: String): MovementCategory {
        return when (muscleGroup.lowercase().trim()) {
            "chest", "shoulders", "triceps" -> MovementCategory.PUSH
            "back", "biceps" -> MovementCategory.PULL
            "legs", "glutes" -> MovementCategory.LEGS
            "core", "full body" -> MovementCategory.CORE
            else -> MovementCategory.CORE
        }
    }

    /**
     * Maps a timestamp to a TimeWindow based on local hour of day.
     * Uses the device's local timezone by default. The timeZone parameter is injectable for testing.
     * EARLY_MORNING (5-7), MORNING (7-10), AFTERNOON (10-15), EVENING (15-20), NIGHT (20-5)
     */
    internal fun classifyTimeWindow(
        timestampMs: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): TimeWindow {
        val instant = Instant.fromEpochMilliseconds(timestampMs)
        val localDateTime = instant.toLocalDateTime(timeZone)
        val hourOfDay = localDateTime.hour
        return when (hourOfDay) {
            in 5..6 -> TimeWindow.EARLY_MORNING
            in 7..9 -> TimeWindow.MORNING
            in 10..14 -> TimeWindow.AFTERNOON
            in 15..19 -> TimeWindow.EVENING
            else -> TimeWindow.NIGHT
        }
    }

    private fun formatTimeWindow(window: TimeWindow): String = when (window) {
        TimeWindow.EARLY_MORNING -> "early morning (5-7am)"
        TimeWindow.MORNING -> "morning (7-10am)"
        TimeWindow.AFTERNOON -> "afternoon (10am-3pm)"
        TimeWindow.EVENING -> "evening (3-8pm)"
        TimeWindow.NIGHT -> "night (8pm-5am)"
    }
}
