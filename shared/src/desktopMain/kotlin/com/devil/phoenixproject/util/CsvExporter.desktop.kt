package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.awt.Desktop
import java.io.File
import java.io.FileWriter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop implementation of CsvExporter.
 * Uses JVM file I/O and JFileChooser for save dialogs.
 */
class DesktopCsvExporter : CsvExporter {

    private val defaultExportDir: File
        get() {
            val userHome = System.getProperty("user.home")
            val dir = File(userHome, "Documents/VitruvianExports")
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
            val defaultName = "personal_records_${System.currentTimeMillis()}.csv"
            val file = showSaveDialog(defaultName) ?: return Result.failure(
                Exception("Export cancelled by user")
            )

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
            val defaultName = "workout_history_${System.currentTimeMillis()}.csv"
            val file = showSaveDialog(defaultName) ?: return Result.failure(
                Exception("Export cancelled by user")
            )

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
            val defaultName = "pr_progression_${System.currentTimeMillis()}.csv"
            val file = showSaveDialog(defaultName) ?: return Result.failure(
                Exception("Export cancelled by user")
            )

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
            if (!file.exists()) return

            // On desktop, "sharing" means opening the containing folder
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.parentFile)
            }
        } catch (e: Exception) {
            // Log error
        }
    }

    private fun showSaveDialog(defaultFileName: String): File? {
        val fileChooser = JFileChooser(defaultExportDir).apply {
            dialogTitle = "Export CSV"
            selectedFile = File(defaultExportDir, defaultFileName)
            fileFilter = FileNameExtensionFilter("CSV Files (*.csv)", "csv")
        }

        return if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = fileChooser.selectedFile
            // Ensure .csv extension
            if (!file.name.endsWith(".csv", ignoreCase = true)) {
                file = File(file.absolutePath + ".csv")
            }
            file
        } else {
            null
        }
    }

    private fun formatDate(timestamp: Long): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.year}-${localDateTime.monthNumber.toString().padStart(2, '0')}-${localDateTime.dayOfMonth.toString().padStart(2, '0')}"
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
