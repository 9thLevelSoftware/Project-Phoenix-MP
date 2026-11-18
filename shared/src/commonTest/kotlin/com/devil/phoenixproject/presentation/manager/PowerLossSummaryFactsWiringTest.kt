package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.ProtocolRepBounds
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.powerloss.PowerLossUiFacts
import com.devil.phoenixproject.presentation.powerloss.PowerLossUiCopy
import com.devil.phoenixproject.presentation.powerloss.powerLossSummaryUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #1392 — summary facts wiring (ui-spec F05/F06, SD6).
 *
 * The live summary projection takes `WorkoutState.SetSummary.powerLoss`
 * (frozen by the engine via [PowerLossSetController.buildSummaryFacts]) and
 * renders the `Stopped at rep 9 — 21% power loss (best: rep 2)` headline, its
 * adjacent completed/attempt clarification, and the per-attempt `Power details`
 * table. This test pins that chain end-to-end so the summary block can never
 * render empty while facts are present (the P7 gap).
 */
class PowerLossSummaryFactsWiringTest {

    private val config = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = 12,
    )

    private val plan: ExecutionTargetPlan = requireNotNull(
        ExecutionTargetPlan.derive(
            config = config,
            bounds = ProtocolRepBounds(
                maxRepresentableWorkingReps = 254,
                protocolIdentityFinite = true,
            ),
            warmupSetCount = 0,
            now = 0L,
        ),
    )

    private fun obs(ordinal: Int, mcp: Float?): PowerLossObservation = PowerLossObservation(
        workingConcentricOrdinal = ordinal,
        isWarmup = false,
        mcpWatts = mcp,
        romA = CableRom.trusted(100f),
        romB = CableRom.trusted(100f),
        activeCables = setOf(PowerCable.A, PowerCable.B),
    )

    /** Drive a §6 representative fixture: best rep 2 @300 W, stop at rep 9 (21%). */
    private fun fixtureController(): PowerLossSetController {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 285f))
        controller.onObservation(config, plan, obs(2, mcp = 300f))
        controller.onObservation(config, plan, obs(3, mcp = 293.4f))
        controller.onObservation(config, plan, obs(4, mcp = 281.4f))
        controller.onObservation(config, plan, obs(5, mcp = 272.4f))
        controller.onObservation(config, plan, obs(6, mcp = 263.4f))
        controller.onObservation(config, plan, obs(7, mcp = 251.4f))
        controller.onObservation(config, plan, obs(8, mcp = 245.4f))
        controller.onObservation(config, plan, obs(9, mcp = 236.4f))
        return controller
    }

    @Test
    fun summaryFactsCarrySavedNominalRepsFromConfig() {
        val facts = assertNotNull(fixtureController().buildSummaryFacts(completedRepCount = 8))
        assertEquals(12, facts.savedNominalReps, "SD6 Saved reps readback comes from the frozen config")
        assertEquals(PowerStopReason.POWER_LOSS, facts.winnerReason)
        assertEquals(9, facts.stoppedAtWorkingAttempt)
        assertEquals(8, facts.completedRepCount, "completed count comes from actualReps, never promoted")
    }

    @Test
    fun summaryUiRendersHeadlineClarificationAndTableFromFacts() {
        val facts = assertNotNull(fixtureController().buildSummaryFacts(completedRepCount = 8))
        // The summary adapter passes facts through unchanged; the UI derives all copy
        // (including the Saved reps readback) from the frozen facts alone.
        val ui = powerLossSummaryUi(PowerLossUiFacts.summaryFacts(facts)!!)
        assertEquals("Stopped at rep 9 — 21% power loss (best: rep 2)", ui.headline)
        assertEquals("8 completed reps · lifting attempt 9", ui.clarification)
        assertEquals("Saved reps: 12", ui.savedRepsHeader)
        assertEquals("Power-loss target active", ui.powerActiveBadge)
        // Per-attempt table (Power details) has one row per working attempt.
        assertEquals(9, ui.table.rows.size, "per-attempt table carries every working attempt")
        assertTrue(ui.table.coreRows.isNotEmpty())
        assertEquals(PowerLossUiCopy.DETAILS_CAPTION, ui.table.caption)
        // Row detail pairs expose reference-at-decision and ROM facts for expansion.
        val triggerRow = ui.table.rows.first { it.attemptLabel == "9" }
        assertTrue(triggerRow.details.any { it.first == PowerLossUiCopy.COL_REFERENCE })
        assertTrue(triggerRow.badges.contains(PowerLossUiCopy.BADGE_TRIGGER))
    }
}
