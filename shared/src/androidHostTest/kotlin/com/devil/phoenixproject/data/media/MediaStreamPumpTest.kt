package com.devil.phoenixproject.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Deterministic stream-failure injection for the shared still-image copy pump
 * (issue #1358, merge-gate finding E). The iOS store's copy loop routes every
 * read/write/finalization through [MediaStreamPump], so these tests exercise
 * the exact production semantics the merge gate flagged: a negative stream
 * read is an ERROR and must fail the copy — never a silent EOF that stages a
 * truncated file — and write/finalization failures must propagate so the
 * caller compensates the entire batch.
 */
class MediaStreamPumpTest {

    /**
     * Scripted chunk reader: each step is the value returned to the pump —
     * >0 = that many bytes, 0 = EOF, <0 = the stream's error signal.
     */
    private class ScriptedReader(vararg script: Int) {
        private val steps = ArrayDeque(script.toList())
        val observed = mutableListOf<Int>()
        fun read(buffer: ByteArray): Int {
            val n = steps.removeFirst()
            observed += n
            for (i in 0 until n.coerceAtMost(buffer.size)) buffer[i] = i.toByte()
            return n
        }
    }

    @Test
    fun cleanEofEndsTheCopyAndFinalizes() {
        val reader = ScriptedReader(4, 3, 0)
        val writtenChunks = mutableListOf<Int>()
        var finalized = 0
        val written = MediaStreamPump("a.jpg", maxBytes = 1024).copy(
            readChunk = reader::read,
            writeChunk = { _, count -> writtenChunks += count },
            finalizeOutput = { finalized++ },
        )
        assertEquals(7L, written, "All bytes before a clean EOF are copied")
        assertEquals(listOf(4, 3), writtenChunks)
        assertEquals(1, finalized, "Output finalization runs exactly once")
    }

    @Test
    fun negativeReadIsAStreamErrorNeverEof() {
        // The gate's exact scenario: chunks arrive, then the stream reports an
        // error (negative read) mid-copy. This must FAIL the copy — the old
        // `if (read <= 0) break` treated it as EOF and staged a truncated file.
        val reader = ScriptedReader(4, -1)
        var finalized = 0
        val error = assertFailsWith<MediaStreamReadException> {
            MediaStreamPump("a.jpg", maxBytes = 1024).copy(
                readChunk = reader::read,
                writeChunk = { _, _ -> },
                finalizeOutput = { finalized++ },
            )
        }
        assertTrue(error.message!!.contains("a.jpg"), "The error names the file")
        assertEquals(listOf(4, -1), reader.observed, "The negative read is reached and surfaced")
        assertEquals(0, finalized, "A failed copy never claims successful finalization")
    }

    @Test
    fun writeFailurePropagates() {
        val reader = ScriptedReader(4, 0)
        assertFailsWith<MediaStreamWriteException> {
            MediaStreamPump("a.jpg", maxBytes = 1024).copy(
                readChunk = reader::read,
                writeChunk = { _, _ -> throw MediaStreamWriteException("fwrite failed") },
                finalizeOutput = { },
            )
        }
    }

    @Test
    fun finalizeFailurePropagates() {
        // The unchecked-fclose case: buffered bytes can be lost silently unless
        // finalization failure is an error.
        val reader = ScriptedReader(4, 0)
        assertFailsWith<MediaStreamWriteException> {
            MediaStreamPump("a.jpg", maxBytes = 1024).copy(
                readChunk = reader::read,
                writeChunk = { _, _ -> },
                finalizeOutput = { throw MediaStreamWriteException("fclose failed") },
            )
        }
    }

    @Test
    fun oversizeInputIsAGuardrailRejection() {
        val reader = ScriptedReader(4, 4, 0)
        val error = assertFailsWith<MediaGuardrailException> {
            MediaStreamPump("big.jpg", maxBytes = 5).copy(
                readChunk = reader::read,
                writeChunk = { _, _ -> },
            )
        }
        assertTrue(error.guardrail is MediaGuardrailError.PhotoTooLarge, "Over-cap bytes reject as too large")
    }

    @Test
    fun cancellationCheckpointRunsBeforeEveryRead() {
        var checkpoints = 0
        val reader = ScriptedReader(1, 0)
        MediaStreamPump("a.jpg", maxBytes = 1024).copy(
            readChunk = reader::read,
            writeChunk = { _, _ -> },
            checkpoint = { checkpoints++ },
        )
        assertEquals(2, checkpoints, "The checkpoint runs before every read (finding D cancellation points)")
    }
}
