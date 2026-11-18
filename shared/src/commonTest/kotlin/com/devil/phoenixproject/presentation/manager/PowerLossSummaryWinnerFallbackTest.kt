package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.ProtocolRepBounds
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.powerloss.PowerLossSummaryVariant
import com.devil.phoenixproject.presentation.powerloss.PowerLossUiCopy
import com.devil.phoenixproject.presentation.powerloss.powerLossSummaryUi
import com.devil.phoenixproject.presentation.powerloss.powerLossSummaryVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #1392 — summary winner fallback (ui-spec §6 / SD2 + SD4).
 *
 * The one-winner completion latch can fire from the machine-counter path
 * (`RepType.WORKOUT_COMPLETE → handleSetCompletion(MAX_REPS_BACKSTOP)`) BEFORE
 * the final rep's metric evaluation latches the policy winner. P7 field QA on
 * the implementation branch caught the consequence: the cap summary card
 * silently degraded to the MANUAL variant and dropped its signed-off
 * `Stopped at 20 working reps — max-reps limit` headline plus SD6
 * clarification. The committed completion reason is the winner of record when
 * the latch is empty; the latched policy winner itself is never overwritten
 * (SD2).
 */
class PowerLossSummaryWinnerFallbackTest {

    private val config = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = 10,
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

    @Test
    fun `machine counter completion keeps the max reps headline`() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        controller.onObservation(config, plan, obs(2, mcp = 100f))

        // No policy winner latched: the machine-counter completion claimed the
        // one-winner latch first (SD4). The committed reason is the winner.
        val facts = assertNotNull(
            controller.buildSummaryFacts(
                completedRepCount = 20,
                fallbackWinnerReason = PowerStopReason.MAX_REPS_BACKSTOP,
            ),
        )
        assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, facts.winnerReason)
        assertEquals(20, facts.stoppedAtWorkingAttempt, "the cap attempt is the trigger of record")

        val ui = powerLossSummaryUi(facts)
        assertEquals(PowerLossSummaryVariant.MAX_REPS_BACKSTOP, powerLossSummaryVariant(facts))
        assertEquals("Stopped at 20 working reps — max-reps limit", ui.headline)
        assertEquals(PowerLossUiCopy.summaryClarification(20, 20), ui.clarification)
    }

    @Test
    fun `latched policy winner is never replaced by the completion fallback`() {
        val controller = PowerLossSetController()
        // Rep 1 establishes the reference; rep 2 loses 50% and latches POWER_LOSS.
        controller.onObservation(config, plan, obs(1, mcp = 130.8f))
        controller.onObservation(config, plan, obs(2, mcp = 65.4f))

        val facts = assertNotNull(
            controller.buildSummaryFacts(
                completedRepCount = 2,
                fallbackWinnerReason = PowerStopReason.MAX_REPS_BACKSTOP,
            ),
        )
        assertEquals(PowerStopReason.POWER_LOSS, facts.winnerReason, "SD2: the latched winner stands")
        assertEquals(2, facts.stoppedAtWorkingAttempt)
    }

    @Test
    fun `manual and safety completions never forge a power winner`() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))

        // A non-power-vocabulary completion (manual, stall, safety, timer) has
        // no power winner — the MANUAL variant keeps its existing null headline.
        val facts = assertNotNull(controller.buildSummaryFacts(completedRepCount = 1))
        assertNull(facts.winnerReason)
        assertNull(facts.stoppedAtWorkingAttempt)
        val ui = powerLossSummaryUi(facts)
        assertNull(ui.headline, "no power-policy winner means no power headline")
    }

    @Test
    fun `evidence records the same committed winner as the summary`() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        controller.onObservation(config, plan, obs(2, mcp = 100f))

        val evidence = assertNotNull(
            controller.buildEvidence(
                sessionId = "s",
                setId = "set-1",
                fallbackWinnerReason = PowerStopReason.MAX_REPS_BACKSTOP,
                fallbackStoppedAtAttempt = 20,
            ),
        )
        assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, evidence.winnerReason)
        assertEquals(20, evidence.triggerWorkingOrdinal)
    }
}
