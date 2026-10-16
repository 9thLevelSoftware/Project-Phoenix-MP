package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.SessionTiming
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurationFormatterTest {

    @Test
    fun formatDuration_subMinute_keepsSeconds() {
        assertEquals("0:45", DurationFormatter.formatDuration(45_000L))
    }

    @Test
    fun formatDuration_exactMinute_padsSeconds() {
        assertEquals("1:00", DurationFormatter.formatDuration(60_000L))
    }

    @Test
    fun formatDuration_minutesPlusSeconds() {
        assertEquals("2:10", DurationFormatter.formatDuration(130_000L))
    }

    @Test
    fun formatDuration_matchesSetSummaryInlineFormat() {
        val card = requireNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt",
            ),
        ) { "SetSummaryCard.kt must be readable from the host test." }
        assertTrue(
            card.contains("DurationFormatter.formatDuration(summary.durationMs)"),
            "Set summary duration must render through DurationFormatter.",
        )
        assertTrue(
            !card.contains("(summary.durationMs / 1000).toInt()"),
            "Set summary must not keep the inline M:SS conversion.",
        )

        val samples = mutableListOf(
            0L,
            1L,
            999L,
            45_000L,
            59_999L,
            60_000L,
            130_000L,
            RoutineTimeEstimator.MAX_PLAUSIBLE_SET_DURATION_MS,
            SessionTiming.MAX_SESSION_DURATION_MS,
            // Last millisecond whose whole-second count still fits in the old Int conversion.
            Int.MAX_VALUE.toLong() * 1000L + 999L,
        )
        var second = 0L
        val daySeconds = SessionTiming.MAX_SESSION_DURATION_MS / 1000L
        while (second <= daySeconds) {
            samples.add(second * 1000L)
            samples.add(second * 1000L + 999L)
            second += 1L
        }
        samples.forEach { durationMs ->
            assertEquals(
                legacySetSummaryDuration(durationMs),
                DurationFormatter.formatDuration(durationMs),
                "durationMs=$durationMs",
            )
        }
    }
}

/**
 * Previous SetSummaryCard expression: whole seconds narrowed to Int, then `M:SS`.
 * Identical to [DurationFormatter.formatDuration] while those seconds fit in an Int,
 * which covers a live set and a saved session on the summary card.
 */
private fun legacySetSummaryDuration(durationMs: Long): String {
    val durationSeconds = (durationMs / 1000).toInt()
    return "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}"
}
