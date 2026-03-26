package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.CsvParser
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Supported third-party CSV formats.
 */
enum class CsvFormat { STRONG, HEVY, UNKNOWN }

/**
 * Preview of a CSV import before committing to the database.
 *
 * @param format Detected source format
 * @param activities Parsed [ExternalActivity] objects ready for insertion
 * @param workoutCount Number of distinct workouts (groups of rows)
 * @param dateRange Pair of (earliestMs, latestMs) across all activities, or null when empty
 * @param totalDurationSeconds Sum of all activity durations in seconds
 * @param errors Human-readable error descriptions for unparseable rows
 */
data class CsvImportPreview(
    val format: CsvFormat,
    val activities: List<ExternalActivity>,
    val workoutCount: Int,
    val dateRange: Pair<Long, Long>?,
    val totalDurationSeconds: Long,
    val errors: List<String>
)

/**
 * Parses third-party workout CSV exports (Strong, Hevy) into [ExternalActivity] objects.
 *
 * ### Weight convention
 * Weights from external apps represent total weight (both cables / free-weight feel).
 * They are stored as-is on [ExternalActivity], which documents that its weights are NOT
 * per-cable. The portal is responsible for display decisions.
 *
 * ### Row grouping
 * Both Strong and Hevy CSVs use one row per *set*. This importer groups rows into a single
 * [ExternalActivity] per *workout session* (identified by name + date key) so the portal
 * sees one activity card per workout, not one per set.
 */
object CsvImporter {

    // Strong column headers (lowercase) that uniquely identify the format
    private val STRONG_REQUIRED_HEADERS = setOf("workout name", "exercise name", "set order")

    // Hevy column headers (lowercase) that uniquely identify the format
    private val HEVY_REQUIRED_HEADERS = setOf("title", "exercise_title", "set_index")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Auto-detect the CSV format by inspecting the header row.
     *
     * Detection is case-insensitive. [CsvFormat.UNKNOWN] is returned when no
     * known header signature matches.
     */
    fun detectFormat(content: String): CsvFormat {
        val headerLine = content.lines().firstOrNull { it.isNotBlank() } ?: return CsvFormat.UNKNOWN
        val headers = CsvParser.parseCsvRow(headerLine).map { it.trim().lowercase() }.toSet()
        return when {
            STRONG_REQUIRED_HEADERS.all { it in headers } -> CsvFormat.STRONG
            HEVY_REQUIRED_HEADERS.all { it in headers } -> CsvFormat.HEVY
            else -> CsvFormat.UNKNOWN
        }
    }

