package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * Policy for removing a Storage Access Framework document when the backup write
 * fails after that document has been created. The Android resolver calls
 * [writeSafDocumentDeletingPartialOnFailure] with
 * [android.provider.DocumentsContract.deleteDocument] and [androidx.documentfile.provider.DocumentFile.delete].
 */
class SafPartialWriteCleanupTest {

    @Test
    fun successfulWrite_leavesTheDocument() {
        var deleted = false
        writeSafDocumentDeletingPartialOnFailure(
            documentUri = "content://com.android.externalstorage.documents/document/backup",
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
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/backup",
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
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/backup",
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
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/backup",
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
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/backup",
                deleteCreatedDocument = { throw cancellation },
            ) {
                throw IllegalStateException("disk full")
            }
        }
        assertSame(cancellation, thrown)
    }
}
