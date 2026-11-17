package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.powerloss.CableRom
import com.devil.phoenixproject.domain.powerloss.CableRomTrust
import com.devil.phoenixproject.domain.powerloss.ConcentricPhaseNormalizer
import com.devil.phoenixproject.domain.powerloss.MeanConcentricPower
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossDecision
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossHudState
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.PowerLossSetSummaryFacts
import com.devil.phoenixproject.domain.powerloss.PowerLossObservation
import com.devil.phoenixproject.domain.powerloss.PowerLossPolicy
import com.devil.phoenixproject.domain.powerloss.PowerLossPolicyState
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers
import com.devil.phoenixproject.domain.powerloss.ValidatedPairedSample
import com.devil.phoenixproject.domain.model.SetEndReason

/**
 * Issue #1392 — per-set power-loss policy runner (P3 seam).
 *
 * Owns the pure reducer state for one active set and converts completed-rep
 * metric segments into sealed observations. Control input comes ONLY from
 * pre-EMA validated paired samples run through [ConcentricPhaseNormalizer] —
 * never from midpoint/first-half fallback segmentation (SD1).
 *
 * Warmups neither tally nor trigger. The working ordinal counts working reps
 * observed in this set (pending-attempt semantics: the rep being processed is
 * the current attempt).
 */
internal class PowerLossSetController {

    private var state: PowerLossPolicyState = PowerLossPolicyState.INITIAL
    private var workingOrdinal = 0

    /** Near-limit cue latch (once per set). Backed by the reducer state too. */
    private var nearLimitCueEmitted = false

    // ===== Issue #1392 P3: deep-frozen evidence (immutable winner latch) =====

    /** Ordered per-attempt records for P4 evidence / P5 summary+history. */
    private val attemptRecords = mutableListOf<PowerLossAttemptRecord>()
    private var lastConfig: com.devil.phoenixproject.domain.powerloss.PowerLossConfig? = null
    private var frozenActiveCables: Set<PowerCable> = setOf(PowerCable.A, PowerCable.B)
    /** Immutable winning reason; latched once, never overwritten (SD2). */
    private var winnerReason: PowerStopReason? = null
    private var shortRomObserved = false
    private var powerLossObserved = false
    private var lossAtStop: Float? = null
    private var stoppedAtOrdinal: Int? = null

    fun reset() {
        state = PowerLossPolicyState.INITIAL
        workingOrdinal = 0
        nearLimitCueEmitted = false
        attemptRecords.clear()
        lastConfig = null
        frozenActiveCables = setOf(PowerCable.A, PowerCable.B)
        winnerReason = null
        shortRomObserved = false
        powerLossObserved = false
        lossAtStop = null
        stoppedAtOrdinal = null
    }

    /**
     * Feed one completed rep's raw metric segment. Returns the reducer step, or
     * null when the runtime is not in POWER mode.
     */
    fun onRep(
        config: com.devil.phoenixproject.domain.powerloss.PowerLossConfig,
        plan: com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan,
        isWarmup: Boolean,
        repMetrics: List<WorkoutMetric>,
    ): PowerLossDecision? {
        if (!isWarmup) workingOrdinal++
        val observation = PowerLossObservation(
            workingConcentricOrdinal = if (isWarmup) 0 else workingOrdinal,
            isWarmup = isWarmup,
            mcpWatts = computeMcp(repMetrics)?.takeIf { it > 0f },
            romA = romFor(repMetrics, PowerCable.A),
            romB = romFor(repMetrics, PowerCable.B),
            activeCables = activeCables(repMetrics),
        )
        return onObservation(config, plan, observation)
    }

    /**
     * Policy step + evidence capture for one sealed observation (P3 latch).
     * Extracted from [onRep] so fixtures can drive the immutable winner latch
     * directly. Both qualification facts are persisted together (SD2); the
     * winner is latched once and never overwritten.
     */
    internal fun onObservation(
        config: com.devil.phoenixproject.domain.powerloss.PowerLossConfig,
        plan: com.devil.phoenixproject.domain.powerloss.ExecutionTargetPlan,
        observation: PowerLossObservation,
    ): PowerLossDecision {
        val step = PowerLossPolicy.observe(state, observation, config, plan)
        state = step.state
        lastConfig = config
        if (!observation.isWarmup) {
            if (attemptRecords.isEmpty()) {
                frozenActiveCables = observation.activeCables
            }
            attemptRecords += PowerLossAttemptRecord(
                workingConcentricOrdinal = step.record.workingConcentricOrdinal,
                completed = true,
                mcpWatts = step.record.mcpWatts,
                lossPercentAtDecision = step.record.lossPercentAtDecision,
                referenceWorkingOrdinal = step.record.referenceWorkingOrdinal,
                romAMm = observation.romA.romMm,
                romBMm = observation.romB.romMm,
                romAPercentOfReference = step.record.romAPercentOfReference,
                romBPercentOfReference = step.record.romBPercentOfReference,
                romTrustworthy = observation.romA.isTrusted || observation.romB.isTrusted,
                shortRom = step.record.shortRomQualified,
                powerLossQualified = step.record.powerLossQualified,
            )
            if (step.record.shortRomQualified) shortRomObserved = true
            if (step.record.powerLossQualified) powerLossObserved = true
        }
        if (step.decision is PowerLossDecision.Stop && winnerReason == null) {
            winnerReason = step.decision.reason
            lossAtStop = step.decision.lossPercentAtDecision
            stoppedAtOrdinal = step.record.workingConcentricOrdinal
        }
        return step.decision
    }

