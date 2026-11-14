package com.devil.phoenixproject.data.media

import com.devil.phoenixproject.util.PickedPhoto

/**
 * Issue #1358 — Phase 1 exercise media file store (phone-only).
 *
 * All file work lives in `phoneMain` presentation-layer collaborators (SD5);
 * `commonMain` never sees a path (F1). Copies are stored app-private; user
 * originals are never modified. Picked handles are consumed at pick time (D4);
 * persistence happens only at the Save boundary (SD2).
 */

/**
 * One photo copied into the session staging directory at pick time.
 * Not persistence: a staged copy rolls back identically to a never-staged one.
 */
data class StagedMedia(
    val stagedPath: String,
    val displayName: String,
    val byteSize: Long,
)

/** A saved photo queued for removal — removed only at Save (SD2/SD3). */
data class RemovedImageRef(
    val imageId: Long,
    val localUrl: String,
)

/**
 * The media delta carried by the widened dialog save payload (SD5). Draft-only:
 * never persisted, never synced (ExerciseImage is in no sync payload).
 */
data class ExerciseMediaDelta(
    val pendingAdditions: List<StagedMedia> = emptyList(),
    val pendingRemovals: List<RemovedImageRef> = emptyList(),
) {
    val isEmpty: Boolean
        get() = pendingAdditions.isEmpty() && pendingRemovals.isEmpty()

    companion object {
        val EMPTY = ExerciseMediaDelta()
    }
}

/** Exact Phase 1 guardrails (SD11): ≤6 photos, ≤10,000,000 bytes (decimal MB) each. */
object ExerciseMediaLimits {
    const val MAX_PHOTOS_PER_EXERCISE = 6
    const val MAX_PHOTO_BYTES: Long = 10_000_000L
}

/** Why a picked batch was rejected. Whole-batch rejection (SD11): nothing is written. */
sealed interface MediaGuardrailError {
    data class TooManyPhotos(val requested: Int, val remaining: Int) : MediaGuardrailError
    data class PhotoTooLarge(val displayName: String) : MediaGuardrailError
    data class UnsupportedType(val displayName: String) : MediaGuardrailError
    data class Unreadable(val displayName: String) : MediaGuardrailError
}

/** Failure carrying a user-presentable guardrail reason. */
class MediaGuardrailException(
    val guardrail: MediaGuardrailError,
) : Exception("Media batch rejected: $guardrail")

/**
 * True when [header] starts with a decodable still-image signature (SD11:
 * decodable-image-type validation; unsupported/corrupt input rejects the batch).
 * Covers JPEG, PNG, GIF, WebP (RIFF/WEBP), BMP, and HEIC/AVIF (ftyp brands).
 */
internal fun isSupportedImageMagic(header: ByteArray): Boolean {
    fun atLeast(n: Int) = header.size >= n
    // JPEG
    if (atLeast(3) && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return true
    // PNG
    if (atLeast(4) && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) return true
    // GIF
    if (atLeast(4) && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()) return true
    // BMP
    if (atLeast(2) && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return true
    // WebP: RIFF....WEBP
    if (atLeast(12) &&
        header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
        header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
        header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
    ) return true
    // HEIC/AVIF: ....ftyp<brand>
    if (atLeast(12) &&
        header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
    ) return true
    return false
}

/**
 * App-private exercise media file store (issue #1358, P3).
 *
 * Layout (managed root is app-private; nothing outside it is ever touched):
 * - `<root>/<exerciseId>/<uuid>.<ext>` — committed per-exercise photos
 * - `<root>/staging/<sessionId>/<uuid>.<ext>` — per-session drafts (SD2)
 *
 * Implementations must never follow symlinks or paths escaping the managed
 * root, never touch user originals or library HTTPS URLs, and must be
 * binary-exact (never route image bytes through text-oriented readers).
 */
interface ExerciseMediaStore {
    /**
     * Copy picked photos into the session staging dir (binary streaming copy,
     * 10,000,000-byte cap enforced DURING each copy).
     *
     * Guardrails run BEFORE any copy (SD11): remaining count ([existingCount]
     * saved photos already on the exercise) and size; any violation rejects the
     * WHOLE new batch — partial copies of this batch are deleted and prior
     * accepted media is untouched.
     */
    suspend fun stage(
        sessionId: String,
        picked: List<PickedPhoto>,
        existingCount: Int,
    ): Result<List<StagedMedia>>

    /**
     * MOVE (not copy) staged files into `<root>/<exerciseId>/` (signoff #4:
     * files land before the row delta). All-or-nothing: on failure, files moved
     * by THIS call are RESTORED to their staged paths so the draft stays
     * retryable (SD6 — the moved copies are removed from the exercise dir,
     * satisfying T26) and the failure is returned.
     * Returns the committed local URLs, in input order.
     */
    fun commitAll(
        sessionId: String,
        staged: List<StagedMedia>,
        exerciseId: String,
    ): Result<List<String>>

    /**
     * Restore committed files back to their session staging paths — the
     * inverse of [commitAll] for files THIS session just moved (SD6 retryable
     * draft), used when the row transaction fails after files landed.
     * Session-owned only: never touches pre-existing rows/files. [staged] and
     * [committedUrls] align by file name. A restore failure falls back to
     * deleting the committed copy so no orphan is left in the exercise dir.
     * Returns true when every committed copy is back in staging (or gone).
     */
    fun restoreToStaging(
        staged: List<StagedMedia>,
        committedUrls: List<String>,
    ): Boolean

    /** Whether a staged draft copy still exists (a pick lost to an external delete can never commit). */
    fun stagedCopyExists(stagedPath: String): Boolean

    /** Delete the managed media directory of [exerciseId] (rows are gone/cascaded). */
    fun deleteForExercise(exerciseId: String): Boolean

    /** Delete one managed file. False when it could not be removed (→ cleanup debt, SD7). */
    fun deleteOne(localUrl: String): Boolean

    /** Drop a session's staged copies (Cancel/dismiss rollback, SD2). */
    fun discardStaging(sessionId: String)

    /**
     * Reference-aware startup reconciliation (SD8). Removes:
     * (a) media dirs whose exerciseId is not in [liveExerciseIds];
     * (b) abandoned staging dirs (sessions not in [activeSessions]);
     * (c) unreferenced files inside live dirs (no row in [referencedUrls]).
     * Preserves referenced files, active-session files (staging dirs AND
     * in-flight exercise ids passed in [activeSessions]), anything outside the
     * managed root, and library/user-original URLs. Never follows symlinks
     * escaping the root; never throws.
     */
    fun reconcile(
        liveExerciseIds: Set<String>,
        referencedUrls: Set<String>,
        activeSessions: Set<String>,
    )

    /** Record a file that failed to delete so the sweep can retry (SD7). */
    fun registerCleanupDebt(localUrl: String)

    /** Retry every recorded cleanup debt; returns how many still remain. */
    fun drainCleanupDebt(): Int

    /** Absolute managed path of an exercise's media dir (for tests/diagnostics). */
    fun exerciseDirPath(exerciseId: String): String
}
