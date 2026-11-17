package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.data.media.ExerciseMediaSessionRegistry
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.util.MediaPickResult
import com.devil.phoenixproject.util.PickedPhoto
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Behavior tests for the issue #1358 shared save coordinator (T27, T28,
 * SD6/SD7). Real temp files via [AndroidExerciseMediaStore]; the repository is
 * the in-memory [FakeExerciseRepository] with an injected row-transaction
 * failure seam — no mocks on the file lifecycle paths (signoff downstream
 * acceptance).
 */
class ExerciseMediaSaveCoordinatorTest {

    private lateinit var root: File
    private lateinit var sources: File
    private lateinit var store: AndroidExerciseMediaStore
    private lateinit var repository: FakeExerciseRepository

    @Before
    fun setup() {
        root = File.createTempFile("media-coordinator-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        sources = File(root, "_sources").also { it.mkdirs() }
        store = AndroidExerciseMediaStore(mediaRoot = File(root, "media"), openPickedStream = { handle ->
            File(handle).takeIf { it.isFile }?.inputStream()
        })
        repository = FakeExerciseRepository()
        // Coordinator staging runs on Dispatchers.Main; Unconfined makes the
        // pick->stage tail complete before onPickResult returns.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private val jpegHeader = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    )

    /**
     * Structurally COMPLETE tiny JPEG (finding A: fixtures are valid input,
     * never prefix+garbage): header + body + EOI terminator.
     */
    private fun payload(size: Int = 64): ByteArray {
        val bytes = ByteArray(maxOf(size, 16)) { (it * 17 + 3).toByte() }
        jpegHeader.copyInto(bytes)
        bytes[bytes.size - 2] = 0xFF.toByte() // EOI
        bytes[bytes.size - 1] = 0xD9.toByte()
        return bytes
    }

    private fun sourceFile(name: String): File =
        File(sources, name).also { it.writeBytes(payload()) }

    private fun picked(name: String): PickedPhoto =
        PickedPhoto(handle = sourceFile(name).absolutePath, displayName = name, byteSize = null)

    private fun draft(id: String? = null) = Exercise(
        id = id,
        name = "Cable Curl",
        muscleGroup = "Biceps",
        muscleGroups = "Biceps",
        equipment = "CABLE",
        isCustom = true,
    )

    // ---- T27: picker Cancel / late staging callback after disposal mutates nothing ----

    @Test
    fun lateStagingCallbackAfterCancelMutatesNothing() {
        // Deterministic race: the staging coroutine is queued, Cancel/dismiss
        // runs first, and only then does the queued copy execute (SD2).
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val coordinator = ExerciseMediaSaveCoordinator(store, ioDispatcher = Dispatchers.Unconfined, sessionRegistry = ExerciseMediaSessionRegistry())

        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("late.jpg"))), existingCount = 0)
        coordinator.discardAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            coordinator.stagedAdditions.isEmpty(),
            "A late staging callback after Cancel/dismiss must not mutate the draft (T27)",
        )
        assertTrue(coordinator.queuedRemovals.isEmpty(), "Cancel must leave no queued removals (T27)")
        assertEquals(MediaPhase.Idle, coordinator.phase, "A late callback must not resurrect a disposed session's phase (T27)")
    }

    @Test
    fun cancelledPickIsCleanNoOp() {
        val coordinator = ExerciseMediaSaveCoordinator(store, ioDispatcher = Dispatchers.Unconfined, sessionRegistry = ExerciseMediaSessionRegistry())
        coordinator.onPickResult(MediaPickResult.Cancelled, existingCount = 0)
        assertTrue(coordinator.stagedAdditions.isEmpty())
        assertEquals(MediaPhase.Idle, coordinator.phase)
        assertEquals(null, coordinator.errorMessage, "Cancel is never an error (SD1)")
    }

    // ---- T28: create-success / media-failure keeps the minted id; retry never duplicates ----

    @Test
    fun rowTransactionFailureKeepsMintedIdAndRetryCommitsExactlyOnce() = runBlocking {
        val coordinator = ExerciseMediaSaveCoordinator(store, ioDispatcher = Dispatchers.Unconfined, sessionRegistry = ExerciseMediaSessionRegistry())
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("a.jpg"))), existingCount = 0)
        assertEquals(1, coordinator.stagedAdditions.size)

        // Real row-transaction failure after the exercise saved (SD6).
        repository.applyMediaDeltaResult = Result.failure(IllegalStateException("injected row transaction failure"))
        val first = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(first is CustomExerciseSaveOutcome.MediaFailed, "A row failure must surface MediaFailed, not a silent save (T28)")

        val minted = coordinator.savedExerciseId
        assertNotNull(minted, "The minted exercise id must be retained after a media failure (T28/SD6)")
        assertEquals(1, repository.getAllExercises().first().size, "The exercise must exist exactly once (T28)")
        assertTrue(
            File(coordinator.stagedAdditions.first().stagedPath).isFile,
            "The staged draft must survive a row-transaction failure so retry can commit it (SD6/T28)",
        )

        // Retry targets the SAME exercise id and commits the media exactly once.
        repository.applyMediaDeltaResult = null
        val second = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(second is CustomExerciseSaveOutcome.Saved)
        assertEquals(minted, (second as CustomExerciseSaveOutcome.Saved).exercise.id, "Retry must target the minted id (SD6)")
        assertEquals(1, repository.getAllExercises().first().size, "Retry must never recreate the exercise (T28/SD6)")
        assertEquals(1, repository.getImages(minted).size, "Retry must not duplicate media rows (T28)")
    }

    @Test
    fun vanishedStagedPickFailsRetryablyWithoutSilentPruning() = runBlocking {
        // Merge-gate finding C: a staged pick whose copy vanished is NEVER
        // silently pruned and must never reduce the saved selection.
        val coordinator = ExerciseMediaSaveCoordinator(store, ioDispatcher = Dispatchers.Unconfined, sessionRegistry = ExerciseMediaSessionRegistry())
        coordinator.onPickResult(
            MediaPickResult.Picked(listOf(picked("keep.jpg"), picked("lost.jpg"))),
            existingCount = 0,
        )
        assertEquals(2, coordinator.stagedAdditions.size)
        // The second pick's staged copy is lost externally — it can never commit.
        File(coordinator.stagedAdditions[1].stagedPath).delete()

        val outcome = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)

        assertTrue(
            outcome is CustomExerciseSaveOutcome.Failed,
            "A vanished staged pick must fail the save as a retryable error (finding C) — never Saved with less media",
        )
        assertEquals(
            2,
            coordinator.stagedAdditions.size,
            "The intended selection must be preserved, never silently pruned (finding C)",
        )
        assertNotNull(coordinator.errorMessage, "The error must report the photos needing re-selection (finding C)")
        assertTrue(
            coordinator.errorMessage!!.contains("lost.jpg"),
            "The error must name the missing photo (finding C: 'report which photos need re-selection')",
        )
        assertEquals(0, repository.getAllExercises().first().size, "A missing-photo failure creates nothing")

        // Retryable: once the lost pick's copy exists again (the user re-picked
        // it), the SAME draft saves with the FULL selection.
        File(coordinator.stagedAdditions[1].stagedPath).writeBytes(payload())
        val retry = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(retry is CustomExerciseSaveOutcome.Saved, "The save must succeed once the selection is complete again")
        val saved = retry as CustomExerciseSaveOutcome.Saved
        assertEquals(
            2,
            repository.getImages(saved.exercise.id!!).size,
            "The full intended selection commits on retry (finding C)",
        )
    }

    // ---- SD7: row removal succeeds but file cleanup fails -> "cleanup pending" debt ----

    @Test
    fun fileCleanupFailureAfterRowRemovalSurfacesCleanupPendingAndDebt() = runBlocking {
        val coordinator = ExerciseMediaSaveCoordinator(store, ioDispatcher = Dispatchers.Unconfined, sessionRegistry = ExerciseMediaSessionRegistry())
        // Pre-existing exercise with one saved photo.
        repository.addExercise(draft(id = "custom-9"))
        repository.addImage("custom-9", "/media/custom-9/saved.jpg", 0)
        val row = repository.getImages("custom-9").first()

        // An honest file-delete failure without mocks: a non-empty directory
        // cannot be deleted with File.delete() (SD7 / T29 pattern).
        val stubborn = File(store.exerciseDirPath("custom-9"), "stubborn").also { it.mkdirs() }
        File(stubborn, "child.jpg").writeBytes(payload(8))
        coordinator.queueRemove(row.id, stubborn.absolutePath)

        val outcome = coordinator.saveWithMedia(draft(id = "custom-9"), editingExerciseId = "custom-9", repository = repository)

        assertTrue(outcome is CustomExerciseSaveOutcome.Saved)
        assertTrue(
            (outcome as CustomExerciseSaveOutcome.Saved).cleanupPending,
            "A failed file cleanup must surface cleanup pending, never false success (SD7/T29)",
        )
        assertTrue(coordinator.cleanupPendingNotice, "The UI notice must reflect cleanup pending (SD7)")
        assertEquals(0, repository.getImages("custom-9").size, "Committed row removals are never replayed (SD7)")
        assertEquals(1, store.drainCleanupDebt(), "The failed delete must be recorded as retryable cleanup debt (SD7/T29)")
    }

    // ---- merge-gate finding D: off-main import, live Copying phase, real progress ----

    @Test
    fun slowProviderCopyRunsOffCallerThreadAndNeverBlocksIt() = runBlocking {
        val callerThread = Thread.currentThread().name
        val gate = java.util.concurrent.CountDownLatch(1)
        val copyThreads = mutableListOf<String>()
        val slowStore = AndroidExerciseMediaStore(
            mediaRoot = File(root, "media"),
            openPickedStream = { handle ->
                copyThreads += Thread.currentThread().name
                val raw = File(handle).inputStream()
                object : java.io.InputStream() {
                    override fun read(): Int {
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        return raw.read()
                    }
                }
            },
        )
        val coordinator = ExerciseMediaSaveCoordinator(
            slowStore,
            ioDispatcher = Dispatchers.IO,
            sessionRegistry = ExerciseMediaSessionRegistry(),
        )
        val done = java.util.concurrent.CountDownLatch(1)
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("slow.jpg"))), existingCount = 0) {
            done.countDown()
        }

        // The caller thread is already past onPickResult while the copy is
        // blocked on the slow provider — import I/O must never block Main, and
        // the Copying phase must be live so the dialog can disable Save.
        assertEquals(
            MediaPhase.Copying(0, 1),
            coordinator.phase,
            "The copy phase must be live while copying (finding D — Save disables on it)",
        )
        gate.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "Staging must complete once the provider unblocks")
        assertEquals(1, coordinator.stagedAdditions.size)
        assertEquals(MediaPhase.Idle, coordinator.phase)
        assertTrue(
            copyThreads.isNotEmpty() && copyThreads.all { it != callerThread },
            "Import I/O must run off the caller/main thread (finding D)",
        )
    }
}
