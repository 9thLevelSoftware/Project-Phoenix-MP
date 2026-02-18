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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Android implementation of DataBackupManager.
 * Uses MediaStore for Android 10+ and direct file access for older versions.
 */
class AndroidDataBackupManager(
    private val context: Context,
    database: VitruvianDatabase
) : BaseDataBackupManager(database) {

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, "backups")
            if (!dir.exists()) dir.mkdirs()
            return dir
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
                    "VitruvianPhoenix"
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
                    "VitruvianPhoenix"
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
                file
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
