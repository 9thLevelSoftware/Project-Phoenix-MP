package com.devil.phoenixproject.domain.powerloss

/**
 * Issue #1392 — P2 pure policy reducer (SD1–SD4).
 *
 * Deterministic, side-effect-free: observations + baseline/streak state in,
 * [PowerLossDecision] out. No BLE, SQL, UI, or audio. Every behavior here is
 * fixture-determined by the signoff §4.1 matrix (`PowerLossPolicyTest`).
 *
 * Key rules (signoff binding decisions win over every older artifact):
 * - SD1: control uses MCP V1 only; a trusted short-ROM qualification may survive
 *   missing power; unavailable power alone never qualifies; a streak resets only
 *   on an observation with **neither** qualifying reason.
 * - SD2: SHORT_ROM wins over POWER_LOSS in the same sealed observation; both
 *   facts are persisted; cap outranks fatigue on the same cycle.
 * - SD3: unknown/untrusted ROM never promotes (upgrades or establishes past)
 *   the reference; missing candidate ROM is neither fabricated full-ROM nor a
 *   short-ROM stop; reference-at-decision facts are frozen before any update.
 * - SD4: cap enforcement is independent of telemetry quality and fatigue streak.
 */

/** ROM trust for one cable's measurement window. */
enum class CableRomTrust { TRUSTED, UNKNOWN }

/** Per-cable ROM observation. `romMm == null` when no trusted measurement exists. */
data class CableRom(
    val romMm: Float?,
    val trust: CableRomTrust,
) {
    /** A trusted ROM measurement strictly above zero. */
    val isTrusted: Boolean get() = trust == CableRomTrust.TRUSTED && romMm != null && romMm > 0f

    companion object {
        val UNKNOWN = CableRom(romMm = null, trust = CableRomTrust.UNKNOWN)

        fun trusted(romMm: Float): CableRom = CableRom(romMm = romMm, trust = CableRomTrust.TRUSTED)
    }
}

/**
 * One sealed working (or warmup) observation fed to the reducer.
 *
 * @property workingConcentricOrdinal Stable `(session, execution, working concentric ordinal)`
 *   identity; warmups carry no ordinal semantics and never tally.
 * @property mcpWatts MCP V1 watts, null when the measurement is unavailable/invalid.
 * @property activeCables Frozen active-cable mask used for ROM qualification.
 */
data class PowerLossObservation(
    val workingConcentricOrdinal: Int,
    val isWarmup: Boolean = false,
    val mcpWatts: Float? = null,
    val romA: CableRom = CableRom.UNKNOWN,
    val romB: CableRom = CableRom.UNKNOWN,
    val activeCables: Set<PowerCable> = setOf(PowerCable.A, PowerCable.B),
) {
    fun romFor(cable: PowerCable): CableRom = when (cable) {
        PowerCable.A -> romA
        PowerCable.B -> romB
    }

    /** True when MCP is a usable positive measurement (zero/negative never qualifies). */
    val hasValidMcp: Boolean get() = mcpWatts != null && mcpWatts > 0f
}

/** Reference facts frozen at establishment/replacement (SD3). */
data class PowerLossReference(
    val workingConcentricOrdinal: Int,
    val mcpWatts: Float,
    val romA: CableRom,
    val romB: CableRom,
) {
    fun romFor(cable: PowerCable): CableRom = when (cable) {
        PowerCable.A -> romA
        PowerCable.B -> romB
    }
}

/** Reducer state. Immutable; each [PowerLossPolicy.observe] returns the successor. */
data class PowerLossPolicyState(
    val reference: PowerLossReference? = null,
    val observedWorkingAttempts: Int = 0,
    val consecutiveQualifyingCount: Int = 0,
    val nearLimitWarned: Boolean = false,
    val terminated: Boolean = false,
    /** Last observed working ordinal; adjacency gaps reset the streak. */
    val lastWorkingConcentricOrdinal: Int? = null,
) {
    companion object {
        val INITIAL = PowerLossPolicyState()
    }
}

