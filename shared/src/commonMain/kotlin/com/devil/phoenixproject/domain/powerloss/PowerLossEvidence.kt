package com.devil.phoenixproject.domain.powerloss

/**
 * Issue #1392 — frozen cross-packet evidence contract (P3 engine ↔ P4 persistence ↔ P5 UI).
 *
 * Pure domain types only: no BLE, SQL, UI, or audio references. These types are the
 * single shared vocabulary for:
 *  - P3 (engine): fills [PowerLossSetEvidence] / [PowerLossHudState] at completion-latch
 *    and live-HUD time, and attaches evidence to `WorkoutExitSnapshot.powerLossEvidence`.
 *  - P4 (persistence): persists evidence durably (set + observation companions, backup,
 *    versioned CSV, power-history export v1) with null-vs-zero semantics preserved and
 *    ZERO new wire keys.
 *  - P5 (UI): renders config/HUD/summary/history strictly from these facts.
 *
 * Frozen by the implementation coordinator before packet fan-out. Extend only when a
 * ui-spec state cannot be expressed, and report the extension in the packet handoff.
 *
 * Binding signoff decisions SD1–SD8 apply to every type here: no fabricated zeros
 * (missing data is null with a quality reason), both qualification facts are persisted,
 * reference-at-decision facts are frozen, and legacy payloads stay unchanged.
 */

/** `powerMetricVersion` for the V1 Mean Concentric Power signal (never backfilled from legacy curves). */
const val POWER_METRIC_VERSION: Int = 1

/**
 * One durable per-attempt observation row (P4 observation companion).
 *
 * Attempt-only rows (a working attempt that never completed a rep) are first-class:
 * `completed = false` with a non-null ordinal. Rows never fabricate zeros — every
 * metric is nullable and absence is preserved as null through export/backup.
 *
 * @property workingConcentricOrdinal Stable `(session, execution, working concentric ordinal)`
 *   identity. Warmups are excluded from the tally and are not persisted here.
 * @property completed False for pending/attempt-only identity (candidate ≠ completed).
 * @property legacyRepIndex Optional association with an existing legacy `RepMetric` row
 *   position; null for attempt-only rows with no legacy rep.
 * @property mcpWatts MCP V1 (sample-mean `9.81/1000 × Σ(load × max(v,0))` W) or null when
 *   the concentric phase/samples could not be validated (never 0 as a stand-in).
 * @property eccentricPowerWatts Eccentric power logged separately (Echo: trigger is
 *   concentric-only). Interrupted eccentric = null, not 0.
 * @property lossPercentAtDecision Unrounded `100 × (1 − MCP/bestMCP)` frozen at decision
 *   time (SD3), or null when the attempt had no valid power.
 * @property referenceWorkingOrdinal Reference-at-decision (frozen before any reference
 *   update), or null when no reference existed yet.
 * @property romAMm / romBMm Measured per-cable ROM in mm; null when unavailable.
 * @property romAPercentOfReference / romBPercentOfReference Percent of the reference ROM
 *   where trustworthy; null otherwise (SD3: unknown ROM is never promoted).
 * @property romTrustworthy False for untrusted/unknown ROM windows.
 * @property quality Enumerable quality markers backing a null metric (sample gap, stale
 *   data, rejected sample, buffer overflow, insufficient samples, phase unavailable).
 * @property shortRom True when this observation qualified as a trusted short-ROM
 *   observation (any active cable strictly < 85% of reference; equality is not short).
 * @property powerLossQualified True when this observation qualified as a power-loss
 *   observation. Both facts may be true simultaneously (SD2 persists both).
 */
data class PowerLossAttemptRecord(
    val workingConcentricOrdinal: Int,
    val completed: Boolean,
    val legacyRepIndex: Int? = null,
    val mcpWatts: Float? = null,
    val eccentricPowerWatts: Float? = null,
    val concentricDurationMs: Long? = null,
    val eccentricDurationMs: Long? = null,
    val lossPercentAtDecision: Float? = null,
    val referenceWorkingOrdinal: Int? = null,
    val romAMm: Float? = null,
    val romBMm: Float? = null,
    val romAPercentOfReference: Float? = null,
    val romBPercentOfReference: Float? = null,
    val romTrustworthy: Boolean = false,
    val quality: SampleQualityMarkers = SampleQualityMarkers(),
    val shortRom: Boolean = false,
    val powerLossQualified: Boolean = false,
)

/**
 * Immutable deep-frozen evidence for one set (P4 set companion). Captured at the
 * leased-completion latch, before claim/teardown, and persisted with the completed
 * set (retry/idempotent). Keyed by stable session id + set id.
 */
