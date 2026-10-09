package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadsPartialWriteCleanupTest {

    @Test
    fun successfulWrite_leavesTheDownloadsRow() {
        var deleted = false
        writeDownloadDeletingRowOnFailure(
            destUri = "content://media/external/downloads/1",
            deleteInsertedRow = { deleted = true },
        ) {
            // write succeeds
        }
        assertFalse(deleted)
    }

    @Test
    fun writeFailure_deletesTheInsertedRow_andRethrows() {
        var deleted = false
        val failure = IllegalStateException("disk full")
        val thrown = assertFailsWith<IllegalStateException> {
            writeDownloadDeletingRowOnFailure(
                destUri = "content://media/external/downloads/1",
                deleteInsertedRow = { deleted = true },
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
            writeDownloadDeletingRowOnFailure(
                destUri = "content://media/external/downloads/1",
                deleteInsertedRow = { throw IllegalStateException("resolver busy") },
            ) {
                throw failure
            }
        }
        assertSame(failure, thrown)
    }
}
