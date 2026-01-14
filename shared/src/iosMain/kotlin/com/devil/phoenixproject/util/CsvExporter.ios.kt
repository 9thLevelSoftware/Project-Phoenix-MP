package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.darwin.NSObject
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation of CsvExporter.
 * Uses Foundation APIs for file I/O and UIActivityViewController for sharing.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCsvExporter : CsvExporter {

    private val fileManager = NSFileManager.defaultManager

    /**
     * Calculate estimated 1RM using Brzycki formula
     */
    private fun calculateOneRM(weight: Float, reps: Int): Float {
        return if (reps <= 1) weight else weight * (36f / (37f - reps))
    }

    override fun exportPersonalRecords(
        personalRecords: List<PersonalRecord>,
        exerciseNames: Map<String, String>,
        weightUnit: WeightUnit,
        formatWeight: (Float, WeightUnit) -> String
    ): Result<String> {
        return try {
            val csv = buildString {
                appendLine("Exercise,Weight (${weightUnit.name}),Reps,1RM,Date")
                personalRecords.forEach { pr ->
                    val exerciseName = exerciseNames[pr.exerciseId] ?: pr.exerciseId
                    val formattedWeight = formatWeight(pr.weightPerCableKg, weightUnit)
                    val oneRM = calculateOneRM(pr.weightPerCableKg, pr.reps)
                    val formattedOneRM = formatWeight(oneRM, weightUnit)
                    val date = KmpUtils.formatTimestamp(pr.timestamp, "yyyy-MM-dd")
                    appendLine("\"$exerciseName\",$formattedWeight,${pr.reps},$formattedOneRM,$date")
                }
            }

            val filePath = writeToTempFile("personal_records.csv", csv)
            Result.success(filePath)
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
            val csv = buildString {
                appendLine("Date,Time,Exercise,Mode,Weight (${weightUnit.name}),Reps,Duration (s)")
                workoutSessions.forEach { session ->
                    val exerciseName = exerciseNames[session.exerciseId] ?: session.exerciseId ?: "Unknown"
                    val date = KmpUtils.formatTimestamp(session.timestamp, "yyyy-MM-dd")
                    val time = KmpUtils.formatTimestamp(session.timestamp, "HH:mm")
                    // For Echo mode, use peak weight (matches official app behavior); otherwise use configured weight
                    val isEchoMode = session.mode.contains("Echo", ignoreCase = true)
                    val effectiveWeight = if (isEchoMode) {
                        session.peakWeightKg ?: session.workingAvgWeightKg ?: session.weightPerCableKg
                    } else {
                        session.weightPerCableKg
                    }
                    val formattedWeight = formatWeight(effectiveWeight, weightUnit)
                    val durationSeconds = session.duration / 1000
                    appendLine("$date,$time,\"$exerciseName\",${session.mode},$formattedWeight,${session.reps},$durationSeconds")
                }
            }

            val filePath = writeToTempFile("workout_history.csv", csv)
            Result.success(filePath)
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
            // Group PRs by exercise and sort by date
            val grouped = personalRecords.groupBy { it.exerciseId }

            val csv = buildString {
                appendLine("Exercise,Date,Weight (${weightUnit.name}),Reps,1RM,Improvement")

                grouped.forEach { (exerciseId, prs) ->
                    val exerciseName = exerciseNames[exerciseId] ?: exerciseId
                    val sortedPrs = prs.sortedBy { it.timestamp }
                    var previousOneRM = 0f

                    sortedPrs.forEach { pr ->
                        val date = KmpUtils.formatTimestamp(pr.timestamp, "yyyy-MM-dd")
                        val formattedWeight = formatWeight(pr.weightPerCableKg, weightUnit)
                        val oneRM = calculateOneRM(pr.weightPerCableKg, pr.reps)
                        val formattedOneRM = formatWeight(oneRM, weightUnit)
                        val improvement = if (previousOneRM > 0) {
                            val diff = oneRM - previousOneRM
                            val diffFormatted = formatWeight(diff, weightUnit)
                            if (diff > 0) "+$diffFormatted" else diffFormatted
                        } else {
                            "-"
                        }
                        previousOneRM = oneRM

                        appendLine("\"$exerciseName\",$date,$formattedWeight,${pr.reps},$formattedOneRM,$improvement")
                    }
                }
            }

            val filePath = writeToTempFile("pr_progression.csv", csv)
            Result.success(filePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun shareCSV(fileUri: String, fileName: String) {
        val url = NSURL.fileURLWithPath(fileUri)

        // Dispatch to main thread - UIKit requires all UI operations on main thread
        dispatch_async(dispatch_get_main_queue()) {
            // Get the key window's root view controller
            val scenes = UIApplication.sharedApplication.connectedScenes
            val windowScene = scenes.firstOrNull {
                it is platform.UIKit.UIWindowScene
            } as? platform.UIKit.UIWindowScene

            val rootViewController = windowScene?.keyWindow?.rootViewController
                ?: return@dispatch_async

            val activityVC = UIActivityViewController(
                activityItems = listOf(url),
                applicationActivities = null
            )

            // Configure popover for iPad - required to prevent crash
            // Access popoverPresentationController via ObjC KVC since K/N bindings don't expose it directly
            if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
                activityVC.valueForKey("popoverPresentationController")?.let { popover ->
                    (popover as? NSObject)?.setValue(rootViewController.view, forKey = "sourceView")
                }
            }

            rootViewController.presentViewController(
                activityVC,
                animated = true,
                completion = null
            )
        }
    }

    /**
     * Write CSV content to a temporary file and return the path.
     */
    private fun writeToTempFile(fileName: String, content: String): String {
        val tempDir = NSTemporaryDirectory()
        val filePath = "$tempDir$fileName"

        // Remove existing file if present
        if (fileManager.fileExistsAtPath(filePath)) {
            fileManager.removeItemAtPath(filePath, null)
        }

        // Write content using NSString
        val nsContent = NSString.create(string = content)
        nsContent.writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )

        return filePath
    }
}
