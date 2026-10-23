package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A cancelled content read propagates out of [readUriContent] and [readUriContentUpTo].
 * [CancellationException] extends [Exception], so the Android readers rethrow it
 * and map every other failure to null / [BoundedUriContent.Unreadable]. Cancelling the
 * caller mid-read interrupts the reading thread, so an interruptible provider read stops
 * instead of running to completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UriContentReaderCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        startKoin {
            modules(module { single<Context> { context } })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun readUriContent_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("picker dismissed")
        register("content://files/routine.csv", ThrowingStream(cancellation))

        val thrown = assertFailsWith<CancellationException> {
            readUriContent("content://files/routine.csv")
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun readUriContentUpTo_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("picker dismissed")
        register("content://files/routine.csv", ThrowingStream(cancellation))

        val thrown = assertFailsWith<CancellationException> {
            readUriContentUpTo("content://files/routine.csv", maxBytes = 100)
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun cancellingReadUriContentInterruptsTheInFlightRead() = runBlocking<Unit> {
        val stream = BlockingStream()
        register("content://files/slow.csv", stream)

        val read = async(Dispatchers.Default) { readUriContent("content://files/slow.csv") }
        assertTrue(stream.started.await(LATCH_SECONDS, TimeUnit.SECONDS), "provider read never started")
        read.cancel()

        assertTrue(stream.interrupted.await(LATCH_SECONDS, TimeUnit.SECONDS), "blocking read was not interrupted")
        assertFailsWith<CancellationException> { read.await() }
    }

    @Test
    fun cancellingReadUriContentUpToInterruptsTheInFlightRead() = runBlocking<Unit> {
        val stream = BlockingStream()
        register("content://files/slow.csv", stream)

        val read = async(Dispatchers.Default) { readUriContentUpTo("content://files/slow.csv", maxBytes = 100) }
        assertTrue(stream.started.await(LATCH_SECONDS, TimeUnit.SECONDS), "provider read never started")
        read.cancel()

        assertTrue(stream.interrupted.await(LATCH_SECONDS, TimeUnit.SECONDS), "blocking read was not interrupted")
        assertFailsWith<CancellationException> { read.await() }
    }

    @Test
    fun otherReadFailuresStayUnreadable() = runTest {
        register("content://files/missing.csv", ThrowingStream(IOException("denied")))
        register("content://files/bounded.csv", ThrowingStream(IOException("denied")))

        assertNull(readUriContent("content://files/missing.csv"))
        assertEquals(
            BoundedUriContent.Unreadable,
            readUriContentUpTo("content://files/bounded.csv", maxBytes = 100),
        )
    }

    @Test
    fun contentWithinTheLimitIsRead() = runTest {
        register("content://files/routine.csv", "a,b\n".byteInputStream())
        register("content://files/bounded.csv", "a,b\n".byteInputStream())

        assertEquals("a,b\n", readUriContent("content://files/routine.csv"))
        assertEquals(
            BoundedUriContent.Read("a,b\n"),
            readUriContentUpTo("content://files/bounded.csv", maxBytes = 100),
        )
    }

    private fun register(uri: String, stream: InputStream) {
        shadowOf(context.contentResolver).registerInputStream(Uri.parse(uri), stream)
    }

    private class ThrowingStream(private val error: Exception) : InputStream() {
        override fun read(): Int = throw error

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw error
    }

    /** Blocks in [read] until the reading thread is interrupted, then rethrows the interrupt. */
    private class BlockingStream : InputStream() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        override fun read(): Int = block()

        override fun read(b: ByteArray, off: Int, len: Int): Int = block()

        private fun block(): Int {
            started.countDown()
            try {
                Thread.sleep(BLOCK_MILLIS)
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
            return -1
        }
    }

    private companion object {
        const val LATCH_SECONDS = 5L

        /** Longer than [LATCH_SECONDS], so an uninterrupted read fails the test instead of passing. */
        const val BLOCK_MILLIS = 15_000L
    }
}
