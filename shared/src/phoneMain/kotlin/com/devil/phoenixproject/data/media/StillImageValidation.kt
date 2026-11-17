package com.devil.phoenixproject.data.media

/**
 * Format-aware still-image validation for picked photos (issue #1358,
 * merge-gate finding A). Signature recognition alone is NOT enough: a prefix
 * can label corrupt, truncated or misleading-brand data (e.g. any `ftyp`
 * box — including video `mp42`). This validator checks real format structure:
 *
 * - a recognized still-image signature ([detectFormat]);
 * - a COMPLETE structure: proper terminators/declared sizes for the detected
 *   format ([isCompleteStillImage]) — truncated files are rejected;
 * - an ftyp brand allowlist — video/container brands are rejected.
 *
 * Platform stores layer an additional real decoder check on top (BitmapFactory
 * on Android, ImageIO on iOS) so bytes that parse structurally but cannot
 * actually decode are also rejected.
 *
 * Byte-position math is done on Long to avoid Int overflow on 10 MB inputs.
 */

/** Still-image formats the store accepts (Phase 1: photos only, never video). */
internal enum class StillImageFormat { JPEG, PNG, GIF, BMP, WEBP, HEIC_AVIF }

internal object StillImageValidator {

    /** ISO-BMFF major brands that denote still images. Anything else (mp42, isom, qt…) is rejected. */
    private val FTPG_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", // HEIF stills
        "mif1", "msf1",                   // HEIF generic stills
        "avif", "avis",                   // AVIF stills
    )

    /**
     * Identify the still-image format from the leading bytes, or null when the
     * input is not a supported still image (unknown signature, GIF version, or
     * a non-image/misleading ftyp brand).
     */
    fun detectFormat(header: ByteArray): StillImageFormat? {
        fun atLeast(n: Int) = header.size >= n
        fun b(i: Int) = header[i]
        fun c(i: Int, ch: Char) = atLeast(i + 1) && b(i) == ch.code.toByte()

        // JPEG: FF D8 FF
        if (atLeast(3) && b(0) == 0xFF.toByte() && b(1) == 0xD8.toByte() && b(2) == 0xFF.toByte()) {
            return StillImageFormat.JPEG
        }
        // PNG: full 8-byte signature
        if (atLeast(8) &&
            b(0) == 0x89.toByte() && b(1) == 0x50.toByte() && b(2) == 0x4E.toByte() && b(3) == 0x47.toByte() &&
            b(4) == 0x0D.toByte() && b(5) == 0x0A.toByte() && b(6) == 0x1A.toByte() && b(7) == 0x0A.toByte()
        ) {
            return StillImageFormat.PNG
        }
        // GIF: GIF87a / GIF89a only (not a bare "GIF8" prefix)
        if (atLeast(6) && c(0, 'G') && c(1, 'I') && c(2, 'F') && c(3, '8') &&
            (b(4) == '7'.code.toByte() || b(4) == '9'.code.toByte()) && b(5) == 'a'.code.toByte()
        ) {
            return StillImageFormat.GIF
        }
        // BMP: "BM" + a full BITMAPFILEHEADER checked structurally below
        if (atLeast(2) && c(0, 'B') && c(1, 'M')) return StillImageFormat.BMP
        // WebP: RIFF....WEBP
        if (atLeast(12) && c(0, 'R') && c(1, 'I') && c(2, 'F') && c(3, 'F') &&
            c(8, 'W') && c(9, 'E') && c(10, 'B') && c(11, 'P')
        ) {
            return StillImageFormat.WEBP
        }
        // HEIC/AVIF: ....ftyp<brand> — brand must name a STILL IMAGE (finding A:
        // "misleading-brand input" — a video brand like mp42 must never pass).
        if (atLeast(12) && c(4, 'f') && c(5, 't') && c(6, 'y') && c(7, 'p')) {
            val brand = header.copyOfRange(8, 12).decodeToString()
            return if (brand in FTPG_BRANDS) StillImageFormat.HEIC_AVIF else null
        }
        return null
    }

    /**
     * Whole-file structural completeness for [format] — the file must END like a
     * complete image of that format (EOI/IEND/trailer) and its declared sizes
     * must fit within [totalBytes]. Corrupt mid-file data is additionally caught
     * by the platform decoder check in each store.
     */
    fun isCompleteStillImage(
        format: StillImageFormat,
        header: ByteArray,
        totalBytes: Long,
        tail: ByteArray,
    ): Boolean {
        return when (format) {
            StillImageFormat.JPEG ->
                // Complete JPEG ends with EOI (FF D9) and cannot be tiny.
                totalBytes >= 12 && tail.size >= 2 &&
                    tail[tail.size - 2] == 0xFF.toByte() && tail[tail.size - 1] == 0xD9.toByte()

            StillImageFormat.PNG ->
                // Signature(8) + IHDR chunk(25) + IEND chunk(12) at minimum;
                // a complete PNG ends with an IEND chunk type.
                totalBytes >= 45 && tail.size >= 12 &&
                    tail[tail.size - 8] == 'I'.code.toByte() &&
                    tail[tail.size - 7] == 'E'.code.toByte() &&
                    tail[tail.size - 6] == 'N'.code.toByte() &&
                    tail[tail.size - 5] == 'D'.code.toByte()

            StillImageFormat.GIF ->
                // Complete GIF ends with the trailer byte 0x3B.
                totalBytes >= 14 && tail.size >= 1 && tail[tail.size - 1] == 0x3B.toByte()

            StillImageFormat.BMP -> {
                if (header.size < 14) return false // a 2-byte "BM" is not a BITMAPFILEHEADER
                val declaredSize = u32le(header, 2)
                val dataOffset = u32le(header, 10)
                totalBytes >= 26 && // BITMAPFILEHEADER + at least a partial info header
                    (declaredSize == 0L || totalBytes >= declaredSize) &&
                    dataOffset >= 14 && dataOffset < totalBytes
            }

            StillImageFormat.WEBP -> {
                if (header.size < 12) return false
                val riffSize = u32le(header, 4)
                // RIFF chunk size counts everything after its own size field.
                totalBytes >= 20 && totalBytes >= riffSize + 8
            }

            StillImageFormat.HEIC_AVIF -> {
                if (header.size < 12) return false
                val ftypBoxSize = u32be(header, 0)
                // The ftyp box must declare a sane size that fits in the file.
                ftypBoxSize >= 16 && totalBytes >= ftypBoxSize && totalBytes >= 24
            }
        }
    }

    /**
     * Full check used by the store's stage path: signature + structure over the
     * bytes actually written ([totalBytes] and the file's [tail]).
     */
    fun validate(header: ByteArray, totalBytes: Long, tail: ByteArray): Boolean {
        val format = detectFormat(header) ?: return false
        return isCompleteStillImage(format, header, totalBytes, tail)
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}

/**
 * Streaming capture of the last [capacity] bytes written, so the structural
 * validator can inspect a file's terminator without re-reading the staged copy.
 */
internal class RollingTail(private val capacity: Int = 32) {
    private val buffer = ByteArray(capacity)
    private var size = 0

    /** Append a whole chunk (e.g. the pre-read header). */
    fun append(chunk: ByteArray) = append(chunk, 0, chunk.size)

    fun append(chunk: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (count >= capacity) {
            chunk.copyInto(buffer, 0, offset + count - capacity, offset + count)
            size = capacity
        } else {
            val keep = minOf(size, capacity - count)
            if (keep > 0) buffer.copyInto(buffer, 0, size - keep, size)
            chunk.copyInto(buffer, keep, offset, offset + count)
            size = keep + count
        }
    }

    fun snapshot(): ByteArray = buffer.copyOf(size)
}
