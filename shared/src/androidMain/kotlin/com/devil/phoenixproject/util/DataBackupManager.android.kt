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
import com.devil.phoenixproject.database.VitruvianDatabase
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of DataBackupManager.
 * Uses MediaStore for Android 10+ and direct file access for older versions.
 */
class AndroidDataBackupManager(private val context: Context, database: VitruvianDatabase) : BaseDataBackupManager(database) {

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    override fun getSessionBackupDirectory(): String {
        // On Q+ we write via MediaStore, but need a staging path for base class path construction.
        // On pre-Q we write directly to public Downloads (survives uninstall).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dir = File(context.cacheDir, "PhoenixBackups")
            if (!dir.exists()) dir.mkdirs()
            dir.absolutePath
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhoenixBackups",
            )
            if (!dir.exists()) dir.mkdirs()
            dir.absolutePath
        }
    }

    /**
     * On Android Q+, write session backups to MediaStore Downloads so they survive
     * app uninstall. On pre-Q, the base class writes directly to public Downloads.
     */
    override fun writeSessionBackupFile(filePath: String, content: String) {
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
            resolver.openOutputStream(destUri)?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
            }
        } else {
            // Pre-Q: write directly to public Downloads path (already set by getSessionBackupDirectory)
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
            arrayOf("Download/PhoenixBackups/", "phoenix-workout-%.json"),
            null,
        )?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                sizes.add(cursor.getLong(sizeColumn))
            }
        }
        sizes
    } else {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhoenixBackups",
        )
        dir.listFiles()
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
                arrayOf("Download/PhoenixBackups/", "phoenix-workout-%.json"),
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
                } catch (_: Exception) {}
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhoenixBackups",
            )
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("phoenix-workout-") && it.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() }
                ?: return
            val excess = files.size - keepCount
            if (excess > 0) {
                files.take(excess).forEach { it.delete() }
            }
        }
    }

    override fun openBackupFolder() {
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
        val fileName = "vitruvian_backup_$timestamp.json"
        return BackupJsonWriter(File(cacheDir, fileName).absolutePath)
    }

    override suspend fun finalizeExport(tempFilePath: String): Result<String> {
        val file = File(tempFilePath)
        val fileName = file.name

        return try {
            val destPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/VitruvianPhoenix")
                }

                val resolver = context.contentResolver
                val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Failed to create file in Downloads")

                resolver.openOutputStream(destUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                destUri.toString()
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "VitruvianPhoenix",
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

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/VitruvianPhoenix")
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
                    "VitruvianPhoenix",
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
            val jsonString = if (filePath.startsWith("content://")) {
                // Content URI
                val uri = filePath.toUri()
                val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                inputStream.bufferedReader().use { it.readText() }
            } else {
                // File path
                File(filePath).readText()
            }

            importFromJson(jsonString)
        } catch (e: Exception) {
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
                putExtra(Intent.EXTRA_SUBJECT, "Vitruvian Phoenix Backup")
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
