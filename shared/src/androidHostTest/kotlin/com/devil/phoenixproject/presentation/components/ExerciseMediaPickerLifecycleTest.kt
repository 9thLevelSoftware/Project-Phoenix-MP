package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.data.media.ExerciseMediaSessionRegistry
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.util.MediaPickResult
import com.devil.phoenixproject.util.PickedPhoto
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round-2 merge-gate R1/R3 behavior tests for the picker-lifecycle busy state:
 * the busy state is owned from picker LAUNCH through asynchronous provider
 * loading to the terminal result, Save is rejected inside that window at the
 * coordinator boundary (the dialog's Save guard is checked by
 * `ExerciseMediaWiringTest`), and a cancelled/disposed session releases the
 * picker-owned transient handles staging never consumed (R3).
 */
class ExerciseMediaPickerLifecycleTest {

    private lateinit var root: File
    private lateinit var sources: File
    private lateinit var store: AndroidExerciseMediaStore
    private lateinit var repository: FakeExerciseRepository
    private val released = mutableListOf<String>()

    @Before
    fun setup() {
        root = File.createTempFile("media-picker-lifecycle-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        sources = File(root, "_sources").also { it.mkdirs() }
        store = AndroidExerciseMediaStore(mediaRoot = File(root, "media"), openPickedStream = { handle ->
            File(handle).takeIf { it.isFile }?.inputStream()
        })
        repository = FakeExerciseRepository()
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

    /** Structurally COMPLETE tiny JPEG (finding A). */
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

    private fun coordinator(dispatcher: TestDispatcher? = null): ExerciseMediaSaveCoordinator {
        if (dispatcher != null) Dispatchers.setMain(dispatcher)
        return ExerciseMediaSaveCoordinator(
            store,
            ioDispatcher = Dispatchers.Unconfined,
            sessionRegistry = ExerciseMediaSessionRegistry(),
            releasePickerHandle = { released += it.handle },
        )
    }

    // ---- R1: delayed provider — Save is refused for the whole owned window ----

    @Test
    fun saveDuringDelayedProviderLoadIsRejectedUntilTerminalResult() = runBlocking {
        // The exact R1 interval: iOS dismisses PHPicker while provider loads
        // still run. The busy state is owned from LAUNCH, so the editor is
        // covered even though no result has arrived and staging is empty.
        val coordinator = coordinator()
        coordinator.onPickerLaunched()
        assertEquals(MediaPhase.Picking, coordinator.phase, "Launch must enter Picking (R1)")

        val outcome = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(
            outcome is CustomExerciseSaveOutcome.Failed,
            "A save inside the picker-loading window must be refused at the coordinator (R1) — " +
                "never a success with an empty stage",
        )
        assertEquals(0, repository.getAllExercises().first().size, "The refused save must create nothing (R1)")

        // The delayed provider result finally arrives and the draft saves fully.
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("delayed.jpg"))), existingCount = 0)
        assertEquals(1, coordinator.stagedAdditions.size)
        assertEquals(MediaPhase.Idle, coordinator.phase)
        val saved = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(saved is CustomExerciseSaveOutcome.Saved, "After the terminal result the save must succeed (R1)")
        assertEquals(1, repository.getImages((saved as CustomExerciseSaveOutcome.Saved).exercise.id!!).size)
    }

    @Test
    fun saveDuringStagingWindowIsRefusedAtCoordinatorBoundary() {
        // Staging (Copying) is part of the same owned window: the dialog
        // disables Save on it and the coordinator refuses it too. Hold the
        // window open with a provider blocked mid-copy (finding D pattern).
        val gate = java.util.concurrent.CountDownLatch(1)
        val slowStore = AndroidExerciseMediaStore(
            mediaRoot = File(root, "media"),
            openPickedStream = { handle ->
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
            releasePickerHandle = { released += it.handle },
        )
        val done = java.util.concurrent.CountDownLatch(1)
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("slow.jpg"))), existingCount = 0) {
            done.countDown()
        }
        assertEquals(MediaPhase.Copying(0, 1), coordinator.phase, "Copying must be live while the copy blocks")

        val outcome = runBlocking {
            val refused = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
            assertEquals(0, repository.getAllExercises().first().size)
            refused
        }
        assertTrue(outcome is CustomExerciseSaveOutcome.Failed, "A save during staging must be refused (R1)")

        gate.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "Staging must complete once the provider unblocks")
        val saved = runBlocking {
            coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        }
        assertTrue(saved is CustomExerciseSaveOutcome.Saved, "After staging the same draft saves normally (R1)")
    }

    // ---- R1: cancel / error / late callback across the owned window ----

    @Test
    fun cancelDuringDelayedLoadReturnsToIdleAndSaveWorks() = runBlocking {
        val coordinator = coordinator()
        coordinator.onPickerLaunched()
        coordinator.onPickResult(MediaPickResult.Cancelled, existingCount = 0)
        assertEquals(MediaPhase.Idle, coordinator.phase, "Cancel must end the owned window cleanly (R1/SD1)")
        assertEquals(null, coordinator.errorMessage, "Cancel is never an error (SD1)")

        val saved = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(saved is CustomExerciseSaveOutcome.Saved, "A cancelled pick must leave the draft fully usable")
    }

    @Test
    fun errorDuringDelayedLoadSurfacesErrorAndKeepsDraftRetryable() = runBlocking {
        val coordinator = coordinator()
        coordinator.onPickerLaunched()
        coordinator.onPickResult(MediaPickResult.Error("provider exploded"), existingCount = 0)
        assertEquals(MediaPhase.Error, coordinator.phase, "A picker failure is a terminal Error phase (R1)")
        assertEquals("provider exploded", coordinator.errorMessage)
        assertTrue(coordinator.stagedAdditions.isEmpty(), "A failed session stages nothing")

        // Error is terminal — the draft itself is still retryable.
        val saved = coordinator.saveWithMedia(draft(), editingExerciseId = null, repository = repository)
        assertTrue(saved is CustomExerciseSaveOutcome.Saved, "A picker error must not wedge the draft")
    }

    @Test
    fun lateResultAfterDisposalMutatesNothingButReleasesItsHandles() {
        val coordinator = coordinator()
        coordinator.onPickerLaunched()
        coordinator.discardAll()

        // The delayed provider finally completes AFTER the dialog was dismissed.
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("late.jpg"))), existingCount = 0)
        assertTrue(coordinator.stagedAdditions.isEmpty(), "A late result must not mutate a disposed session (T27/R1)")
        assertEquals(MediaPhase.Idle, coordinator.phase, "A late result must not resurrect the phase (T27/R1)")
        assertTrue(
            released.any { it.endsWith("late.jpg") },
            "R3: the late batch's picker-owned handles must be released, not orphaned — got $released",
        )
    }

    // ---- R3: cancellation/disposal releases picks staging never consumed ----

    @Test
    fun cancelBeforeStagingRunsReleasesPickerOwnedHandles() {
        val dispatcher = StandardTestDispatcher()
        val coordinator = coordinator(dispatcher)
        // The staging job is queued but has not run; Cancel/dismiss happens first.
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("a.jpg"), picked("b.jpg"))), existingCount = 0)
        coordinator.discardAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(coordinator.stagedAdditions.isEmpty())
        assertTrue(
            released.any { it.endsWith("a.jpg") } && released.any { it.endsWith("b.jpg") },
            "R3: picks cancelled before staging must have their transient handles released — got $released",
        )
    }

    @Test
    fun pickerLaunchIsIdempotentAndDisposedSessionsStayDisarmed() {
        val coordinator = coordinator()
        coordinator.onPickerLaunched()
        coordinator.onPickerLaunched()
        assertEquals(MediaPhase.Picking, coordinator.phase, "A repeated launch is one owned session (R1)")

        coordinator.discardAll()
        coordinator.onPickerLaunched()
        assertEquals(MediaPhase.Idle, coordinator.phase, "A disposed session must never re-enter Picking (R1)")
    }

    @Test
    fun launchDuringAnOwnedSessionDoesNotCorruptThePhase() {
        val dispatcher = StandardTestDispatcher()
        val coordinator = coordinator(dispatcher)
        coordinator.onPickResult(MediaPickResult.Picked(listOf(picked("c.jpg"))), existingCount = 0)
        val copying = coordinator.phase
        coordinator.onPickerLaunched()
        assertEquals(copying, coordinator.phase, "A launch during staging must not downgrade Copying (R1)")
        dispatcher.scheduler.advanceUntilIdle()
    }
}
