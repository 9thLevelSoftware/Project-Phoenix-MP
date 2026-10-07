package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.integration.CsvExporter as StrongCsvExporter
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/**
 * Phoenix workout-history and personal-record CSV, shared by Android and iOS.
 *
 * Platform exporters only write and share these bytes. Strong and Hevy exports stay in
 * `data/integration/CsvExporter.kt`.
 *
 * History header (Android's columns, plus the Time column iOS already wrote):
 * [HISTORY_HEADER].
 *
 * `Duration (s)` is whole seconds. [WorkoutSession.duration] is milliseconds, so encode
 * divides by 1000 (dropping any sub-second remainder) and parse multiplies by 1000.
 * Android builds from before this codec wrote raw milliseconds into that column under a
 * header with no Time column. Only that legacy layout lacks Time, so a history header
 * without Time reads Duration as milliseconds unchanged.
 *
 * A legacy iOS history row (`Date,Time,Exercise,Mode,Weight (UNIT),Progression,Reps,Duration (s)`)
 * still parses. Its single `Reps` cell is [WorkoutSession.reps] (the target). Warmup, working,
 * total, Just Lift, and eccentric load were not in that file, so they stay at the parser defaults.
 *
 * Personal records use [PERSONAL_RECORD_HEADER]. `1RM` is the hybrid estimate from
 * [OneRepMaxCalculator.estimate], formatted with the same weight function as the load cell.
 * PR progression uses [PR_PROGRESSION_HEADER]; "Progress From Previous" is the per-cable
 * weight change, not a 1RM change.
 */
object PhoenixCsvCodec {

    const val HISTORY_HEADER =
        "Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load"

    const val PERSONAL_RECORD_HEADER = "Exercise,Phase,Weight,Reps,Date,Mode,1RM"

    const val PR_PROGRESSION_HEADER = "Exercise,Phase,Date,Weight,Reps,Mode,1RM,Progress From Previous"

    fun encodeWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = buildString {
        appendLine(HISTORY_HEADER)
        workoutSessions.sortedByDescending { it.timestamp }.forEach { session ->
            val exerciseName = historyExerciseName(session, exerciseNames)
            val weightKg = exportWeightKg(session)
            appendLine(
                row(
                    text(formatDate(session.timestamp)),
                    text(formatTime(session.timestamp)),
                    text(exerciseName),
                    text(session.mode),
                    session.reps.toString(),
                    session.warmupReps.toString(),
                    session.workingReps.toString(),
                    session.totalReps.toString(),
                    text(formatWeight(weightKg, weightUnit)),
                    text(formatSignedWeight(session.progressionKg, weightUnit, formatWeight)),
                    durationMsToCsvSeconds(session.duration).toString(),
                    text(if (session.isJustLift) "Yes" else "No"),
                    session.eccentricLoad.toString(),
                ),
            )
        }
    }

    fun encodePersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = buildString {
        appendLine(PERSONAL_RECORD_HEADER)
        personalRecords.sortedByDescending { it.timestamp }.forEach { pr ->
            appendLine(personalRecordRow(pr, exerciseNames, weightUnit, formatWeight))
        }
    }

    fun encodePrProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = buildString {
        appendLine(PR_PROGRESSION_HEADER)
        // Group order follows the first time each exercise appears. Within a group, date
        // order is what "progress from previous" compares.
        personalRecords.groupBy { it.exerciseId }.forEach { (exerciseId, records) ->
            var previousWeight: Float? = null
            records.sortedBy { it.timestamp }.forEach { pr ->
                val progress = previousWeight?.let { previous ->
                    formatSignedWeight(pr.weightPerCableKg - previous, weightUnit, formatWeight)
                } ?: "-"
                appendLine(
                    row(
                        text(personalRecordExerciseName(pr, exerciseNames, exerciseId)),
                        text(pr.phase.name),
                        text(formatDate(pr.timestamp)),
                        text(formatWeight(pr.weightPerCableKg, weightUnit)),
                        pr.reps.toString(),
                        text(pr.workoutMode),
                        text(formatWeight(OneRepMaxCalculator.estimate(pr.weightPerCableKg, pr.reps), weightUnit)),
                        text(progress),
                    ),
                )
                previousWeight = pr.weightPerCableKg
            }
        }
    }

