package com.devil.phoenixproject.data.media

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Merge-gate finding B: the cleanup snapshot/sweep is coordinated with REAL
 * active import/commit sessions through [ExerciseMediaSessionRegistry] — the
 * production wiring used by `ExerciseMediaReconciler` (startup sweep),
 * `ExerciseMediaSaveCoordinator` (staging + commit) — not a hand-supplied set.
 *
 * The old behavior: `reconcile()` defaulted `activeSessions` to empty and the
 * sweep could delete live staging or just-moved, not-yet-referenced commit
 * files that were absent from its earlier DB snapshot.
 */
class ExerciseMediaSessionRegistryTest {

    private lateinit var root: File
    private lateinit var store: AndroidExerciseMediaStore
    private lateinit var repository: FakeExerciseRepository
    private lateinit var registry: ExerciseMediaSessionRegistry
    private lateinit var reconciler: ExerciseMediaReconciler

    @Before
    fun setup() {
        root = File.createTempFile("media-registry-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        store = AndroidExerciseMediaStore(mediaRoot = File(root, "media"), openPickedStream = { handle ->
            File(handle).takeIf { it.isFile }?.inputStream()
        })
        repository = FakeExerciseRepository()
        registry = ExerciseMediaSessionRegistry()
        reconciler = ExerciseMediaReconciler(repository = repository, mediaStore = store)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun draft(id: String) = Exercise(
        id = id,
        name = "Cable Curl",
        muscleGroup = "Biceps",
        muscleGroups = "Biceps",
        equipment = "CABLE",
        isCustom = true,
    )

    @Test
    fun sweepWaitsForAnInFlightCommitAndProtectsItsJustMovedFiles() = runBlocking {
        repository.addExercise(draft("ex-live"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // A Save's files-move + row-delta window (finding B): the file lands in
        // the exercise dir BEFORE its row exists — the exact window the stale
        // snapshot sweep used to observe and delete.
        val commit = async {
            registry.withActiveCommit("sess-c", "ex-live") {
                val dir = File(store.exerciseDirPath("ex-live")).also { it.mkdirs() }
                val moved = File(dir, "just-moved.jpg").also { it.writeBytes(ByteArray(8) { 1 }) }
                started.complete(Unit)
                release.await()
                // The row delta lands inside the commit window (signoff #4: a
                // successful commit ends with rows referencing its files).
                repository.addImage("ex-live", moved.absolutePath, 0)
            }
        }
        started.await()

        val sweep = async { reconciler.reconcile(registry) }
        yield()
        assertFalse(sweep.isCompleted, "The sweep must NOT run while a commit is in flight (finding B)")
        release.complete(Unit)
        commit.await()
        sweep.await()

        assertTrue(
            File(store.exerciseDirPath("ex-live"), "just-moved.jpg").isFile,
            "The sweep must observe the commit's fresh snapshot and preserve its committed files (finding B)",
        )

        // Non-vacuous: a file NO row references is still reclaimed.
        File(store.exerciseDirPath("ex-live"), "orphan.jpg").writeBytes(ByteArray(8) { 3 })
        reconciler.reconcile(registry)
        assertFalse(
            File(store.exerciseDirPath("ex-live"), "orphan.jpg").exists(),
            "An unreferenced file is reclaimed once nothing protects it (T30/SD8c)",
        )
        assertTrue(
            File(store.exerciseDirPath("ex-live"), "just-moved.jpg").isFile,
            "Row-referenced files always survive the sweep (T13)",
        )
    }

    @Test
    fun sweepPreservesLiveImportStagingUntilTheSessionIsReleased() = runBlocking {
        // A pick-time import registered through the production lifecycle hook.
        registry.withActiveSession("sess-live") {
            val dir = File(root, "media/staging/sess-live").also { it.mkdirs() }
            File(dir, "draft.jpg").writeBytes(ByteArray(8) { 2 })
        }
        // The registration OUTLIVES the copy — the draft sits in staging
        // between pick and Save.
        reconciler.reconcile(registry)
        assertTrue(
            File(root, "media/staging/sess-live").isDirectory,
            "A live import session's staging dir must survive the sweep (finding B)",
        )

        // Non-vacuous: after the dialog releases the session, the sweep
        // reclaims the abandoned staging dir.
        registry.releaseSession("sess-live")
        reconciler.reconcile(registry)
        assertFalse(
            File(root, "media/staging/sess-live").exists(),
            "An abandoned staging dir is reclaimed after release (T13b)",
        )
    }
}
