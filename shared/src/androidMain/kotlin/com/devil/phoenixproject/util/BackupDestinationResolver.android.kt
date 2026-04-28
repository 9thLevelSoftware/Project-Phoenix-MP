package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val log = Logger.withTag("BackupDestinationResolver.Android")

/**
 * Android implementation of [BackupDestinationResolver].
 *
 * Uses [DocumentFile] + persisted URI permissions from [ContentResolver]
 * to read/write files in user-selected directories obtained via
 * [OpenDocumentTree].
 */
class AndroidBackupDestinationResolver(
    private val context: Context,
) : BackupDestinationResolver {

    override suspend fun isAccessible(destination: BackupDestination.Custom): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(destination.uri)

                // Check that we still have persisted read+write permissions
                val persisted = context.contentResolver.persistedUriPermissions
                val hasPermission = persisted.any { perm ->
                    perm.uri == uri && perm.isReadPermission && perm.isWritePermission
                }
                if (!hasPermission) {
                    log.w { "No persisted URI permission for ${destination.uri}" }
                    return@withContext false
                }

                // Verify the directory still exists and is writable
                val docFile = DocumentFile.fromTreeUri(context, uri)
                val accessible = docFile != null && docFile.exists() && docFile.canWrite()
                if (!accessible) {
                    log.w { "DocumentFile check failed: exists=${docFile?.exists()}, canWrite=${docFile?.canWrite()}" }
                }
                accessible
            } catch (e: Exception) {
                log.e(e) { "isAccessible failed for ${destination.displayName}" }
                false
            }
        }

    override suspend fun writeFile(
        destination: BackupDestination.Custom,
        fileName: String,
        tempFilePath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val treeUri = android.net.Uri.parse(destination.uri)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Cannot resolve tree URI: ${destination.uri}"))

            // Check for existing file with same name and remove it
            val existing = treeDoc.findFile(fileName)
            if (existing != null && existing.exists()) {
                existing.delete()
            }

            // Create the new file
            val newFile = treeDoc.createFile("application/json", fileName)
                ?: return@withContext Result.failure(Exception("Failed to create file: $fileName"))

            // Copy from temp file to the destination
            val tempFile = File(tempFilePath)
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open output stream for ${newFile.uri}"))

            log.d { "Wrote $fileName to ${destination.displayName} (${newFile.uri})" }
            Result.success(newFile.uri.toString())
        } catch (e: Exception) {
            log.e(e) { "writeFile failed for $fileName to ${destination.displayName}" }
            Result.failure(e)
        }
    }

    override suspend fun listFiles(destination: BackupDestination.Custom): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val treeUri = android.net.Uri.parse(destination.uri)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext emptyList()

                treeDoc.listFiles()
                    .filter { it.isFile && (it.name?.endsWith(".json") == true) }
                    .mapNotNull { it.name }
            } catch (e: Exception) {
                log.e(e) { "listFiles failed for ${destination.displayName}" }
                emptyList()
            }
        }
}
