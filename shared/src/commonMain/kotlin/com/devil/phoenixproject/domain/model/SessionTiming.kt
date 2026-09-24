package com.devil.phoenixproject.domain.model

/**
 * Start time and duration of a saved workout session, resolved so neither can be garbage.
 *
 * Production pushed sessions with `startedAt = 1970-01-01` and a `durationSeconds` equal to the
 * current Unix time: a save read `workoutStartTime` after a teardown/reset had zeroed it, so the
 * start was `0` and the duration was `now - 0`. Every session builder resolves its timing here,
 * and the startup repair and the portal push adapter re-check rows already on disk with the
 * same predicates.
 *
 * The predicates match only that failure signature. Old history is legitimate: CSV imports
 * store any parseable date, so a 2012 workout with a normal duration is never touched.
 */
data class SessionTiming(
    /** Session start in epoch ms. Never near the epoch (see [isCorruptStartMs]). */
    val startMs: Long,
    /** Session duration in ms. Never negative or epoch-sized (see [sanitizeDurationMs]). */
    val durationMs: Long,
    /** False when no real start was available and [startMs] is the save time with a zero duration. */
    val startKnown: Boolean,
) {
    companion object {
        /**
         * 1971-01-01T00:00:00Z. A start before it is the zeroed-start signature (`0`, or a small
         * offset from it), not a real workout. Anything later, including pre-2015 imports, is kept.
         */
        const val MIN_VALID_START_MS: Long = 365L * 24L * 60L * 60L * 1000L

        /** A single session never runs a day; only a longer duration can be epoch-sized. */
        const val MAX_SESSION_DURATION_MS: Long = 24L * 60L * 60L * 1000L

        /**
         * 2015-01-01T00:00:00Z as a millisecond count. The 1970 bug stored `duration = now - 0`,
         * the save time itself, and no save predates this app, so a duration at least this long
         * (~45 years) is that signature and nothing else.
         */
        const val MIN_EPOCH_SIZED_DURATION_MS: Long = 1_420_070_400_000L

        /** True for the zeroed-start signature: `<= 0` or within the first year after the epoch. */
        fun isCorruptStartMs(epochMs: Long): Boolean = epochMs < MIN_VALID_START_MS

        /** A usable start at [nowMs]: not the zeroed-start signature and not in the future. */
        fun isValidStartMs(epochMs: Long, nowMs: Long): Boolean = !isCorruptStartMs(epochMs) && epochMs <= nowMs

        /** True for the `now - 0` signature: longer than a day and roughly the epoch time itself. */
        fun isEpochSizedDurationMs(durationMs: Long): Boolean =
            durationMs > MAX_SESSION_DURATION_MS && durationMs >= MIN_EPOCH_SIZED_DURATION_MS

        /** A negative or epoch-sized duration is replaced by `0` (unknown); every other value is kept. */
        fun sanitizeDurationMs(durationMs: Long): Long =
            if (durationMs < 0L || isEpochSizedDurationMs(durationMs)) 0L else durationMs

        /**
         * Resolves the start and duration of a session being saved at [nowMs].
         *
         * [workoutStartMs] is the set's start. When it is missing (`0` after a reset) or in the
         * future, [fallbackStartMs] (for example the first collected sample) is used instead. If
         * neither is usable, the session starts at [nowMs] with a zero duration: the data is kept,
         * but no fabricated duration is written. [warmupCompleteMs], when it falls inside
         * `start..now`, excludes warmup time from the duration (Issue #252).
         */
        fun resolve(
            workoutStartMs: Long,
            warmupCompleteMs: Long,
            nowMs: Long,
            fallbackStartMs: Long? = null,
        ): SessionTiming {
            val start = workoutStartMs.takeIf { isValidStartMs(it, nowMs) }
                ?: fallbackStartMs?.takeIf { isValidStartMs(it, nowMs) }
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
