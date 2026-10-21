package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue
import platform.Foundation.NSProcessInfo

/**
 * The simulator cannot be put to sleep here. These checks lock the part of the
 * contract that is still observable: a positive boot-relative reading that does
 * not move backwards, is not wall-clock time, and is at least the awake uptime
 * (time spent asleep can only add to [elapsedRealtimeMillis]).
 */
class ElapsedRealtimeIosTest {
    @Test
    fun elapsedRealtimeIsBootRelativeAndDoesNotGoBackwards() {
        val first = elapsedRealtimeMillis()
        val awakeUptimeMs = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
        val second = elapsedRealtimeMillis()

        assertTrue(first > 0L, "elapsed realtime should be past boot, was $first")
        assertTrue(second >= first, "elapsed realtime went backwards: $first then $second")
        assertTrue(
            second < WALL_CLOCK_MS,
            "elapsed realtime looks like wall-clock time: $second",
        )
        assertTrue(
            second + SAMPLE_SKEW_MS >= awakeUptimeMs,
            "sleep-inclusive clock $second is behind awake uptime $awakeUptimeMs",
        )
    }

    private companion object {
        /** Below any current Unix epoch in milliseconds (2001-09-09). */
        private const val WALL_CLOCK_MS = 1_000_000_000_000L
        private const val SAMPLE_SKEW_MS = 2_000L
    }
}
