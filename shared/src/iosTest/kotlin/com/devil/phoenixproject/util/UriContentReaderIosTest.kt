@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class UriContentReaderIosTest {

    @Test
    fun readUriContentAndBoundedReadHandleUtf8AndSizeLimits() = runTest {
        val text = "Bench Press \uD83D\uDCAA 100kg\n"
        val expectedBytes = text.encodeToByteArray().size
        val fileName = "uri_reader_test_${NSUUID().UUIDString}.txt"
        val filePath = assertNotNull(writeUtf8TempFile(fileName, text))
        val missingPath = "${NSTemporaryDirectory()}missing_${NSUUID().UUIDString}.txt"
        try {
            assertEquals(text, readUriContent(filePath))
            assertNull(readUriContent(missingPath))

            assertEquals(BoundedUriContent.Read(text), readUriContentUpTo(filePath, expectedBytes))
            assertEquals(BoundedUriContent.TooLarge, readUriContentUpTo(filePath, expectedBytes - 1))
            assertEquals(BoundedUriContent.Unreadable, readUriContentUpTo(missingPath, 1024))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        }
    }

    @Test
    fun readUriContentAndReadUriContentUpToPropagateCancellation() = runTest {
        val cancel1 = assertFailsWith<CancellationException> {
            readUriContentImpl("/tmp/unused") {
                throw CancellationException("picker cancelled")
            }
        }
        assertEquals("picker cancelled", cancel1.message)

        val cancel2 = assertFailsWith<CancellationException> {
            readUriContentUpToImpl(
                uriOrPath = "/tmp/unused",
                maxBytes = 100,
                fileSizeBytes = { 10L },
                readFile = { throw CancellationException("bounded read cancelled") },
            )
        }
        assertEquals("bounded read cancelled", cancel2.message)

        assertNull(
            readUriContentImpl("/tmp/unused") { throw IllegalStateException("io failure") },
        )
        assertEquals(
            BoundedUriContent.Unreadable,
            readUriContentUpToImpl(
                uriOrPath = "/tmp/unused",
                maxBytes = 100,
                fileSizeBytes = { 10L },
                readFile = { throw IllegalStateException("io failure") },
            ),
        )
    }
}
