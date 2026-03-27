package com.devil.phoenixproject.util

import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation of DataBackupManager.
 * Uses NSFileManager for file operations and Documents directory for storage.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDataBackupManager(database: VitruvianDatabase) : BaseDataBackupManager(database) {

    private val fileManager = NSFileManager.defaultManager

    private val documentsDirectory: String
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            )
            return paths.firstOrNull() as? String ?: ""
        }

    private val backupDirectory: String
        get() {
            val dir = "$documentsDirectory/PhoenixBackups"
            val url = NSURL.fileURLWithPath(dir)
            if (!fileManager.fileExistsAtPath(dir)) {
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
            return dir
        }

    override fun getSessionBackupDirectory(): String {
        val dir = "$documentsDirectory/PhoenixBackups"
        if (!fileManager.fileExistsAtPath(dir)) {
            val url = NSURL.fileURLWithPath(dir)
            fileManager.createDirectoryAtURL(
                url,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        return dir
    }

    override fun listBackupFileSizes(): List<Long> {
        val dir = getSessionBackupDirectory()
        val contents = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return emptyList()
        val sizes = mutableListOf<Long>()
        for (item in contents) {
            val fileName = item as? String ?: continue
            if (!fileName.endsWith(".json")) continue
            val filePath = "$dir/$fileName"
            val attrs = fileManager.attributesOfItemAtPath(filePath, error = null) ?: continue
            val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
            sizes.add(size)
        }
        return sizes
    }

    /**
     * iOS does not support opening arbitrary folders in Files.app programmatically.
     * Present a share sheet for the backup directory so the user can interact with it
     * via any installed file manager or sharing target.
     */
    override fun pruneOldBackups(keepCount: Int) {
        val dir = getSessionBackupDirectory()
        val contents = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return
        val backupFiles = contents
            .mapNotNull { it as? String }
            .filter { it.startsWith("phoenix-workout-") && it.endsWith(".json") }
            .sorted() // Filename starts with date, so lexicographic sort = chronological
        val excess = backupFiles.size - keepCount
        if (excess > 0) {
            backupFiles.take(excess).forEach { fileName ->
                fileManager.removeItemAtPath("$dir/$fileName", error = null)
            }
        }
    }

    override fun openBackupFolder() {
        val dir = getSessionBackupDirectory()
        val fileURL = NSURL.fileURLWithPath(dir)

        dispatch_async(dispatch_get_main_queue()) {
            val scenes = UIApplication.sharedApplication.connectedScenes
            val windowScene = scenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
            val rootViewController = windowScene?.keyWindow?.rootViewController ?: return@dispatch_async

            val activityVC = UIActivityViewController(
                activityItems = listOf(fileURL),
                applicationActivities = null,
            )

            if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
                activityVC.valueForKey("popoverPresentationController")?.let { popover ->
                    (popover as? NSObject)?.setValue(rootViewController.view, forKey = "sourceView")
                }
            }

            rootViewController.presentViewController(
                activityVC,
                animated = true,
                completion = null,
            )
        }
    }

    override fun createBackupWriter(): BackupJsonWriter {
        val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
            .replace("-", "") + "_" +
            KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                .replace(":", "")
        val fileName = "vitruvian_backup_$timestamp.json"
        val tempDir = NSTemporaryDirectory()
        return BackupJsonWriter("$tempDir$fileName")
    }

    override suspend fun finalizeExport(tempFilePath: String): Result<String> = try {
        val fileName = tempFilePath.substringAfterLast('/')
        val destPath = "$backupDirectory/$fileName"

        // Remove existing file if present
        if (fileManager.fileExistsAtPath(destPath)) {
            fileManager.removeItemAtPath(destPath, error = null)
        }

        val success = fileManager.moveItemAtPath(tempFilePath, toPath = destPath, error = null)
        if (!success) throw Exception("Failed to move backup to Documents")

        Result.success(destPath)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // Legacy save path (kept for backward compatibility)
    override suspend fun saveToFile(backup: BackupData): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = json.encodeToString(backup)
            val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
                .replace("-", "") + "_" +
                KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                    .replace(":", "")
            val fileName = "vitruvian_backup_$timestamp.json"
            val filePath = "$backupDirectory/$fileName"

            val data = NSString.create(string = jsonString).dataUsingEncoding(NSUTF8StringEncoding)
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

            val jsonString = NSString.create(data, NSUTF8StringEncoding)?.toString()
                ?: throw Exception("Cannot decode file contents")

            importFromJson(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Share backup via iOS share sheet (streaming path)
     */
    override suspend fun shareBackup() {
        val cachePath = withContext(Dispatchers.IO) { exportToCache() }
        val fileURL = NSURL.fileURLWithPath(cachePath)

        // Present share sheet on main thread
        dispatch_async(dispatch_get_main_queue()) {
            val scenes = UIApplication.sharedApplication.connectedScenes
            val windowScene = scenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
            val rootViewController = windowScene?.keyWindow?.rootViewController ?: return@dispatch_async

            val activityVC = UIActivityViewController(
                activityItems = listOf(fileURL),
                applicationActivities = null,
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
                completion = null,
            )
        }
    }
}
