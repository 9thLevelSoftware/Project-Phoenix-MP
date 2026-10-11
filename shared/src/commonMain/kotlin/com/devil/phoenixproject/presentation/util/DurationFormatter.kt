package com.devil.phoenixproject.presentation.util

/**
 * Shared workout-duration display for surfaces that show a set or session length.
 *
 * Milliseconds become `M:SS`, so a sub-minute set keeps its seconds
 * (`45_000` -> `"0:45"`, `130_000` -> `"2:10"`). Minutes are not wrapped into hours.
 */
object DurationFormatter {
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
