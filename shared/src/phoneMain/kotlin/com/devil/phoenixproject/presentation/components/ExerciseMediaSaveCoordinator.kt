package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.media.ExerciseMediaSessionRegistry
import com.devil.phoenixproject.data.media.ExerciseMediaStore
import com.devil.phoenixproject.data.media.MediaGuardrailError
import com.devil.phoenixproject.data.media.MediaGuardrailException
import com.devil.phoenixproject.data.media.RemovedImageRef
import com.devil.phoenixproject.data.media.StagedMedia
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.MediaPickResult
import com.devil.phoenixproject.util.PickedPhoto
import com.devil.phoenixproject.util.releasePickerOwnedHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.media_error_generic
import projectphoenix.shared.generated.resources.media_error_missing_staged
import projectphoenix.shared.generated.resources.media_error_too_large
import projectphoenix.shared.generated.resources.media_error_too_many
import projectphoenix.shared.generated.resources.media_error_unreadable
import projectphoenix.shared.generated.resources.media_error_unsupported
import projectphoenix.shared.generated.resources.media_save_busy

private val log = Logger.withTag("ExerciseMediaSave")

/** Save-boundary outcome surfaced to the dialog (P4/SD6/SD7). */
sealed interface CustomExerciseSaveOutcome {
    /** Exercise and media rows are committed. [cleanupPending] = SD7 debt surfaced. */
    data class Saved(val exercise: Exercise, val cleanupPending: Boolean) : CustomExerciseSaveOutcome

    /**
     * The exercise itself is saved (minted id retained — SD6) but media commit
     * failed. Retry targets the SAME exercise id and never recreates the
     * exercise or duplicates rows.
     */
    data class MediaFailed(val exercise: Exercise) : CustomExerciseSaveOutcome

    /** The exercise create/update failed; the draft stays retryable. */
    data class Failed(val message: String) : CustomExerciseSaveOutcome
}

/** Phase of the session-scoped media draft (SD5). */
sealed interface MediaPhase {
    data object Idle : MediaPhase
    data object Picking : MediaPhase
    data class Copying(val done: Int, val total: Int) : MediaPhase
    data object Saving : MediaPhase
    data object Error : MediaPhase
}

/**
 * Session-scoped save coordinator owning media files and BOTH custom-exercise
 * dialog entry points (issue #1358, SD5/F2).
 *
 * Owns: session UUID, staged additions, queued removals, phase, and the
 * commit/rollback tail shared by `ExercisePicker` and `SingleExerciseScreen`.
 *
 * Draft semantics (SD2): picks copy into session staging at pick time and
 * removals are only QUEUED; final placement + row writes + queued-removal
 * deletes happen only at the Save boundary. Cancel/dismiss ([discardAll])
 * discards staged copies and queued removals only — saved rows and files are
 * never mutated.
 */