/** Terminal stop reason persisted via `SetEndReason` mapping (P3). */
enum class PowerStopReason {
    /** `1 − MCP_n/MCP_best ≥ threshold`. */
    POWER_LOSS,

    /** Trusted short-ROM observation (< 85% of reference ROM on any active cable). */
    SHORT_ROM,

    /** Mandatory max-reps backstop (SD4). */
    MAX_REPS_BACKSTOP,
}

/** Outcome of one observation. */
sealed interface PowerLossDecision {
    /** No action; keep working. */
    data object Continue : PowerLossDecision

    /** Once-per-set near-limit warning at `max(0, threshold − 5)` loss. */
    data class NearLimitWarning(
        val lossPercent: Float,
        val referenceWorkingOrdinal: Int,
    ) : PowerLossDecision

    /** Set termination. Facts are frozen at decision time (SD3). */
    data class Stop(
        val reason: PowerStopReason,
        val lossPercentAtDecision: Float?,
        val referenceWorkingOrdinal: Int?,
        val powerLossQualified: Boolean,
        val shortRomQualified: Boolean,
    ) : PowerLossDecision
}

/**
 * Persisted per-observation facts (P4 companion row): both qualification facts
 * always persisted together with measured loss when valid (SD2).
 */
data class PowerLossObservationRecord(
    val workingConcentricOrdinal: Int,
    val isWarmup: Boolean,
    val mcpWatts: Float?,
    val lossPercentAtDecision: Float?,
    val referenceWorkingOrdinal: Int?,
    val powerLossQualified: Boolean,
    val shortRomQualified: Boolean,
    val romAPercentOfReference: Float?,
    val romBPercentOfReference: Float?,
    val activeCables: Set<PowerCable>,
) {
    /** Display helper: clamped 0–100 (P2 acceptance). */
    fun displayLossPercent(): Float? = lossPercentAtDecision?.coerceIn(0f, 100f)
}

/** One reducer step: decision + record + successor state. */
data class PowerLossStep(
    val decision: PowerLossDecision,
    val record: PowerLossObservationRecord,
    val state: PowerLossPolicyState,
)

/** Pure deterministic power-loss policy reducer. */
object PowerLossPolicy {

    /** Short-ROM bound: strictly below 85% of the reference ROM is short (equality is not). */
    const val SHORT_ROM_FRACTION = 0.85

    /** SD1 guard: ≥2 working reps before any fatigue trigger (earliest 2nd working attempt). */
    const val MIN_ATTEMPTS_FOR_TRIGGER = 2

