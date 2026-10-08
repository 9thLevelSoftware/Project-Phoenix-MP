package com.devil.phoenixproject.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.database.PhoenixDatabase
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-specific `Documents/PhoenixBackups`, or internal `files/PhoenixBackups` when
 * external storage is unavailable.
 *
 * Shared by pre-Q session backups and pre-Q full exports. App-specific external
 * storage needs no storage permission (the manifest declares none), sits outside
 * the public Downloads/MediaStore collection, and is removed on uninstall.
 * Before scoped storage, apps holding READ_EXTERNAL_STORAGE can still read it.
 */
private fun appSpecificPhoenixBackupsDirectory(
    filesDir: File,
    externalDocumentsDir: () -> File?,
): File = File(externalDocumentsDir() ?: filesDir, "PhoenixBackups")

/**
 * Directory for per-session auto-backups written through the file API.
 *
 * - Android 10+ (Q): a cache staging dir; the real write goes to MediaStore Downloads.
 * - Android 9 and older: [appSpecificPhoenixBackupsDirectory]. Pre-Q full exports
 *   use that same directory ([preQFullExportDirectory]).
 */
internal fun sessionBackupDirectory(
    sdkInt: Int,
    cacheDir: File,
    filesDir: File,
    externalDocumentsDir: () -> File?,
): File = if (sdkInt >= Build.VERSION_CODES.Q) {
    File(cacheDir, "PhoenixBackups")
} else {
    appSpecificPhoenixBackupsDirectory(filesDir, externalDocumentsDir)
}

/**
 * Directory for a manual full export on Android 9 and older (API 26–28).
 *
 * Same layout as [sessionBackupDirectory] below Q — not public Downloads.
 * Public Downloads needs a storage permission this app does not declare, so that
 * write fails on API 26–28. Android 10+ does not use this; those exports go
 * through MediaStore `Download/ProjectPhoenix`.
 */
internal fun preQFullExportDirectory(
    filesDir: File,
    externalDocumentsDir: () -> File?,
): File = appSpecificPhoenixBackupsDirectory(filesDir, externalDocumentsDir)

/**
 * Copy a finished full-export file into [preQFullExportDirectory].
 * Overwrites an existing file with the same name. Caller deletes [source].
 */
internal fun copyFullExportToPreQDocuments(
    source: File,
    filesDir: File,
    externalDocumentsDir: () -> File?,
): File {
    val destDir = preQFullExportDirectory(filesDir, externalDocumentsDir)
    if (!destDir.exists()) destDir.mkdirs()
    val destFile = File(destDir, source.name)
    source.copyTo(destFile, overwrite = true)
    return destFile
}

/** Android 9 and older keep auto-backups in app storage; say so in the setting. */
internal fun autoBackupLocationNoteFor(sdkInt: Int): String? = if (sdkInt < Build.VERSION_CODES.Q) {
    "On Android 9 and older, auto-backups are saved in app storage " +
        "(Android/data/.../files/Documents/PhoenixBackups) and are deleted if the app is uninstalled."
} else {
    null
}

