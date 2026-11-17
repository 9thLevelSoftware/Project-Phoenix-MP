package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.PowerLossAttemptCompanion as PowerLossAttemptCompanionRow
import com.devil.phoenixproject.database.PowerLossSetCompanion as PowerLossSetCompanionRow
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers
import com.devil.phoenixproject.domain.powerloss.TargetMode

/**
 * Issue #1392 (packet P4) — durable power-loss companions.
 *
 * Persists [PowerLossSetEvidence] / [PowerLossAttemptRecord] (the frozen cross-packet
 * contract in `domain/powerloss/PowerLossEvidence.kt`) into the additive nullable
 * companion tables. Contract invariants enforced here:
 *
 *  - **Idempotent retry:** companion writes are INSERT-if-absent on their stable keys,
 *    inside the caller's transaction. A retried commit is a no-op; a partially failed
 *    one is filled in. Never `INSERT OR REPLACE` (no parent rows exist to cascade).
 *  - **Attempt-only rows** (`completed = 0`, no legacy `RepMetric` row) are first-class.
 *    The attempt table carries NO foreign key to `RepMetric`, so the completion commit's
 *    wholesale rep-row replacement can never cascade attempts away.
 *  - **Null-vs-zero:** missing data is stored and returned as NULL, never 0.
 *  - **No wire semantics:** nothing in these tables is pushed to the portal
 *    (no new wire keys; legacy `avgPowerWatts`/`powerWatts` untouched).
 *  - **Strict readers fail safe:** unknown persisted enum values never arm POWER —
 *    a set row with an unknown [TargetMode] is skipped (not coerced), an unknown
 *    winner reason reads as null (no power-policy stop claimed).
 */
internal object PowerLossCompanionStore {

    /** Serializes the frozen active-cable mask as stable enum names, e.g. `A,B`. */
    internal fun encodeActiveCables(cables: Set<PowerCable>): String =
        cables.map { it.name }.sorted().joinToString(",")

    /**
     * Strict mask parse. Unknown names fail the whole row safe (returns null) —
     * a mask with unrecognizable members is never silently treated as a known mask.
     */
    internal fun decodeActiveCables(encoded: String): Set<PowerCable>? {
        if (encoded.isEmpty()) return emptySet()
        val result = mutableSetOf<PowerCable>()
        for (token in encoded.split(',')) {
            val cable = PowerCable.entries.firstOrNull { it.name == token.trim() } ?: return null
            result += cable
        }
        return result
    }

    /** Bit-flag serialization of the per-sample quality markers (`C`,`R`,`S`,`G`). */
    internal fun encodeQuality(quality: SampleQualityMarkers): String = buildString {
        if (quality.clamped) append('C')
        if (quality.rejected) append('R')
        if (quality.stale) append('S')
        if (quality.gapBefore) append('G')
    }

    /** Strict quality parse: known flags decoded; unknown tokens are preserved-ignorable. */
    internal fun decodeQuality(encoded: String): SampleQualityMarkers = SampleQualityMarkers(
        clamped = 'C' in encoded,
        rejected = 'R' in encoded,
        stale = 'S' in encoded,
        gapBefore = 'G' in encoded,
    )

    /**
     * Writes the set companion and every attempt companion in the caller's
     * transaction. Idempotent on retry (INSERT-if-absent). Immutable contract:
     * an existing row for the same stable key is left untouched.
     */
    internal fun commitEvidence(
        queries: PhoenixDatabaseQueries,
        evidence: PowerLossSetEvidence,
        nowMs: Long,
    ) {
        queries.insertPowerLossSetCompanionRowIfAbsent(evidence, nowMs)
        for (attempt in evidence.attempts) {
            queries.insertPowerLossAttemptCompanionRowIfAbsent(evidence.sessionId, evidence.setId, attempt)
        }
    }

    /**
     * Reads a session's persisted evidence, newest set first. Rows with unknown
     * persisted enum values are skipped (fail safe, never armed) and logged.
     */
    internal fun readEvidence(
        queries: PhoenixDatabaseQueries,
        sessionId: String,
    ): List<PowerLossSetEvidence> {
        val attemptsBySet = queries.selectPowerLossAttemptCompanionsBySession(sessionId)
            .executeAsList()
            .groupBy({ it.set_id }, { it.toAttemptRecord() })
        return queries.selectPowerLossSetCompanionsBySession(sessionId)
            .executeAsList()
            .mapNotNull { row -> row.toSetEvidence(attemptsBySet[row.set_id].orEmpty()) }
    }
}

/** INSERT-if-absent: retry-safe, immutable, API-26 safe (no upsert syntax). */
internal fun PhoenixDatabaseQueries.insertPowerLossSetCompanionRowIfAbsent(
    evidence: PowerLossSetEvidence,
    nowMs: Long,
) {
    val targetMode = evidence.targetMode.name
    insertPowerLossSetCompanionIfAbsent(
        session_id = evidence.sessionId,
        set_id = evidence.setId,
        metric_version = evidence.metricVersion.toLong(),
        target_mode = targetMode,
        threshold_percent = evidence.thresholdPercent.toLong(),
        max_working_reps = evidence.maxWorkingReps?.toLong(),
        require_two_consecutive = if (evidence.requireTwoConsecutive) 1L else 0L,
        saved_nominal_reps = evidence.savedNominalReps?.toLong(),
        active_cables = PowerLossCompanionStore.encodeActiveCables(evidence.activeCables),
        active_cable_provenance = evidence.activeCableProvenance,
        reference_working_ordinal = evidence.referenceWorkingOrdinal?.toLong(),
        reference_mcp_watts = evidence.referenceMcpWatts?.toDouble(),
        reference_rom_a_mm = evidence.referenceRomAMm?.toDouble(),
        reference_rom_b_mm = evidence.referenceRomBMm?.toDouble(),
        trigger_working_ordinal = evidence.triggerWorkingOrdinal?.toLong(),
        winner_reason = evidence.winnerReason?.name,
        short_rom_observed = if (evidence.shortRomObserved) 1L else 0L,
        power_loss_observed = if (evidence.powerLossObserved) 1L else 0L,
        loss_percent_at_decision = evidence.lossPercentAtDecision?.toDouble(),
        created_at = nowMs,
    )
}

