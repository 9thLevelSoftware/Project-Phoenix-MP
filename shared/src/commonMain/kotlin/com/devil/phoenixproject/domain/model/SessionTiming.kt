package com.devil.phoenixproject.domain.model

/**
 * Start time and duration of a saved workout session, resolved so neither can be garbage.
 *
 * Production pushed sessions with `startedAt = 1970-01-01` and a `durationSeconds` equal to the
 * current Unix time: a save read `workoutStartTime` after a teardown/reset had zeroed it, so the
 * start was `0` and the duration was `now - 0`. Every session builder resolves its timing here,
 * and the portal push adapter re-checks rows already on disk with the same bounds.
 */
data class SessionTiming(
    /** Session start in epoch ms. Always plausible (see [isPlausibleStartMs]). */
    val startMs: Long,
    /** Session duration in ms, within `0..MAX_SESSION_DURATION_MS`. */
    val durationMs: Long,
    /** False when no real start was available and [startMs] is the save time with a zero duration. */
    val startKnown: Boolean,
) {
    companion object {
        /** 2015-01-01T00:00:00Z. No Phoenix/V-Form session can predate it; `0` (1970) is the known bad value. */
        const val MIN_PLAUSIBLE_START_MS: Long = 1_420_070_400_000L

        /** A single saved session never runs a day; anything longer is a corrupt start/end pair. */
        const val MAX_SESSION_DURATION_MS: Long = 24L * 60L * 60L * 1000L

        fun isPlausibleStartMs(epochMs: Long): Boolean = epochMs >= MIN_PLAUSIBLE_START_MS

        /**
         * A stored duration is usable only when it is non-negative and at most a day. An
         * epoch-sized value (the 1970 bug stored `now - 0`) is replaced by `0` (unknown).
         */
        fun sanitizeDurationMs(durationMs: Long): Long =
            if (durationMs in 0L..MAX_SESSION_DURATION_MS) durationMs else 0L

        /**
         * Resolves the start and duration of a session being saved at [nowMs].
         *
         * [workoutStartMs] is the set's start. When it is missing (`0` after a reset) or
         * implausible, [fallbackStartMs] (for example the first collected sample) is used
         * instead. If neither is usable, the session starts at [nowMs] with a zero duration:
         * the data is kept, but no fabricated duration is written. [warmupCompleteMs], when it
         * falls inside `start..now`, excludes warmup time from the duration (Issue #252). A
         * duration over [MAX_SESSION_DURATION_MS] (a stale start) is written as `0`, not capped.
         */
        fun resolve(
            workoutStartMs: Long,
            warmupCompleteMs: Long,
            nowMs: Long,
            fallbackStartMs: Long? = null,
        ): SessionTiming {
            val start = workoutStartMs.takeIf { isPlausibleStartMs(it) && it <= nowMs }
                ?: fallbackStartMs?.takeIf { isPlausibleStartMs(it) && it <= nowMs }
                ?: return SessionTiming(startMs = nowMs, durationMs = 0L, startKnown = false)
            val durationFrom = warmupCompleteMs.takeIf { it in start..nowMs } ?: start
            return SessionTiming(
                startMs = start,
                durationMs = sanitizeDurationMs(nowMs - durationFrom),
                startKnown = true,
            )
        }
    }
}
