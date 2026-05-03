package com.devil.phoenixproject.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSInputStream

/**
 * iOS [BackupStreamSource] backed by an [NSInputStream] with UTF-8 decoding.
 *
 * Reads bytes in 8 KB chunks, decodes to characters on demand, and correctly
 * handles UTF-8 multi-byte sequences that may be split across chunk boundaries
 * by keeping leftover incomplete bytes for the next read.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileBackupStreamSource(private val filePath: String) : BackupStreamSource {
    private var stream: NSInputStream? = null
    private val byteBuffer = ByteArray(8192)

    /**
     * Leftover bytes from the previous [refill] call that form the leading
     * portion of an incomplete UTF-8 multi-byte sequence. These are prepended
     * to the next raw read so the decoder always sees complete characters.
     */
    private var leftover = ByteArray(0)

    // Decoded character buffer
    private var charBuf = CharArray(0)
    private var charPos = 0
    private var charLen = 0
    private var eof = false

    override fun open() {
        stream = NSInputStream(uRL = platform.Foundation.NSURL.fileURLWithPath(filePath))
        stream?.open()
    }

    override fun close() {
        stream?.close()
        stream = null
        leftover = ByteArray(0)
        charBuf = CharArray(0)
        charPos = 0
        charLen = 0
        eof = false
    }

    override fun read(): Int {
        if (charPos < charLen) return charBuf[charPos++].code
        if (eof) return -1
        refill()
        return if (charPos < charLen) charBuf[charPos++].code else -1
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var totalRead = 0
        while (totalRead < length) {
            if (charPos >= charLen) {
                if (eof) break
                refill()
                if (charPos >= charLen) break
            }
            val available = charLen - charPos
            val toRead = minOf(available, length - totalRead)
            charBuf.copyInto(buffer, offset + totalRead, charPos, charPos + toRead)
            charPos += toRead
            totalRead += toRead
        }
        return if (totalRead == 0 && eof) -1 else totalRead
    }

    /**
     * Reads a chunk of raw bytes from the stream, prepends any [leftover]
     * bytes from a previous incomplete UTF-8 sequence, determines the safe
     * decode boundary, and fills [charBuf] with the decoded characters.
     */
    private fun refill() {
        val s = stream ?: return

        // Read raw bytes from NSInputStream via pinned ByteArray.
        // NSInputStream.read expects CPointer<UByteVar> (uint8_t*);
        // addressOf(0) on a pinned ByteArray yields CPointer<ByteVar>,
        // which is layout-compatible, so the reinterpret cast is safe.
        @Suppress("UNCHECKED_CAST")
        val bytesRead = byteBuffer.usePinned { pinned ->
            s.read(
                pinned.addressOf(0) as kotlinx.cinterop.CPointer<platform.posix.uint8_tVar>,
                maxLength = byteBuffer.size.convert(),
            )
        }

        if (bytesRead <= 0) {
            // On EOF, decode any remaining leftover bytes (may produce a
            // replacement character if the file was truncated mid-sequence).
            if (leftover.isNotEmpty()) {
                val decoded = leftover.decodeToString()
                charBuf = decoded.toCharArray()
                charPos = 0
                charLen = charBuf.size
                leftover = ByteArray(0)
            }
            eof = true
            return
        }

        // Combine leftover bytes from the previous read with fresh bytes.
        val raw: ByteArray
        val rawLen: Int
        if (leftover.isNotEmpty()) {
            rawLen = leftover.size + bytesRead.toInt()
            raw = ByteArray(rawLen)
            leftover.copyInto(raw)
            byteBuffer.copyInto(raw, leftover.size, 0, bytesRead.toInt())
            leftover = ByteArray(0)
        } else {
            raw = byteBuffer
            rawLen = bytesRead.toInt()
        }

        // Find the safe decode boundary: the last position in raw[0..rawLen)
        // that does not split a multi-byte UTF-8 sequence.
        val safeBound = findUtf8SafeBoundary(raw, rawLen)

        // Stash any trailing incomplete bytes for the next refill.
        if (safeBound < rawLen) {
            leftover = raw.copyOfRange(safeBound, rawLen)
        }

        // Decode the safe portion to characters.
        val decoded = raw.decodeToString(0, safeBound)
        charBuf = decoded.toCharArray()
        charPos = 0
        charLen = charBuf.size
    }

    /**
     * Scans backwards from the end of [data] (up to [length] bytes) to find
     * the boundary that does not split a multi-byte UTF-8 sequence.
     *
     * UTF-8 encoding:
     * - 0xxxxxxx  (0x00-0x7F): single-byte (ASCII)
     * - 110xxxxx  (0xC0-0xDF): 2-byte lead
     * - 1110xxxx  (0xE0-0xEF): 3-byte lead
     * - 11110xxx  (0xF0-0xF7): 4-byte lead
     * - 10xxxxxx  (0x80-0xBF): continuation byte
     *
     * We scan back at most 3 bytes (max continuation run in a 4-byte sequence)
     * to find the last lead byte, then verify whether enough continuation bytes
     * follow to complete the character.
     */
    private fun findUtf8SafeBoundary(data: ByteArray, length: Int): Int {
        if (length == 0) return 0

        // Walk backwards at most 3 positions looking for a lead byte.
        val scanStart = maxOf(0, length - 3)
        for (i in (length - 1) downTo scanStart) {
            val b = data[i].toInt() and 0xFF
            if (b and 0x80 == 0) {
                // Single-byte ASCII -- always complete; safe boundary is after it.
                return length
            }
            if (b and 0xC0 != 0x80) {
                // This is a lead byte (110..., 1110..., or 11110...).
                val expectedLen = when {
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1 // Malformed; treat as single byte.
                }
                val available = length - i
                return if (available >= expectedLen) {
                    // The multi-byte sequence is complete.
                    length
                } else {
                    // Incomplete sequence at the tail; split before it.
                    i
                }
            }
            // b is a continuation byte (10xxxxxx); keep scanning backwards.
        }
        // Entire tail is continuation bytes with no lead byte found in range.
        // This is malformed UTF-8; decode everything and let the decoder handle it.
        return length
    }
}
