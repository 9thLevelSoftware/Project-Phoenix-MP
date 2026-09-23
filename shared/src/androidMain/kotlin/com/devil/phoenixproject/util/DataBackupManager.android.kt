package com.devil.phoenixproject.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Directory for per-session auto-backups written through the file API.
 *
 * - Android 10+ (Q): a cache staging dir; the real write goes to MediaStore Downloads.
 * - Android 9 and older: the app-specific external Documents dir
 *   (`Android/data/<package>/files/Documents/PhoenixBackups`): app-specific external
 *   storage, so no storage permission is needed (the manifest declares none), it is
 *   outside the public Downloads/MediaStore collection, and it is removed on uninstall.
 *   Before scoped storage, apps holding READ_EXTERNAL_STORAGE can still read it.
 *   Falls back to internal storage when external storage is unavailable.
 */
internal fun sessionBackupDirectory(
    sdkInt: Int,
    cacheDir: File,
    filesDir: File,
    externalDocumentsDir: () -> File?,
): File = if (sdkInt >= Build.VERSION_CODES.Q) {
    File(cacheDir, "PhoenixBackups")
} else {
    File(externalDocumentsDir() ?: filesDir, "PhoenixBackups")
}

/** Android 9 and older keep auto-backups in app storage; say so in the setting. */
internal fun autoBackupLocationNoteFor(sdkInt: Int): String? = if (sdkInt < Build.VERSION_CODES.Q) {
    "On Android 9 and older, auto-backups are saved in app storage " +
        "(Android/data/.../files/Documents/PhoenixBackups) and are deleted if the app is uninstalled."
} else {
    null
}

/** Settings label for [BackupDestination.Default]; must match [sessionBackupDirectory]. */
internal fun defaultBackupLocationLabelFor(sdkInt: Int): String = if (sdkInt < Build.VERSION_CODES.Q) {
    "App storage (Android/data/.../files/Documents/PhoenixBackups)"
} else {
    "Downloads/PhoenixBackups"
}

/**
 * The pre-Q app-specific dir can't be opened reliably in a file manager, so the
 * "Open Backup Folder" shortcut only exists on Android 10+.
 */