/**
 * Settings label for [BackupDestination.Default].
 *
 * - Android 10+ (Q): `"Downloads/PhoenixBackups"`. Session backups are written
 *   through MediaStore into that Downloads collection (`Download/PhoenixBackups`).
 *   [sessionBackupDirectory] is only a cache staging path on these API levels.
 * - Android 9 and older (API 26–28): `"App storage (Android/data/.../files/Documents/PhoenixBackups)"`,
 *   the app-specific Documents location [sessionBackupDirectory] writes to.
 */
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
 * Uses MediaStore for Android 10+ and app-specific Documents for older versions.
 *
 * When the user has selected a custom backup destination via [PreferencesManager],
 * exports and session backups are routed through [BackupDestinationResolver]. If the
 * custom destination is inaccessible or write fails, the manager falls back to the
 * default location to guarantee data is never lost.
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
     * Insert a JSON file into MediaStore Downloads and write [write] to it.
     *
     * Session backups (`Download/PhoenixBackups`) and full exports
     * (`Download/ProjectPhoenix`) share this path. Callers keep their own folder
     * and failure text. A null output stream means nothing was written: the empty
     * row is deleted and the call throws [streamFailureMessage] (F060 fail-closed).
     *
     * @return the inserted content URI
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertDownloadsJson(
        fileName: String,
        relativePath: String,
        insertFailureMessage: String,
        streamFailureMessage: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        val resolver = context.contentResolver
        val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception(insertFailureMessage)
        val outputStream = resolver.openOutputStream(destUri)
        if (outputStream == null) {
            runCatching { resolver.delete(destUri, null, null) }
            throw Exception(streamFailureMessage)
        }
        outputStream.use(write)
        return destUri
    }

    /**
     * Session auto-backups in `Download/PhoenixBackups` named `phoenix-*.json`.
     *
     * MediaStore stores [MediaStore.Downloads.RELATIVE_PATH] with a trailing
     * slash, so this query uses `Download/PhoenixBackups/` rather than the
     * insert path. Shared by [listBackupFileSizes] and [pruneOldBackups].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun querySessionBackupCollection(
        projection: Array<String>,
        sortOrder: String?,
    ) = context.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
        arrayOf("Download/PhoenixBackups/", "phoenix-%.json"),
        sortOrder,
    )

    /**
     * Route session backups through [tryCustomDestination], the same resolver path
     * full export uses. That checks the persisted URI permission and writes via
     * [BackupDestinationResolver], including a null output stream as failure.
     * Falls back to the default location when the custom destination is inaccessible.
     *
     * On Android Q+, the default location is MediaStore Downloads so backups survive
     * uninstall. On pre-Q, the base class writes to the app-specific Documents dir.
     */
    override suspend fun writeSessionBackupFile(filePath: String, content: String) {
        val destination = preferencesManager.preferencesFlow.value.backupDestination
        if (destination is BackupDestination.Custom) {
            val tempFile = File(context.cacheDir, "session_backup_temp.json")
            try {
                val fileName = File(filePath).name
                tempFile.writeText(content, Charsets.UTF_8)
                val customResult = tryCustomDestination(destination, fileName, tempFile.absolutePath)
                if (customResult != null) {
                    Logger.d { "Session backup written to custom destination: ${destination.displayName}" }
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Falling back to default backup location after custom destination error" }
            } finally {
                tempFile.delete()
            }
        }

        // Default behavior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertDownloadsJson(
                fileName = File(filePath).name,
                relativePath = "Download/PhoenixBackups",
                insertFailureMessage = "Failed to create backup file in Downloads",
                streamFailureMessage = "Failed to open output stream for backup file in Downloads",
            ) { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }
        } else {
            // Pre-Q: write to the app-specific path already set by getSessionBackupDirectory
            super.writeSessionBackupFile(filePath, content)
        }
    }

    override fun listBackupFileSizes(): List<Long> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val sizes = mutableListOf<Long>()
        querySessionBackupCollection(
            projection = arrayOf(MediaStore.Downloads.SIZE),
            sortOrder = null,
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
            val toDelete = mutableListOf<Uri>()
            querySessionBackupCollection(
                projection = arrayOf(MediaStore.Downloads._ID),
                sortOrder = "${MediaStore.Downloads.DATE_ADDED} ASC",
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
        } catch (e: CancellationException) {
            throw e
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
                val destUri = insertDownloadsJson(
                    fileName = fileName,
                    relativePath = "Download/ProjectPhoenix",
                    insertFailureMessage = "Failed to create file in Downloads",
                    streamFailureMessage = "Failed to open output stream for export file in Downloads",
                ) { stream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(stream)
                    }
                }
                destUri.toString()
            } else {
                // Pre-Q: same app-specific Documents dir as session backups.
                copyFullExportToPreQDocuments(
                    source = file,
                    filesDir = context.filesDir,
                    externalDocumentsDir = { context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) },
                ).absolutePath
            }

            // Clean up cache file
            file.delete()
            Result.success(destPath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
