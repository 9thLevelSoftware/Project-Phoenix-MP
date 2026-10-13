package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException

/**
 * Policy for removing a Storage Access Framework document when a CreateDocument
 * save fails after the picker has already created that document.
 * [FilePicker] calls [writeFilePickerDocumentDeletingPartialOnFailure] with
 * [android.provider.DocumentsContract.deleteDocument] and
 * [androidx.documentfile.provider.DocumentFile.delete].
 */
class FilePickerCreatedDocumentCleanupTest {

    @Test
    fun successfulWrite_leavesTheDocument() {
        var deleted = false
        writeFilePickerDocumentDeletingPartialOnFailure(
            documentUri = "content://com.android.externalstorage.documents/document/export",
            deleteCreatedDocument = { deleted = true },
        ) {
            // write succeeds
        }
        assertFalse(deleted)
    }

    @Test
    fun writeFailure_deletesTheDocument_andRethrowsTheOriginalException() {
        var deleted = false
        val failure = IllegalStateException("disk full")
        val thrown = assertFailsWith<IllegalStateException> {
            writeFilePickerDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/export",
                deleteCreatedDocument = { deleted = true },
            ) {
                throw failure
            }
        }
        assertTrue(deleted)
        assertSame(failure, thrown)
    }

    @Test
    fun writeFailure_whenDeleteFails_rethrowsTheOriginalException() {
        val failure = IllegalStateException("disk full")
        val thrown = assertFailsWith<IllegalStateException> {
            writeFilePickerDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/export",
                deleteCreatedDocument = { throw IllegalStateException("resolver busy") },
            ) {
                throw failure
            }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun writeCancellation_deletesTheDocument_andRethrowsCancellation() {
        var deleted = false
        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            writeFilePickerDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/export",
                deleteCreatedDocument = {
                    deleted = true
                    throw IllegalStateException("resolver busy")
                },
            ) {
                throw cancellation
            }
        }
        assertTrue(deleted)
        assertSame(cancellation, thrown)
    }

    @Test
    fun deleteCancellation_isRethrown() {
        val cancellation = CancellationException("delete cancelled")
        val thrown = assertFailsWith<CancellationException> {
            writeFilePickerDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/export",
                deleteCreatedDocument = { throw cancellation },
            ) {
                throw IllegalStateException("disk full")
            }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun saverDelegatesTheCreatedDocumentToTheCleanupHelper() {
        val source = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/FilePicker.android.kt",
        ) ?: fail("Missing FilePicker.android.kt")
        val body = region(source, "private fun LaunchDocumentSaver", "Remember a FilePicker instance")
        val nullUri = body.indexOf("uri == null")
        val saveCall = body.indexOf("saveFilePickerCreatedDocument(context, uri, content)")

        assertTrue(nullUri >= 0, "dismissed picker still reports a null result")
        assertTrue(saveCall > nullUri, "a chosen document is written through the cleanup helper")
        assertTrue(
            !body.substring(nullUri, saveCall).contains("delete"),
            "dismissing the picker does not delete a document",
        )
        assertTrue(body.contains("onSaved(null)"), "dismissed picker preserves the null result")
        assertTrue(
            source.contains("DocumentsContract.deleteDocument"),
            "cleanup tries DocumentsContract.deleteDocument first",
        )
        assertTrue(
            source.contains("DocumentFile.fromSingleUri"),
            "cleanup falls back to DocumentFile.delete",
        )
    }

    private fun region(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue(start >= 0, "Missing $startMarker")
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue(end > start, "Missing $endMarker")
        return source.substring(start, end)
    }
}
