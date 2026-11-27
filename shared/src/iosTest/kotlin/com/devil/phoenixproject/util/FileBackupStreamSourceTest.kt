@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

class FileBackupStreamSourceTest {

    private fun writeBytes(bytes: ByteArray): String {
        val path = "${NSTemporaryDirectory()}backup_stream_${NSUUID().UUIDString}.json"
        val file = fopen(path, "wb") ?: error("fopen failed for $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
        return path
    }

    @Test
    fun multiByteUtf8SplitAcross8KbChunkBoundaryDecodesCleanly() {
        val prefix = "a".repeat(8190)
        val emoji = "\uD83D\uDD25" // 4-byte UTF-8 sequence straddling 8192 boundary (bytes 8190..8193)
        val suffix = "b".repeat(128)
        val expected = prefix + emoji + suffix
        val path = writeBytes(expected.encodeToByteArray())
        val source = FileBackupStreamSource(path)
        try {
            source.open()
            val out = StringBuilder()
            val buf = CharArray(512)
            while (true) {
                val read = source.read(buf, 0, buf.size)
                if (read < 0) break
                out.appendRange(buf, 0, read)
            }
            assertEquals(expected, out.toString())
        } finally {
            source.close()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun missingFileReadThrowsBackupStreamReadFailedInsteadOfEof() {
        val missingPath = "${NSTemporaryDirectory()}missing_stream_${NSUUID().UUIDString}.json"
        val source = FileBackupStreamSource(missingPath)
        source.open()
        try {
            val error = assertFailsWith<IllegalStateException> {
                source.read()
            }
            assertTrue(
                error.message?.startsWith("Backup stream read failed:") == true,
                "Expected stream read error message, got: ${error.message}",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun emptyFileReturnsCleanEof() {
        val path = writeBytes(ByteArray(0))
        val source = FileBackupStreamSource(path)
        source.open()
        try {
            val buf = CharArray(16)
            assertEquals(0, source.read(buf, 0, 0))
            assertEquals(-1, source.read())
            assertEquals(-1, source.read(buf, 0, buf.size))
        } finally {
            source.close()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}
