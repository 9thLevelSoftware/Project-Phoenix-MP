package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import co.touchlab.kermit.Logger
import java.io.IOException
import kotlinx.coroutines.CancellationException

private val log = Logger.withTag("FilePicker.Android")

/**
 * Android implementation of FilePicker using Activity Result APIs.
 * Uses ActivityResultContracts.OpenDocument for file picking and
 * ActivityResultContracts.CreateDocument for file saving.
 *
 * CreateDocument inserts the destination before this callback runs. A failed
 * write deletes that document only when it was empty before the write; when the
 * user picked an existing file to overwrite, the file is left in place.
 */
actual class FilePicker {

    @Composable
    actual fun LaunchFilePicker(onFilePicked: (String?) -> Unit) {
        LaunchDocumentPicker(arrayOf("application/json"), onFilePicked)
    }

    @Composable
    actual fun LaunchCsvFilePicker(onFilePicked: (String?) -> Unit) {
        LaunchDocumentPicker(
            arrayOf("text/csv", "text/comma-separated-values", "*/*"),
            onFilePicked,
        )
    }

    @Composable
    private fun LaunchDocumentPicker(mimeTypes: Array<String>, onFilePicked: (String?) -> Unit) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            onFilePicked(uri?.toString())
        }

        LaunchedEffect(Unit) {
            launcher.launch(mimeTypes)
        }
    }

    @Composable
    actual fun LaunchFileSaver(fileName: String, content: String, onSaved: (String?) -> Unit) {
        LaunchDocumentSaver("application/json", fileName, content, onSaved)
    }

    @Composable
    actual fun LaunchCsvFileSaver(fileName: String, content: String, onSaved: (String?) -> Unit) {
        LaunchDocumentSaver("text/csv", fileName, content, onSaved)
    }

    @Composable
    private fun LaunchDocumentSaver(mimeType: String, fileName: String, content: String, onSaved: (String?) -> Unit) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mimeType),
        ) { uri: Uri? ->
            if (uri == null) {
                // The user dismissed the picker. CreateDocument did not insert a file.
                onSaved(null)
            } else {
                onSaved(saveFilePickerCreatedDocument(context, uri, content))
            }
        }

        LaunchedEffect(Unit) {
            launcher.launch(fileName)
        }
    }
}

/**
 * Remember a FilePicker instance for use in Compose.
 */
@Composable
actual fun rememberFilePicker(): FilePicker = remember { FilePicker() }

/**
 * Write [content] to the document CreateDocument returned.
 *
 * Returns the document URI on success. A null output stream or a write
 * exception returns null — the same failure result the saver reported before
 * cleanup. The document is deleted on failure only when the provider reported
 * it as empty before the write: CreateDocument also returns an existing
 * document when the user confirms an overwrite, and that file must never be
 * deleted. [CancellationException] is rethrown after any best-effort delete.
 */
internal fun saveFilePickerCreatedDocument(
    context: Context,
    uri: Uri,
    content: String,
): String? {
    try {
        val createdEmpty = isEmptyDocument(context, uri)
        writeFilePickerDocumentDeletingPartialOnFailure(
            documentUri = uri.toString(),
            deleteCreatedDocument = {
                if (createdEmpty) {
                    deleteFilePickerCreatedDocument(context, uri)
                } else {
                    log.w { "Keeping $uri after a failed write: it was not reported empty before the write" }
                }
            },
        ) {
            // A null stream wrote nothing, but CreateDocument already inserted the row.
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Failed to open output stream for $uri")
            stream.use { it.write(content.toByteArray()) }
        }
        return uri.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Failed to save document $uri" }
        return null
    }
}

/**
 * True only when the provider reports [uri] with a size of 0. An unknown size
 * (no row, a null size or a query error) is treated as not empty, so an
 * existing file is never deleted on a guess.
 */
internal fun isEmptyDocument(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) == 0L
    } == true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e) { "Could not read the size of $uri" }
    false
}

/**
 * Best-effort delete of a document created by CreateDocument whose write did not finish.
 *
 * [DocumentsContract.deleteDocument] is tried first. [DocumentFile.delete] is the
 * fallback when that call returns false or throws. Delete errors are logged and
 * ignored. [CancellationException] is rethrown.
 */
internal fun deleteFilePickerCreatedDocument(context: Context, uri: Uri) {
    val deletedByContract = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "DocumentsContract.deleteDocument failed for $uri" }
        false
    }
    if (deletedByContract) return

    val deletedByFile = try {
        DocumentFile.fromSingleUri(context, uri)?.delete() == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "DocumentFile.delete failed for $uri" }
        false
    }
    if (!deletedByFile) {
        log.w { "Failed to delete created document $uri" }
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
internal fun writeFilePickerDocumentDeletingPartialOnFailure(
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
            log.w(deleteError) { "Failed to delete created document $documentUri" }
        }
        throw writeError
    }
}
