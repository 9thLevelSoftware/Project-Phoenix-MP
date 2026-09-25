package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTimingTest {
    private val now = 1_790_000_000_000L

    @Test
    fun `a real start gives that start and the elapsed duration`() {
        val timing = SessionTiming.resolve(workoutStartMs = now - 90_000, warmupCompleteMs = 0L, nowMs = now)

        assertEquals(SessionTiming(startMs = now - 90_000, durationMs = 90_000, startKnown = true), timing)
    }

    @Test
    fun `warmup completion inside the set excludes the warmup from the duration`() {
        val timing = SessionTiming.resolve(
            workoutStartMs = now - 90_000,
            warmupCompleteMs = now - 60_000,
            nowMs = now,
        )

        assertEquals(now - 90_000, timing.startMs)
        assertEquals(60_000, timing.durationMs)
    }

    @Test
    fun `a warmup completion outside the set is ignored`() {
        val stale = SessionTiming.resolve(workoutStartMs = now - 90_000, warmupCompleteMs = now - 500_000, nowMs = now)
        val future = SessionTiming.resolve(workoutStartMs = now - 90_000, warmupCompleteMs = now + 5_000, nowMs = now)

        assertEquals(90_000, stale.durationMs)
        assertEquals(90_000, future.durationMs)
    }

    @Test
    fun `a zeroed start never produces a 1970 start or an epoch sized duration`() {
        val timing = SessionTiming.resolve(workoutStartMs = 0L, warmupCompleteMs = 0L, nowMs = now)

        assertEquals(SessionTiming(startMs = now, durationMs = 0L, startKnown = false), timing)
    }

    @Test
    fun `a zeroed start falls back to the first sample`() {
        val timing = SessionTiming.resolve(
            workoutStartMs = 0L,
            warmupCompleteMs = 0L,
            nowMs = now,
            fallbackStartMs = now - 45_000,
        )

        assertEquals(SessionTiming(startMs = now - 45_000, durationMs = 45_000, startKnown = true), timing)
    }

    @Test
    fun `implausible or future starts and fallbacks are rejected`() {
        val timing = SessionTiming.resolve(
            workoutStartMs = now + 60_000,
            warmupCompleteMs = 0L,
            nowMs = now,
            fallbackStartMs = 1_000L,
        )

        assertFalse(timing.startKnown)
        assertEquals(now, timing.startMs)
        assertEquals(0L, timing.durationMs)
    }

    @Test
    fun `a pre 2015 imported start is valid and resolves unchanged`() {
        val start2012 = 1_336_000_000_000L // 2012-05-02
        val timing = SessionTiming.resolve(workoutStartMs = start2012, warmupCompleteMs = 0L, nowMs = start2012 + 3_600_000)

        assertEquals(SessionTiming(startMs = start2012, durationMs = 3_600_000, startKnown = true), timing)
    }

    @Test
    fun `sanitizeDurationMs keeps real durations and zeroes negative or epoch sized ones`() {
        assertEquals(0L, SessionTiming.sanitizeDurationMs(0L))
        assertEquals(3_600_000L, SessionTiming.sanitizeDurationMs(3_600_000L))
        // Longer than a day but nowhere near the epoch time: not the bug signature, kept.
        val twoDays = 2 * SessionTiming.MAX_SESSION_DURATION_MS
        assertEquals(twoDays, SessionTiming.sanitizeDurationMs(twoDays))
        assertEquals(0L, SessionTiming.sanitizeDurationMs(-1L))
        assertEquals(0L, SessionTiming.sanitizeDurationMs(now))
        assertEquals(0L, SessionTiming.sanitizeDurationMs(SessionTiming.MIN_EPOCH_SIZED_DURATION_MS))
        assertEquals(
            SessionTiming.MIN_EPOCH_SIZED_DURATION_MS - 1,
            SessionTiming.sanitizeDurationMs(SessionTiming.MIN_EPOCH_SIZED_DURATION_MS - 1),
        )
    }

    @Test
    fun `only starts near the epoch are corrupt`() {
        assertTrue(SessionTiming.isCorruptStartMs(0L))
        assertTrue(SessionTiming.isCorruptStartMs(-1L))
        assertTrue(SessionTiming.isCorruptStartMs(SessionTiming.MIN_VALID_START_MS - 1))
        assertFalse(SessionTiming.isCorruptStartMs(SessionTiming.MIN_VALID_START_MS))
        assertFalse(SessionTiming.isCorruptStartMs(1_336_000_000_000L))
    }

    @Test
    fun `a valid start is neither corrupt nor in the future`() {
        assertTrue(SessionTiming.isValidStartMs(now, now))
        assertFalse(SessionTiming.isValidStartMs(now + 1, now))
        assertFalse(SessionTiming.isValidStartMs(0L, now))
    }
}