    /** True until the once-per-set near-limit cue has been emitted. */
    fun consumeNearLimitCue(): Boolean {
        if (nearLimitCueEmitted) return false
        nearLimitCueEmitted = true
        return true
    }

    /**
     * Deep-frozen evidence for the exit snapshot / P4 companions (P3 latch).
     * Null when POWER was never armed (no config seen) — never fabricated.
     */
    fun buildEvidence(sessionId: String, setId: String): PowerLossSetEvidence? {
        val config = lastConfig ?: return null
        if (attemptRecords.isEmpty() && winnerReason == null) return null
        val reference = state.reference
        return PowerLossSetEvidence(
            sessionId = sessionId,
            setId = setId,
            targetMode = config.targetMode,
            thresholdPercent = config.thresholdPercent,
            maxWorkingReps = config.maxWorkingReps,
            requireTwoConsecutive = config.requireTwoConsecutive,
            savedNominalReps = config.savedNominalReps,
            activeCables = frozenActiveCables,
            activeCableProvenance = "rep-metric-load-scan:first-working-attempt",
            referenceWorkingOrdinal = reference?.workingConcentricOrdinal,
            referenceMcpWatts = reference?.mcpWatts,
            referenceRomAMm = reference?.romA?.romMm,
            referenceRomBMm = reference?.romB?.romMm,
            triggerWorkingOrdinal = stoppedAtOrdinal,
            winnerReason = winnerReason,
            shortRomObserved = shortRomObserved,
            powerLossObserved = powerLossObserved,
            lossPercentAtDecision = lossAtStop,
            attempts = attemptRecords.toList(),
        )
    }

    /** Summary facts for `WorkoutState.SetSummary.powerLoss` (P5 renders with SD6 clarification). */
    fun buildSummaryFacts(completedRepCount: Int): PowerLossSetSummaryFacts? {
        val config = lastConfig ?: return null
        return PowerLossSetSummaryFacts(
            winnerReason = winnerReason,
            thresholdPercent = config.thresholdPercent,
            maxWorkingReps = config.maxWorkingReps,
            requireTwoConsecutive = config.requireTwoConsecutive,
            stoppedAtWorkingAttempt = stoppedAtOrdinal,
            completedRepCount = completedRepCount,
            lossPercentAtStop = lossAtStop,
            bestWorkingOrdinal = state.reference?.workingConcentricOrdinal,
            shortRomObserved = shortRomObserved,
            powerLossObserved = powerLossObserved,
            attempts = attemptRecords.toList(),
        )
    }

    /** Live HUD facts (P3 → P5 `WorkoutHud`). [endingSet] is the committed `Ending set…` state. */
    fun hudState(completedRepCount: Int, endingSet: Boolean): PowerLossHudState {
        val config = lastConfig ?: com.devil.phoenixproject.domain.powerloss.PowerLossConfig.DEFAULT
        val lastAttempt = attemptRecords.lastOrNull()
        val lastValid = attemptRecords.lastOrNull { it.mcpWatts != null }
        return PowerLossHudState(
            powerActive = lastConfig != null,
            thresholdPercent = config.thresholdPercent,
            nearLimitLossPercent = config.nearLimitLossPercent,
            maxWorkingReps = config.maxWorkingReps,
            savedNominalReps = config.savedNominalReps,
            currentAttemptOrdinal = workingOrdinal,
            completedRepCount = completedRepCount,
            referenceEstablished = state.reference != null,
            referenceWorkingOrdinal = state.reference?.workingConcentricOrdinal,
            lastLossPercent = lastAttempt?.lossPercentAtDecision,
            lastValidAttemptOrdinal = lastValid?.workingConcentricOrdinal,
            invalidPowerReason = if (lastAttempt != null && lastAttempt.mcpWatts == null) {
                "phase unavailable"
            } else {
                null
            },
            nearLimitCueEmitted = nearLimitCueEmitted,
            cableRomPercentOfReference = buildMap {
                lastAttempt?.romAPercentOfReference?.let { put(PowerCable.A, it) }
                lastAttempt?.romBPercentOfReference?.let { put(PowerCable.B, it) }
            },
            endingSet = endingSet,
        )
    }

