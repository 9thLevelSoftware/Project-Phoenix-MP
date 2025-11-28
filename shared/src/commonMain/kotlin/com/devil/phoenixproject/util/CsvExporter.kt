package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Interface for CSV export functionality.
 * Platform-specific implementations handle file I/O and sharing.
 */
interface CsvExporter {
    /**
     * Export personal records to CSV file
     *
     * @param personalRecords List of personal records to export
     * @param exerciseNames Map of exercise IDs to display names
     * @param weightUnit Unit to use for weight values
     * @param formatWeight Function to format weight values
     * @return Result containing URI/path to the exported file or error
     */
    fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String>

    /**
     * Export workout history to CSV file
     *
     * @param workoutSessions List of workout sessions to export
     * @param exerciseNames Map of exercise IDs to display names
     * @param weightUnit Unit to use for weight values
     * @param formatWeight Function to format weight values
     * @return Result containing URI/path to the exported file or error
     */
    fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String>

    /**
     * Export all PRs grouped by exercise with progression data
     *
     * @param personalRecords List of personal records to export
     * @param exerciseNames Map of exercise IDs to display names
     * @param weightUnit Unit to use for weight values
     * @param formatWeight Function to format weight values
     * @return Result containing URI/path to the exported file or error
     */
    fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String>

    /**
     * Share CSV file using platform-specific sharing mechanism
     *
     * @param fileUri URI or path to the CSV file
     * @param fileName Display name for the file
     */
    fun shareCSV(fileUri: String, fileName: String)
}

/**
 * Stub implementation for platforms without export support
 */
class StubCsvExporter : CsvExporter {
    override fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> = Result.failure(NotImplementedError("CSV export not available on this platform"))

    override fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> = Result.failure(NotImplementedError("CSV export not available on this platform"))

    override fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> = Result.failure(NotImplementedError("CSV export not available on this platform"))

    override fun shareCSV(fileUri: String, fileName: String) {
        // No-op
    }
}
