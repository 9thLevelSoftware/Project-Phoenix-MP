package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Low-level CSV helpers plus the history import entry point.
 *
 * Workout-history and personal-record bytes are produced and parsed by [PhoenixCsvCodec].
 * [parseWorkoutHistory] delegates there so existing importers keep this call site.
 * [parseCsvRow] and [parseWeight] stay here because Strong/Hevy import and routine CSV use them.
 */
object CsvParser {

    /**
     * Parse a Phoenix workout-history CSV into [WorkoutSession] rows.
     *
     * `Duration (s)` is whole seconds and is stored as milliseconds.
     * See [PhoenixCsvCodec] for the header and the legacy iOS column layout.
     */
    fun parseWorkoutHistory(csvContent: String): Pair<List<WorkoutSession>, List<String>> = PhoenixCsvCodec.parseWorkoutHistory(csvContent)

    /**
     * Parse a weight string that may contain unit suffixes or +/- prefix.
     * Examples: "80.0", "80.0 kg", "+2.5 kg", "-1.0", "176.4 lb"
     */
    internal fun parseWeight(value: String?): Float {
        if (value.isNullOrBlank() || value == "0") return 0f
        // Detect the unit BEFORE stripping suffixes. Exporters format weights in
        // the user's display unit, so a pounds export ("176.4 lbs") must be
        // converted back to kg (the stored unit) instead of being stored verbatim
        // as kilograms (audit F067).
        val isPounds = value.contains("lb", ignoreCase = true)
        // Strip everything except digits, dot, minus, plus
        val numeric = value.replace(Regex("[^\\d.\\-+]"), "").trim()
        val parsed = numeric.toFloatOrNull() ?: 0f
        return if (isPounds) UnitConverter.lbToKg(parsed) else parsed
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
