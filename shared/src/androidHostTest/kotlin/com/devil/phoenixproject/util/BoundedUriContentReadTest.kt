package com.devil.phoenixproject.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Issue #772: a picked routine CSV is never read past its size limit. */
class BoundedUriContentReadTest {
    @Test
    fun aDocumentUpToTheLimitIsReadAsUtf8() {
        val text = "﻿routine,Übung\n"
        val bytes = text.toByteArray(Charsets.UTF_8)

        val read = assertIs<BoundedUriContent.Read>(readUpTo(ByteArrayInputStream(bytes), maxBytes = bytes.size))
        assertEquals(text, read.content)
    }

    @Test
    fun readingStopsOnceTheLimitIsPassed() {
        val source = CountingStream(size = 10 * 1024 * 1024)

        assertEquals(BoundedUriContent.TooLarge, readUpTo(source, maxBytes = 2 * 1024 * 1024))
        assertEquals(true, source.consumed <= 2 * 1024 * 1024 + 8 * 1024, "consumed ${source.consumed} bytes")
    }

    @Test
    fun oneByteOverTheLimitIsTooLarge() {
        assertEquals(BoundedUriContent.TooLarge, readUpTo(ByteArrayInputStream(ByteArray(101)), maxBytes = 100))
    }

    /** An endless-looking source that records how much was read from it. */
    private class CountingStream(private val size: Int) : InputStream() {
        var consumed = 0
            private set

        override fun read(): Int = if (consumed < size) {
            consumed++
            'x'.code
        } else {
            -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= size) return -1
            val count = minOf(len, size - consumed)
            b.fill('x'.code.toByte(), off, off + count)
            consumed += count
            return count
        }
    }
}
