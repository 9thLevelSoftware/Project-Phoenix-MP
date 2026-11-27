@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.FakePreferencesManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

class BookmarkBase64IosTest {

    private fun dataFromBytes(bytes: ByteArray): NSData {
        val path = "${NSTemporaryDirectory()}bookmark_data_${NSUUID().UUIDString}.bin"
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
        return try {
            assertNotNull(NSData.create(contentsOfFile = path))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun encodeAndDecodeBookmarkRoundTripBinaryDataWithoutLineWrapping() {
        val raw = ByteArray(128) { idx -> (idx * 7).toByte() }
        val original = dataFromBytes(raw)

        val encoded = assertNotNull(encodeBookmarkBase64(original))
        assertTrue(encoded.length > 76, "Encoded payload must span past RFC line length to verify options=0")
        assertFalse(encoded.contains('\n'), "encodeBookmarkBase64 must not wrap lines (options = 0)")
        assertFalse(encoded.contains('\r'), "encodeBookmarkBase64 must not emit CR (options = 0)")

        val decoded = assertNotNull(decodeBookmarkBase64(encoded))
        assertEquals(original.length, decoded.length)
        assertTrue(original.isEqualToData(decoded))

        assertNull(decodeBookmarkBase64("%%%not-valid-base64%%%"))
    }

    @Test
    fun resolverRejectsBlankAndInvalidBookmarksWithoutCrashing() = runTest {
        val resolver = IosBackupDestinationResolver(FakePreferencesManager())
        val blankBookmark = BackupDestination.Custom(
            uri = "file:///tmp/Backups",
            displayName = "Backups",
            bookmarkData = "",
        )
        val corruptBookmark = BackupDestination.Custom(
            uri = "file:///tmp/Backups",
            displayName = "Backups",
            bookmarkData = "%%%corrupt%%%",
        )

        assertFalse(resolver.isAccessible(blankBookmark))
        assertFalse(resolver.isAccessible(corruptBookmark))
        assertTrue(resolver.writeFile(blankBookmark, "b.json", "/tmp/b.json").isFailure)
        assertTrue(resolver.writeFile(corruptBookmark, "b.json", "/tmp/b.json").isFailure)
    }
}
