package com.devil.phoenixproject.presentation.manager

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one guarantee [CollectedMetricsBuffer]'s lock exists for, and that the
 * single-threaded tests in `commonTest` cannot reach: the buffer is safe when the
 * live-set collector appends on the main dispatcher while a *different* thread
 * snapshots or clears it.
 *
 * That is not hypothetical. On VBT auto-end the biomechanics continuation is still
 * on `Dispatchers.Default` when it reaches `buildExitSnapshot`, which snapshots both
 * buffers — see the class KDoc for the call chain. Without the lock an unsynchronised
 * `ArrayList` loses appends outright and hands readers torn or null-padded copies.
 *
 * JVM-only (`androidHostTest`) because it needs real threads; `commonTest` has none.
 */
class CollectedMetricsBufferConcurrencyTest {

    @Test
    fun `concurrent appends from several threads lose no elements`() {
        val buffer = CollectedMetricsBuffer<Int>()
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (0 until APPENDER_THREADS).map { worker ->
            thread(isDaemon = true, name = "buffer-appender-$worker") {
                try {
                    start.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    val base = worker * APPENDS_PER_THREAD
                    repeat(APPENDS_PER_THREAD) { i -> buffer.append(base + i) }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(JOIN_TIMEOUT_MS) }
        workers.forEach { assertFalse(it.isAlive, "${it.name} did not finish within ${JOIN_TIMEOUT_MS}ms") }

        assertTrue(errors.isEmpty(), "appenders threw: ${errors.joinToString()}")

        val expectedTotal = APPENDER_THREADS * APPENDS_PER_THREAD
        assertEquals(expectedTotal, buffer.size, "concurrent appends must not be lost or duplicated")

        val snapshot = buffer.snapshot()
        assertEquals(expectedTotal, snapshot.size, "snapshot must expose every appended element")
        // A set comparison catches all three unsynchronised-ArrayList failure modes at
        // once: lost writes, duplicated slots, and null padding from a resize race.
        assertEquals((0 until expectedTotal).toSet(), snapshot.toSet(), "every appended value must survive exactly once")
    }

    @Test
    fun `snapshots taken while other threads append and clear stay intact`() {
        val buffer = CollectedMetricsBuffer<Int>()
        val running = AtomicBoolean(true)
        val appended = AtomicInteger(0)
        val snapshots = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        // The appender's counter never restarts, so whatever survives a clear() is
        // still a strictly increasing suffix of the append order. A torn copy breaks
        // that ordering; a null-padded one throws on unboxing. Both land in `errors`.
        val appender = thread(isDaemon = true, name = "buffer-appender") {
            try {
                var value = 0
                while (running.get() && value < APPEND_BUDGET) {
                    buffer.append(value++)
                    appended.set(value)
                }
            } catch (t: Throwable) {
                errors += t
            }
        }
        val clearer = thread(isDaemon = true, name = "buffer-clearer") {
            try {
                while (running.get()) {
                    Thread.sleep(CLEAR_INTERVAL_MS)
                    buffer.clear()
                }
            } catch (_: InterruptedException) {
                // shutdown
            } catch (t: Throwable) {
                errors += t
            }
        }
        val reader = thread(isDaemon = true, name = "buffer-reader") {
            try {
                while (running.get()) {
                    val snapshot = buffer.snapshot()
                    snapshots.incrementAndGet()
                    var previous = Int.MIN_VALUE
                    for (element in snapshot) {
                        if (element <= previous) {
                            throw AssertionError("torn snapshot: $element followed $previous")
                        }
                        previous = element
                    }
                }
            } catch (t: Throwable) {
                errors += t
            }
        }

        appender.join(RUN_BUDGET_MS)
        running.set(false)
        listOf(appender, clearer, reader).forEach { worker ->
            worker.interrupt()
            worker.join(JOIN_TIMEOUT_MS)
        }

        assertTrue(errors.isEmpty(), "concurrent access failed: ${errors.joinToString()}")
        assertTrue(appended.get() > 0, "the appender made no progress")
        assertTrue(snapshots.get() > 0, "the reader took no snapshots")
    }

    private companion object {
        const val APPENDER_THREADS = 4
        const val APPENDS_PER_THREAD = 25_000

        /** Upper bound on the append/clear/snapshot race, so the test cannot run away. */
        const val APPEND_BUDGET = 2_000_000
        const val RUN_BUDGET_MS = 3_000L
        const val CLEAR_INTERVAL_MS = 2L

        /** Generous: the threads are short, this only has to beat a loaded CI box. */
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
