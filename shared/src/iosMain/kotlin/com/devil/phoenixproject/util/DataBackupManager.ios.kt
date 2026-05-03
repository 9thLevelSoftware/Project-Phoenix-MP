package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setValue
import platform.Foundation.valueForKey
import platform.Foundation.writeToFile
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
 *
 * When the user has selected a custom backup destination via [PreferencesManager],
 * exports are routed through [BackupDestinationResolver]. If the custom destination
 * is inaccessible or write fails, the manager falls back to the default location
 * to guarantee data is never lost.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDataBackupManager(
    database: VitruvianDatabase,
    private val preferencesManager: PreferencesManager,
    private val destinationResolver: BackupDestinationResolver,
) : BaseDataBackupManager(database) {

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

    /**
     * Override to route session backups to a custom destination when configured.
     * Falls back to default location if the custom destination is inaccessible.
     */
    override fun writeSessionBackupFile(filePath: String, content: String) {
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            try {
                val fileName = filePath.substringAfterLast('/')
                // Write content to a temp file, then copy to custom destination
                val tempPath = "${NSTemporaryDirectory()}session_backup_temp.json"
                val data = NSString.create(string = content).dataUsingEncoding(NSUTF8StringEncoding)
                    ?: throw Exception("Failed to encode session backup content")
                val written = data.writeToFile(tempPath, atomically = true)
                if (!written) throw Exception("Failed to write temp session backup file")

                // Try to resolve and write to custom destination
                // Note: This is called from a coroutine context in exportSession,
                // so we can use the bookmark resolution synchronously
                val base64 = destination.bookmarkData
                if (!base64.isNullOrBlank()) {
                    val bookmarkData = NSData.create(base64EncodedString = base64, options = 0u)
                    if (bookmarkData != null) {
                        @Suppress("UNCHECKED_CAST")
                        val url = NSURL.URLByResolvingBookmarkData(
                            bookmarkData = bookmarkData,
                            options = platform.Foundation.NSURLBookmarkResolutionWithoutUI,
                            relativeToURL = null,
                            bookmarkDataIsStale = null,
                            error = null,
                        )
                        if (url != null) {
                            val accessing = url.startAccessingSecurityScopedResource()
                            try {
                                val dirPath = url.path
                                if (dirPath != null) {
                                    val destPath = "$dirPath/$fileName"
                                    if (fileManager.fileExistsAtPath(destPath)) {
                                        fileManager.removeItemAtPath(destPath, error = null)
                                    }
                                    val success = data.writeToFile(destPath, atomically = true)
                                    if (success) {
                                        fileManager.removeItemAtPath(tempPath, error = null)
                                        Logger.d { "Session backup written to custom destination: ${destination.displayName}" }
                                        return
                                    }
                                }
                            } finally {
                                if (accessing) url.stopAccessingSecurityScopedResource()
                            }
                        }
                    }
                }
                fileManager.removeItemAtPath(tempPath, error = null)
                Logger.w { "Custom backup destination not accessible, falling back to default" }
            } catch (e: Exception) {
                Logger.w(e) { "Falling back to default backup location after custom destination error" }
            }
        }

        // Default: write to session backup directory using base class
        super.writeSessionBackupFile(filePath, content)
    }

    /**
     * Attempt to write the temp file to a custom backup destination.
     * Returns the successful [Result] or null if the custom destination
     * is inaccessible and the caller should fall back to default.
     */
    private suspend fun tryCustomDestination(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String>? {
        return try {
            if (!destinationResolver.isAccessible(destination)) {
                Logger.w { "Custom backup destination '${destination.displayName}' is not accessible, falling back to default" }
                return null
            }
            val result = destinationResolver.writeFile(destination, fileName, tempFilePath)
            if (result.isSuccess) {
                result
            } else {
                Logger.w(result.exceptionOrNull()) { "Falling back to default backup location after custom destination write failure" }
                null
            }
        } catch (e: Exception) {
            Logger.w(e) { "Falling back to default backup location after custom destination error" }
            null
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

    override suspend fun finalizeExport(tempFilePath: String): Result<String> {
        val fileName = tempFilePath.substringAfterLast('/')

        // Check for custom backup destination
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            val customResult = tryCustomDestination(destination, fileName, tempFilePath)
            if (customResult != null) {
                // Clean up temp file after successful custom write
                fileManager.removeItemAtPath(tempFilePath, error = null)
                return customResult
            }
            // Custom destination failed — fall through to default
        }

        return try {
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
            val attrs = fileManager.attributesOfItemAtPath(filePath, error = null)
            val fileSize = (attrs?.get(NSFileSize) as? NSNumber)?.longValue

            if (fileSize != null && fileSize < STREAMING_IMPORT_THRESHOLD) {
                // Small file: use proven non-streaming path
                val data = NSData.dataWithContentsOfFile(filePath)
                    ?: throw Exception("Cannot read file")
                val jsonString = NSString.create(data, NSUTF8StringEncoding)?.toString()
                    ?: throw Exception("Cannot decode file contents")
                importFromJson(jsonString)
            } else {
                // Large file or unknown size: streaming import to avoid OOM
                Logger.i { "Using streaming import for file (size=${fileSize ?: "unknown"} bytes)" }
                val source = FileBackupStreamSource(filePath)
                try {
                    source.open()
                    importFromStream(source)
                } finally {
                    source.close()
                }
            }
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
