package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.PolarRrBeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolarTelemetryUtilsTest {

    @Test
    fun testEmptyOrSingleBeatReturnsNull() {
        val now = 100_000L
        assertNull(computeRollingRmssd(emptyList(), currentTimeMs = now))
        assertNull(computeRollingRmssd(listOf(PolarRrBeat(800.0, now, 75)), currentTimeMs = now))
    }

    @Test
    fun testKnownRmssdCalculation() {
        val now = 100_000L
        // Intervals: 800, 850 (+50), 820 (-30)
        // Diffs: 50, -30 -> Squared: 2500, 900 -> Mean: 3400 / 2 = 1700 -> sqrt(1700) = 41.231
        val beats = listOf(
            PolarRrBeat(800.0, now - 2000, 75),
            PolarRrBeat(850.0, now - 1150, 71),
            PolarRrBeat(820.0, now - 330, 73),
        )

        val rmssd = computeRollingRmssd(beats, windowMs = 60_000L, currentTimeMs = now)
        assertTrue(rmssd != null)
        assertEquals(41.23, rmssd, 0.01)
        assertEquals("41 ms", formatRmssdMs(rmssd))
    }

    @Test
    fun testSlidingWindowExcludesOldBeats() {
        val now = 100_000L
        // Beat at now - 70_000 is outside 60s window
        val beats = listOf(
            PolarRrBeat(1200.0, now - 70_000, 50),
            PolarRrBeat(800.0, now - 1000, 75),
            PolarRrBeat(850.0, now - 150, 71),
        )

        // Only 2 beats are within window: 800 and 850 (diff 50) -> sqrt(2500) = 50.0
        val rmssd = computeRollingRmssd(beats, windowMs = 60_000L, currentTimeMs = now)
        assertTrue(rmssd != null)
        assertEquals(50.0, rmssd, 0.01)
    }

    @Test
    fun testOutlierAndGappedBeatsAreFiltered() {
        val now = 100_000L
        val beats = listOf(
            // Beat 1 and 2: huge gap of 10 seconds (dropped connection / dropped packet)
            PolarRrBeat(800.0, now - 12_000, 75),
            PolarRrBeat(820.0, now - 2_000, 73),
            // Beat 3: valid time gap (1000ms), diff = +20 -> squared = 400
            PolarRrBeat(840.0, now - 1_000, 71),
        )

        val rmssd = computeRollingRmssd(beats, windowMs = 60_000L, currentTimeMs = now)
        assertTrue(rmssd != null)
        // Only pair (820, 840) is within valid gap tolerance
        assertEquals(20.0, rmssd, 0.01)
    }
}
