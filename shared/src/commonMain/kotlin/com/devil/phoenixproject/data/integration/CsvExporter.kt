package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Exports workout sessions to Strong-compatible CSV format.
 *
 * Strong CSV format columns:
 * `Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes`
 *
 * Weight convention: [WorkoutSession.weightPerCableKg] is PER-CABLE. This exporter
 * multiplies by [WEIGHT_MULTIPLIER] (2) to produce the total weight before any
 * unit conversion, matching the portal's display convention.
 *
 * Each session row represents one "set" in Strong terms. Sessions sharing the same
 * [WorkoutSession.routineSessionId] are grouped under the same workout name and
 * their set order is numbered sequentially per exercise.
 */
object CsvExporter {

    private const val WEIGHT_MULTIPLIER = 2
    private const val KG_TO_LB = 2.20462f

    private val STRONG_HEADER =
        "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes"

    /**
     * Generate a Strong-compatible CSV string from a list of [WorkoutSession] objects.
     *
     * @param sessions The sessions to export. May be empty.
     * @param weightUnit Target weight unit for the Weight column ([WeightUnit.KG] or [WeightUnit.LB]).
     * @return A CSV string including the header row. Returns header-only when [sessions] is empty.
     */
    fun generateStrongCsv(sessions: List<WorkoutSession>, weightUnit: WeightUnit): String {
        if (sessions.isEmpty()) return STRONG_HEADER

        val sb = StringBuilder()
        sb.appendLine(STRONG_HEADER)

        // Group sessions: routine sessions by routineSessionId, standalone by their own id.
        // Within each group, track per-exercise set order.
        val groups = groupSessions(sessions)

        for ((_, groupSessions) in groups) {
            // Per-exercise set counters reset for each workout group.
            val setOrderByExercise = mutableMapOf<String, Int>()

            for (session in groupSessions) {
                val exerciseKey = session.exerciseName ?: session.id
                val setOrder = (setOrderByExercise[exerciseKey] ?: 0) + 1
                setOrderByExercise[exerciseKey] = setOrder

                val row = buildRow(session, setOrder, weightUnit)
                sb.appendLine(row)
            }
        }

        // Remove trailing newline added by the last appendLine
        return sb.toString().trimEnd('\r', '\n')
    }

    // -------------------------------------------------------------------------
    // Internal helpers (internal visibility so unit tests can call them directly)
    // -------------------------------------------------------------------------

    /**
     * Group sessions by workout boundary.
     * - Sessions with a non-null [WorkoutSession.routineSessionId] are grouped together.
     * - Standalone sessions each form their own group.
     *
     * Returns a [LinkedHashMap] preserving insertion order so CSV rows stay chronological.
     */
    internal fun groupSessions(sessions: List<WorkoutSession>): LinkedHashMap<String, List<WorkoutSession>> {
        val result = LinkedHashMap<String, List<WorkoutSession>>()
        val routineGroups = mutableMapOf<String, MutableList<WorkoutSession>>()

        for (session in sessions) {
            val routineId = session.routineSessionId
            if (routineId != null) {
                routineGroups.getOrPut(routineId) { mutableListOf() }.add(session)
            } else {
                // Standalone: one group per session, keyed by session id
                result[session.id] = listOf(session)
            }
        }

        // Insert routine groups in the order of their first session's appearance.
        // We need to merge them at the correct position relative to standalones.
        // Rebuild result respecting original session order.
        val orderedResult = LinkedHashMap<String, List<WorkoutSession>>()
        val seenRoutines = mutableSetOf<String>()

        for (session in sessions) {
            val routineId = session.routineSessionId
            if (routineId != null) {
                if (seenRoutines.add(routineId)) {
                    orderedResult[routineId] = routineGroups[routineId] ?: listOf(session)
                }
            } else {
                orderedResult[session.id] = listOf(session)
            }
        }

        return orderedResult
    }

    /**
     * Build a single Strong CSV row for a session.
     */
    internal fun buildRow(session: WorkoutSession, setOrder: Int, weightUnit: WeightUnit): String {
        val date = formatTimestamp(session.timestamp)
        val workoutName = resolveWorkoutName(session)
        val duration = formatDuration(session.duration)
        val exerciseName = session.exerciseName ?: ""
        val weight = formatWeight(session.weightPerCableKg, weightUnit)
        val reps = if (session.totalReps > 0) session.totalReps else session.reps

        return buildString {
            append(escapeCsvField(date))
            append(',')
            append(escapeCsvField(workoutName))
            append(',')
            append(escapeCsvField(duration))
            append(',')
            append(escapeCsvField(exerciseName))
            append(',')
            append(setOrder)
            append(',')
            append(weight)
            append(',')
            append(reps)
            append(',')
            append("") // Distance — not applicable for cable machines
            append(',')
            append("") // Seconds — not applicable (TUT duration not exported)
            append(',')
            append("") // Notes
            append(',')
            append("") // Workout Notes
        }
    }

    /**
     * Format epoch milliseconds to `YYYY-MM-DD HH:MM:SS` in the device's local time zone.
     */
    internal fun formatTimestamp(epochMs: Long): String {
        val instant = Instant.fromEpochMilliseconds(epochMs)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            local.year, local.month.number, local.day,
            local.hour, local.minute, local.second
        )
    }

    /**
     * Format duration in seconds to Strong's `1h 23m` or `45m` format.
     * Zero duration returns `"0m"`.
     */
    internal fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0L) return "0m"
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /**
     * Format per-cable weight to total weight string (multiplied by [WEIGHT_MULTIPLIER]).
     * Converts to lbs when [weightUnit] is [WeightUnit.LB].
     * Returns a plain decimal string with up to 2 decimal places, trimming trailing zeros.
     */
    internal fun formatWeight(perCableKg: Float, weightUnit: WeightUnit): String {
        val totalKg = perCableKg * WEIGHT_MULTIPLIER
        val value = if (weightUnit == WeightUnit.LB) totalKg * KG_TO_LB else totalKg
        // Format with up to 2 decimal places, strip trailing zeros after decimal
        val formatted = "%.2f".format(value)
        return formatted.trimEnd('0').trimEnd('.')
    }

    /**
     * Resolve a human-readable workout name for the Strong "Workout Name" column.
     * - Routine sessions use [WorkoutSession.routineName] (or "Routine" as fallback).
     * - Standalone sessions use the exercise name, or `"Workout"` as last resort.
     */
    internal fun resolveWorkoutName(session: WorkoutSession): String {
        return when {
            session.routineSessionId != null -> session.routineName ?: "Routine"
            session.exerciseName != null -> session.exerciseName
            else -> "Workout"
        }
    }

    /**
     * Escape a CSV field value. Wraps in double-quotes if the value contains
     * a comma, double-quote, or newline. Internal double-quotes are doubled.
     */
    internal fun escapeCsvField(value: String): String {
        val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n')
        return if (needsQuoting) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
