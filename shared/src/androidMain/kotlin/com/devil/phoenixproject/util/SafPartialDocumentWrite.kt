package com.devil.phoenixproject.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

/**
 * Backup writes omit [writeSafDocumentDeletingPartialOnFailure]'s [onDeleteFailure]
 * and keep this tag plus the partial-backup message.
 */
private val backupPartialWriteLog = Logger.withTag("BackupDestinationResolver.Android")

/**
 * Run [write] for a SAF document that has already been created.
 *
 * If [write] fails, [deleteCreatedDocument] removes that document and the
 * original exception is rethrown, including [CancellationException]. Errors
 * from the delete are logged and ignored so the write failure is preserved,
 * except a [CancellationException] from the delete, which is rethrown.
 *
 * Backup and file-saver writes both use this helper. Pass [onDeleteFailure]
 * to keep a call site's own log tag and message; the default matches the
 * backup resolver.
 */
internal fun writeSafDocumentDeletingPartialOnFailure(
    documentUri: String,
    deleteCreatedDocument: () -> Unit,
    onDeleteFailure: ((Exception) -> Unit)? = null,
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
            if (onDeleteFailure != null) {
                onDeleteFailure(deleteError)
            } else {
                backupPartialWriteLog.w(deleteError) { "Failed to delete partial SAF backup $documentUri" }
            }
        }
        throw writeError
    }
}

/**
 * Best-effort delete of a SAF document created for a write that did not finish.
 *
 * [DocumentsContract.deleteDocument] is tried first. [deleteByDocumentFile] is the
 * fallback when that call returns false or throws. Delete errors are logged and
 * ignored. [CancellationException] is rethrown.
 */
internal fun deleteSafDocumentBestEffort(
    contentResolver: ContentResolver,
    uri: Uri,
    deleteByDocumentFile: () -> Boolean,
    logger: Logger,
    failedDeleteMessage: String,
) {
    val deletedByContract = try {
        DocumentsContract.deleteDocument(contentResolver, uri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "DocumentsContract.deleteDocument failed for $uri" }
        false
    }
    if (deletedByContract) return

    val deletedByFile = try {
        deleteByDocumentFile()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "DocumentFile.delete failed for $uri" }
        false
    }
    if (!deletedByFile) {
        logger.w { failedDeleteMessage }
    }
}
