package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
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
import kotlinx.coroutines.launch
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.media_error_generic
import projectphoenix.shared.generated.resources.media_error_too_large
import projectphoenix.shared.generated.resources.media_error_too_many
import projectphoenix.shared.generated.resources.media_error_unreadable
import projectphoenix.shared.generated.resources.media_error_unsupported

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
) {
    val sessionId: String = generateUUID()

    /** Session scope for staging copies; cancelled work mutates nothing after disposal. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
    )
    private var disposed = false

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

    fun onPickResult(
        result: MediaPickResult,
        existingCount: Int,
        onDone: () -> Unit = {},
    ) {
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
        scope.launch {
            val result = mediaStore.stage(sessionId, photos, existingCount + stagedAdditions.size)
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

    /** Queue one saved photo for removal at Save (SD2). */
    fun queueRemove(imageId: Long, localUrl: String) {
        if (queuedRemovals.none { it.imageId == imageId }) {
            queuedRemovals.add(RemovedImageRef(imageId, localUrl))
        }
    }

    /** Undo a queued removal (draft state only). */
    fun cancelQueuedRemove(imageId: Long) {
        queuedRemovals.removeAll { it.imageId == imageId }
    }

    fun isQueuedForRemoval(imageId: Long): Boolean = queuedRemovals.any { it.imageId == imageId }

    /**
     * SD2 rollback: discard staged copies and queued removals ONLY. Saved rows
     * and files are never mutated by Cancel/dismiss.
     */
    fun discardAll() {
        disposed = true
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
        phase = MediaPhase.Saving
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

            // A pick whose staged copy vanished (external delete) can never
            // commit — drop only the dead entries so the rest of the draft and
            // the save stay retryable (SD6).
            stagedAdditions.removeAll { !mediaStore.stagedCopyExists(it.stagedPath) }

            val additions = stagedAdditions.toList()
            val removals = queuedRemovals.toList()

            // Media commit: files move first (signoff #4), rows in one
            // transaction (SD3). On failure only THIS session's moved files are
            // rolled back (SD6) and the minted id is kept.
            val committedUrls = if (additions.isNotEmpty()) {
                val moved = mediaStore.commitAll(sessionId, additions, exerciseId)
                val urls = moved.getOrNull()
                if (urls == null) {
                    log.w(moved.exceptionOrNull()) { "Media commit failed for $exerciseId" }
                    // Rollback of moved files happens inside commitAll. Keep the
                    // staged copies so a retry can commit them to the same id.
                    phase = MediaPhase.Error
                    return CustomExerciseSaveOutcome.MediaFailed(exercise)
                }
                urls
            } else {
                emptyList()
            }

            if (committedUrls.isNotEmpty() || removals.isNotEmpty()) {
                val delta = repository.applyMediaDelta(
                    exerciseId = exerciseId,
                    additionUrls = committedUrls,
                    removalIds = removals.map { it.imageId },
                )
                if (delta.isFailure) {
                    // Real row-transaction failure: restore only THIS session's
                    // moved files back to staging so the draft stays retryable
                    // (SD6/T28); never touch pre-existing rows/files.
                    mediaStore.restoreToStaging(additions, committedUrls)
                    log.w(delta.exceptionOrNull()) { "Row delta failed for $exerciseId" }
                    phase = MediaPhase.Error
                    return CustomExerciseSaveOutcome.MediaFailed(exercise)
                }
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
