package com.devil.phoenixproject.domain.powerloss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 signoff §4.1 pure-signal/policy fixture matrix (P2 acceptance).
 *
 * All tests use binary-exact MCP ratios (64→48 = exactly 25% loss) so the
 * unrounded-threshold semantics are exercised without float rounding artifacts.
 */
class PowerLossPolicyTest {

    // Fixtures: threshold 25%, MCP reference 64 W. 48 W is exactly 25% loss.
    private val config = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 25,
        maxWorkingReps = 20,
    )

    private val plan = ExecutionTargetPlan(
        targetMode = TargetMode.POWER,
        nominalReps = 12,
        maxWorkingReps = 20,
        warmupSetCount = 0,
        protocolIdentityFinite = true,
        planValidatedAt = 0L,
    )

    private fun planWithCap(cap: Int) = plan.copy(maxWorkingReps = cap)

    private fun obs(
        ordinal: Int,
        mcp: Float?,
        romA: CableRom = CableRom.trusted(200f),
        romB: CableRom = CableRom.trusted(200f),
        warmup: Boolean = false,
        active: Set<PowerCable> = setOf(PowerCable.A, PowerCable.B),
    ) = PowerLossObservation(
        workingConcentricOrdinal = ordinal,
        isWarmup = warmup,
        mcpWatts = mcp,
        romA = romA,
        romB = romB,
        activeCables = active,
    )

    private fun feed(
        state: PowerLossPolicyState = PowerLossPolicyState.INITIAL,
        observation: PowerLossObservation,
        config: PowerLossConfig = this.config,
        plan: ExecutionTargetPlan = this.plan,
    ) = PowerLossPolicy.observe(state, observation, config, plan)

    // ===== Warmup exclusion =====

    @Test
    fun `warmup observations never establish reference or tally or trigger`() {
        val s1 = feed(observation = obs(0, mcp = 10f, warmup = true))
        assertTrue(s1.decision is PowerLossDecision.Continue)
        assertNull(s1.state.reference)
        assertEquals(0, s1.state.observedWorkingAttempts)
    }

    // ===== Attempt gating and baseline =====

    @Test
    fun `first working attempt never triggers even at total loss`() {
        // A brand-new observation cannot be measured against "its own" loss.
        val s1 = feed(observation = obs(1, mcp = 4f))
        assertTrue(s1.decision is PowerLossDecision.Continue)
        assertEquals(1, s1.state.reference?.workingConcentricOrdinal)
    }

    @Test
    fun `positive baseline required - zero or negative MCP never establishes a reference`() {
        val s1 = feed(observation = obs(1, mcp = 0f))
        assertNull(s1.state.reference)
        val s2 = feed(state = s1.state, observation = obs(2, mcp = -5f))
        assertNull(s2.state.reference)
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 64f))
        assertEquals(3, s3.state.reference?.workingConcentricOrdinal)
    }

    @Test
    fun `first row zero loss is relative to its own decision-time reference`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        assertEquals(0f, s1.record.lossPercentAtDecision)
        assertEquals(1, s1.record.referenceWorkingOrdinal)
    }

    // ===== Reference semantics =====

    @Test
    fun `later best updates reference and earlier decision facts stay frozen`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Rep 2 is better: decision loss is computed against the frozen rep-1 reference
        // (negative loss), then the reference upgrades to rep 2.
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 80f))
        assertTrue(s2.decision is PowerLossDecision.Continue)
        assertTrue((s2.record.lossPercentAtDecision ?: 0f) < 0f)
        assertEquals(1, s2.record.referenceWorkingOrdinal)
        assertEquals(2, s2.state.reference?.workingConcentricOrdinal)
        // Rep 3 at 60 W is a 25% loss against the rep-2 reference (80 → 60).
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 60f))
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.POWER_LOSS, stop.reason)
        assertEquals(2, stop.referenceWorkingOrdinal)
    }

    @Test
    fun `earliest tie retention - equal MCP does not replace the reference`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 64f))
        assertEquals(1, s2.state.reference?.workingConcentricOrdinal)
    }

    @Test
    fun `no short-rep reference replacement`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Rep 2 is short (75% ROM) with better power: it can never replace the reference.
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 90f, romA = CableRom.trusted(150f), romB = CableRom.trusted(150f)),
        )
        assertEquals(1, s2.state.reference?.workingConcentricOrdinal)
    }

    // ===== Threshold semantics =====

    @Test
    fun `unrounded threshold equality qualifies exactly at threshold`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 48f))
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.POWER_LOSS, stop.reason)
        assertEquals(25f, stop.lossPercentAtDecision)
    }

    @Test
    fun `sub-threshold unrounded loss does not trigger even where display rounding would`() {
        // 1000 → 751 W is 24.9% loss; rounding to 25% would wrongly trigger.
        val s1 = feed(observation = obs(1, mcp = 1000f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 751f))
        assertFalse(s2.decision is PowerLossDecision.Stop)
        assertFalse(s2.record.powerLossQualified)
    }

    @Test
    fun `display loss clamps to 0-100`() {
        val below = PowerLossObservationRecord(
            workingConcentricOrdinal = 1, isWarmup = false, mcpWatts = 1f,
            lossPercentAtDecision = -5f, referenceWorkingOrdinal = 1,
            powerLossQualified = false, shortRomQualified = false,
            romAPercentOfReference = null, romBPercentOfReference = null,
            activeCables = setOf(PowerCable.A, PowerCable.B),
        )
        assertEquals(0f, below.displayLossPercent())
        val above = below.copy(lossPercentAtDecision = 150f)
        assertEquals(100f, above.displayLossPercent())
    }

    // ===== Short-ROM semantics (SD1/SD2/SD3) =====

    @Test
    fun `SHORT_ROM wins over POWER_LOSS in the same observation and both facts persist`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Rep 2: 75% ROM (short) AND 37.5% power loss (40 W).
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 40f, romA = CableRom.trusted(150f), romB = CableRom.trusted(150f)),
        )
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.SHORT_ROM, stop.reason)
        assertTrue(stop.shortRomQualified)
        assertTrue(stop.powerLossQualified)
        assertTrue(s2.record.shortRomQualified)
        assertTrue(s2.record.powerLossQualified)
        assertEquals(37.5f, s2.record.lossPercentAtDecision)
    }

    @Test
    fun `one short arm cannot hide - any active cable strictly below 85 percent qualifies`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 64f, romA = CableRom.trusted(150f), romB = CableRom.trusted(200f)),
        )
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.SHORT_ROM, stop.reason)
    }

    @Test
    fun `ROM equality at exactly 85 percent is not short`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 64f, romA = CableRom.trusted(170f), romB = CableRom.trusted(170f)),
        )
        assertTrue(s2.decision is PowerLossDecision.Continue)
        assertFalse(s2.record.shortRomQualified)
    }

    @Test
    fun `trusted short ROM with null MCP qualifies per SD1`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = null, romA = CableRom.trusted(150f), romB = CableRom.trusted(150f)),
        )
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.SHORT_ROM, stop.reason)
        assertTrue(stop.shortRomQualified)
        assertFalse(stop.powerLossQualified)
        assertNull(stop.lossPercentAtDecision)
    }

    @Test
    fun `unavailable power alone never qualifies and resets the streak`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Rep 2: qualifying power loss (streak 1).
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 44f), config = cfg)
        assertEquals(1, s2.state.consecutiveQualifyingCount)
        // Rep 3: null MCP, full trusted ROM — neither qualifying reason → streak reset.
        val s3 = feed(state = s2.state, observation = obs(3, mcp = null), config = cfg)
        assertEquals(0, s3.state.consecutiveQualifyingCount)
        assertTrue(s3.decision is PowerLossDecision.Continue)
    }

    @Test
    fun `unknown ROM is neither fabricated full-ROM nor a short stop`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = null))
        // obs() defaults trusted ROM; build unknown explicitly.
        val unknown = obs(2, mcp = null, romA = CableRom.UNKNOWN, romB = CableRom.UNKNOWN)
        val step = feed(state = s1.state, observation = unknown)
        assertTrue(step.decision is PowerLossDecision.Continue)
        assertFalse(step.record.shortRomQualified)
        assertFalse(step.record.romAPercentOfReference != null)
        assertTrue(s2.decision is PowerLossDecision.Continue)
    }

    @Test
    fun `unknown ROM never promotes the reference per SD3`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Rep 2 has better power but an unknown ROM window: reference must not upgrade.
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 80f, romA = CableRom.UNKNOWN, romB = CableRom.UNKNOWN),
        )
        assertEquals(1, s2.state.reference?.workingConcentricOrdinal)
        // A later trusted-full-ROM rep at 48 W is still judged against the rep-1 reference.
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 48f))
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.POWER_LOSS, stop.reason)
        assertEquals(1, stop.referenceWorkingOrdinal)
    }

    @Test
    fun `frozen active-cable mask is persisted as provenance`() {
        val s1 = feed(observation = obs(1, mcp = 64f, active = setOf(PowerCable.A)))
        assertEquals(setOf(PowerCable.A), s1.record.activeCables)
    }

    // ===== Two-consecutive mode (SD1/SD2) =====

    @Test
    fun `two-consecutive mode stops only on the second adjacent qualifying observation`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 44f), config = cfg)
        assertFalse(s2.decision is PowerLossDecision.Stop)
        assertEquals(1, s2.state.consecutiveQualifyingCount)
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 44f), config = cfg)
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.POWER_LOSS, stop.reason)
    }

    @Test
    fun `two-consecutive terminal observation tie-breaks to SHORT_ROM`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val s1 = feed(observation = obs(1, mcp = 64f))
        // Prior qualifier may have either reason: rep 2 qualifies by power loss only.
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 44f), config = cfg)
        assertEquals(1, s2.state.consecutiveQualifyingCount)
        // Terminal observation qualifies for BOTH → SHORT_ROM wins (SD2).
        val s3 = feed(
            state = s2.state,
            observation = obs(3, mcp = 40f, romA = CableRom.trusted(150f), romB = CableRom.trusted(150f)),
            config = cfg,
        )
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.SHORT_ROM, stop.reason)
    }

    @Test
    fun `two-consecutive accepts either prior reason - SHORT_ROM then POWER_LOSS stops`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(
            state = s1.state,
            observation = obs(2, mcp = 64f, romA = CableRom.trusted(150f), romB = CableRom.trusted(150f)),
            config = cfg,
        )
        assertEquals(1, s2.state.consecutiveQualifyingCount)
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 48f), config = cfg)
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.POWER_LOSS, stop.reason)
    }

    @Test
    fun `skipped ordinals break adjacency and reset the streak`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 44f), config = cfg)
        assertEquals(1, s2.state.consecutiveQualifyingCount)
        // Ordinal 4 arrives after 2: adjacency broke; the qualifying observation
        // restarts the streak at 1 instead of stopping.
        val s3 = feed(state = s2.state, observation = obs(4, mcp = 44f), config = cfg)
        assertFalse(s3.decision is PowerLossDecision.Stop)
        assertEquals(1, s3.state.consecutiveQualifyingCount)
    }

    // ===== Cap semantics (SD2/SD4) =====

    @Test
    fun `cap stops the set with telemetry unavailable`() {
        val capPlan = planWithCap(3)
        val s1 = feed(observation = obs(1, mcp = 64f), plan = capPlan)
        val s2 = feed(state = s1.state, observation = obs(2, mcp = null), plan = capPlan)
        assertTrue(s2.decision is PowerLossDecision.Continue)
        val s3 = feed(state = s2.state, observation = obs(3, mcp = null), plan = capPlan)
        val stop = assertIs<PowerLossDecision.Stop>(s3.decision)
        assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, stop.reason)
    }

    @Test
    fun `cap outranks fatigue on the same cycle but both facts persist`() {
        val capPlan = planWithCap(2)
        val s1 = feed(observation = obs(1, mcp = 64f), plan = capPlan)
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 44f), plan = capPlan)
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, stop.reason)
        assertTrue(s2.record.powerLossQualified)
    }

    @Test
    fun `cap is independent of the fatigue streak`() {
        val cfg = config.copy(requireTwoConsecutive = true)
        val capPlan = planWithCap(2)
        val s1 = feed(observation = obs(1, mcp = 64f), config = cfg, plan = capPlan)
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 64f), config = cfg, plan = capPlan)
        val stop = assertIs<PowerLossDecision.Stop>(s2.decision)
        assertEquals(PowerStopReason.MAX_REPS_BACKSTOP, stop.reason)
    }

    @Test
    fun `saved nominal reps below the cap never stop POWER early`() {
        // plan.nominalReps = 12, cap = 20: 15 identical working reps must not stop.
        var state = PowerLossPolicyState.INITIAL
        repeat(15) { i ->
            val step = feed(state = state, observation = obs(i + 1, mcp = 64f))
            assertTrue(step.decision is PowerLossDecision.Continue, "attempt ${i + 1} must continue")
            state = step.state
        }
    }

    // ===== Near-limit warning =====

    @Test
    fun `near-limit warning fires once and terminal supersedes it`() {
        val cfg = config.copy(thresholdPercent = 25) // near-limit at 20%
        val s1 = feed(observation = obs(1, mcp = 1000f), config = cfg)
        // 790 W = 21% loss ≥ 20% near-limit → warning.
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 790f), config = cfg)
        assertIs<PowerLossDecision.NearLimitWarning>(s2.decision)
        // 780 W = 22% loss: warning already latched → continue.
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 780f), config = cfg)
        assertTrue(s3.decision is PowerLossDecision.Continue)
        // 700 W = 30% loss → terminal stop, no warning.
        val s4 = feed(state = s3.state, observation = obs(4, mcp = 700f), config = cfg)
        assertIs<PowerLossDecision.Stop>(s4.decision)
    }

    @Test
    fun `zero-loss baseline at threshold 5 never cues`() {
        val cfg = config.copy(thresholdPercent = 5) // near-limit at 0%
        val s1 = feed(observation = obs(1, mcp = 64f), config = cfg)
        // Rep 2 at zero loss: never a cue despite near-limit = 0.
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 64f), config = cfg)
        assertTrue(s2.decision is PowerLossDecision.Continue)
        // First positive loss cues.
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 63f), config = cfg)
        assertIs<PowerLossDecision.NearLimitWarning>(s3.decision)
    }

    // ===== Termination latch =====

    @Test
    fun `state after stop is terminated and later observations are ignored`() {
        val s1 = feed(observation = obs(1, mcp = 64f))
        val s2 = feed(state = s1.state, observation = obs(2, mcp = 48f))
        assertTrue(s2.state.terminated)
        val s3 = feed(state = s2.state, observation = obs(3, mcp = 48f))
        assertTrue(s3.decision is PowerLossDecision.Continue)
        assertTrue(s3.state.terminated)
    }

    // ===== Config validation =====

    @Test
    fun `config validation rejects out-of-range values without clamping`() {
        assertTrue(PowerLossConfig.DEFAULT.isValid())
        assertNull(PowerLossConfig(thresholdPercent = 4).validated())
        assertNull(PowerLossConfig(thresholdPercent = 41).validated())
        assertTrue(PowerLossConfig(thresholdPercent = 5).isValid())
        assertTrue(PowerLossConfig(thresholdPercent = 40).isValid())
        assertNull(PowerLossConfig(maxWorkingReps = 1).validated())
        assertNotNull(PowerLossConfig(maxWorkingReps = 2).validated())
    }

    @Test
    fun `near-limit offset floors at zero`() {
        assertEquals(0, PowerLossConfig(thresholdPercent = 5).nearLimitLossPercent)
        assertEquals(15, PowerLossConfig(thresholdPercent = 20).nearLimitLossPercent)
    }

    @Test
    fun `unknown persisted target mode never resolves to POWER`() {
        assertEquals(TargetMode.POWER, TargetMode.fromPersisted("POWER"))
        assertEquals(TargetMode.REP_TARGET, TargetMode.fromPersisted("REP_TARGET"))
        assertNull(TargetMode.fromPersisted("power"))
        assertNull(TargetMode.fromPersisted(null))
        assertNull(TargetMode.fromPersisted("FUTURE_MODE"))
    }
}
