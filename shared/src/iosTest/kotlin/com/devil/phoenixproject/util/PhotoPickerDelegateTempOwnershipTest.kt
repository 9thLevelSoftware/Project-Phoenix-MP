package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * Round-2 merge-gate R3 failure-injection tests for the iOS picker's temp-copy
 * ownership: a batch error deletes the copies accumulated so far, late-arriving
 * and post-disposal callbacks delete their own copies, cancellation/disposal
 * keeps none, and a successful batch transfers ownership to the media store
 * (real transient-file consumption preserved — the handles stay readable for
 * staging).
 *
 * Drives the exact accumulate/finish/dispose transitions of
 * [PhotoPickerDelegate] with REAL temp files in the picker temp namespace.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotoPickerDelegateTempOwnershipTest {

    private val fileManager = NSFileManager.defaultManager

    private fun makeTempCopy(name: String): String {
        val path = "${NSTemporaryDirectory()}picked_${NSUUID().UUIDString}_$name"
        val file = fopen(path, "wb") ?: error("fopen failed for $path")
        try {
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
            }
        } finally {
            fclose(file)
        }
        return path
    }

    private fun exists(path: String): Boolean = fileManager.fileExistsAtPath(path)

    private fun delegate(
        results: MutableList<MediaPickResult>,
    ): PhotoPickerDelegate = PhotoPickerDelegate(
        onFinished = { results += it },
        log = Logger.withTag("PhotoPickerDelegateTempOwnershipTest"),
    )

    // ---- R3: mixed success/failure — accumulated copies are cleaned ----

    @Test
    fun mixedSuccessAndFailureDeletesTheAccumulatedCopy() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)
        val first = makeTempCopy("first.jpg")

        delegate.beginBatch(2)
        delegate.onItemLoaded(tempPath = first, displayName = "first.jpg", loadFailed = false)
        assertTrue(exists(first), "During an open session the copy is owned, not yet deleted (D4)")

        // A different item fails: the batch ends in Error and the successful
        // copy made BEFORE the failure must not leak (R3).
        delegate.onItemLoaded(tempPath = null, displayName = null, loadFailed = true)

        assertEquals(1, results.size, "Exactly one terminal result is delivered")
        val result = results.single()
        assertTrue(result is MediaPickResult.Error, "A failed item fails the session (existing behavior)")
        assertTrue(!exists(first), "R3: the pre-error accumulated copy must be deleted, not leaked")
        assertTrue(delegate.ownedTempPaths().isEmpty(), "R3: the session owns no temp copies after failure")
    }

    // ---- R3: late-arriving copies are cleaned ----

    @Test
    fun lateArrivalAfterFailureDeletesItsOwnCopy() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)
        val late = makeTempCopy("late.jpg")

        delegate.beginBatch(2)
        delegate.onItemLoaded(tempPath = null, displayName = null, loadFailed = true)
        assertEquals(1, results.size, "The failure is the session's only delivery")

        // The slow provider finishes AFTER the session already failed: its
        // transient copy must be deleted immediately (R3 — "later callbacks
        // copy before the finished check and then return without deleting
        // their copies" is exactly this path).
        delegate.onItemLoaded(tempPath = late, displayName = "late.jpg", loadFailed = false)

        assertTrue(!exists(late), "R3: a late-arriving copy must be deleted, never orphaned")
        assertEquals(1, results.size, "A late callback never delivers a second result (T27)")
    }

    // ---- R3: disposal and cancellation keep no copies ----

    @Test
    fun postDisposalCallbackDeletesItsCopy() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)
        val stray = makeTempCopy("stray.jpg")

        delegate.beginBatch(1)
        delegate.dispose()
        assertEquals(0, results.size, "Disposal delivers no result (the consumer is gone)")

        delegate.onItemLoaded(tempPath = stray, displayName = "stray.jpg", loadFailed = false)
        assertTrue(!exists(stray), "R3: a post-disposal callback must delete its copy")
    }

    @Test
    fun disposalDuringLoadDeletesAccumulatedCopies() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)
        val a = makeTempCopy("a.jpg")
        val b = makeTempCopy("b.jpg")

        delegate.beginBatch(2)
        delegate.onItemLoaded(tempPath = a, displayName = "a.jpg", loadFailed = false)
        // The dialog is dismissed while the second provider load is in flight.
        delegate.dispose()
        assertTrue(!exists(a), "R3: disposal deletes every accumulated copy")

        delegate.onItemLoaded(tempPath = b, displayName = "b.jpg", loadFailed = false)
        assertTrue(!exists(b), "R3: the in-flight copy arriving after disposal is deleted too")
        assertEquals(0, results.size)
        assertTrue(delegate.ownedTempPaths().isEmpty())
    }

    @Test
    fun cancelledPickIsACleanNoOp() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)

        delegate.beginBatch(0)
        delegate.finish(MediaPickResult.Cancelled)

        assertEquals(listOf<MediaPickResult>(MediaPickResult.Cancelled), results, "Cancel is delivered (SD1)")
        assertTrue(delegate.ownedTempPaths().isEmpty())
    }

    // ---- Success: ownership transfers to the store (D4 consumption kept) ----

    @Test
    fun successfulBatchTransfersOwnershipToTheStore() {
        val results = mutableListOf<MediaPickResult>()
        val delegate = delegate(results)
        val a = makeTempCopy("ok-a.jpg")
        val b = makeTempCopy("ok-b.jpg")

        delegate.beginBatch(2)
        delegate.onItemLoaded(tempPath = a, displayName = "ok-a.jpg", loadFailed = false)
        delegate.onItemLoaded(tempPath = b, displayName = "ok-b.jpg", loadFailed = false)

        val result = results.single()
        assertTrue(result is MediaPickResult.Picked, "A complete batch delivers Picked")
        val picked = result as MediaPickResult.Picked
        assertEquals(listOf(a, b), picked.photos.map { it.handle }, "Handles are the real temp copies (D4)")

        // Real transient-file consumption: the copies stay readable for the
        // media store, which deletes them at stage time.
        assertTrue(exists(a) && exists(b), "Success keeps the copies alive for staging (D4)")
        assertTrue(delegate.ownedTempPaths().isEmpty(), "Ownership has transferred to the store (R3)")

        // A later dispose must not delete the handed-off copies.
        delegate.dispose()
        assertTrue(exists(a) && exists(b), "R3: post-handoff disposal never deletes store-owned handles")
    }
}
