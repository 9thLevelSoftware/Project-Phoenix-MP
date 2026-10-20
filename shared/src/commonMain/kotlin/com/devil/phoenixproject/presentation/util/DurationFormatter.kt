package com.devil.phoenixproject.presentation.util

/**
 * Shared workout-duration display for surfaces that show a set or session length.
 *
 * Milliseconds become `M:SS`, so a sub-minute set keeps its seconds
 * (`45_000` -> `"0:45"`, `130_000` -> `"2:10"`). Minutes are not wrapped into hours.
 * Partial milliseconds floor toward zero. The Just Lift rest/idle countdown uses
 * [formatJustLiftRestClock], which keeps a sub-minute countdown as `Ns`.
 */
object DurationFormatter {
    fun formatDuration(durationMs: Long): String {
        val (minutes, seconds) = splitClock(durationMs)
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * Just Lift rest/idle countdown.
     *
     * Callers that hold whole seconds convert at the call site with
     * `secondsRemaining.toLong() * 1000L` so this stays milliseconds in, and so
     * an [Int] countdown cannot overflow before the division. Partial milliseconds
     * floor toward zero, the same way [formatDuration] does.
     *
     * Under one minute the clock stays `Ns` (`45_000` -> `"45s"`, `0` -> `"0s"`,
     * `-5_000` -> `"-5s"`). At one minute or more it is the same `M:SS` text as
     * [formatDuration] (`90_000` -> `"1:30"`, `600_000` -> `"10:00"`).
     */
    fun formatJustLiftRestClock(durationMs: Long): String {
        val (minutes, seconds) = splitClock(durationMs)
        return if (minutes > 0) {
            formatDuration(durationMs)
        } else {
            "${seconds}s"
        }
    }

    /** Whole seconds, floored toward zero, then `minutes to seconds`. */
    private fun splitClock(durationMs: Long): Pair<Long, Long> {
        val totalSeconds = durationMs / 1000
        return totalSeconds / 60 to totalSeconds % 60
    }
}
