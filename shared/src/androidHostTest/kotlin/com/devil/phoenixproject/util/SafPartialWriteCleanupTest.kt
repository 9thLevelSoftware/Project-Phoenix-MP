package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException

/**
 * Policy for removing a Storage Access Framework document when a backup or
 * file-saver write fails after that document has been created. Both call
 * [writeSafDocumentDeletingPartialOnFailure] and [deleteSafDocumentBestEffort],
 * which tries [android.provider.DocumentsContract.deleteDocument] and then
 * [androidx.documentfile.provider.DocumentFile.delete].
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
    fun writeFailure_whenDeleteFails_invokesOnDeleteFailure_andRethrowsTheOriginalException() {
        val failure = IllegalStateException("disk full")
        val deleteFailure = IllegalStateException("resolver busy")
        var reported: Exception? = null
        val thrown = assertFailsWith<IllegalStateException> {
            writeSafDocumentDeletingPartialOnFailure(
                documentUri = "content://com.android.externalstorage.documents/document/export",
                deleteCreatedDocument = { throw deleteFailure },
                onDeleteFailure = { reported = it },
            ) {
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertSame(deleteFailure, reported)
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

    @Test
    fun backupAndFileSaverShareThePartialWriteHelper() {
        val helper = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/SafPartialDocumentWrite.kt",
        ) ?: fail("Missing SafPartialDocumentWrite.kt")
        val backup = readProjectFile(
            "src/androidMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.android.kt",
        ) ?: fail("Missing BackupDestinationResolver.android.kt")

        assertTrue(helper.contains("fun writeSafDocumentDeletingPartialOnFailure"))
        assertTrue(helper.contains("fun deleteSafDocumentBestEffort"))
        assertTrue(helper.contains("DocumentsContract.deleteDocument"))
        assertTrue(
            helper.contains("deleteByDocumentFile()"),
            "deleteDocument false or throw falls back to DocumentFile.delete",
        )
        assertTrue(backup.contains("writeSafDocumentDeletingPartialOnFailure("))
        assertTrue(backup.contains("deleteSafDocumentBestEffort("))
        assertTrue(backup.contains("deleteByDocumentFile = { newFile.delete() }"))
        assertTrue(backup.contains("Failed to delete partial SAF backup"))
        assertFalse(backup.contains("fun writeSafDocumentDeletingPartialOnFailure"))
        assertFalse(backup.contains("fun deletePartialSafDocument"))
        assertEquals(
            1,
            helper.split("fun writeSafDocumentDeletingPartialOnFailure").size - 1,
        )
    }
}