    /**
     * Parse a Phoenix history CSV.
     *
     * @return parsed sessions and row errors. Session ids are new; callers detect duplicates.
     */
    fun parseWorkoutHistory(csvContent: String): Pair<List<WorkoutSession>, List<String>> {
        val lines = csvContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList<WorkoutSession>() to listOf("CSV file is empty")

        val headers = CsvParser.parseCsvRow(lines.first()).map { it.trim().lowercase() }
        val columnMap = buildHistoryColumnMap(headers)
        if (columnMap.isEmpty()) {
            return emptyList<WorkoutSession>() to listOf(
                "Unrecognized CSV format. Expected headers: $HISTORY_HEADER",
            )
        }

        // Legacy Android exports (no Time column) wrote milliseconds into Duration (s).
        val durationIsMillis = !columnMap.containsKey("time")

        val sessions = mutableListOf<WorkoutSession>()
        val errors = mutableListOf<String>()
        for (i in 1 until lines.size) {
            try {
                val session = mapHistoryRow(CsvParser.parseCsvRow(lines[i]), columnMap, durationIsMillis)
                if (session != null) {
                    sessions.add(session)
                } else {
                    errors.add("Row ${i + 1}: Could not parse required fields")
                }
            } catch (e: Exception) {
                errors.add("Row ${i + 1}: ${e.message ?: "Unknown parse error"}")
            }
        }
        return sessions to errors
    }

    private fun personalRecordRow(
        pr: PersonalRecord,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = row(
        text(personalRecordExerciseName(pr, exerciseNames, pr.exerciseId)),
        text(pr.phase.name),
        text(formatWeight(pr.weightPerCableKg, weightUnit)),
        pr.reps.toString(),
        text(formatDate(pr.timestamp)),
        text(pr.workoutMode),
        text(formatWeight(OneRepMaxCalculator.estimate(pr.weightPerCableKg, pr.reps), weightUnit)),
    )

    private fun buildHistoryColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((index, header) in headers.withIndex()) {
            val normalized = when {
                header == "date" -> "date"

                header == "time" -> "time"

                header == "exercise" -> "exercise"

                header == "mode" -> "mode"

                header.contains("target") && header.contains("rep") -> "target_reps"

                header == "warmup reps" -> "warmup_reps"

                header == "working reps" -> "working_reps"

                header == "total reps" -> "total_reps"

                // iOS wrote WorkoutSession.reps here and had no target/total split.
                header == "reps" -> "target_reps"

                header.startsWith("weight") -> "weight"

                header == "progression" -> "progression"

                header.contains("duration") -> "duration"

                header.contains("just lift") -> "just_lift"

                header.contains("eccentric") -> "eccentric_load"

                else -> null
            }
            if (normalized != null) {
                map[normalized] = index
            }
        }
        return if (map.containsKey("date") && map.containsKey("exercise") && map.containsKey("mode")) {
            map
        } else {
            emptyMap()
        }
    }

