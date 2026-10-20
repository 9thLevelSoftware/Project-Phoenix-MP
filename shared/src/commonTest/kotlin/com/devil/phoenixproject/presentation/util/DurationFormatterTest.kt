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

    @Test
    fun formatDuration_floorsTowardZero_includingNegativeZeroAndTenMinutes() {
        assertEquals("0:00", DurationFormatter.formatDuration(0L))
        assertEquals("0:00", DurationFormatter.formatDuration(999L))
        assertEquals("0:00", DurationFormatter.formatDuration(-1L))
        assertEquals("0:59", DurationFormatter.formatDuration(59_999L))
        assertEquals("1:00", DurationFormatter.formatDuration(60_999L))
        assertEquals("0:-5", DurationFormatter.formatDuration(-5_000L))
        assertEquals("10:00", DurationFormatter.formatDuration(600_000L))
        assertEquals("10:05", DurationFormatter.formatDuration(605_000L))
    }

    @Test
    fun formatJustLiftRestClock_matchesPreviousInlineClock() {
        val secondsSamples = (-120..700).toList() + listOf(
            0,
            45,
            59,
            60,
            90,
            599,
            600,
            605,
            -1,
            -5,
            -59,
            -60,
            -61,
            Int.MIN_VALUE,
            -2_147_484,
            2_147_483,
            2_147_484,
            Int.MAX_VALUE,
        )
        secondsSamples.distinct().forEach { seconds ->
            assertEquals(
                legacyJustLiftRestClock(seconds),
                DurationFormatter.formatJustLiftRestClock(seconds.toLong() * 1000L),
                "seconds=$seconds",
            )
        }
    }

    @Test
    fun formatJustLiftRestClock_floorsPartialMillisecondsAndKeepsSubMinuteLabel() {
        assertEquals("0s", DurationFormatter.formatJustLiftRestClock(0L))
        assertEquals("0s", DurationFormatter.formatJustLiftRestClock(999L))
        assertEquals("0s", DurationFormatter.formatJustLiftRestClock(-1L))
        assertEquals("45s", DurationFormatter.formatJustLiftRestClock(45_000L))
        assertEquals("45s", DurationFormatter.formatJustLiftRestClock(45_999L))
        assertEquals("59s", DurationFormatter.formatJustLiftRestClock(59_999L))
        assertEquals("-5s", DurationFormatter.formatJustLiftRestClock(-5_000L))
        assertEquals("-1s", DurationFormatter.formatJustLiftRestClock(-1_001L))
        assertEquals("0s", DurationFormatter.formatJustLiftRestClock(-60_000L))
        assertEquals("1:00", DurationFormatter.formatJustLiftRestClock(60_000L))
        assertEquals("1:00", DurationFormatter.formatJustLiftRestClock(60_999L))
        assertEquals("1:30", DurationFormatter.formatJustLiftRestClock(90_000L))
        assertEquals("10:00", DurationFormatter.formatJustLiftRestClock(600_000L))
        assertEquals("10:05", DurationFormatter.formatJustLiftRestClock(605_000L))
        assertEquals("9:59", DurationFormatter.formatJustLiftRestClock(599_000L))

        listOf(60_000L, 90_000L, 600_000L, 605_000L, 3_600_000L).forEach { durationMs ->
            assertEquals(
                DurationFormatter.formatDuration(durationMs),
                DurationFormatter.formatJustLiftRestClock(durationMs),
                "durationMs=$durationMs",
            )
        }
    }

    @Test
    fun formatJustLiftRestClock_bothCallSitesUseSharedHelper() {
        val call = "DurationFormatter.formatJustLiftRestClock(secondsRemaining.toLong() * 1000L)"
        listOf(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt",
        ).forEach { path ->
            val src = requireNotNull(readProjectFile(path)) { "$path must be readable from the host test." }
            assertTrue(src.contains(call), "$path must format the Just Lift rest clock through DurationFormatter.")
            assertTrue(
                !src.contains("secondsRemaining / 60") && !src.contains("secondsRemaining % 60"),
                "$path must not keep the inline rest-clock conversion.",
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

/**
 * Previous Just Lift rest badge: whole seconds, `Ns` under one minute, `M:SS` after.
 */
private fun legacyJustLiftRestClock(secondsRemaining: Int): String {
    val minutes = secondsRemaining / 60
    val seconds = secondsRemaining % 60
    return if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')}" else "${seconds}s"
}
