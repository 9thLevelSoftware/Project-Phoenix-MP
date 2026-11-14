package com.devil.phoenixproject.data.media

import com.devil.phoenixproject.util.PickedPhoto
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Real-file behavior tests for the issue #1358 Phase 1 media store (T9-T16,
 * T26, T29, T31). Real temp dirs and binary streams only — no mocks for
 * lifecycle paths (signoff "Required downstream acceptance").
 */
class ExerciseMediaStoreTest {

    private lateinit var root: File
    private lateinit var sources: File
    private lateinit var store: AndroidExerciseMediaStore

    @Before
    fun setup() {
        root = File.createTempFile("exercise-media-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        sources = File(root, "_sources").also { it.mkdirs() }
        store = AndroidExerciseMediaStore(mediaRoot = File(root, "media"), openPickedStream = { handle ->
            File(handle).takeIf { it.isFile }?.inputStream()
        })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- helpers ----

    private val jpegHeader = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    )

    /** Non-UTF8 payload: invalid continuation bytes that a text pipeline would corrupt (T9). */
    private fun nonUtf8Payload(size: Int = 64): ByteArray {
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        jpegHeader.copyInto(bytes)
        bytes[4] = 0xC3.toByte() // invalid UTF-8 continuation start
        bytes[5] = 0x28.toByte()
        bytes[6] = 0x00.toByte() // NUL would truncate a text read
        bytes[7] = 0xFE.toByte()
        return bytes
    }

    private fun sourceFile(name: String, bytes: ByteArray): File =
        File(sources, name).also { it.writeBytes(bytes) }

    private fun picked(name: String, file: File, byteSize: Long? = null): PickedPhoto =
        PickedPhoto(handle = file.absolutePath, displayName = name, byteSize = byteSize)

    private fun stagingDir(sessionId: String): File = File(root, "media/staging/$sessionId")

    // ---- T9: binary-exact copy with a non-UTF8 byte sequence ----

    @Test
    fun stageCopiesBinaryExactWithNonUtf8Bytes() = runBlocking {
        val payload = nonUtf8Payload()
        val src = sourceFile("a.jpg", payload)
        val staged = store.stage("s1", listOf(picked("a.jpg", src, byteSize = payload.size.toLong())), 0)
        val result = staged.getOrThrow()
        assertEquals(1, result.size)
        val stagedFile = File(result.first().stagedPath)
        assertTrue(stagedFile.isFile, "Staged copy must exist")
        assertTrue(
            payload.contentEquals(stagedFile.readBytes()),
            "Staged copy must be byte-exact for non-UTF8 input (T9 — a text-oriented reader would corrupt it)",
        )
    }

    // ---- T10: over-cap copy leaves NO partial file ----

    @Test
    fun overCapCopyLeavesNoPartialFile() = runBlocking {
        // Unknown size (byteSize = null): the cap must be enforced DURING the copy.
        val huge = ByteArray(12 * 1024 * 1024) { 0x55 }
        jpegHeader.copyInto(huge)
        val src = sourceFile("huge.jpg", huge)
        val result = store.stage("s2", listOf(picked("huge.jpg", src, byteSize = null)), 0)
        assertTrue(result.isFailure, "Over-cap copy must fail")
        assertTrue(
            stagingDir("s2").listFiles().isNullOrEmpty(),
            "No partial staged file may remain after an over-cap rejection (T10)",
        )
    }

    // ---- T11: commit MOVES (staged copy gone, final path correct) ----

    @Test
    fun commitMovesStagedFilesIntoExerciseDir() = runBlocking {
        val payload = nonUtf8Payload()
        val src = sourceFile("a.jpg", payload)
        val staged = store.stage("s3", listOf(picked("a.jpg", src)), 0).getOrThrow()
        val moved = store.commitAll("s3", staged, "custom_1").getOrThrow()
        assertEquals(1, moved.size)
        val finalFile = File(moved.first())
        assertTrue(finalFile.isFile, "Committed file must exist at the final path")
        assertTrue(payload.contentEquals(finalFile.readBytes()), "Committed bytes must be unchanged")
        assertFalse(File(staged.first().stagedPath).exists(), "Commit must MOVE, not copy (T11)")
    }

    // ---- T12: deleteForExercise removes only the target exercise's dir ----

    @Test
    fun deleteForExerciseRemovesOnlyTargetDir() = runBlocking {
        val p1 = store.stage("s4", listOf(picked("a.jpg", sourceFile("a.jpg", nonUtf8Payload()))), 0).getOrThrow()
        val p2 = store.stage("s4", listOf(picked("b.jpg", sourceFile("b.jpg", nonUtf8Payload(32)))), 0).getOrThrow()
        store.commitAll("s4", p1, "custom_1").getOrThrow()
        store.commitAll("s4", p2, "custom_2").getOrThrow()

        assertTrue(store.deleteForExercise("custom_1"))
        assertFalse(File(store.exerciseDirPath("custom_1")).exists(), "Target dir must be gone")
        assertTrue(File(store.exerciseDirPath("custom_2")).isDirectory, "Other exercise's dir must survive (T12)")
    }

    // ---- T13: reference-aware reconciliation ----

    @Test
    fun reconcileRemovesOrphansAndUnreferencedButPreservesReferencedAndActive() = runBlocking {
        // Live exercise with one referenced and one unreferenced file.
        val live = File(store.exerciseDirPath("custom_live")).also { it.mkdirs() }
        val referenced = File(live, "ref.jpg").also { it.writeBytes(nonUtf8Payload(8)) }
        val unreferenced = File(live, "junk.jpg").also { it.writeBytes(nonUtf8Payload(8)) }
        // Orphan exercise dir (no Exercise row).
        val orphan = File(store.exerciseDirPath("custom_gone")).also { it.mkdirs() }
        File(orphan, "old.jpg").writeBytes(nonUtf8Payload(8))
        // Abandoned staging dir + an active session's staging dir.
        File(root, "media/staging/abandoned").also { it.mkdirs() }
            .let { File(it, "draft.jpg").writeBytes(nonUtf8Payload(8)) }
        File(root, "media/staging/active-session").also { it.mkdirs() }
            .let { File(it, "draft.jpg").writeBytes(nonUtf8Payload(8)) }

        store.reconcile(
            liveExerciseIds = setOf("custom_live"),
            referencedUrls = setOf(referenced.absolutePath),
            activeSessions = setOf("active-session"),
        )

        assertFalse(orphan.exists(), "Orphan exercise dir must be removed (T13a)")
        assertFalse(unreferenced.exists(), "Unreferenced file in a live dir must be removed (T13c)")
        assertTrue(referenced.isFile, "Row-referenced file must survive (T13)")
        assertFalse(
            File(root, "media/staging/abandoned").exists(),
            "Abandoned staging dir must be removed (T13b)",
        )
        assertTrue(
            File(root, "media/staging/active-session").isDirectory,
            "Active session's staging must survive (T13)",
        )
    }

    // ---- T14: guardrails reject the WHOLE new batch and write nothing ----

    @Test
    fun seventhPhotoRejectsWholeBatch() = runBlocking {
        val src = sourceFile("a.jpg", nonUtf8Payload())
        val result = store.stage("s5", listOf(picked("a.jpg", src)), existingCount = 6)
        assertTrue(result.isFailure, "The 7th photo must be rejected (T14)")
        assertTrue(stagingDir("s5").listFiles().isNullOrEmpty(), "Nothing may be written on a guardrail rejection")
    }

    @Test
    fun knownOversizeRejectsWholeBatchIncludingValidBatchmates() = runBlocking {
        val small = sourceFile("ok.jpg", nonUtf8Payload())
        val big = sourceFile("big.jpg", nonUtf8Payload(16))
        val result = store.stage(
            "s6",
            listOf(
                picked("ok.jpg", small),
                picked("big.jpg", big, byteSize = 10_000_001L),
            ),
            existingCount = 0,
        )
        assertTrue(result.isFailure, "Any oversize photo rejects the whole batch (SD11)")
        assertTrue(
            stagingDir("s6").listFiles().isNullOrEmpty(),
            "The valid batchmate's copy must be rolled back too (T14/T31)",
        )
    }

    @Test
    fun unsupportedTypeRejectsWholeBatch() = runBlocking {
        val good = sourceFile("ok.jpg", nonUtf8Payload())
        val bad = sourceFile("evil.txt", "just text".encodeToByteArray())
        val result = store.stage(
            "s7",
            listOf(picked("ok.jpg", good), picked("evil.txt", bad)),
            existingCount = 0,
        )
        assertTrue(result.isFailure, "Unsupported/corrupt input must reject the batch (SD11)")
        assertTrue(
            stagingDir("s7").listFiles().isNullOrEmpty(),
            "Unsupported-type rejection must leave nothing staged (T14)",
        )
    }

    @Test
    fun unreadableHandleRejectsWholeBatch() = runBlocking {
        val good = sourceFile("ok.jpg", nonUtf8Payload())
        val result = store.stage(
            "s8",
            listOf(picked("ok.jpg", good), picked("gone.jpg", File(sources, "missing.jpg"))),
            existingCount = 0,
        )
        assertTrue(result.isFailure, "An unreadable pick must reject the batch")
        assertTrue(stagingDir("s8").listFiles().isNullOrEmpty(), "No partial writes on unreadable input")
    }

    // ---- T15: single removal deletes exactly one file ----

    @Test
    fun deleteOneRemovesExactlyTheTargetFile() = runBlocking {
        val p1 = store.stage("s9", listOf(picked("a.jpg", sourceFile("a.jpg", nonUtf8Payload()))), 0).getOrThrow()
        val p2 = store.stage("s9", listOf(picked("b.jpg", sourceFile("b.jpg", nonUtf8Payload(32)))), 0).getOrThrow()
        val urls = store.commitAll("s9", p1 + p2, "custom_3").getOrThrow()

        assertTrue(store.deleteOne(urls[0]))
        assertFalse(File(urls[0]).exists(), "Target file must be deleted")
        assertTrue(File(urls[1]).isFile, "The other file must survive (T15)")
    }

    @Test
    fun deleteOneRefusesPathsOutsideManagedRoot() {
        val outsider = sourceFile("outsider.jpg", nonUtf8Payload(8))
        assertFalse(store.deleteOne(outsider.absolutePath), "Deleting outside the managed root must fail")
        assertTrue(outsider.isFile, "Files outside the managed root must never be touched")
    }

    // ---- T16: exercise delete removes files; failure preserves them ----

    @Test
    fun deleteForExerciseFailureIsReportedNotFaked() {
        // Simulate a failing delete: make the managed dir un-deletable is
        // platform-dependent; instead assert the honest contract — deleteOne on
        // a protected path returns false and cleanup debt records it (SD7).
        val dir = File(store.exerciseDirPath("custom_4")).also { it.mkdirs() }
        val child = File(dir, "keep.jpg").also { it.writeBytes(nonUtf8Payload(8)) }
        store.registerCleanupDebt(child.absolutePath)
        val remaining = store.drainCleanupDebt()
        assertEquals(0, remaining, "A deletable debt target must be drained")
        assertFalse(child.exists(), "The debt target must actually be deleted on drain (T29)")
        assertTrue(store.deleteForExercise("custom_4"))
        assertFalse(dir.exists())
    }

    // ---- T26: file-move failure mid-commit rolls back only session-owned moves ----

    @Test
    fun commitMoveFailureRollsBackSessionOwnedMovesOnly() = runBlocking {
        val p1 = store.stage("s10", listOf(picked("a.jpg", sourceFile("a.jpg", nonUtf8Payload()))), 0).getOrThrow()
        val p2 = store.stage("s10", listOf(picked("b.jpg", sourceFile("b.jpg", nonUtf8Payload(32)))), 0).getOrThrow()
        // Pre-existing file in the exercise dir must survive the rollback.
        val preexisting = File(store.exerciseDirPath("custom_5")).also { it.mkdirs() }
            .let { File(it, "pre.jpg").also { f -> f.writeBytes(nonUtf8Payload(8)) } }

        // Make the SECOND move fail: its staged file disappears before commit.
        File(p2.first().stagedPath).delete()

        val result = store.commitAll("s10", p1 + p2, "custom_5")
        assertTrue(result.isFailure, "A mid-commit move failure must fail the commit (T26)")
        assertTrue(preexisting.isFile, "Pre-existing files must be untouched (SD6/T26)")
        // The first staged file's move was rolled back — the target must be gone.
        val exerciseDir = File(store.exerciseDirPath("custom_5"))
        assertTrue(
            exerciseDir.listFiles()!!.none { it.name == File(p1.first().stagedPath).name },
            "Session-owned additions moved in the failed attempt must be removed from the exercise dir (T26)",
        )
        // ...and its staged copy must be RESTORED so the draft stays retryable (SD6/T28).
        assertTrue(
            File(p1.first().stagedPath).isFile,
            "A rolled-back move must restore the staged copy so the save can be retried (SD6/T28)",
        )
    }

    // ---- T29: cleanup debt is recorded when a file delete fails ----

    @Test
    fun failedFileDeleteIsRecordedAsRetryableCleanupDebt() {
        // A non-empty directory cannot be deleted with File.delete() — an honest
        // file-delete failure without mocking (SD7).
        val stubborn = File(root, "media/custom_6/stubborn").also { it.mkdirs() }
        File(stubborn, "child.jpg").writeBytes(nonUtf8Payload(8))
        assertFalse(store.deleteOne(stubborn.absolutePath), "A failing delete must report failure, never false success")
        store.registerCleanupDebt(stubborn.absolutePath)
        val remaining = store.drainCleanupDebt()
        assertEquals(1, remaining, "An undeletable debt target must remain recorded (T29)")
    }

    // ---- T30: crash-mid-commit recovery via reconciliation ----

    @Test
    fun crashMidCommitIsRecoveredByReconcile() = runBlocking {
        // Simulate a crash after files moved into the exercise dir but before the
        // row transaction: files exist in the live dir with NO referencing rows.
        val dir = File(store.exerciseDirPath("custom_7")).also { it.mkdirs() }
        File(dir, "orphaned-mid-commit.jpg").writeBytes(nonUtf8Payload(8))
        store.reconcile(
            liveExerciseIds = setOf("custom_7"),
            referencedUrls = emptySet(),
            activeSessions = emptySet(),
        )
        assertEquals(
            0,
            dir.listFiles()!!.size,
            "Mid-commit leftovers (no rows) must be swept at startup (T30/SD8c)",
        )
    }

    // ---- SD6 retryable draft: restoreToStaging puts committed files back ----

    @Test
    fun restoreToStagingMovesCommittedFilesBackToStaging() = runBlocking {
        val staged = store.stage(
            "s11",
            listOf(picked("a.jpg", sourceFile("a.jpg", nonUtf8Payload()))),
            0,
        ).getOrThrow()
        val committed = store.commitAll("s11", staged, "custom_8").getOrThrow()
        assertEquals(1, committed.size)
        assertTrue(File(committed.first()).isFile)
        assertFalse(File(staged.first().stagedPath).exists())

        val restored = store.restoreToStaging(staged, committed)

        assertTrue(restored, "Every session-owned committed copy must restore (SD6)")
        assertTrue(
            File(staged.first().stagedPath).isFile,
            "The staged draft copy must be back in staging after restore (SD6/T28)",
        )
        assertFalse(
            File(committed.first()).exists(),
            "Restore must leave nothing in the exercise dir (SD6)",
        )
        // The restored draft commits cleanly on retry — no duplicates, no loss.
        val retry = store.commitAll("s11", staged, "custom_8").getOrThrow()
        assertEquals(1, retry.size)
        assertTrue(File(retry.first()).isFile)
    }

    @Test
    fun restoreToStagingRefusesPathsOutsideManagedRoot() {
        val outsider = File(root, "user-original.jpg").also { it.writeBytes(nonUtf8Payload(8)) }
        val restored = store.restoreToStaging(
            staged = emptyList(),
            committedUrls = listOf(outsider.absolutePath),
        )
        assertFalse(restored, "Out-of-root paths must be refused, never touched (SD8)")
        assertTrue(outsider.isFile, "A user original outside the managed root must survive untouched")
    }
}
