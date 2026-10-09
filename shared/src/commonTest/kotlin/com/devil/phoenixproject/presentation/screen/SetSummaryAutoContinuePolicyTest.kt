package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1226: the auto-continue decision behind [SetSummaryCard]'s countdown. The
 * terminal routine summary is held like a Manual one for every summary preference, so the
 * Compose countdown (the third of three auto-advance schedulers) must be inert there while
 * intermediate summaries keep their per-preference behaviour.
 */
internal class SetSummaryAutoContinuePolicyTest {

    @Test
    fun `countdown never runs at the terminal summary for any preference`() {
        for (summarySeconds in listOf(-1, 0, 5, 10, 30)) {
            assertFalse(
                shouldAutoContinueSetSummary(
                    autoplayEnabled = true,
                    summaryCountdownSeconds = summarySeconds,
                    isHistoryView = false,
                    holdAutoContinue = false,
                    isTerminalSummary = true,
                ),
                "terminal summary must never auto-continue (summaryCountdownSeconds=$summarySeconds)",
            )
        }
    }

    @Test
    fun `intermediate summary keeps its per-preference behaviour`() {
        assertTrue(
            shouldAutoContinueSetSummary(
                autoplayEnabled = true,
                summaryCountdownSeconds = 5,
                isHistoryView = false,
                holdAutoContinue = false,
                isTerminalSummary = false,
            ),
            "a timed intermediate summary must still auto-continue",
        )
        assertFalse(
            shouldAutoContinueSetSummary(
                autoplayEnabled = true,
                summaryCountdownSeconds = -1,
                isHistoryView = false,
                holdAutoContinue = false,
                isTerminalSummary = false,
            ),
            "Automatic (-1) never drives the Compose countdown",
        )
        assertFalse(
            shouldAutoContinueSetSummary(
                autoplayEnabled = true,
                summaryCountdownSeconds = 0,
                isHistoryView = false,
                holdAutoContinue = false,
                isTerminalSummary = false,
            ),
            "Manual (0) never drives the Compose countdown",
        )
    }

    @Test
    fun `existing hold history and autoplay gates are unchanged`() {
        assertFalse(
            shouldAutoContinueSetSummary(
                autoplayEnabled = true,
                summaryCountdownSeconds = 5,
                isHistoryView = false,
                holdAutoContinue = true,
                isTerminalSummary = false,
            ),
            "the Add Exercise hold must still stop the countdown",
        )
        assertFalse(
            shouldAutoContinueSetSummary(
                autoplayEnabled = true,
                summaryCountdownSeconds = 5,
                isHistoryView = true,
                holdAutoContinue = false,
                isTerminalSummary = false,
            ),
            "history view must never auto-continue",
        )
        assertFalse(
            shouldAutoContinueSetSummary(
                autoplayEnabled = false,
                summaryCountdownSeconds = 5,
                isHistoryView = false,
                holdAutoContinue = false,
                isTerminalSummary = false,
            ),
            "autoplay-off must never auto-continue",
        )
    }
}