    fun observe(
        state: PowerLossPolicyState,
        observation: PowerLossObservation,
        config: PowerLossConfig,
        plan: ExecutionTargetPlan,
    ): PowerLossStep {
        if (state.terminated) {
            return PowerLossStep(
                decision = PowerLossDecision.Continue,
                record = recordOf(observation, loss = null, referenceOrdinal = null, powerLoss = false, shortRom = false),
                state = state,
            )
        }

        // Warmups: no reference, no tally, never trigger.
        if (observation.isWarmup) {
            return PowerLossStep(
                decision = PowerLossDecision.Continue,
                record = recordOf(observation, loss = null, referenceOrdinal = null, powerLoss = false, shortRom = false),
                state = state,
            )
        }

        val frozenRef = state.reference
        val attemptOrdinal = state.observedWorkingAttempts + 1

        // SD3: freeze reference-at-decision facts before any reference update.
        val romPercents = romPercentOfReference(observation, frozenRef)
        // Unrounded loss in double precision: `100 × (1 − MCP/bestMCP)` compared
        // against thresholdPercent without any display rounding (P2 acceptance).
        val lossDouble: Double? = when {
            frozenRef != null && observation.hasValidMcp ->
                100.0 * (1.0 - (observation.mcpWatts!!.toDouble() / frozenRef.mcpWatts.toDouble()))
            frozenRef == null && observation.hasValidMcp -> 0.0 // own decision-time reference
            else -> null
        }
        val lossUnrounded: Float? = lossDouble?.toFloat()
        val referenceOrdinal: Int? = when {
            frozenRef != null -> frozenRef.workingConcentricOrdinal
            observation.hasValidMcp -> observation.workingConcentricOrdinal
            else -> null
        }

        // SD2: SHORT_ROM qualification survives missing power (SD1).
        val shortRomQualified = isShortRomQualified(observation, frozenRef)
        val powerLossQualified =
            lossDouble != null &&
                frozenRef != null &&
                lossDouble >= config.thresholdPercent.toDouble() &&
                attemptOrdinal >= MIN_ATTEMPTS_FOR_TRIGGER

        val qualifying = powerLossQualified || shortRomQualified

        // P2: invalid/missing/skipped ordinals break adjacency — a streak survives
        // only across strictly consecutive working concentric ordinals.
        val ordinalAdjacent = state.lastWorkingConcentricOrdinal == null ||
            observation.workingConcentricOrdinal == state.lastWorkingConcentricOrdinal + 1
        val priorStreak = if (ordinalAdjacent) state.consecutiveQualifyingCount else 0

        // SD4: cap is independent of telemetry quality and fatigue streak; SD2: cap > fatigue.
        val capReached = plan.capStopAtWorkingAttempt(attemptOrdinal)

        val record = recordOf(
            observation = observation,
            loss = lossUnrounded,
            referenceOrdinal = referenceOrdinal,
            powerLoss = powerLossQualified,
            shortRom = shortRomQualified,
            romAPercent = romPercents[PowerCable.A],
            romBPercent = romPercents[PowerCable.B],
        )

        val decision: PowerLossDecision = when {
            capReached -> PowerLossDecision.Stop(
                reason = PowerStopReason.MAX_REPS_BACKSTOP,
                lossPercentAtDecision = lossUnrounded,
                referenceWorkingOrdinal = referenceOrdinal,
                powerLossQualified = powerLossQualified,
                shortRomQualified = shortRomQualified,
            )
            qualifying && fatigueStopAllowed(priorStreak, config) -> PowerLossDecision.Stop(
                // SD2: SHORT_ROM wins over POWER_LOSS in the same observation.
                reason = if (shortRomQualified) PowerStopReason.SHORT_ROM else PowerStopReason.POWER_LOSS,
                lossPercentAtDecision = lossUnrounded,
                referenceWorkingOrdinal = referenceOrdinal,
                powerLossQualified = powerLossQualified,
                shortRomQualified = shortRomQualified,
            )
            shouldWarnNearLimit(state, config, lossUnrounded, frozenRef) -> {
                PowerLossDecision.NearLimitWarning(
                    lossPercent = lossUnrounded!!,
                    referenceWorkingOrdinal = referenceOrdinal!!,
                )
            }
            else -> PowerLossDecision.Continue
        }

        val stopped = decision is PowerLossDecision.Stop
        val nextState = PowerLossPolicyState(
            reference = updatedReference(state, observation, frozenRef),
            observedWorkingAttempts = attemptOrdinal,
            // SD1: only an observation with neither qualifying reason resets the streak.
            consecutiveQualifyingCount = if (qualifying) priorStreak + 1 else 0,
            nearLimitWarned = state.nearLimitWarned || decision is PowerLossDecision.NearLimitWarning || stopped,
            terminated = stopped,
            lastWorkingConcentricOrdinal = observation.workingConcentricOrdinal,
        )
        return PowerLossStep(decision = decision, record = record, state = nextState)
    }

    private fun fatigueStopAllowed(priorStreak: Int, config: PowerLossConfig): Boolean =
        !config.requireTwoConsecutive || priorStreak >= 1

    private fun shouldWarnNearLimit(
        state: PowerLossPolicyState,
        config: PowerLossConfig,
        loss: Float?,
        frozenRef: PowerLossReference?,
    ): Boolean {
        if (state.nearLimitWarned) return false
        if (loss == null || frozenRef == null) return false
        // Zero-loss baseline never cues (at threshold 5 this is "first positive loss").
        return loss > 0f && loss >= config.nearLimitLossPercent
    }

