package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke check only. The simulator cannot be put to sleep here, so nothing in this
 * test tells the sleep-inclusive clock from the awake-only one; that rests on the
 * clock id cited in `PlatformUtils.ios.kt`. These checks lock what is observable:
 * a positive boot-relative reading that does not move backwards and is not
 * wall-clock time.
 */
class ElapsedRealtimeIosTest {
    @Test
    fun elapsedRealtimeIsBootRelativeAndDoesNotGoBackwards() {
        val first = elapsedRealtimeMillis()
        val second = elapsedRealtimeMillis()

        assertTrue(first > 0L, "elapsed realtime should be past boot, was $first")
        assertTrue(second >= first, "elapsed realtime went backwards: $first then $second")
        assertTrue(
            second < WALL_CLOCK_MS,
            "elapsed realtime looks like wall-clock time: $second",
        )
    }

    private companion object {
        /** Below any current Unix epoch in milliseconds (2001-09-09). */
        private const val WALL_CLOCK_MS = 1_000_000_000_000L
    }
}
