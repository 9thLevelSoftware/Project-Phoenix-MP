package com.devil.phoenixproject.data.media

/**
 * Strict binary stream pump for still-image staging (issue #1358, merge-gate
 * finding E). Shared by the platform stores so the stream semantics live in
 * ONE testable place instead of per-platform copy loops.
 *
 * Read contract for [MediaStreamPump.copy]'s `readChunk`:
 * - `> 0` — that many bytes were read;
 * - `0` — clean end-of-stream (EOF);
 * - `< 0` — the stream reported an ERROR. This is NEVER treated as EOF: the
 *   pump throws [MediaStreamReadException] so an error after an accepted
 *   header can never stage a truncated file as success.
 *
 * `writeChunk` and `finalizeOutput` MUST throw [MediaStreamWriteException] on
 * failure — finalization (flush/close) is always attempted exactly once after
 * the last write and an unchecked close can silently lose buffered bytes.
 *
 * Every exception propagates so the caller can compensate the ENTIRE batch
 * (delete every partial of the batch and fail it), never stage a partial.
 */

/** A stream read reported an error (negative read) — not EOF (finding E). */
class MediaStreamReadException(message: String) : Exception(message)

/** A write or output finalization (flush/close) failed (finding E). */
class MediaStreamWriteException(message: String) : Exception(message)

internal class MediaStreamPump(
    private val displayName: String,
    private val maxBytes: Long = ExerciseMediaLimits.MAX_PHOTO_BYTES,
) {
    /**
     * Streams one file body. Returns bytes written (excluding any pre-written
     * header — callers cap the total via [maxBytes] adjusted for it).
     *
     * @param checkpoint invoked every iteration before the read so callers can
     * honor coroutine cancellation between blocking chunks (finding D).
     * @param onChunk observes every accepted chunk (e.g. rolling tail capture).
     */
    fun copy(
        readChunk: (ByteArray) -> Int,
        writeChunk: (ByteArray, Int) -> Unit,
        finalizeOutput: () -> Unit = {},
        checkpoint: () -> Unit = {},
        onChunk: (ByteArray, Int) -> Unit = { _, _ -> },
    ): Long {
        var written = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            checkpoint()
            val read = readChunk(buffer)
            if (read < 0) {
                throw MediaStreamReadException("Stream read failed for $displayName")
            }
            if (read == 0) break
            written += read
            if (written > maxBytes) {
                throw MediaGuardrailException(MediaGuardrailError.PhotoTooLarge(displayName))
            }
            writeChunk(buffer, read)
            onChunk(buffer, read)
        }
        // Finding E: finalization failures are errors, never silently dropped.
        finalizeOutput()
        return written
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
    }
}
