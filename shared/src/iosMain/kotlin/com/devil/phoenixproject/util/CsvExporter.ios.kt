package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/**
 * iOS implementation of CsvExporter.
 * Uses Foundation APIs for file I/O and [presentShareSheet] for sharing.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCsvExporter : CsvExporter {

    override fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("personal_records.csv") {
        PhoenixCsvCodec.encodePersonalRecords(personalRecords, exerciseNames, weightUnit, formatWeight)
    }

    override fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("workout_history.csv") {
        PhoenixCsvCodec.encodeWorkoutHistory(workoutSessions, exerciseNames, weightUnit, formatWeight)
    }

    override fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("pr_progression.csv") {
        PhoenixCsvCodec.encodePrProgression(personalRecords, exerciseNames, weightUnit, formatWeight)
    }

    override fun shareCSV(fileUri: String, fileName: String) {
        presentShareSheet(
            items = listOf(NSURL.fileURLWithPath(fileUri)),
            onShown = {},
        )
    }

    private fun writeCsv(fileName: String, csv: () -> String): Result<String> = try {
        Result.success(writeToTempFile(fileName, csv()))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Write CSV content to a temporary file and return the path.
     */
    private fun writeToTempFile(fileName: String, content: String): String {
        val filePath = writeUtf8TempFile(fileName, content)
        if (filePath == null) {
            throw IllegalStateException("Failed to write CSV to ${NSTemporaryDirectory()}$fileName")
        }
        return filePath
    }
}