    /**
     * MCP V1 from the rep's validated paired samples: the concentric window is
     * derived by [ConcentricPhaseNormalizer] from the full rep segment (the
     * window seals on the next fresh sample after turnaround). Returns null when
     * the phase cannot be established — never a fallback segmentation (SD1).
     */
    private fun computeMcp(repMetrics: List<WorkoutMetric>): Float? {
        if (repMetrics.size < ValidatedPairedSample.MIN_SAMPLES) return null
        val normalizer = ConcentricPhaseNormalizer()
        var sealedWindow: List<ValidatedPairedSample>? = null
        for (metric in repMetrics) {
            val sealed = normalizer.onSample(metric.toValidatedSample())
            if (sealed != null) {
                sealedWindow = sealed
                break
            }
        }
        val window = sealedWindow ?: return null
        return MeanConcentricPower.compute(window).mcpWatts
    }

    private fun romFor(repMetrics: List<WorkoutMetric>, cable: PowerCable): CableRom {
        val positions = repMetrics.map {
            when (cable) {
                PowerCable.A -> it.positionA
                PowerCable.B -> it.positionB
            }
        }
        if (positions.size < ValidatedPairedSample.MIN_SAMPLES) return CableRom.UNKNOWN
        val rom = (positions.max() - positions.min())
        return if (rom > 0f) CableRom.trusted(rom) else CableRom.UNKNOWN
    }

    private fun activeCables(repMetrics: List<WorkoutMetric>): Set<PowerCable> {
        val last = repMetrics.lastOrNull() ?: return setOf(PowerCable.A, PowerCable.B)
        return buildSet {
            if (last.loadA > 0f) add(PowerCable.A)
            if (last.loadB > 0f) add(PowerCable.B)
        }.ifEmpty { setOf(PowerCable.A, PowerCable.B) }
    }
}

/** Map a [PowerStopReason] onto the persisted [SetEndReason] vocabulary. */
internal fun PowerStopReason.toSetEndReason(): SetEndReason = when (this) {
    PowerStopReason.POWER_LOSS -> SetEndReason.POWER_LOSS
    PowerStopReason.SHORT_ROM -> SetEndReason.SHORT_ROM
    PowerStopReason.MAX_REPS_BACKSTOP -> SetEndReason.MAX_REPS_BACKSTOP
}

/**
 * Runtime settings for the active set's power-loss target (P3 seam). Defaults are
 * inert (REP_TARGET) — POWER arms only through explicit per-exercise config
 * behind the capability gate.
 */
internal data class PowerLossRuntimeSettings(
    val targetMode: com.devil.phoenixproject.domain.powerloss.TargetMode =
        com.devil.phoenixproject.domain.powerloss.TargetMode.REP_TARGET,
    val thresholdPercent: Int = 20,
    val maxWorkingReps: Int = 20,
    val requireTwoConsecutive: Boolean = false,
    val savedNominalReps: Int? = null,
    val protocolFamily: com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily =
        com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily.UNSUPPORTED,
    val pairedSamplesValidated: Boolean = false,
) {
    val powerActive: Boolean
        get() = targetMode == com.devil.phoenixproject.domain.powerloss.TargetMode.POWER &&
            com.devil.phoenixproject.domain.powerloss.PowerLossCapability.isEligible(
                protocolFamily = protocolFamily,
                pairedSamplesValidated = pairedSamplesValidated,
            )

    fun toConfig(): com.devil.phoenixproject.domain.powerloss.PowerLossConfig =
        com.devil.phoenixproject.domain.powerloss.PowerLossConfig(
            targetMode = targetMode,
            thresholdPercent = thresholdPercent,
            maxWorkingReps = maxWorkingReps,
            requireTwoConsecutive = requireTwoConsecutive,
            savedNominalReps = savedNominalReps,
        )
}

/** Derive the protocol family for runtime gating from the mode + Just Lift style. */
internal fun protocolFamilyOf(mode: ProgramMode, isJustLift: Boolean) =
    com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily.of(mode, isJustLift)

private fun WorkoutMetric.toValidatedSample(): ValidatedPairedSample = ValidatedPairedSample(
    executionId = "",
    windowOrdinal = 0,
    loadAKg = loadA,
    loadBKg = loadB,
    velocityAMmS = velocityA.toFloat(),
    velocityBMmS = velocityB.toFloat(),
    positionAMm = positionA,
    positionBMm = positionB,
    deviceTicks = ticks,
    monotonicTimestampMs = timestamp,
    quality = SampleQualityMarkers(),
)
