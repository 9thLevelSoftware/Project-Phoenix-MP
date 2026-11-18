package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetSummaryFacts
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — summary reason variants and SD6 headline/clarification rules.
 */
class PowerLossSummaryUiStateTest {

    /** ui-spec §6 representative fixture (SIMULATED): 9 attempts, best rep 2, stop 21%. */
    private val fixtureAttempts = listOf(
        PowerLossAttemptRecord(1, completed = true, mcpWatts = 285f, lossPercentAtDecision = 0f, referenceWorkingOrdinal = 1),
        PowerLossAttemptRecord(2, completed = true, mcpWatts = 300f, lossPercentAtDecision = 0f, referenceWorkingOrdinal = 2),
        PowerLossAttemptRecord(3, completed = true, mcpWatts = 294f, lossPercentAtDecision = 2f, referenceWorkingOrdinal = 2),
        PowerLossAttemptRecord(7, completed = true, mcpWatts = 252f, lossPercentAtDecision = 16f, referenceWorkingOrdinal = 2),
        PowerLossAttemptRecord(9, completed = false, mcpWatts = 237f, lossPercentAtDecision = 21f, referenceWorkingOrdinal = 2),
    )

    private fun facts(
        winner: PowerStopReason? = PowerStopReason.POWER_LOSS,
        stoppedAt: Int? = 9,
        completed: Int = 8,
        attempts: List<PowerLossAttemptRecord> = fixtureAttempts,
    ) = PowerLossSetSummaryFacts(
        winnerReason = winner,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        stoppedAtWorkingAttempt = stoppedAt,
        completedRepCount = completed,
        lossPercentAtStop = 21f,
        bestWorkingOrdinal = 2,
        shortRomObserved = false,
        powerLossObserved = true,
        attempts = attempts,
    )

    @Test
    fun `power-loss headline renders only with the SD6 clarification`() {
        val ui = powerLossSummaryUi(facts(), savedNominalReps = 12)
        assertEquals("Stopped at rep 9 — 21% power loss (best: rep 2)", ui.headline)
        assertEquals("8 completed reps · lifting attempt 9", ui.clarification)
        assertNotNull(ui.headlineWithClarification)
        assertTrue(ui.headlineWithClarification!!.contains("\n"), "Clarification renders adjacent to the headline")
        assertTrue(
            ui.headlineWithClarification!!.endsWith("8 completed reps · lifting attempt 9"),
            "Completed count comes from actualReps — never promoted to match the headline",
        )
        assertEquals("Target 20% · Max 20 working reps · Consecutive requirement off", ui.configLine)
        assertEquals("Saved reps: 12", ui.savedRepsHeader)
        assertEquals("Power-loss target active", ui.powerActiveBadge)
    }

    @Test
    fun `completed count is never promoted to the headline number`() {
        val ui = powerLossSummaryUi(facts(completed = 8, stoppedAt = 9))
        assertTrue(ui.clarification!!.startsWith("8 completed"))
        assertFalse(ui.clarification!!.startsWith("9 completed"))
    }

    @Test
    fun `short-rom variant keeps the trigger attempt and measured facts`() {
        val trigger = PowerLossAttemptRecord(
            workingConcentricOrdinal = 9,
            completed = false,
            romAMm = 410f,
            romBMm = 500f,
            romAPercentOfReference = 82f,
            romBPercentOfReference = 100f,
            romTrustworthy = true,
            shortRom = true,
        )
        val ui = powerLossSummaryUi(facts(winner = PowerStopReason.SHORT_ROM, attempts = listOf(trigger)))
        assertEquals("Stopped at attempt 9 — short range of motion", ui.headline)
        assertEquals("8 completed reps · lifting attempt 9", ui.clarification)
        assertTrue(ui.secondaryLine!!.contains("Cable A: 82% of reference"), ui.secondaryLine)
        // ROM deficit never becomes a measured loss.
        assertEquals("—", ui.table.rows.single().mcpText)
        assertEquals("—", ui.table.rows.single().lossAtDecisionText)
    }

    @Test
    fun `max-reps backstop never implies fatigue`() {
        val ui = powerLossSummaryUi(
            facts(
                winner = PowerStopReason.MAX_REPS_BACKSTOP,
                stoppedAt = 20,
                completed = 20,
                attempts = emptyList(),
            ),
        )
        assertEquals("Stopped at 20 working reps — max-reps limit", ui.headline)
        assertFalse(ui.headline!!.contains("power loss"), "Cap stop must not imply a fatigue cause")
    }

    @Test
    fun `manual stop shows observations not a causal headline`() {
        val ui = powerLossSummaryUi(facts(winner = null, stoppedAt = null), endedManually = true)
        assertEquals("Set stopped manually", ui.headline)
        assertNull(ui.clarification)
    }

    @Test
    fun `legacy set without evidence shows the unavailable line`() {
        val ui = powerLossSummaryUi(
            facts(winner = null, stoppedAt = null, attempts = emptyList()),
        )
        assertNull(ui.headline)
        assertEquals("Power-loss details unavailable for this set.", ui.unavailableLine)
        assertNull(ui.powerActiveBadge)
    }

    @Test
    fun `first row zero loss is relative to its own decision-time reference`() {
        val ui = powerLossSummaryUi(facts())
        val firstRow = ui.table.rows.first()
        assertEquals("0%", firstRow.lossAtDecisionText)
        assertEquals(
            "1",
            firstRow.referenceAtDecisionText,
            "Row 1's reference-at-decision is its own ordinal, not the final best",
        )
        val laterRow = ui.table.rows.first { it.attemptLabel == "7" }
        assertEquals("2", laterRow.referenceAtDecisionText)
    }

    @Test
    fun `variants are mutually exclusive`() {
        assertEquals(PowerLossSummaryVariant.POWER_LOSS, powerLossSummaryVariant(facts()))
        assertEquals(
            PowerLossSummaryVariant.SHORT_ROM,
            powerLossSummaryVariant(facts(winner = PowerStopReason.SHORT_ROM)),
        )
        assertEquals(
            PowerLossSummaryVariant.MAX_REPS_BACKSTOP,
            powerLossSummaryVariant(facts(winner = PowerStopReason.MAX_REPS_BACKSTOP)),
        )
        assertEquals(PowerLossSummaryVariant.MANUAL, powerLossSummaryVariant(facts(winner = null)))
        assertEquals(
            PowerLossSummaryVariant.LEGACY_UNAVAILABLE,
            powerLossSummaryVariant(facts(winner = null, attempts = emptyList())),
        )
    }

    @Test
    fun `summary disclosure keeps the local-data boundary`() {
        val ui = powerLossSummaryUi(facts())
        assertEquals(
            "Detailed power data is stored on this device, not synced to the portal.",
            ui.disclosure,
        )
    }
}
