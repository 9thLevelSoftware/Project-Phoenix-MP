package com.devil.phoenixproject.util

import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import platform.Foundation.*
import platform.darwin.NSObject
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIWindowScene
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation of DataBackupManager.
 * Uses NSFileManager for file operations and Documents directory for storage.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDataBackupManager(
    database: VitruvianDatabase
) : BaseDataBackupManager(database) {

    private val fileManager = NSFileManager.defaultManager

    private val documentsDirectory: String
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true
            )
            return paths.firstOrNull() as? String ?: ""
        }

    private val backupDirectory: String
        get() {
            val dir = "$documentsDirectory/VitruvianBackups"
            val url = NSURL.fileURLWithPath(dir)
            if (!fileManager.fileExistsAtPath(dir)) {
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }
            return dir
        }

    override suspend fun saveToFile(backup: BackupData): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = json.encodeToString(backup)
            val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
                .replace("-", "") + "_" +
                KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                    .replace(":", "")
            val fileName = "vitruvian_backup_$timestamp.json"
            val filePath = "$backupDirectory/$fileName"

            val data = (jsonString as NSString).dataUsingEncoding(NSUTF8StringEncoding)
                ?: throw Exception("Failed to encode backup data")

            val success = data.writeToFile(filePath, atomically = true)
            if (!success) {
                throw Exception("Failed to write backup file")
            }

            Result.success(filePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun importFromFile(filePath: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val data = NSData.dataWithContentsOfFile(filePath)
                ?: throw Exception("Cannot read file")

            val jsonString = NSString.create(data, NSUTF8StringEncoding) as? String
                ?: throw Exception("Cannot decode file contents")

            importFromJson(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get list of available backup files
     */
    fun getAvailableBackups(): List<String> {
        return try {
            val contents = fileManager.contentsOfDirectoryAtPath(backupDirectory, null)
            (contents as? List<*>)
                ?.filterIsInstance<String>()
                ?.filter { it.endsWith(".json") }
                ?.map { "$backupDirectory/$it" }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Share backup via iOS share sheet (UIActivityViewController)
     */
    override suspend fun shareBackup() {
        val backup = exportAllData()
        val jsonString = json.encodeToString(backup)
        val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
            .replace("-", "") + "_" +
            KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                .replace(":", "")
        val fileName = "vitruvian_backup_$timestamp.json"

        // Save to temp file
        val tempDir = NSTemporaryDirectory()
        val filePath = "$tempDir$fileName"

        val nsContent = (jsonString as NSString)
        val success = nsContent.writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )

        if (!success) return

        val fileURL = NSURL.fileURLWithPath(filePath)

        // Present share sheet on main thread
        dispatch_async(dispatch_get_main_queue()) {
            val scenes = UIApplication.sharedApplication.connectedScenes
            val windowScene = scenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
            val rootViewController = windowScene?.keyWindow?.rootViewController ?: return@dispatch_async

            val activityVC = UIActivityViewController(
                activityItems = listOf(fileURL),
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
}
