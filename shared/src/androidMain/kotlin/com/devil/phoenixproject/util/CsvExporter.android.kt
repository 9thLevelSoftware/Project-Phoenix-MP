package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import java.io.File

/**
 * Android implementation of CsvExporter.
 * Uses internal storage for file creation and FileProvider for sharing.
 */
class AndroidCsvExporter(private val context: Context) : CsvExporter {

    private val exportDir: File
        get() {
            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    override fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("personal_records_${System.currentTimeMillis()}.csv") {
        PhoenixCsvCodec.encodePersonalRecords(personalRecords, exerciseNames, weightUnit, formatWeight)
    }

    override fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("workout_history_${System.currentTimeMillis()}.csv") {
        PhoenixCsvCodec.encodeWorkoutHistory(workoutSessions, exerciseNames, weightUnit, formatWeight)
    }

    override fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String,
    ): Result<String> = writeCsv("pr_progression_${System.currentTimeMillis()}.csv") {
        PhoenixCsvCodec.encodePrProgression(personalRecords, exerciseNames, weightUnit, formatWeight)
    }

    override fun shareCSV(fileUri: String, fileName: String) {
        try {
            val file = File(fileUri)
            if (!file.exists()) {
                android.util.Log.e("CsvExporter", "File does not exist: $fileUri")
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix Export: $fileName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share CSV").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("CsvExporter", "Failed to share CSV: ${e.message}", e)
        }
    }

    private fun writeCsv(fileName: String, csv: () -> String): Result<String> = try {
        val file = File(exportDir, fileName)
        file.writeText(csv(), Charsets.UTF_8)
        Result.success(file.absolutePath)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
