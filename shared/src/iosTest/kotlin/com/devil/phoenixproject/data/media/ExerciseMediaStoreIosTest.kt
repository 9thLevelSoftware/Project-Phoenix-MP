package com.devil.phoenixproject.data.media

import com.devil.phoenixproject.util.PickedPhoto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

/**
 * Real-file integration tests for the iOS exercise media store (issue #1358,
 * merge-gate findings A and E) on the simulator: structural + real UIImage
 * decode validation, whole-batch compensation, and strict stream semantics.
 * Deterministic read/write/finalization failure injection lives in
 * `MediaStreamPumpTest` (host suite) against the same shared pump this store
 * uses.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class ExerciseMediaStoreIosTest {

    private val fileManager = NSFileManager.defaultManager

    private fun makeRoot(): String {
        val root = "${NSTemporaryDirectory()}/media-ios-test-${NSUUID().UUIDString}"
        fileManager.createDirectoryAtPath(root, true, null, null)
        return root
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("fopen failed for $path")
        try {
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
            }
        } finally {
            fclose(file)
        }
    }

    private fun readFileBytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("missing $path")
        try {
            val chunks = mutableListOf<Byte>()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = buffer.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file)
                }.toInt()
                if (read <= 0) break
                for (i in 0 until read) chunks += buffer[i]
            }
            return chunks.toByteArray()
        } finally {
            fclose(file)
        }
    }

    private fun listDir(path: String): List<String> =
        (fileManager.contentsOfDirectoryAtPath(path, null) as? List<*>)?.filterIsInstance<String>()
            ?: emptyList()

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** Verified 1x1 PNG (IHDR+IDAT+IEND, valid CRCs) — a REAL decodable still image. */
    private val tinyPng = hexToBytes(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489" +
            "0000000d4944415478da6364f8cf500f00038601805a347d6b0000000049454e44ae426082",
    )

    /** Structurally complete JPEG SHAPED bytes that no decoder can decode. */
    private fun undecodableJpeg(size: Int = 32): ByteArray {
        val bytes = ByteArray(size) { (it * 23 + 9).toByte() }
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).copyInto(bytes)
        bytes[bytes.size - 2] = 0xFF.toByte()
        bytes[bytes.size - 1] = 0xD9.toByte()
        return bytes
    }

    @Test
    fun realDecodablePngStagesBinaryExactAndReportsProgress() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            val source = "$root/px.png"
            writeFile(source, tinyPng)
            val progress = mutableListOf<Pair<Int, Int>>()
            val result = store.stage(
                "s-ok",
                listOf(PickedPhoto(handle = source, displayName = "px.png", byteSize = null)),
                existingCount = 0,
            ) { done, total -> progress += done to total }
            val staged = result.getOrThrow()
            assertEquals(1, staged.size)
            val stagedBytes = readFileBytes(staged.first().stagedPath)
            assertEquals(tinyPng.size, stagedBytes.size, "Staged copy must be byte-exact")
            assertTrue(tinyPng.contentEquals(stagedBytes), "Staged bytes must match the source (T9)")
            assertEquals(listOf(1 to 1), progress, "Progress must advance per copied file (finding D)")
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun structurallyPlausibleButUndecodableImageRejectsWholeBatch() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            val source = "$root/fake.jpg"
            writeFile(source, undecodableJpeg())
            val result = store.stage(
                "s-decode",
                listOf(PickedPhoto(handle = source, displayName = "fake.jpg", byteSize = null)),
                existingCount = 0,
            )
            assertTrue(
                result.isFailure,
                "Bytes no real decoder can decode must reject (finding A: decodable images, not prefixes)",
            )
            assertTrue(
                listDir("$root/media/staging/s-decode").isEmpty(),
                "A decode rejection must leave nothing staged (finding A)",
            )
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun truncatedPngRejectsWholeBatchAndStagesNothing() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            val good = "$root/good.png"
            writeFile(good, tinyPng)
            val cut = "$root/cut.png"
            writeFile(cut, tinyPng.copyOf(tinyPng.size - 12)) // IEND chunk cut off
            val result = store.stage(
                "s-trunc",
                listOf(
                    PickedPhoto(handle = good, displayName = "good.png", byteSize = null),
                    PickedPhoto(handle = cut, displayName = "cut.png", byteSize = null),
                ),
                existingCount = 0,
            )
            assertTrue(result.isFailure, "Truncated input must reject the whole batch (finding A)")
            assertTrue(
                listDir("$root/media/staging/s-trunc").isEmpty(),
                "A truncated-file rejection must compensate the whole batch (finding A)",
            )
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun misleadingFtypVideoBrandRejectsWholeBatch() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            val mp4 = ByteArray(64) { 0 }
            byteArrayOf(0, 0, 0, 0x20).copyInto(mp4)
            "ftypmp42".encodeToByteArray().copyInto(mp4, 4)
            val source = "$root/clip.mp4"
            writeFile(source, mp4)
            val result = store.stage(
                "s-mp42",
                listOf(PickedPhoto(handle = source, displayName = "clip.mp4", byteSize = null)),
                existingCount = 0,
            )
            assertTrue(result.isFailure, "A video ftyp brand must never pass as a still image (finding A)")
            assertTrue(listDir("$root/media/staging/s-mp42").isEmpty(), "Nothing may be staged")
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun streamErrorIsAnErrorNotEofAndRejectsWholeBatch() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            // A directory handle makes NSInputStream report a read ERROR
            // (negative read) — which must fail the copy, never pass as EOF and
            // stage an empty/truncated file (finding E).
            val dir = "$root/not-a-file"
            fileManager.createDirectoryAtPath(dir, true, null, null)
            val result = store.stage(
                "s-err",
                listOf(PickedPhoto(handle = dir, displayName = "err.jpg", byteSize = null)),
                existingCount = 0,
            )
            assertTrue(result.isFailure, "A stream read error must fail the batch, never stage a truncated file (finding E)")
            assertTrue(listDir("$root/media/staging/s-err").isEmpty(), "Nothing may be staged")
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun unreadableHandleRejectsWholeBatchAndCompensatesBatchmates() = runBlocking {
        val root = makeRoot()
        try {
            val store = IosExerciseMediaStore(mediaRootPath = "$root/media")
            val good = "$root/good.png"
            writeFile(good, tinyPng)
            val result = store.stage(
                "s-mixed",
                listOf(
                    PickedPhoto(handle = good, displayName = "good.png", byteSize = null),
                    PickedPhoto(handle = "$root/missing.png", displayName = "missing.png", byteSize = null),
                ),
                existingCount = 0,
            )
            assertTrue(result.isFailure, "An unreadable pick must reject the batch")
            assertTrue(
                listDir("$root/media/staging/s-mixed").isEmpty(),
                "The valid batchmate's copy must be rolled back too (SD11)",
            )
        } finally {
            fileManager.removeItemAtPath(root, null)
        }
    }
}