internal fun canOpenBackupFolderFor(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

// Getters (not stored vals) so host tests never touch Build.VERSION on class load.
actual val autoBackupLocationNote: String? get() = autoBackupLocationNoteFor(Build.VERSION.SDK_INT)
actual val defaultBackupLocationLabel: String get() = defaultBackupLocationLabelFor(Build.VERSION.SDK_INT)
actual val canOpenBackupFolder: Boolean get() = canOpenBackupFolderFor(Build.VERSION.SDK_INT)

/**
 * Android implementation of DataBackupManager.
 * Uses MediaStore for Android 10+ and direct file access for older versions.
 *
 * When the user has selected a custom backup destination via [PreferencesManager],
 * exports are routed through [BackupDestinationResolver]. If the custom destination
 * is inaccessible or write fails, the manager falls back to the default location
 * to guarantee data is never lost.
 */
class AndroidDataBackupManager(
    private val context: Context,
    database: PhoenixDatabase,
    private val preferencesManager: PreferencesManager,
    private val destinationResolver: BackupDestinationResolver,
    profilePreferencesRepository: ProfilePreferencesRepository,
    userProfileRepository: UserProfileRepository,
    portalTokenStorage: PortalTokenStorage,
    pendingProfileDeletionStore: PendingProfileDeletionStore,
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

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private fun sessionBackupDir(): File = sessionBackupDirectory(
        sdkInt = Build.VERSION.SDK_INT,
        cacheDir = context.cacheDir,
        filesDir = context.filesDir,
        externalDocumentsDir = { context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) },
    )

    override fun getSessionBackupDirectory(): String {
        // On Q+ we write via MediaStore, but need a staging path for base class path construction.
        // On pre-Q we write directly to the app-specific Documents dir (see sessionBackupDirectory).
        val dir = sessionBackupDir()
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    /**
     * On Android Q+, write session backups to MediaStore Downloads so they survive
     * app uninstall. On pre-Q, the base class writes to the app-specific Documents dir.
     *
     * When a custom backup destination is configured, writes there first.
     * Falls back to default location if the custom destination is inaccessible.
     */
    override fun writeSessionBackupFile(filePath: String, content: String) {
        // Check for custom backup destination (synchronous read of current preference value)
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            try {
                val fileName = File(filePath).name
                // Write content to a temp file first, then use DocumentFile to copy
                val tempFile = File(context.cacheDir, "session_backup_temp.json")
                tempFile.writeText(content, Charsets.UTF_8)

                val treeUri = android.net.Uri.parse(destination.uri)
                val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                if (treeDoc != null && treeDoc.exists() && treeDoc.canWrite()) {
                    // Remove existing file with same name
                    treeDoc.findFile(fileName)?.delete()
                    val newFile = treeDoc.createFile("application/json", fileName)
                    if (newFile != null) {
                        // F061: only treat the custom write as done when the stream
                        // actually opened. A null stream means nothing was written;
                        // don't delete the temp file and report success — fall
                        // through to the default location instead.
                        val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                        if (outputStream != null) {
                            outputStream.use { stream ->
                                tempFile.inputStream().use { it.copyTo(stream) }
                            }
                            tempFile.delete()
                            Logger.d { "Session backup written to custom destination: ${destination.displayName}" }
                            return
                        }
                        Logger.w { "openOutputStream returned null for custom destination; falling back to default" }
                    }
                }
                tempFile.delete()
                Logger.w { "Custom backup destination not writable, falling back to default" }
            } catch (e: Exception) {
                Logger.w(e) { "Falling back to default backup location after custom destination error" }
            }
        }

        // Default behavior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fileName = File(filePath).name
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PhoenixBackups")
            }
            val resolver = context.contentResolver
            val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create backup file in Downloads")
            // F060: a null output stream means the backup was never written; remove
            // the empty MediaStore row and fail instead of leaving an empty file
            // and reporting success.
            val outputStream = resolver.openOutputStream(destUri)
            if (outputStream == null) {
                runCatching { resolver.delete(destUri, null, null) }
                throw Exception("Failed to open output stream for backup file in Downloads")
            }
            outputStream.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
        } else {
            // Pre-Q: write to the app-specific path already set by getSessionBackupDirectory
            super.writeSessionBackupFile(filePath, content)
        }
    }

    override fun listBackupFileSizes(): List<Long> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val sizes = mutableListOf<Long>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("Download/PhoenixBackups/", "phoenix-%.json"),
            null,
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                sizes.add(cursor.getLong(sizeColumn))
            }
        }
        sizes
    } else {
        sessionBackupDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.length() }
            ?: emptyList()
    }

    override fun pruneOldBackups(keepCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            // Query all session backups sorted by date_added ascending (oldest first)
            val toDelete = mutableListOf<android.net.Uri>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("Download/PhoenixBackups/", "phoenix-%.json"),
                "${MediaStore.Downloads.DATE_ADDED} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val excess = cursor.count - keepCount
                var deleted = 0
                while (cursor.moveToNext() && deleted < excess) {
                    val id = cursor.getLong(idColumn)
                    toDelete.add(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                    )
                    deleted++
                }
            }
            toDelete.forEach { uri ->
                try {
                    resolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to delete old MediaStore backup $uri during prune" }
                }
            }
        } else {
            val files = sessionBackupDir().listFiles()
                ?.filter { it.isFile && it.name.startsWith("phoenix-") && it.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() }
                ?: return
            val excess = files.size - keepCount
            if (excess > 0) {
                files.take(excess).forEach { it.delete() }
            }
        }
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
        if (!canOpenBackupFolder) {
            // Pre-Q auto-backups live in app-specific storage, not Downloads (button is hidden).
            Logger.w { "Open backup folder is not supported below Android 10" }
            return
        }
        try {
            // Open Downloads/PhoenixBackups in system file manager
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val downloadsUri = "content://com.android.externalstorage.documents/document/primary:Download%2FPhoenixBackups".toUri()
                data = downloadsUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback: open general Downloads folder
            try {
                val fallbackIntent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                Logger.w { "Could not open backup folder - no compatible file manager found" }
            }
        }
    }

    override fun createBackupWriter(): BackupJsonWriter {
        val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
            .replace("-", "") + "_" +
            KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                .replace(":", "")
        val fileName = "phoenix_backup_$timestamp.json"
        return BackupJsonWriter(File(cacheDir, fileName).absolutePath)
    }

    override suspend fun finalizeExport(tempFilePath: String): Result<String> {
        val file = File(tempFilePath)
        val fileName = file.name

        // Check for custom backup destination
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            val customResult = tryCustomDestination(destination, fileName, tempFilePath)
            if (customResult != null) {
                file.delete()
                return customResult
            }
            // Custom destination failed — fall through to default
        }

        return try {
            val destPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProjectPhoenix")
                }

                val resolver = context.contentResolver
                val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create file in Downloads")

                // F060: fail (and clean up the empty row) when the stream can't be
                // opened, instead of returning the URI as if the export succeeded.
                val outputStream = resolver.openOutputStream(destUri)
                if (outputStream == null) {
                    runCatching { resolver.delete(destUri, null, null) }
                    throw Exception("Failed to open output stream for export file in Downloads")
                }
                outputStream.use { stream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(stream)
                    }
                }

                destUri.toString()
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ProjectPhoenix",
                )
                downloadsDir.mkdirs()
                val destFile = File(downloadsDir, fileName)
                file.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            }

            // Clean up cache file
            file.delete()
            Result.success(destPath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            val fileName = "phoenix_backup_$timestamp.json"

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProjectPhoenix")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create file in Downloads")

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray())
                }

                uri.toString()
            } else {
                // Android 9 and below - direct file access
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ProjectPhoenix",
                )
                downloadsDir.mkdirs()

                val file = File(downloadsDir, fileName)
                file.writeText(jsonString)

                file.absolutePath
            }

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun importFromFile(filePath: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val inputStream = if (filePath.startsWith("content://")) {
                context.contentResolver.openInputStream(filePath.toUri())
                    ?: throw Exception("Cannot open file")
            } else {
                File(filePath).inputStream()
            }

            val source = InputStreamBackupSource(inputStream)
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
     * Share backup via Android share sheet (streaming path)
     */
    override suspend fun shareBackup() {
        val cachePath = withContext(Dispatchers.IO) { exportToCache() }
        val file = File(cachePath)

        if (!file.exists()) {
            throw Exception("Backup file was not created")
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix Backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to share backup file: ${file.absolutePath}" }
            // Clean up cache file on sharing error
            file.delete()
            throw e
        }
    }
}
