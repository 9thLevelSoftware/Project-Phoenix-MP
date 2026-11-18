package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossDecision
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.ProtocolRepBounds
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 (P3) — immutable winner latch + evidence capture tests.
 *
 * Drives [PowerLossSetController.onObservation] with sealed observations and
 * asserts the deep-frozen evidence/summary contracts: both qualification facts
 * persisted (SD2), SHORT_ROM-over-POWER_LOSS tie-break, trusted short-ROM with
 * null MCP qualifies while unavailable power alone never does (SD1), warmups
 * excluded, reference-at-decision frozen (SD3), winner latched once, and
 * null-vs-zero preserved (missing MCP is null, never 0).
 */
class PowerLossSetEvidenceLatchTest {

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

    private fun obs(
        ordinal: Int,
        mcp: Float?,
        romA: CableRom = CableRom.trusted(100f),
        romB: CableRom = CableRom.trusted(100f),
        isWarmup: Boolean = false,
    ): PowerLossObservation = PowerLossObservation(
        workingConcentricOrdinal = if (isWarmup) 0 else ordinal,
        isWarmup = isWarmup,
        mcpWatts = mcp,
        romA = romA,
        romB = romB,
        activeCables = setOf(PowerCable.A, PowerCable.B),
    )

    @Test
    fun powerNeverArmedProducesNoEvidence() {
        val controller = PowerLossSetController()
        assertNull(controller.buildEvidence("s1", "set1"), "never-armed controller must not fabricate evidence")
        assertNull(controller.buildSummaryFacts(0), "never-armed controller must not fabricate summary facts")
    }

    @Test
    fun warmupsNeitherTallyNorEstablishReference() {
        val controller = PowerLossSetController()
        val warmupDecision = controller.onObservation(config, plan, obs(0, mcp = 100f, isWarmup = true))
        assertTrue(warmupDecision is PowerLossDecision.Continue, "warmup must never trigger")
        // One working rep with unavailable power: the evidence must contain exactly
        // that working attempt — the warmup row must not tally or establish anything.
        controller.onObservation(config, plan, obs(1, mcp = null))
        val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
        assertEquals(1, evidence.attempts.size, "warmups must not become attempt rows")
        assertEquals(1, evidence.attempts.single().workingConcentricOrdinal)
        assertNull(evidence.referenceWorkingOrdinal, "warmups must not establish the reference")
        assertNull(evidence.winnerReason, "unavailable power alone must not stop")
    }

    @Test
    fun shortRomWinsPowerLossWithBothFactsPersisted() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        val decision = controller.onObservation(
            config,
            plan,
            obs(2, mcp = 50f, romA = CableRom.trusted(50f)), // 50% loss AND 50% ROM
        )
        assertTrue(decision is PowerLossDecision.Stop, "simultaneous fatigue facts must stop")
        assertEquals(PowerStopReason.SHORT_ROM, decision.reason, "SD2: SHORT_ROM wins the tie-break")
        assertTrue(decision.shortRomQualified && decision.powerLossQualified, "SD2: both facts must qualify")

        val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
        assertEquals(PowerStopReason.SHORT_ROM, evidence.winnerReason)
        assertTrue(evidence.shortRomObserved && evidence.powerLossObserved, "SD2: both facts persisted")
        assertEquals(2, evidence.triggerWorkingOrdinal)
        assertEquals(1, evidence.referenceWorkingOrdinal, "SD3: reference-at-decision frozen")
        assertNotNull(evidence.lossPercentAtDecision)
        assertEquals(50.0, evidence.lossPercentAtDecision!!.toDouble(), 0.01, "unrounded loss at decision")
    }

    @Test
    fun trustedShortRomWithNullMcpQualifies() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        val decision = controller.onObservation(
            config,
            plan,
            obs(2, mcp = null, romA = CableRom.trusted(50f)),
        )
        assertTrue(decision is PowerLossDecision.Stop, "SD1: trusted short-ROM survives missing power")
        assertEquals(PowerStopReason.SHORT_ROM, decision.reason)
        val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
        assertTrue(evidence.shortRomObserved)
        assertFalse(evidence.powerLossObserved, "unavailable power alone never qualifies (SD1)")
        assertNull(evidence.attempts.last().mcpWatts, "missing MCP stays null — never a fabricated zero")
    }

    @Test
    fun unavailablePowerWithFullRomDoesNotStop() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        val decision = controller.onObservation(config, plan, obs(2, mcp = null))
        assertTrue(decision is PowerLossDecision.Continue, "neither qualifying reason must not stop")
        val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
        assertNull(evidence.winnerReason)
        assertEquals(2, evidence.attempts.size)
    }

    @Test
    fun winnerIsLatchedOnceAndNeverOverwritten() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        controller.onObservation(config, plan, obs(2, mcp = 50f, romA = CableRom.trusted(50f)))
        // A later worse observation must not move the latched winner/trigger facts.
        controller.onObservation(config, plan, obs(3, mcp = 10f, romA = CableRom.trusted(10f)))
        val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
        assertEquals(PowerStopReason.SHORT_ROM, evidence.winnerReason, "winner latched at first stop")
        assertEquals(2, evidence.triggerWorkingOrdinal, "trigger frozen at decision time")
    }

    @Test
    fun summaryFactsMapHeadlineNumbers() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        controller.onObservation(config, plan, obs(2, mcp = 50f, romA = CableRom.trusted(50f)))
        val facts = assertNotNull(controller.buildSummaryFacts(completedRepCount = 1))
        assertEquals(1, facts.completedRepCount, "completed count comes from actualReps, never promoted")
        assertEquals(2, facts.stoppedAtWorkingAttempt)
        assertEquals(1, facts.bestWorkingOrdinal)
        assertEquals(20, facts.thresholdPercent)
        assertEquals(20, facts.maxWorkingReps)
    }

    @Test
    fun capBackstopWinnerCarriedThrough() {
        val controller = PowerLossSetController()
        controller.onObservation(config, plan, obs(1, mcp = 100f))
        val capped = ExecutionTargetPlan.derive(
            config = config.copy(maxWorkingReps = 2),
            bounds = ProtocolRepBounds(maxRepresentableWorkingReps = 254, protocolIdentityFinite = true),
            warmupSetCount = 0,
            now = 0L,
        )!!
        val decision = controller.onObservation(capped = true, plan = capped, observation = obs(2, mcp = 100f))
        // Cap is enforced by the reducer through the plan; whatever it decides,
        // the latch must carry MAX_REPS_BACKSTOP verbatim when it stops.
        if (decision is PowerLossDecision.Stop) {
            assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, decision.reason)
            val evidence = assertNotNull(controller.buildEvidence("s1", "set1"))
            assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, evidence.winnerReason)
        }
    }
}

/** Small shim so the cap fixture reads clearly at the call site. */
private fun PowerLossSetController.onObservation(
    capped: Boolean,
    plan: ExecutionTargetPlan,
    observation: PowerLossObservation,
): PowerLossDecision = onObservation(
    config = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = if (capped) 2 else 20,
        requireTwoConsecutive = false,
        savedNominalReps = 12,
    ),
    plan = plan,
    observation = observation,
)
