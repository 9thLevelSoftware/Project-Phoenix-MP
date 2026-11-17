package com.devil.phoenixproject.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Format-aware still-image validation (issue #1358, merge-gate finding A).
 *
 * The merge gate ran a byte-exact probe of the old `isSupportedImageMagic` and
 * caught it accepting non-images. These tests pin the REJECT cases from that
 * probe — two-byte `BM`, a three-byte JPEG prefix, `ftyp/mp42` — plus
 * corrupt/truncated completeness rules, and the accepted complete inputs.
 */
class StillImageValidatorTest {

    private fun jpeg(size: Int = 32): ByteArray {
        val bytes = ByteArray(size) { (it * 13 + 5).toByte() }
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).copyInto(bytes)
        bytes[bytes.size - 2] = 0xFF.toByte()
        bytes[bytes.size - 1] = 0xD9.toByte()
        return bytes
    }

    private fun validate(bytes: ByteArray): Boolean =
        StillImageValidator.validate(bytes, bytes.size.toLong(), bytes)

    // ---- the gate's executed probe cases must NOT validate ----

    @Test
    fun twoByteBmpPrefixIsNotAValidImage() {
        val bytes = byteArrayOf('B'.code.toByte(), 'M'.code.toByte())
        assertEquals(StillImageFormat.BMP, StillImageValidator.detectFormat(bytes), "BM is recognized as BMP-ish")
        assertFalse(validate(bytes), "A two-byte prefix is not a decodable image (gate probe: two-byte BM)")
    }

    @Test
    fun threeByteJpegPrefixIsNotAValidImage() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertEquals(StillImageFormat.JPEG, StillImageValidator.detectFormat(bytes))
        assertFalse(validate(bytes), "A three-byte JPEG prefix is not a decodable image (gate probe)")
    }

    @Test
    fun ftypVideoBrandIsRejected() {
        // ftyp/mp42 — a video container. The old magic check accepted ANY ftyp.
        val bytes = ByteArray(32) { 0 }
        byteArrayOf(0, 0, 0, 0x20).copyInto(bytes)
        "ftypmp42".encodeToByteArray().copyInto(bytes, 4)
        assertNull(StillImageValidator.detectFormat(bytes), "A misleading video brand must not pass as a still image")
        assertFalse(validate(bytes))
    }

    @Test
    fun plainTextIsRejected() {
        assertNull(StillImageValidator.detectFormat("just text".encodeToByteArray()))
    }

    @Test
    fun truncatedJpegWithoutEoiIsRejected() {
        val bytes = jpeg(32).copyOf(30) // EOI cut off
        assertFalse(validate(bytes), "Truncated input must be rejected (finding A)")
    }

    @Test
    fun gifNeedsItsTrailerByte() {
        val head = "GIF89a".encodeToByteArray() + ByteArray(20) { 1 }
        assertFalse(validate(head), "A GIF without the 0x3B trailer is incomplete")
        assertTrue(validate(head + byteArrayOf(0x3B.toByte())))
    }

    @Test
    fun pngNeedsIend() {
        val head = ByteArray(45) { 2 }
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(head)
        assertFalse(validate(head), "A PNG without an IEND chunk is incomplete")
        // IEND chunk type at size-8, then its 4-byte CRC.
        val complete = head + "IEND".encodeToByteArray() + ByteArray(4) { 0 }
        assertTrue(validate(complete))
    }

    // ---- accepted complete inputs ----

    @Test
    fun completeTinyJpegValidates() {
        assertTrue(validate(jpeg()), "A structurally complete JPEG must pass")
    }

    @Test
    fun heicStillBrandValidatesStructurally() {
        val bytes = ByteArray(32) { 0 }
        byteArrayOf(0, 0, 0, 0x20).copyInto(bytes)
        "ftypheic".encodeToByteArray().copyInto(bytes, 4)
        assertEquals(StillImageFormat.HEIC_AVIF, StillImageValidator.detectFormat(bytes))
        assertTrue(validate(bytes))
    }

    @Test
    fun rollingTailKeepsOnlyTheFinalBytes() {
        val tail = RollingTail(8)
        tail.append(byteArrayOf(1, 2, 3, 4))
        tail.append(byteArrayOf(5, 6, 7, 8, 9))
        assertTrue(
            byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9).contentEquals(tail.snapshot()),
            "The rolling tail must hold exactly the last bytes written",
        )
    }
}
