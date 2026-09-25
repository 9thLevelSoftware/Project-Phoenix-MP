package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Seed a buffer with a fixed set of samples, replacing whatever it holds.
 *
 * Tests used to assign `collectedMetrics.value = listOf(...)` straight into the
 * old `MutableStateFlow`. The buffer has no settable value by design — the live
 * path only ever appends — so seeding goes through the same `clear()`/`append()`
 * pair production uses.
 */
internal fun <T> CollectedMetricsBuffer<T>.seed(vararg items: T) {
    clear()
    items.forEach { append(it) }
}

/** [seed] for a sample list that is already built. */
internal fun <T> CollectedMetricsBuffer<T>.seedAll(items: List<T>) {
    clear()
    items.forEach { append(it) }
}

/**
 * Pins the contract [CollectedMetricsBuffer] replaced the copy-on-append
 * `MutableStateFlow<List<WorkoutMetric>>` with: appends are O(1), a snapshot is
 * a private copy, and `clear()` starts a set from empty.
 */
class CollectedMetricsBufferTest {

    private fun metric(index: Int) = WorkoutMetric(
        timestamp = 1_000L + index,
        loadA = 20f,
        loadB = 20f,
        positionA = index.toFloat(),
        positionB = index.toFloat(),
    )

    @Test
    fun `thirty thousand appended samples are all present and in order`() {
        val buffer = CollectedMetricsBuffer<WorkoutMetric>()
        val samples = List(SAMPLE_COUNT) { metric(it) }

        // Only the appends are timed; building the sample objects is test setup.
        // The old `update { it + metric }` copied the whole list on every sample,
        // so a set this long cost ~450M element copies plus the garbage for
        // 30,000 intermediate lists. The bound is deliberately loose — it is a
        // smoke guard against a quadratic regression on a loaded CI box, not a
        // microbenchmark.
        val elapsed = measureTime {
            samples.forEach { buffer.append(it) }
        }

        assertEquals(SAMPLE_COUNT, buffer.size, "every appended sample must be retained")

        val snapshot = buffer.snapshot()
        assertEquals(SAMPLE_COUNT, snapshot.size, "snapshot must expose every appended sample")
        assertEquals(samples.first(), snapshot.first(), "the first sample must stay first")
        assertEquals(samples.last(), snapshot.last(), "the last sample must stay last")
        // Order matters: rep segmentation slices this list by boundary timestamps.
        val outOfOrder = (1 until snapshot.size).firstOrNull {
            snapshot[it].timestamp <= snapshot[it - 1].timestamp
        }
        assertTrue(outOfOrder == null, "samples must stay in append order (first break at index $outOfOrder)")

        assertTrue(
            elapsed < APPEND_TIME_BOUND,
            "appending $SAMPLE_COUNT samples took $elapsed, which exceeds $APPEND_TIME_BOUND",
        )
    }

    @Test
    fun `a snapshot taken before further appends does not grow`() {
        val buffer = CollectedMetricsBuffer<WorkoutMetric>()
        buffer.append(metric(0))
        buffer.append(metric(1))

        val snapshot = buffer.snapshot()
        buffer.append(metric(2))

        assertEquals(2, snapshot.size, "a held snapshot must not see later appends")
        assertEquals(3, buffer.size, "the buffer itself must still be growing")
    }

    @Test
    fun `a snapshot taken before clear keeps its samples`() {
        val buffer = CollectedMetricsBuffer<WorkoutMetric>()
        buffer.append(metric(0))
        buffer.append(metric(1))

        val snapshot = buffer.snapshot()
        buffer.clear()

        assertEquals(2, snapshot.size, "a held snapshot must survive the next set's clear()")
        assertEquals(metric(0), snapshot[0])
        assertEquals(metric(1), snapshot[1])
    }

    @Test
    fun `clear leaves the buffer empty so a set cannot inherit the previous set's samples`() {
        val buffer = CollectedMetricsBuffer<WorkoutMetric>()
        buffer.append(metric(0))
        assertFalse(buffer.isEmpty())

        buffer.clear()

        assertTrue(buffer.isEmpty(), "clear() must empty the buffer")
        assertEquals(0, buffer.size)
        assertTrue(buffer.snapshot().isEmpty(), "a cleared buffer must snapshot as empty")

        buffer.append(metric(5))
        assertEquals(listOf(metric(5)), buffer.snapshot(), "the next set starts from its own first sample")
    }

    @Test
    fun `an untouched buffer is empty`() {
        val buffer = CollectedMetricsBuffer<WorkoutMetric>()

        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size)
        assertTrue(buffer.snapshot().isEmpty())
    }

    private companion object {
        const val SAMPLE_COUNT = 30_000
        val APPEND_TIME_BOUND = 2.seconds
    }
}