data class PowerLossSetEvidence(
    val sessionId: String,
    val setId: String,
    val metricVersion: Int = POWER_METRIC_VERSION,
    // Frozen target configuration (SD4: cap independent; saved nominal never stops POWER).
    val targetMode: TargetMode,
    val thresholdPercent: Int,
    val maxWorkingReps: Int?,
    val requireTwoConsecutive: Boolean,
    val savedNominalReps: Int?,
    // Frozen active-cable mask + provenance (SD3).
    val activeCables: Set<PowerCable>,
    val activeCableProvenance: String,
    // Reference-at-decision facts, frozen before any reference update (SD3).
    val referenceWorkingOrdinal: Int?,
    val referenceMcpWatts: Float?,
    val referenceRomAMm: Float?,
    val referenceRomBMm: Float?,
    // Trigger facts.
    val triggerWorkingOrdinal: Int?,
    // Immutable winning reason; null when the set ended without a power-policy stop
    // (manual/safety/REP paths keep their own SetEndReason).
    val winnerReason: PowerStopReason?,
    // Both qualification facts persisted even when simultaneous (SD2).
    val shortRomObserved: Boolean,
    val powerLossObserved: Boolean,
    val lossPercentAtDecision: Float?,
    val attempts: List<PowerLossAttemptRecord> = emptyList(),
)

/**
 * Live HUD facts for the active set (P3 → P5). The UI renders copy from these facts
 * per ui-spec §3–§7 with SD6 corrections; missing data shows `—` with a reason and
 * is never fabricated as zero.
 */
data class PowerLossHudState(
    val powerActive: Boolean = false,
    val thresholdPercent: Int = 20,
    val nearLimitLossPercent: Int = 15,
    val maxWorkingReps: Int? = null,
    val savedNominalReps: Int? = null,
    /** Working-attempt identity of the rep being processed (pending semantics). */
    val currentAttemptOrdinal: Int = 0,
    /** Authoritative completed count (`actualReps`) — never promoted to the attempt count. */
    val completedRepCount: Int = 0,
    val referenceEstablished: Boolean = false,
    val referenceWorkingOrdinal: Int? = null,
    /** Last attempt's loss at decision; null when power was invalid/unavailable. */
    val lastLossPercent: Float? = null,
    /** Ordinal of the last attempt with valid power (for `Last valid · attempt N` labeling). */
    val lastValidAttemptOrdinal: Int? = null,
    /** Enumerable invalid-power reason (quality reason) for the current attempt, or null. */
    val invalidPowerReason: String? = null,
    val nearLimitCueEmitted: Boolean = false,
    /** Per-cable ROM as percent of reference where trustworthy (e.g. `Cable A 82% of reference`). */
    val cableRomPercentOfReference: Map<PowerCable, Float> = emptyMap(),
    /** Committed `Ending set…` state (distinct from the completed transition). */
    val endingSet: Boolean = false,
    /** Latest sealed concentric MCP in watts (`Latest lifting power … W`); null when unavailable. */
    val lastMcpWatts: Float? = null,
    /** Best working MCP in watts (`Best: rep N · … W`); null until a reference exists. */
    val bestMcpWatts: Float? = null,
    /**
     * Two-consecutive qualifying streak (1..2), or null when the requirement is
     * off (`Qualifying attempts: 1 of 2`, SD1). A reset streak is 0 and renders
     * the neutral state, never a stale qualifier line.
     */
    val qualifyingCount: Int? = null,
    /** Initial ROM/mask uncertainty: the reference exists but no trustworthy ROM. */
    val referenceRomUncertain: Boolean = false,
)

/**
 * Summary/history facts for a stopped set (P3 → P5). The headline
 * `Stopped at rep 9 — 21% power loss (best: rep 2)` renders ONLY with the adjacent
 * `8 completed reps · lifting attempt 9` clarification (SD6); [completedRepCount]
 * comes from `actualReps` and is never promoted to match the headline.
 */
data class PowerLossSetSummaryFacts(
    val winnerReason: PowerStopReason?,
    val thresholdPercent: Int,
    val maxWorkingReps: Int?,
    val requireTwoConsecutive: Boolean,
    /** Working attempt at which the set stopped (headline number), or null. */
    val stoppedAtWorkingAttempt: Int?,
    val completedRepCount: Int,
    val lossPercentAtStop: Float?,
    val bestWorkingOrdinal: Int?,
    val shortRomObserved: Boolean,
    val powerLossObserved: Boolean,
    val attempts: List<PowerLossAttemptRecord> = emptyList(),
)