class ExerciseMediaSaveCoordinator(
    private val mediaStore: ExerciseMediaStore,
    /** Finding D: blocking import I/O runs here — never on the Main dispatcher. */
    private val ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    /** Finding B: import/commit sessions coordinate with the cleanup sweep here. */
    private val sessionRegistry: ExerciseMediaSessionRegistry = ExerciseMediaSessionRegistry.shared,
    /**
     * R3: release a picker-owned transient handle whose pick never reached
     * staging (a cancelled/disposed session). Idempotent — a handle the media
     * store already consumed at stage time is already gone.
     */
    private val releasePickerHandle: (PickedPhoto) -> Unit = { releasePickerOwnedHandle(it) },
) {
    val sessionId: String = generateUUID()

    /** Session scope for staging copies; cancelled work mutates nothing after disposal. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
    )
    private var stagingJob: Job? = null
    private var disposed = false

    /**
     * R3: picks handed to an in-flight staging job. The media store consumes
     * their transient handles at stage time; if the session is cancelled or
     * disposed before staging ever runs, [discardAll] releases them instead so
     * no picker-owned temp copy outlives its session.
     */
    private var pendingPicks: List<PickedPhoto> = emptyList()

    var phase by mutableStateOf<MediaPhase>(MediaPhase.Idle)
        private set

    /** Session-staged photo copies in pick order (draft, not persistence). */
    val stagedAdditions = mutableStateListOf<StagedMedia>()

    /** Saved photos queued for removal — applied only at Save (SD2/SD3). */
    val queuedRemovals = mutableStateListOf<RemovedImageRef>()

    /** Inline error copy for the attachments UI. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** SD7: set when file cleanup failed after a committed row change ("cleanup pending"). */
    var cleanupPendingNotice by mutableStateOf(false)
        private set

    /**
     * Minted id of an exercise created in THIS session whose media commit is
     * still pending (SD6): a retry must target this id and never re-create.
     */
    var savedExerciseId by mutableStateOf<String?>(null)
        private set

    /**
     * R1: the picker's busy state is owned from LAUNCH — not from the terminal
     * result. iOS dismisses PHPicker while provider loads still run, so the
     * whole launch → provider loading → staging → terminal-result window is one
     * owned busy session: the dialog disables Save on [MediaPhase.Picking] and
     * [saveWithMedia] refuses a save inside it.
     */
    fun onPickerLaunched() {
        if (disposed) return
        when (phase) {
            MediaPhase.Idle, MediaPhase.Error, MediaPhase.Picking -> {
                phase = MediaPhase.Picking
                errorMessage = null
            }
            else -> {} // Copying/Saving: an owned session is already in flight.
        }
    }

    fun onPickResult(
        result: MediaPickResult,
        existingCount: Int,
        onDone: () -> Unit = {},
    ) {
        if (disposed) {
            // T27/R1: a late terminal result after Cancel/dismiss mutates
            // nothing. R3: a picked batch still owns its transient handles —
            // release them since staging will never consume them.
            if (result is MediaPickResult.Picked) {
                result.photos.forEach(releasePickerHandle)
            }
            return
        }
        when (result) {
            is MediaPickResult.Cancelled -> {
                // Clean no-op (SD1): no error UI, no state change.
                if (phase is MediaPhase.Picking) phase = MediaPhase.Idle
                onDone()
            }
            is MediaPickResult.Error -> {
                phase = MediaPhase.Error
                errorMessage = result.message
                onDone()
            }
            is MediaPickResult.Picked -> {
                stagePhotos(result.photos, existingCount, onDone)
            }
        }
    }

    private fun stagePhotos(
        photos: List<PickedPhoto>,
        existingCount: Int,
        onDone: () -> Unit,
    ) {
        phase = MediaPhase.Copying(0, photos.size)
        val targetCount = existingCount + stagedAdditions.size
        // R3: the store consumes the picker handles at stage time; until then
        // this session owns them (released by discardAll if staging never runs).
        pendingPicks = photos
        stagingJob = scope.launch {
            // Finding D: blocking import I/O runs OFF the Main dispatcher, and
            // the copy reports real per-file progress. Finding B: staging is a
            // live import session — the startup sweep never observes it.
            val result = withContext(ioDispatcher) {
                sessionRegistry.withActiveSession(sessionId) {
                    mediaStore.stage(sessionId, photos, targetCount) { done, total ->
                        phase = MediaPhase.Copying(done, total)
                    }
                }
            }
            // stage() consumed the picker handles on every exit that ran
            // (success, whole-batch rejection, in-stage cancellation).
            pendingPicks = emptyList()
            if (disposed) return@launch // T27: late callback after disposal mutates nothing
            result.fold(
                onSuccess = { staged ->
                    stagedAdditions.addAll(staged)
                    phase = MediaPhase.Idle
                    errorMessage = null
                    onDone()
                },
                onFailure = { error ->
                    phase = MediaPhase.Error
                    errorMessage = guardrailMessage(error)
                    onDone()
                },
            )
        }
    }

    /**
     * User-facing guardrail copy (whole-batch rejection — SD11: nothing from
     * the rejected batch was written). Localized via compose-resources (P6).
     */
    private suspend fun guardrailMessage(error: Throwable): String = when (
        val guardrail = (error as? MediaGuardrailException)?.guardrail
    ) {
        is MediaGuardrailError.TooManyPhotos ->
            org.jetbrains.compose.resources.getString(Res.string.media_error_too_many)
        is MediaGuardrailError.PhotoTooLarge ->
            org.jetbrains.compose.resources.getString(Res.string.media_error_too_large, guardrail.displayName)
        is MediaGuardrailError.UnsupportedType ->
            org.jetbrains.compose.resources.getString(Res.string.media_error_unsupported, guardrail.displayName)
        is MediaGuardrailError.Unreadable ->
            org.jetbrains.compose.resources.getString(Res.string.media_error_unreadable, guardrail.displayName)
        null -> org.jetbrains.compose.resources.getString(Res.string.media_error_generic)
    }

    /**
     * Finding C: user-facing copy naming the photos that need re-selection.
     * Localized via compose-resources (P6); falls back to the same copy when
     * resource resolution is unavailable (e.g. host unit tests).
     */
    private suspend fun missingStagedMessage(names: String): String =
        runCatching {
            org.jetbrains.compose.resources.getString(Res.string.media_error_missing_staged, names)
        }.getOrNull() ?: "Not saved: $names could not be found. Remove or re-pick them, then try again."

    /**
     * R1: user-facing copy for a save attempted while a picker/staging session
     * is still outstanding. Same localization fallback as [missingStagedMessage].
     */
    private suspend fun pickerBusyMessage(): String =
        runCatching {
            org.jetbrains.compose.resources.getString(Res.string.media_save_busy)
        }.getOrNull() ?: "Photos are still being added. Wait for the selection to finish, then save."

    /** Queue one saved photo for removal at Save (SD2). */
    fun queueRemove(imageId: Long, localUrl: String) {
        if (queuedRemovals.none { it.imageId == imageId }) {
            queuedRemovals.add(RemovedImageRef(imageId, localUrl))
        }
    }

    fun isQueuedForRemoval(imageId: Long): Boolean = queuedRemovals.any { it.imageId == imageId }

    /**
     * SD2 rollback: discard staged copies and queued removals ONLY. Saved rows
     * and files are never mutated by Cancel/dismiss. Finding D: an in-flight
     * copy is cancelled and owns its partials. Finding B: the live session is
     * released so the startup sweep may reclaim its staging dir afterwards.
     */
    fun discardAll() {
        disposed = true
        stagingJob?.cancel()
        scope.launch { sessionRegistry.releaseSession(sessionId) }
        // R3: a staging job cancelled before the store consumed the picker
        // handles leaves them owned by this session — release every pending
        // pick so no picker-owned temp copy outlives cancellation/disposal.
        // Idempotent: already-consumed handles are already gone.
        pendingPicks.forEach(releasePickerHandle)
        pendingPicks = emptyList()
        mediaStore.discardStaging(sessionId)
        stagedAdditions.clear()
        queuedRemovals.clear()
        phase = MediaPhase.Idle
        errorMessage = null
    }

    /**
     * The shared commit/rollback tail (SD5 — extracted so both dialog entry
     * points run one identical save flow):
     *
     * 1. create/update exercise (skipped when SD6 already minted an id this
     *    session);
     * 2. MOVE staged files into the exercise dir (before rows — signoff #4);
     * 3. ONE transactional row delta (SD3);
     * 4. delete queued-removal files best-effort (SD7 cleanup debt on failure).
     *
     * Failure at any point leaves a retryable draft and never duplicates the
     * exercise or rows (SD6).
     */
    suspend fun saveWithMedia(
        draftExercise: Exercise,
        editingExerciseId: String?,
        repository: ExerciseRepository,
    ): CustomExerciseSaveOutcome {
        // R1: coordinator admission boundary mirroring the dialog's Save guard.
        // An outstanding picker session (launch → provider loading → terminal
        // result) or in-flight staging/save owns the busy state; a save inside
        // that window is refused before anything is created, moved or written,
        // so a slow provider load can never produce a save with an empty stage.
        if (
            phase is MediaPhase.Picking ||
            phase is MediaPhase.Copying ||
            phase is MediaPhase.Saving
        ) {
            return CustomExerciseSaveOutcome.Failed(pickerBusyMessage())
        }
        phase = MediaPhase.Saving
        // Finding C: a pick whose staged copy vanished (external delete) is
        // NEVER silently pruned. The intended selection is preserved exactly as
        // the user made it, and the save fails with a retryable error naming the
        // photos that need re-selection — never a silent success with less
        // media, and never before the user fixes or removes the dead pick.
        val missing = stagedAdditions.filter { !mediaStore.stagedCopyExists(it.stagedPath) }
        if (missing.isNotEmpty()) {
            phase = MediaPhase.Error
            errorMessage = missingStagedMessage(missing.joinToString(", ") { it.displayName })
            return CustomExerciseSaveOutcome.Failed(errorMessage ?: "Selected photos are missing")
        }
        return try {
            val exercise: Exercise
            val minted = savedExerciseId
            if (minted != null) {
                // SD6 retry: exercise already exists — media only, same id.
                exercise = repository.getExerciseById(minted)
                    ?: draftExercise.copy(id = minted, isCustom = true)
            } else {
                val action = resolveCustomExerciseSaveAction(draftExercise, editingExerciseId)
                val saved = when (action) {
                    is CustomExerciseSaveAction.Create -> repository.createCustomExercise(action.exercise)
                    is CustomExerciseSaveAction.Update -> repository.updateCustomExercise(action.exercise)
                }
                val savedExercise = saved.getOrNull()
                if (savedExercise == null) {
                    phase = MediaPhase.Error
                    return CustomExerciseSaveOutcome.Failed(
                        saved.exceptionOrNull()?.message ?: "Could not save the exercise",
                    )
                }
                exercise = savedExercise
                savedExerciseId = exercise.id
            }

            val exerciseId = exercise.id
                ?: return CustomExerciseSaveOutcome.Failed("Saved exercise has no id")

            val additions = stagedAdditions.toList()
            val removals = queuedRemovals.toList()

            // Media commit: files move first (signoff #4), rows in one
            // transaction (SD3). On failure only THIS session's moved files are
            // rolled back (SD6) and the minted id is kept. Finding B: the whole
            // move+row window runs as an in-flight commit session — the startup
            // sweep can neither observe a stale snapshot nor delete just-moved,
            // not-yet-referenced files.
            var commitFailed = false
            var committedUrls: List<String> = emptyList()
            sessionRegistry.withActiveCommit(sessionId, exerciseId) {
                if (additions.isNotEmpty()) {
                    val moved = withContext(ioDispatcher) {
                        mediaStore.commitAll(sessionId, additions, exerciseId)
                    }
                    val urls = moved.getOrNull()
                    if (urls == null) {
                        log.w(moved.exceptionOrNull()) { "Media commit failed for $exerciseId" }
                        // Rollback of moved files happens inside commitAll. Keep the
                        // staged copies so a retry can commit them to the same id.
                        commitFailed = true
                    } else {
                        committedUrls = urls
                    }
                }
                if (!commitFailed && (committedUrls.isNotEmpty() || removals.isNotEmpty())) {
                    val delta = repository.applyMediaDelta(
                        exerciseId = exerciseId,
                        additionUrls = committedUrls,
                        removalIds = removals.map { it.imageId },
                    )
                    if (delta.isFailure) {
                        // Real row-transaction failure: restore only THIS session's
                        // moved files back to staging so the draft stays retryable
                        // (SD6/T28); never touch pre-existing rows/files.
                        withContext(ioDispatcher) {
                            mediaStore.restoreToStaging(additions, committedUrls)
                        }
                        log.w(delta.exceptionOrNull()) { "Row delta failed for $exerciseId" }
                        commitFailed = true
                    }
                }
            }
            if (commitFailed) {
                phase = MediaPhase.Error
                return CustomExerciseSaveOutcome.MediaFailed(exercise)
            }

            // Rows committed. Best-effort file cleanup for queued removals
            // (SD7): a failure surfaces "cleanup pending" and records retryable
            // cleanup debt — never replays committed row mutations.
            var cleanupPending = false
            removals.forEach { removal ->
                if (!mediaStore.deleteOne(removal.localUrl)) {
                    mediaStore.registerCleanupDebt(removal.localUrl)
                    cleanupPending = true
                }
            }
            stagedAdditions.clear()
            queuedRemovals.clear()
            phase = MediaPhase.Idle
            errorMessage = null
            cleanupPendingNotice = cleanupPending
            // Finding B: rows reference the committed files now — end the
            // session's sweep protection.
            sessionRegistry.releaseSession(sessionId)
            CustomExerciseSaveOutcome.Saved(exercise, cleanupPending)
        } catch (e: Exception) {
            log.w(e) { "saveWithMedia failed" }
            phase = MediaPhase.Error
            CustomExerciseSaveOutcome.Failed(e.message ?: "Could not save the exercise")
        }
    }

    /**
     * Delete tail shared by both entry points (P4): after a successful
     * `deleteCustomExercise` (rows cascade), remove the managed files. A DB
     * failure preserves files (SD7); a file failure surfaces the transient
     * cleanup status and is retried by the sweep.
     */
    suspend fun deleteExerciseWithMedia(
        exerciseId: String,
        repository: ExerciseRepository,
    ): CustomExerciseSaveOutcome {
        val deleted = repository.deleteCustomExercise(exerciseId)
        if (deleted.isFailure) {
            // Failed DB delete preserves files for retry (SD7).
            return CustomExerciseSaveOutcome.Failed(
                deleted.exceptionOrNull()?.message ?: "Could not delete the exercise",
            )
        }
        val cleanupPending = !mediaStore.deleteForExercise(exerciseId)
        if (cleanupPending) {
            mediaStore.registerCleanupDebt(mediaStore.exerciseDirPath(exerciseId))
        }
        cleanupPendingNotice = cleanupPending
        discardAll()
        return CustomExerciseSaveOutcome.Saved(
            Exercise(name = "", muscleGroup = "", muscleGroups = "", equipment = "", isCustom = true),
            cleanupPending = cleanupPending,
        )
    }
}
