package com.devil.phoenixproject.util

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import co.touchlab.kermit.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun isAccessible(destination: BackupDestination.Custom): Boolean = withContext(Dispatchers.IO) {
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
        } catch (e: CancellationException) {
            throw e
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

            // Copy from temp file to the destination. A null stream is a failure of
            // the write, so it throws into the cleanup below instead of returning early.
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = newFile.uri.toString(),
                deleteCreatedDocument = { deletePartialSafDocument(context.contentResolver, newFile) },
            ) {
                val tempFile = File(tempFilePath)
                context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                    tempFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: throw Exception("Failed to open output stream for ${newFile.uri}")
            }

            log.d { "Wrote $fileName to ${destination.displayName} (${newFile.uri})" }
            Result.success(newFile.uri.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "writeFile failed for $fileName to ${destination.displayName}" }
            Result.failure(e)
        }
    }
}

/**
 * Best-effort delete of a SAF document created for a backup that did not finish.
 * [DocumentsContract.deleteDocument] is tried first; [DocumentFile.delete] is the
 * fallback when that call reports failure. A false result is logged and ignored.
 */
internal fun deletePartialSafDocument(contentResolver: ContentResolver, document: DocumentFile) {
    val deleted = DocumentsContract.deleteDocument(contentResolver, document.uri) || document.delete()
    if (!deleted) {
        log.w { "Failed to delete partial SAF backup ${document.uri}" }
    }
}

/**
 * Run [write] for a SAF document that has already been created.
 *
 * If [write] fails, [deleteCreatedDocument] removes that document and the
 * original exception is rethrown, including [CancellationException]. Errors
 * from the delete are logged and ignored so the write failure is preserved,
 * except a [CancellationException] from the delete, which is rethrown.
 */
internal fun writeSafDocumentDeletingPartialOnFailure(
    documentUri: String,
    deleteCreatedDocument: () -> Unit,
    write: () -> Unit,
) {
    try {
        write()
    } catch (writeError: Exception) {
        try {
            deleteCreatedDocument()
        } catch (deleteError: CancellationException) {
            throw deleteError
        } catch (deleteError: Exception) {
            log.w(deleteError) { "Failed to delete partial SAF backup $documentUri" }
        }
        throw writeError
    }
}