    /**
     * SD2: an ANY-active-cable strictly-<85% trusted ROM observation qualifies;
     * equality is not short; one short arm cannot hide; missing/untrusted candidate
     * ROM is never itself a short stop (SD3). The 85% bound is computed in double
     * precision so exact-equality ROMs are never misclassified as short.
     */
    private fun isShortRomQualified(
        observation: PowerLossObservation,
        frozenRef: PowerLossReference?,
    ): Boolean {
        if (frozenRef == null) return false
        return observation.activeCables.any { cable ->
            val obsRom = observation.romFor(cable)
            val refRom = frozenRef.romFor(cable)
            obsRom.isTrusted && refRom.isTrusted &&
                obsRom.romMm!!.toDouble() < SHORT_ROM_FRACTION.toDouble() * refRom.romMm!!.toDouble()
        }
    }

    /** Per-cable ROM as percent of reference ROM where both trusted (SD6 display). */
    private fun romPercentOfReference(
        observation: PowerLossObservation,
        frozenRef: PowerLossReference?,
    ): Map<PowerCable, Float?> =
        PowerCable.entries.associateWith { cable ->
            val obsRom = observation.romFor(cable)
            val refRom = frozenRef?.romFor(cable)
            if (obsRom.isTrusted && refRom != null && refRom.isTrusted) {
                100f * (obsRom.romMm!! / refRom.romMm!!)
            } else {
                null
            }
        }

    /**
     * SD3 reference rules: establishment from any positive-MCP working rep;
     * replacement only by a strictly-better MCP candidate with trusted sufficient
     * ROM on **every** active cable (unknown ROM never promotes; short reps never
     * replace the reference). Earliest-tie retention: equal MCP keeps the earlier.
     */
    private fun updatedReference(
        state: PowerLossPolicyState,
        observation: PowerLossObservation,
        frozenRef: PowerLossReference?,
    ): PowerLossReference? {
        if (!observation.hasValidMcp) return frozenRef
        val mcp = observation.mcpWatts!!
        if (frozenRef == null) {
            return PowerLossReference(
                workingConcentricOrdinal = observation.workingConcentricOrdinal,
                mcpWatts = mcp,
                romA = observation.romA,
                romB = observation.romB,
            )
        }
        if (mcp <= frozenRef.mcpWatts) return frozenRef // earliest-tie retention
        val eligible = observation.activeCables.isNotEmpty() && observation.activeCables.all { cable ->
            val obsRom = observation.romFor(cable)
            val refRom = frozenRef.romFor(cable)
            obsRom.isTrusted && refRom.isTrusted &&
                obsRom.romMm!!.toDouble() >= SHORT_ROM_FRACTION.toDouble() * refRom.romMm!!.toDouble()
        }
        if (!eligible) return frozenRef
        return PowerLossReference(
            workingConcentricOrdinal = observation.workingConcentricOrdinal,
            mcpWatts = mcp,
            romA = observation.romA,
            romB = observation.romB,
        )
    }

    private fun recordOf(
        observation: PowerLossObservation,
        loss: Float?,
        referenceOrdinal: Int?,
        powerLoss: Boolean,
        shortRom: Boolean,
        romAPercent: Float? = null,
        romBPercent: Float? = null,
    ): PowerLossObservationRecord = PowerLossObservationRecord(
        workingConcentricOrdinal = observation.workingConcentricOrdinal,
        isWarmup = observation.isWarmup,
        mcpWatts = observation.mcpWatts,
        lossPercentAtDecision = loss,
        referenceWorkingOrdinal = referenceOrdinal,
        powerLossQualified = powerLoss,
        shortRomQualified = shortRom,
        romAPercentOfReference = romAPercent,
        romBPercentOfReference = romBPercent,
        activeCables = observation.activeCables,
    )
}
