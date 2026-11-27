package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
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
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

actual val autoBackupLocationNote: String? = null

// Matches getSessionBackupDirectory(): <app Documents>/PhoenixBackups.
actual val defaultBackupLocationLabel: String = "Documents/PhoenixBackups"

actual val canOpenBackupFolder: Boolean = true

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
internal class IosDataBackupManager(
    database: PhoenixDatabase,
    private val preferencesManager: PreferencesManager,
    private val destinationResolver: BackupDestinationResolver,
    profilePreferencesRepository: ProfilePreferencesRepository,
    userProfileRepository: UserProfileRepository,
    portalTokenStorage: PortalTokenStorage,
    pendingProfileDeletionStore: PendingProfileDeletionStore,
    private val folderOpener: IosFolderOpener = IosFolderOpener(),
    private val uiScope: CoroutineScope = MainScope(),
) : BaseDataBackupManager(
    database,
    profilePreferencesRepository,
    userProfileRepository,
    portalTokenStorage,
    preferencesManager,
    pendingProfileDeletionStore,
) {

    override val includeRawTelemetryInBackups: Boolean
        get() = preferencesManager.preferencesFlow.value.includeRawTelemetryInBackups

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

    override fun pruneOldBackups(keepCount: Int) {
        val dir = getSessionBackupDirectory()
        val contents = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return
        // Sort by file modification date so prune always deletes the genuinely oldest
        // backups first. Filenames have two different prefixes (`phoenix-routine-` and
        // `phoenix-workout-`), which means lexicographic sort no longer matches
        // chronological order — sorting on filename would silently delete recent
        // routine backups while keeping ancient workout backups.
        val backupFiles = contents
            .mapNotNull { it as? String }
            .filter { it.startsWith("phoenix-") && it.endsWith(".json") }
            .map { fileName ->
                val filePath = "$dir/$fileName"
                val attrs = fileManager.attributesOfItemAtPath(filePath, error = null)
                val mtime = attrs?.get(NSFileModificationDate) as? NSDate
                // Fall back to epoch so files with missing mtime are considered oldest
                // and pruned first (defensive; should never happen for files we wrote).
                fileName to (mtime?.timeIntervalSince1970 ?: 0.0)
            }
            .sortedBy { it.second }
            .map { it.first }
        val excess = backupFiles.size - keepCount
        if (excess > 0) {
            backupFiles.take(excess).forEach { fileName ->
                fileManager.removeItemAtPath("$dir/$fileName", error = null)
            }
        }
    }

    /**
     * Route session backups through [tryCustomDestination], the same resolver path
     * full export uses. That resolves the security-scoped bookmark and refreshes
     * it when stale. Falls back to the default location if the custom destination
     * is inaccessible.
     */
    override suspend fun writeSessionBackupFile(filePath: String, content: String) {
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            val tempPath = "${NSTemporaryDirectory()}session_backup_temp.json"
            try {
                val fileName = filePath.substringAfterLast('/')
                val data = NSString.create(string = content).dataUsingEncoding(NSUTF8StringEncoding)
                    ?: throw Exception("Failed to encode session backup content")
                val written = data.writeToFile(tempPath, atomically = true)
                if (!written) throw Exception("Failed to write temp session backup file")

                val customResult = tryCustomDestination(destination, fileName, tempPath)
                if (customResult != null) {
                    Logger.d { "Session backup written to custom destination: ${destination.displayName}" }
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Falling back to default backup location after custom destination error" }
            } finally {
                fileManager.removeItemAtPath(tempPath, error = null)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Falling back to default backup location after custom destination error" }
            null
        }
    }

    /**
     * Open the active backup destination folder in the Files app (issue #1423).
     *
     * The handoff is a `shareddocuments://` URL built via [filesHandoffUrl]
     * (percent-encoded) and opened with the 3-argument `UIApplication.openURL`.
     * Fixed fallback: a custom destination that cannot be resolved or handed
     * off retries with the default folder, and when no handoff is accepted a
     * folder document-picker browse of the target is presented instead.
     * A share sheet is never used: iOS cannot share folders, so the old
     * `presentShareSheet(dirURL)` always failed.
     *
     * Stays non-suspend for the interface; bookmark resolution runs on the
     * manager-owned injectable main scope ([uiScope]), never `runBlocking`.
     */
    override fun openBackupFolder() {
        val defaultDir = getSessionBackupDirectory()
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            uiScope.launch {
                val targetPath = resolveCustomFolderPath(destination) ?: defaultDir
                handoffOrBrowse(targetPath, defaultDir)
            }
        } else {
            handoffOrBrowse(defaultDir, defaultDir)
        }
    }

    /**
     * Resolve the custom destination folder for the handoff, treating an
     * unresolvable bookmark like an inaccessible destination: null so the
     * caller falls back to the default folder.
     */
    private suspend fun resolveCustomFolderPath(destination: BackupDestination.Custom): String? =
        try {
            destinationResolver.resolveFolderPath(destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Could not resolve the custom backup folder; falling back to default" }
            null
        }

    /**
     * Files handoff for [targetPath], then for the default folder, then a
     * document-picker browse of [targetPath]. Never a share sheet.
     */
    private fun handoffOrBrowse(targetPath: String, defaultDir: String) {
        handoff(targetPath) { accepted ->
            if (accepted) return@handoff
            if (targetPath == defaultDir) {
                folderOpener.presentFolderBrowse(NSURL.fileURLWithPath(targetPath))
            } else {
                handoff(defaultDir) { defaultAccepted ->
                    if (!defaultAccepted) {
                        folderOpener.presentFolderBrowse(NSURL.fileURLWithPath(targetPath))
                    }
                }
            }
        }
    }

    /**
     * Attempt the `shareddocuments://` handoff for [folderPath].
     * [onResult] receives true only when the system accepted the handoff
     * request — not that the folder became visible in the Files app.
     */
    private fun handoff(folderPath: String, onResult: (Boolean) -> Unit) {
        val url = filesHandoffUrl(folderPath)
        if (url == null) {
            Logger.w { "Cannot build a Files handoff URL for $folderPath" }
            onResult(false)
        } else {
            folderOpener.openFolderInFiles(url, onResult)
        }
    }

    override fun createBackupWriter(): BackupJsonWriter {
        val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
            .replace("-", "") + "_" +
            KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                .replace(":", "")
        val fileName = "phoenix_backup_$timestamp.json"
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
            val destPath = "${getSessionBackupDirectory()}/$fileName"

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

    override suspend fun importFromFile(filePath: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val source = FileBackupStreamSource(filePath)
            try {
                source.open()
                importFromStream(source)
            } finally {
                source.close()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Share backup via iOS share sheet (streaming path)
     */
    override suspend fun shareBackup() {
        val cachePath = withContext(Dispatchers.IO) { exportToCache() }
        presentShareSheet(
            items = listOf(NSURL.fileURLWithPath(cachePath)),
            onShown = {},
        )
    }
}