    private fun mapHistoryRow(
        fields: List<String>,
        columnMap: Map<String, Int>,
        durationIsMillis: Boolean,
    ): WorkoutSession? {
        fun field(key: String): String? {
            val idx = columnMap[key] ?: return null
            return if (idx < fields.size) fields[idx].trim() else null
        }

        val dateStr = field("date") ?: return null
        val exerciseName = field("exercise")?.let(StrongCsvExporter::unescapeFormulaGuard) ?: return null
        val mode = field("mode")?.let(StrongCsvExporter::unescapeFormulaGuard) ?: return null
        val timestamp = parseDateTimeToEpochMs(dateStr, field("time")) ?: throw IllegalArgumentException(
            "Invalid date format: '$dateStr' (expected yyyy-MM-dd)",
        )

        return WorkoutSession(
            id = generateUUID(),
            timestamp = timestamp,
            mode = mode,
            reps = field("target_reps")?.toIntOrNull() ?: 0,
            weightPerCableKg = CsvParser.parseWeight(field("weight")),
            progressionKg = CsvParser.parseWeight(field("progression")),
            duration = (field("duration")?.toLongOrNull() ?: 0L).let { raw ->
                if (durationIsMillis) raw.coerceAtLeast(0L) else csvSecondsToDurationMs(raw)
            },
            totalReps = field("total_reps")?.toIntOrNull() ?: 0,
            warmupReps = field("warmup_reps")?.toIntOrNull() ?: 0,
            workingReps = field("working_reps")?.toIntOrNull() ?: 0,
            isJustLift = field("just_lift")?.let {
                it.equals("yes", ignoreCase = true) || it == "1" || it.equals("true", ignoreCase = true)
            } ?: false,
            eccentricLoad = field("eccentric_load")?.toIntOrNull() ?: 100,
            exerciseName = exerciseName,
        )
    }

    private fun parseDateTimeToEpochMs(dateStr: String, timeStr: String?): Long? {
        val localDate = try {
            LocalDate.parse(dateStr)
        } catch (_: Exception) {
            return null
        }

        if (timeStr.isNullOrBlank()) {
            return localDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }

        return try {
            val localDateTime = LocalDateTime(localDate, LocalTime.parse(timeStr.trim()))
            localDateTime.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        } catch (_: Exception) {
            localDate.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }
    }

    /** Echo achieved load is what users see; other modes export the configured per-cable load. */
    private fun exportWeightKg(session: WorkoutSession): Float {
        // Issue #1182: the achieved Echo load is the measured peak per cable (the resolver),
        // not a phase-peak/average chain and never the configured seed. An Echo set with no
        // measurement exports 0 rather than the placeholder.
        return EchoAchievedLoadResolver.primaryLoadKg(session) ?: 0f
    }

    private fun historyExerciseName(session: WorkoutSession, exerciseNames: Map<String, String>): String = session.exerciseName?.takeIf { it.isNotBlank() }
        ?: session.exerciseId?.let(exerciseNames::get)?.takeIf { it.isNotBlank() }
        ?: session.exerciseId?.takeIf { it.isNotBlank() }
        ?: "Unknown"

    private fun personalRecordExerciseName(
        pr: PersonalRecord,
        exerciseNames: Map<String, String>,
        exerciseId: String,
    ): String = exerciseNames[exerciseId]?.takeIf { it.isNotBlank() }
        ?: pr.exerciseName.takeIf { it.isNotBlank() }
        ?: exerciseId.ifBlank { "Unknown" }

    /** Zero stays `0`. A positive delta is prefixed with `+`. The formatter supplies the unit. */
    private fun formatSignedWeight(
        kg: Float,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): String = when {
        kg > 0f -> "+${formatWeight(kg, weightUnit)}"
        kg < 0f -> formatWeight(kg, weightUnit)
        else -> "0"
    }

    private fun formatDate(timestamp: Long): String = KmpUtils.formatTimestamp(timestamp, "yyyy-MM-dd")

    private fun formatTime(timestamp: Long): String = KmpUtils.formatTimestamp(timestamp, "HH:mm")

    private fun durationMsToCsvSeconds(durationMs: Long): Long = durationMs.coerceAtLeast(0L) / 1000L

    private fun csvSecondsToDurationMs(seconds: Long): Long = seconds.coerceAtLeast(0L) * 1000L

    /** Text cells go through the shared formula guard. Numeric cells are passed through raw. */
    private fun text(value: String): String = StrongCsvExporter.escapeCsvField(value)

    private fun row(vararg cells: String): String = cells.joinToString(",")
}
