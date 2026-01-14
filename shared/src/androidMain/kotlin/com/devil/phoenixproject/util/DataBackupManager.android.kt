package com.devil.phoenixproject.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
                val uri = Uri.parse(filePath)
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
     * Save backup to cache for sharing
     */
    suspend fun saveToCache(backup: BackupData): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val jsonString = json.encodeToString(backup)
            val timestamp = KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "yyyy-MM-dd")
                .replace("-", "") + "_" +
                KmpUtils.formatTimestamp(KmpUtils.currentTimeMillis(), "HH:mm:ss")
                    .replace(":", "")
            val fileName = "vitruvian_backup_$timestamp.json"

            val file = File(cacheDir, fileName)
            file.writeText(jsonString)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Share backup via Android share sheet
     */
    override suspend fun shareBackup() {
        val backup = exportAllData()
        val cacheResult = saveToCache(backup)

        cacheResult.onSuccess { uri ->
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
        }
    }

    /**
     * Import from Android content URI (e.g., from file picker)
     */
    suspend fun importFromUri(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open file")

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            importFromJson(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
