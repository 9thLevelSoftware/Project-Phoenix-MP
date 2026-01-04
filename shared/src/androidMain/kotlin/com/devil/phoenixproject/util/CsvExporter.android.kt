package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.FileWriter

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
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "personal_records_$timestamp.csv"
            val file = File(exportDir, fileName)

            FileWriter(file).use { writer ->
                // Header
                writer.appendLine("Exercise,Weight,Reps,Date,Mode,1RM")

                // Data rows
                personalRecords.sortedByDescending { it.timestamp }.forEach { pr ->
                    val exerciseName = exerciseNames[pr.exerciseId] ?: "Unknown"
                    val weight = formatWeight(pr.weightPerCableKg, weightUnit)
                    val date = formatDate(pr.timestamp)
                    val oneRM = calculateOneRM(pr.weightPerCableKg, pr.reps)

                    writer.appendLine(
                        "${escapeCsv(exerciseName)},$weight,${pr.reps},$date,${pr.workoutMode},${String.format("%.1f", oneRM)}"
                    )
                }
            }

            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun exportWorkoutHistory(
        workoutSessions: List<WorkoutSession>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "workout_history_$timestamp.csv"
            val file = File(exportDir, fileName)

            FileWriter(file).use { writer ->
                // Header
                writer.appendLine("Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Duration (s),Just Lift,Eccentric Load")

                // Data rows
                workoutSessions.sortedByDescending { it.timestamp }.forEach { session ->
                    val exerciseName = session.exerciseName
                        ?: exerciseNames[session.exerciseId]
                        ?: "Unknown"
                    val date = formatDate(session.timestamp)
                    val weight = formatWeight(session.weightPerCableKg, weightUnit)
                    val justLift = if (session.isJustLift) "Yes" else "No"

                    writer.appendLine(
                        "$date,${escapeCsv(exerciseName)},${session.mode},${session.reps}," +
                        "${session.warmupReps},${session.workingReps},${session.totalReps}," +
                        "$weight,${session.duration},$justLift,${session.eccentricLoad}"
                    )
                }
            }

            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun exportPRProgression(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "pr_progression_$timestamp.csv"
            val file = File(exportDir, fileName)

            // Group by exercise, then sort by date
            val grouped = personalRecords.groupBy { it.exerciseId }
                .mapValues { entry -> entry.value.sortedBy { it.timestamp } }

            FileWriter(file).use { writer ->
                // Header
                writer.appendLine("Exercise,Date,Weight,Reps,Mode,1RM,Progress From Previous")

                grouped.forEach { (exerciseId, records) ->
                    val exerciseName = exerciseNames[exerciseId] ?: "Unknown"
                    var previousWeight: Float? = null

                    records.forEach { pr ->
                        val weight = formatWeight(pr.weightPerCableKg, weightUnit)
                        val date = formatDate(pr.timestamp)
                        val oneRM = calculateOneRM(pr.weightPerCableKg, pr.reps)
                        val progress = if (previousWeight != null) {
                            val diff = pr.weightPerCableKg - previousWeight!!
                            if (diff > 0) "+${formatWeight(diff, weightUnit)}" else formatWeight(diff, weightUnit)
                        } else {
                            "-"
                        }

                        writer.appendLine(
                            "${escapeCsv(exerciseName)},$date,$weight,${pr.reps},${pr.workoutMode},${String.format("%.1f", oneRM)},$progress"
                        )

                        previousWeight = pr.weightPerCableKg
                    }
                }
            }

            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Vitruvian Export: $fileName")
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

    private fun formatDate(timestamp: Long): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val monthNum = localDateTime.month.ordinal + 1
        return "${localDateTime.year}-${monthNum.toString().padStart(2, '0')}-${localDateTime.day.toString().padStart(2, '0')}"
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun calculateOneRM(weight: Float, reps: Int): Float {
        // Brzycki formula: 1RM = weight * (36 / (37 - reps))
        return if (reps >= 37) weight else weight * (36f / (37f - reps))
    }
}
