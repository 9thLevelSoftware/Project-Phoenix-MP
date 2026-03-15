package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Result of a CSV import operation.
 *
 * @param imported Number of sessions successfully imported
 * @param skipped Number of sessions skipped (duplicate session IDs)
 * @param failed Number of rows that could not be parsed
 * @param errors Human-readable error descriptions for failed rows
 */
data class CsvImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
) {
    /** True if at least one record was successfully imported */
    val hasImports: Boolean get() = imported > 0

    /** Total rows processed (regardless of outcome) */
    val totalProcessed: Int get() = imported + skipped + failed

    /** Human-readable summary for display in UI */
    fun summary(): String = buildString {
        append("$imported imported")
        if (skipped > 0) append(", $skipped skipped (duplicates)")
        if (failed > 0) append(", $failed failed")
    }
}

/**
 * Interface for importing workout history from CSV files.
 *
 * Reads CSVs produced by [CsvExporter.exportWorkoutHistory] and inserts
 * the parsed [WorkoutSession] records into the local database.
 *
 * Platform implementations handle file I/O (ContentResolver on Android,
 * Foundation on iOS).
 */
interface CsvImporter {
    /**
     * Import workout sessions from a CSV file at the given URI/path.
     *
     * The CSV must contain the header row produced by [CsvExporter.exportWorkoutHistory]:
     * `Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load`
     *
     * Conflict strategy: rows whose parsed session ID (or matching timestamp+exercise)
     * already exist in the database are **skipped** -- existing data is never overwritten.
     *
     * @param uri Platform-specific file identifier (content:// URI on Android, file path on iOS)
     * @return [CsvImportResult] with counts and any error details
     */
    suspend fun importFromCsv(uri: String): CsvImportResult
}
