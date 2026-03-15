package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Shared CSV parsing logic for workout history CSVs exported by [CsvExporter].
 *
 * Expected header (Android export format):
 * `Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load`
 *
 * The parser is tolerant of:
 * - Weight values with unit suffixes ("80.0 kg", "176.4 lb") -- stripped to numeric
 * - Missing optional columns (defaults applied)
 * - Quoted fields containing commas
 */
object CsvParser {

    /** Column names we expect (case-insensitive matching) */
    private val EXPECTED_HEADERS = listOf(
        "date", "exercise", "mode", "target reps", "warmup reps",
        "working reps", "total reps", "weight", "progression",
        "duration (s)", "just lift", "eccentric load"
    )

    /**
     * Parse a complete CSV string into a list of [WorkoutSession] objects.
     *
     * @param csvContent Full CSV file content (including header row)
     * @return Pair of (parsed sessions, list of error messages for failed rows)
     */
    fun parseWorkoutHistory(csvContent: String): Pair<List<WorkoutSession>, List<String>> {
        val lines = csvContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList<WorkoutSession>() to listOf("CSV file is empty")

        // Parse header to build column index map
        val headerLine = lines.first()
        val headers = parseCsvRow(headerLine).map { it.trim().lowercase() }
        val columnMap = buildColumnMap(headers)

        if (columnMap.isEmpty()) {
            return emptyList<WorkoutSession>() to listOf(
                "Unrecognized CSV format. Expected headers: ${EXPECTED_HEADERS.joinToString(", ")}"
            )
        }

        val sessions = mutableListOf<WorkoutSession>()
        val errors = mutableListOf<String>()

        for (i in 1 until lines.size) {
            val line = lines[i]
            try {
                val fields = parseCsvRow(line)
                val session = mapRowToSession(fields, columnMap, rowNumber = i + 1)
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

    /**
     * Build a map of logical column name to column index.
     * Returns empty map if no recognized headers found.
     */
    private fun buildColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((index, header) in headers.withIndex()) {
            // Match against expected headers (handle variations)
            val normalized = when {
                header == "date" -> "date"
                header == "exercise" -> "exercise"
                header == "mode" -> "mode"
                header.contains("target") && header.contains("rep") -> "target_reps"
                header == "warmup reps" -> "warmup_reps"
                header == "working reps" -> "working_reps"
                header == "total reps" || header == "reps" -> "total_reps"
                header == "weight" || header.startsWith("weight") -> "weight"
                header == "progression" -> "progression"
                header.contains("duration") -> "duration"
                header.contains("just lift") -> "just_lift"
                header.contains("eccentric") -> "eccentric_load"
                // iOS format extras
                header == "time" -> "time"
                else -> null
            }
            if (normalized != null) {
                map[normalized] = index
            }
        }

        // Require at minimum: date, exercise, mode
        return if (map.containsKey("date") && map.containsKey("exercise") && map.containsKey("mode")) {
            map
        } else {
            emptyMap()
        }
    }

    /**
     * Map a single CSV row to a [WorkoutSession].
     * Returns null if required fields are missing or unparseable.
     */
    private fun mapRowToSession(
        fields: List<String>,
        columnMap: Map<String, Int>,
        rowNumber: Int
    ): WorkoutSession? {
        fun field(key: String): String? {
            val idx = columnMap[key] ?: return null
            return if (idx < fields.size) fields[idx].trim() else null
        }

        // Required fields
        val dateStr = field("date") ?: return null
        val exerciseName = field("exercise") ?: return null
        val mode = field("mode") ?: return null

        // Parse date to epoch millis
        val timestamp = parseDateToEpochMs(dateStr) ?: throw IllegalArgumentException(
            "Invalid date format: '$dateStr' (expected yyyy-MM-dd)"
        )

        // Optional numeric fields with safe defaults
        val targetReps = field("target_reps")?.toIntOrNull() ?: 0
        val warmupReps = field("warmup_reps")?.toIntOrNull() ?: 0
        val workingReps = field("working_reps")?.toIntOrNull() ?: 0
        val totalReps = field("total_reps")?.toIntOrNull() ?: 0
        val weight = parseWeight(field("weight"))
        val progression = parseWeight(field("progression"))
        val duration = field("duration")?.toLongOrNull() ?: 0L
        val justLift = field("just_lift")?.let {
            it.equals("yes", ignoreCase = true) || it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        val eccentricLoad = field("eccentric_load")?.toIntOrNull() ?: 100

        return WorkoutSession(
            id = generateUUID(), // New ID -- duplicates are detected by timestamp+exercise in importer
            timestamp = timestamp,
            mode = mode,
            reps = targetReps,
            weightPerCableKg = weight,
            progressionKg = progression,
            duration = duration,
            totalReps = totalReps,
            warmupReps = warmupReps,
            workingReps = workingReps,
            isJustLift = justLift,
            eccentricLoad = eccentricLoad,
            exerciseName = exerciseName
        )
    }

    /**
     * Parse a date string in yyyy-MM-dd format to epoch milliseconds.
     */
    private fun parseDateToEpochMs(dateStr: String): Long? {
        return try {
            val localDate = LocalDate.parse(dateStr)
            val instant = localDate.atStartOfDayIn(TimeZone.currentSystemDefault())
            instant.toEpochMilliseconds()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse a weight string that may contain unit suffixes or +/- prefix.
     * Examples: "80.0", "80.0 kg", "+2.5 kg", "-1.0", "176.4 lb"
     */
    internal fun parseWeight(value: String?): Float {
        if (value.isNullOrBlank() || value == "0") return 0f
        // Strip everything except digits, dot, minus, plus
        val numeric = value.replace(Regex("[^\\d.\\-+]"), "").trim()
        return numeric.toFloatOrNull() ?: 0f
    }

    /**
     * Parse a CSV row respecting quoted fields.
     * Handles fields containing commas when enclosed in double quotes.
     * Handles escaped quotes ("") within quoted fields.
     */
    internal fun parseCsvRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> {
                    inQuotes = true
                }
                c == '"' && inQuotes -> {
                    // Check for escaped quote
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // skip next quote
                    } else {
                        inQuotes = false
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> {
                    current.append(c)
                }
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