    /**
     * Parse CSV [content] into a [CsvImportPreview].
     *
     * @param content Full CSV file text including the header row
     * @param weightUnit User's preferred weight unit (stored as metadata, not converted here)
     * @param profileId Profile to assign to each [ExternalActivity]
     * @param isPaidUser Controls [ExternalActivity.needsSync] — only paid users sync to portal
     */
    fun parse(
        content: String,
        weightUnit: WeightUnit,
        profileId: String = "default",
        isPaidUser: Boolean = false
    ): CsvImportPreview {
        val format = detectFormat(content)
        return when (format) {
            CsvFormat.STRONG -> parseStrongCsv(content, weightUnit, profileId, isPaidUser)
            CsvFormat.HEVY -> parseHevyCsv(content, weightUnit, profileId, isPaidUser)
            CsvFormat.UNKNOWN -> CsvImportPreview(
                format = CsvFormat.UNKNOWN,
                activities = emptyList(),
                workoutCount = 0,
                dateRange = null,
                totalDurationSeconds = 0L,
                errors = listOf("Unrecognized CSV format — expected Strong or Hevy headers")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Strong CSV parser
    // -------------------------------------------------------------------------

    /**
     * Parse a Strong-format CSV into a [CsvImportPreview].
     *
     * Strong CSV columns (not all required):
     * `Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes`
     *
     * Rows are grouped by `Workout Name + Date` key; one [ExternalActivity] is created per group.
     */
    internal fun parseStrongCsv(
        content: String,
        weightUnit: WeightUnit,
        profileId: String,
        isPaidUser: Boolean
    ): CsvImportPreview {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyPreview(CsvFormat.STRONG, "CSV is empty")

        val headers = CsvParser.parseCsvRow(lines[0]).map { it.trim().lowercase() }
        val colIdx = buildStrongColumnMap(headers)

        if (!colIdx.containsKey("workout_name") || !colIdx.containsKey("date")) {
            return emptyPreview(CsvFormat.STRONG, "Missing required Strong columns: 'Workout Name' or 'Date'")
        }

        // Group rows by (workoutName + date) key to produce one activity per workout
        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        val errors = mutableListOf<String>()

        for (i in 1 until lines.size) {
            try {
                val fields = CsvParser.parseCsvRow(lines[i])
                val workoutName = fieldAt(fields, colIdx["workout_name"]).trim()
                val date = fieldAt(fields, colIdx["date"]).trim()
                if (workoutName.isBlank() || date.isBlank()) {
                    errors.add("Row ${i + 1}: missing workout name or date — skipped")
                    continue
                }
                val key = "$workoutName|$date"
                groups.getOrPut(key) { mutableListOf() }.add(fields)
            } catch (e: Exception) {
                errors.add("Row ${i + 1}: ${e.message ?: "parse error"}")
            }
        }

        val activities = mutableListOf<ExternalActivity>()

        for ((_, rows) in groups) {
            try {
                val firstRow = rows[0]
                val workoutName = fieldAt(firstRow, colIdx["workout_name"]).trim()
                val dateStr = fieldAt(firstRow, colIdx["date"]).trim()
                val durationStr = fieldAt(firstRow, colIdx["duration"]).trim()

                val startedAt = parseStrongDate(dateStr)
                val durationSec = parseStrongDuration(durationStr)

                val externalId = buildStrongExternalId(workoutName, startedAt)

                activities.add(
                    ExternalActivity(
                        id = generateUUID(),
                        externalId = externalId,
                        provider = IntegrationProvider.STRONG,
                        name = workoutName,
                        activityType = "strength",
                        startedAt = startedAt,
                        durationSeconds = durationSec,
                        syncedAt = currentTimeMillis(),
                        profileId = profileId,
                        needsSync = isPaidUser
                    )
                )
            } catch (e: Exception) {
                errors.add("Workout group parse error: ${e.message ?: "unknown"}")
            }
        }

        return buildPreview(CsvFormat.STRONG, activities, errors)
    }

    // -------------------------------------------------------------------------
    // Hevy CSV parser
    // -------------------------------------------------------------------------

    /**
     * Parse a Hevy-format CSV into a [CsvImportPreview].
     *
     * Hevy CSV columns (not all required):
     * `title,start_time,end_time,description,exercise_title,superset_id,notes,set_index,...`
     *
     * Rows are grouped by `title + start_time` key; one [ExternalActivity] is created per group.
     * Duration is derived from `end_time - start_time`.
     */
    internal fun parseHevyCsv(
        content: String,
        weightUnit: WeightUnit,
        profileId: String,
        isPaidUser: Boolean
    ): CsvImportPreview {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyPreview(CsvFormat.HEVY, "CSV is empty")

        val headers = CsvParser.parseCsvRow(lines[0]).map { it.trim().lowercase() }
        val colIdx = buildHevyColumnMap(headers)

        if (!colIdx.containsKey("title") || !colIdx.containsKey("start_time")) {
            return emptyPreview(CsvFormat.HEVY, "Missing required Hevy columns: 'title' or 'start_time'")
        }

        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        val errors = mutableListOf<String>()

        for (i in 1 until lines.size) {
            try {
                val fields = CsvParser.parseCsvRow(lines[i])
                val title = fieldAt(fields, colIdx["title"]).trim()
                val startTime = fieldAt(fields, colIdx["start_time"]).trim()
                if (title.isBlank() || startTime.isBlank()) {
                    errors.add("Row ${i + 1}: missing title or start_time — skipped")
                    continue
                }
                val key = "$title|$startTime"
                groups.getOrPut(key) { mutableListOf() }.add(fields)
            } catch (e: Exception) {
                errors.add("Row ${i + 1}: ${e.message ?: "parse error"}")
            }
        }

        val activities = mutableListOf<ExternalActivity>()

        for ((_, rows) in groups) {
            try {
                val firstRow = rows[0]
                val title = fieldAt(firstRow, colIdx["title"]).trim()
                val startTimeStr = fieldAt(firstRow, colIdx["start_time"]).trim()
                val endTimeStr = fieldAt(firstRow, colIdx["end_time"]).trim()

                val startedAt = parseHevyDate(startTimeStr)
                val durationSec = if (endTimeStr.isNotBlank()) {
                    val endMs = parseHevyDate(endTimeStr)
                    ((endMs - startedAt) / 1000L).toInt().coerceAtLeast(0)
                } else {
                    0
                }

                val externalId = buildHevyExternalId(title, startedAt)

                activities.add(
                    ExternalActivity(
                        id = generateUUID(),
                        externalId = externalId,
                        provider = IntegrationProvider.HEVY,
                        name = title,
                        activityType = "strength",
                        startedAt = startedAt,
                        durationSeconds = durationSec,
                        syncedAt = currentTimeMillis(),
                        profileId = profileId,
                        needsSync = isPaidUser
                    )
                )
            } catch (e: Exception) {
                errors.add("Workout group parse error: ${e.message ?: "unknown"}")
            }
        }

        return buildPreview(CsvFormat.HEVY, activities, errors)
    }

    // -------------------------------------------------------------------------
    // Column index builders
    // -------------------------------------------------------------------------

    private fun buildStrongColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((i, h) in headers.withIndex()) {
            val key = when (h) {
                "date" -> "date"
                "workout name" -> "workout_name"
                "duration" -> "duration"
                "exercise name" -> "exercise_name"
                "set order" -> "set_order"
                "weight" -> "weight"
                "reps" -> "reps"
                "workout notes" -> "workout_notes"
                "notes" -> "notes"
                else -> null
            }
            if (key != null) map[key] = i
        }
        return map
    }

    private fun buildHevyColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((i, h) in headers.withIndex()) {
            val key = when (h) {
                "title" -> "title"
                "start_time" -> "start_time"
                "end_time" -> "end_time"
                "description" -> "description"
                "exercise_title" -> "exercise_title"
                "set_index" -> "set_index"
                "weight_kg" -> "weight_kg"
                "reps" -> "reps"
                "notes" -> "notes"
                else -> null
            }
            if (key != null) map[key] = i
        }
        return map
    }

    // -------------------------------------------------------------------------
    // Date parsers
    // -------------------------------------------------------------------------

    /**
     * Parse a Strong date string: `"YYYY-MM-DD HH:MM:SS"` → epoch milliseconds.
     * Returns 0L on failure.
     *
     * **Timezone handling:** Strong CSV files do not carry timezone information.
     * Dates are interpreted as the device's local timezone via [TimeZone.currentSystemDefault].
     * This is consistent with [CsvExporter.formatTimestamp] which also writes local time.
     *
     * **Cross-device caveat:** Importing a file exported on a device in a different timezone
     * will shift timestamps by the timezone offset. Since [externalId] includes the epoch
     * timestamp, the same workout imported on two devices in different timezones will produce
     * different dedup keys and be treated as separate activities.
     */
    internal fun parseStrongDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return try {
            // Strong format: "2023-10-15 09:30:00"
            val normalized = dateStr.trim().replace(' ', 'T')
            val localDt = LocalDateTime.parse(normalized)
            localDt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Parse a Hevy date string to epoch milliseconds.
     *
     * Hevy exports two formats:
     * - ISO-8601 with timezone offset: `"2023-10-15T09:30:00+00:00"` or `"2023-10-15T09:30:00Z"`
     * - Local datetime (no offset): `"2023-10-15 09:30:00"` — treated as device local time
     *
     * Returns 0L on failure.
     */
    internal fun parseHevyDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val trimmed = dateStr.trim()
        return try {
            // Attempt ISO-8601 parse with timezone (handles 'Z' and '+HH:MM' offsets)
            Instant.parse(trimmed).toEpochMilliseconds()
        } catch (_: Exception) {
            try {
                // Fall back to local datetime (no timezone) — treat as device local time
                val normalized = trimmed.replace(' ', 'T')
                val localDt = LocalDateTime.parse(normalized)
                localDt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            } catch (_: Exception) {
                0L
            }
        }
    }

    // -------------------------------------------------------------------------
    // Duration parser (Strong "1h 23m" format)
    // -------------------------------------------------------------------------

    /**
     * Parse Strong's duration format: `"1h 23m"`, `"45m"`, `"0m"`, etc.
     * Returns 0 for blank or unrecognized input.
     */
    internal fun parseStrongDuration(durationStr: String): Int {
        if (durationStr.isBlank()) return 0
        val text = durationStr.trim()

        val hoursMatch = Regex("""(\d+)h""").find(text)
        val minutesMatch = Regex("""(\d+)m""").find(text)

        val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = minutesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        return (hours * 3600) + (minutes * 60)
    }

    // -------------------------------------------------------------------------
    // External ID builders
    // -------------------------------------------------------------------------

    /**
     * Build a deterministic external ID for a Strong workout.
     * Format: `strong-{workout_name_snake_case}-{epochSeconds}`
     */
    internal fun buildStrongExternalId(workoutName: String, startedAtMs: Long): String {
        val slug = workoutName.trim().lowercase().replace(Regex("""\s+"""), "_")
        val epochSec = startedAtMs / 1000L
        return "strong-$slug-$epochSec"
    }

    /**
     * Build a deterministic external ID for a Hevy workout.
     * Format: `hevy-{title_snake_case}-{epochSeconds}`
     */
    internal fun buildHevyExternalId(title: String, startedAtMs: Long): String {
        val slug = title.trim().lowercase().replace(Regex("""\s+"""), "_")
        val epochSec = startedAtMs / 1000L
        return "hevy-$slug-$epochSec"
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fieldAt(fields: List<String>, index: Int?): String {
        if (index == null || index < 0 || index >= fields.size) return ""
        return fields[index]
    }

    private fun emptyPreview(format: CsvFormat, error: String) = CsvImportPreview(
        format = format,
        activities = emptyList(),
        workoutCount = 0,
        dateRange = null,
        totalDurationSeconds = 0L,
        errors = listOf(error)
    )

    private fun buildPreview(
        format: CsvFormat,
        activities: List<ExternalActivity>,
        errors: List<String>
    ): CsvImportPreview {
        val dateRange = if (activities.isEmpty()) null else {
            val min = activities.minOf { it.startedAt }
            val max = activities.maxOf { it.startedAt }
            min to max
        }
        val totalDuration = activities.sumOf { it.durationSeconds.toLong() }
        return CsvImportPreview(
            format = format,
            activities = activities,
            workoutCount = activities.size,
            dateRange = dateRange,
            totalDurationSeconds = totalDuration,
            errors = errors
        )
    }
}