/** INSERT-if-absent per `(session, working concentric ordinal)` identity. */
internal fun PhoenixDatabaseQueries.insertPowerLossAttemptCompanionRowIfAbsent(
    sessionId: String,
    setId: String,
    attempt: PowerLossAttemptRecord,
) {
    insertPowerLossAttemptCompanionIfAbsent(
        session_id = sessionId,
        set_id = setId,
        working_concentric_ordinal = attempt.workingConcentricOrdinal.toLong(),
        legacy_rep_index = attempt.legacyRepIndex?.toLong(),
        completed = if (attempt.completed) 1L else 0L,
        mcp_watts = attempt.mcpWatts?.toDouble(),
        eccentric_power_watts = attempt.eccentricPowerWatts?.toDouble(),
        concentric_duration_ms = attempt.concentricDurationMs,
        eccentric_duration_ms = attempt.eccentricDurationMs,
        loss_percent_at_decision = attempt.lossPercentAtDecision?.toDouble(),
        reference_working_ordinal = attempt.referenceWorkingOrdinal?.toLong(),
        rom_a_mm = attempt.romAMm?.toDouble(),
        rom_b_mm = attempt.romBMm?.toDouble(),
        rom_a_percent_of_reference = attempt.romAPercentOfReference?.toDouble(),
        rom_b_percent_of_reference = attempt.romBPercentOfReference?.toDouble(),
        rom_trustworthy = if (attempt.romTrustworthy) 1L else 0L,
        quality = PowerLossCompanionStore.encodeQuality(attempt.quality),
        short_rom = if (attempt.shortRom) 1L else 0L,
        power_loss_qualified = if (attempt.powerLossQualified) 1L else 0L,
    )
}

private fun PowerLossAttemptCompanionRow.toAttemptRecord(): PowerLossAttemptRecord = PowerLossAttemptRecord(
    workingConcentricOrdinal = working_concentric_ordinal.toInt(),
    completed = completed != 0L,
    legacyRepIndex = legacy_rep_index?.toInt(),
    mcpWatts = mcp_watts?.toFloat(),
    eccentricPowerWatts = eccentric_power_watts?.toFloat(),
    concentricDurationMs = concentric_duration_ms,
    eccentricDurationMs = eccentric_duration_ms,
    lossPercentAtDecision = loss_percent_at_decision?.toFloat(),
    referenceWorkingOrdinal = reference_working_ordinal?.toInt(),
    romAMm = rom_a_mm?.toFloat(),
    romBMm = rom_b_mm?.toFloat(),
    romAPercentOfReference = rom_a_percent_of_reference?.toFloat(),
    romBPercentOfReference = rom_b_percent_of_reference?.toFloat(),
    romTrustworthy = rom_trustworthy != 0L,
    quality = PowerLossCompanionStore.decodeQuality(quality),
    shortRom = short_rom != 0L,
    powerLossQualified = power_loss_qualified != 0L,
)

private fun PowerLossSetCompanionRow.toSetEvidence(
    attempts: List<PowerLossAttemptRecord>,
): PowerLossSetEvidence? {
    // Strict readers fail safe: an unknown target mode must never arm POWER.
    val targetMode = TargetMode.fromPersisted(target_mode)
    if (targetMode == null) {
        Logger.w {
            "PowerLossCompanionStore: skipping set companion $session_id/$set_id with unknown target mode '$target_mode'"
        }
        return null
    }
    val cables = PowerLossCompanionStore.decodeActiveCables(active_cables)
    if (cables == null) {
        Logger.w {
            "PowerLossCompanionStore: skipping set companion $session_id/$set_id with unknown active-cable mask '$active_cables'"
        }
        return null
    }
    // Unknown winner reasons read as null (no power-policy stop claimed); the raw
    // value stays inspectable in the row itself.
    val winner = winner_reason?.let { reason ->
        PowerStopReason.entries.firstOrNull { it.name == reason }.also {
            if (it == null) {
                Logger.w {
                    "PowerLossCompanionStore: unknown winner reason '$reason' on $session_id/$set_id; reading as no power-policy stop"
                }
            }
        }
    }
    return PowerLossSetEvidence(
        sessionId = session_id,
        setId = set_id,
        metricVersion = metric_version.toInt(),
        targetMode = targetMode,
        thresholdPercent = threshold_percent.toInt(),
        maxWorkingReps = max_working_reps?.toInt(),
        requireTwoConsecutive = require_two_consecutive != 0L,
        savedNominalReps = saved_nominal_reps?.toInt(),
        activeCables = cables,
        activeCableProvenance = active_cable_provenance,
        referenceWorkingOrdinal = reference_working_ordinal?.toInt(),
        referenceMcpWatts = reference_mcp_watts?.toFloat(),
        referenceRomAMm = reference_rom_a_mm?.toFloat(),
        referenceRomBMm = reference_rom_b_mm?.toFloat(),
        triggerWorkingOrdinal = trigger_working_ordinal?.toInt(),
        winnerReason = winner,
        shortRomObserved = short_rom_observed != 0L,
        powerLossObserved = power_loss_observed != 0L,
        lossPercentAtDecision = loss_percent_at_decision?.toFloat(),
        attempts = attempts,
    )
}
