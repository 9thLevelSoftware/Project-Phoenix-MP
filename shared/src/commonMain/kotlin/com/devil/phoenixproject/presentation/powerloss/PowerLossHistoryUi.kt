package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers

/**
 * Issue #1392 — pure history / per-rep projection (P5, ui-spec §6).
 *
 * Compact core rows + expandable details (NOT the crowded wide table), text
 * best/trigger badges, screen-reader-friendly strings. Attempt-only rows (a
 * working attempt with no completed legacy rep) remain visible. Missing data
 * shows `—` with a reason — NEVER a fabricated zero.
 */
data class PowerLossAttemptRowUi(
    val attemptLabel: String,
    val mcpText: String,
    val mcpMissingReason: String?,
    val lossAtDecisionText: String,
    val referenceAtDecisionText: String,
    val romAText: String,
    val romBText: String,
    val concentricText: String,
    val eccentricText: String,
    val eccentricMissingReason: String?,
    val statusText: String,
    val badges: List<String>,
    /** Expandable detail lines (`label` to `value`). */
    val details: List<Pair<String, String>>,
) {
    /** Compact single-line core row (small screens; no horizontal scrolling). */
    val coreLine: String
        get() = listOf(attemptLabel, mcpText, lossAtDecisionText, statusText + badgeSuffix).joinToString(" · ")

    private val badgeSuffix: String
        get() = if (badges.isEmpty()) "" else " (" + badges.joinToString(", ") + ")"
}

/** One set's per-attempt table (summary `Power details` and history expand). */
data class PowerLossRepTableUi(
    val caption: String = PowerLossUiCopy.DETAILS_CAPTION,
    val rows: List<PowerLossAttemptRowUi>,
    val coreRows: List<String>,
)

/**
 * Build the per-attempt rows from frozen evidence facts.
 *
 * Each historical loss is relative to its reference **at that decision**, never
 * recomputed against the final best; the first row's zero loss is relative to its
 * own decision-time reference (SD6).
 */
fun powerLossRepTable(
    attempts: List<PowerLossAttemptRecord>,
    triggerOrdinal: Int?,
    bestWorkingOrdinal: Int?,
    thresholdPercent: Int,
    nearLimitLossPercent: Int,
    activeCables: Set<PowerCable>,
): PowerLossRepTableUi {
    val rows = attempts.map { record ->
        attemptRow(
            record = record,
            triggerOrdinal = triggerOrdinal,
            bestWorkingOrdinal = bestWorkingOrdinal,
            nearLimitLossPercent = nearLimitLossPercent,
            activeCables = activeCables,
        )
    }
    return PowerLossRepTableUi(rows = rows, coreRows = rows.map { it.coreLine })
}

private fun attemptRow(
    record: PowerLossAttemptRecord,
    triggerOrdinal: Int?,
    bestWorkingOrdinal: Int?,
    nearLimitLossPercent: Int,
    activeCables: Set<PowerCable>,
): PowerLossAttemptRowUi {
    val loss = record.lossPercentAtDecision
    val mcpMissing = record.mcpWatts == null
    val qualityReason = record.quality.reasonText()

    val badges = buildList {
        if (record.workingConcentricOrdinal == bestWorkingOrdinal) add(PowerLossUiCopy.BADGE_BEST)
        if (record.workingConcentricOrdinal == triggerOrdinal) add(PowerLossUiCopy.BADGE_TRIGGER)
        if (loss != null && loss > 0f && loss >= nearLimitLossPercent) add(PowerLossUiCopy.BADGE_NEAR_LIMIT)
        if (mcpMissing) add(PowerLossUiCopy.BADGE_INVALID)
    }

    val romAText = romCableText(record.romAMm, record.romAPercentOfReference, record.romTrustworthy, activeCables.contains(PowerCable.A))
    val romBText = romCableText(record.romBMm, record.romBPercentOfReference, record.romTrustworthy, activeCables.contains(PowerCable.B))

    val details = buildList {
        add(PowerLossUiCopy.COL_REFERENCE to (record.referenceWorkingOrdinal?.toString() ?: PowerLossUiCopy.MISSING))
        add(PowerLossUiCopy.COL_ROM_A to romAText)
        add(PowerLossUiCopy.COL_ROM_B to romBText)
        add(
            PowerLossUiCopy.COL_CONCENTRIC to
                (record.concentricDurationMs?.let { formatMs(it) } ?: PowerLossUiCopy.MISSING),
        )
        add(PowerLossUiCopy.COL_ECCENTRIC to eccentricText(record))
        add(PowerLossUiCopy.COL_STATUS to statusText(record))
        if (qualityReason != null) add("Quality" to qualityReason)
        if (record.shortRom) add("Short range of motion" to "Qualified — trusted short ROM")
        if (record.powerLossQualified) add("Power loss" to "Qualified at decision")
    }

    return PowerLossAttemptRowUi(
        attemptLabel = record.workingConcentricOrdinal.toString(),
        mcpText = record.mcpWatts?.let { "${it.toInt()} W" } ?: PowerLossUiCopy.MISSING,
        mcpMissingReason = if (mcpMissing) qualityReason else null,
        lossAtDecisionText = loss?.let { "${displayPercent(it)}%" } ?: PowerLossUiCopy.MISSING,
        referenceAtDecisionText = record.referenceWorkingOrdinal?.toString() ?: PowerLossUiCopy.MISSING,
        romAText = romAText,
        romBText = romBText,
        concentricText = record.concentricDurationMs?.let { formatMs(it) } ?: PowerLossUiCopy.MISSING,
        eccentricText = eccentricText(record),
        eccentricMissingReason = if (record.eccentricPowerWatts == null) {
            PowerLossUiCopy.ECCENTRIC_INTERRUPTED
        } else {
            null
        },
        statusText = statusText(record),
        badges = badges,
        details = details,
    )
}

private fun statusText(record: PowerLossAttemptRecord): String =
    if (record.completed) PowerLossUiCopy.STATUS_COMPLETED else PowerLossUiCopy.STATUS_PENDING

private fun eccentricText(record: PowerLossAttemptRecord): String = when {
    // Interrupted eccentric is null, not 0 W (SD: never fabricate zeros).
    record.eccentricPowerWatts == null -> PowerLossUiCopy.MISSING
    // A true measured zero stays 0 W.
    else -> "${record.eccentricPowerWatts.toInt()} W"
}

private fun romCableText(
    romMm: Float?,
    percentOfReference: Float?,
    trustworthy: Boolean,
    active: Boolean,
): String = when {
    !active -> PowerLossUiCopy.CABLE_INACTIVE
    romMm == null -> PowerLossUiCopy.MISSING
    !trustworthy || percentOfReference == null -> "${romMm.toInt()} mm · ${PowerLossUiCopy.ROM_UNTRUSTED}"
    else -> "${romMm.toInt()} mm · ${percentOfReference.toInt()}% of reference"
}

internal fun formatMs(ms: Long): String {
    val seconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "$seconds.$tenths s"
}

/** Enumerable quality reason text for a missing metric (ui-spec quality vocabulary). */
internal fun SampleQualityMarkers.reasonText(): String? = buildList {
    if (gapBefore) add("sample gap")
    if (stale) add("stale data")
    if (rejected) add("rejected sample")
    if (clamped) add("clamped sample")
}.joinToString(", ").takeIf { it.isNotEmpty() }

/**
 * History projection for one or more sessions (grouped or single-session view).
 * Attempt-only rows — including a trigger-only row with no completed rep — stay
 * visible even without a legacy `RepMetric` row.
 */
data class PowerLossHistorySetUi(
    val sessionId: String,
    val setId: String,
    val headerLine: String,
    val coreRows: List<String>,
    val table: PowerLossRepTableUi,
    val triggerOnlyRow: String?,
    val disclosure: String = PowerLossUiCopy.SUMMARY_DISCLOSURE,
)

fun powerLossHistorySet(evidence: PowerLossSetEvidence): PowerLossHistorySetUi {
    val table = powerLossRepTable(
        attempts = evidence.attempts,
        triggerOrdinal = evidence.triggerWorkingOrdinal,
        bestWorkingOrdinal = evidence.attempts
            .filter { it.mcpWatts != null && !it.shortRom }
            .maxByOrNull { it.mcpWatts ?: 0f }
            ?.workingConcentricOrdinal,
        thresholdPercent = evidence.thresholdPercent,
        nearLimitLossPercent = maxOf(0, evidence.thresholdPercent - 5),
        activeCables = evidence.activeCables,
    )
    val triggerOnly = evidence.attempts
        .filter { !it.completed }
        .joinToString("\n") { "${PowerLossUiCopy.COL_ATTEMPT} ${it.workingConcentricOrdinal} · ${PowerLossUiCopy.TRIGGER_ONLY_ROW_NOTE}" }
        .takeIf { it.isNotEmpty() }
    return PowerLossHistorySetUi(
        sessionId = evidence.sessionId,
        setId = evidence.setId,
        headerLine = PowerLossUiCopy.summaryConfigLine(
            thresholdPercent = evidence.thresholdPercent,
            maxWorkingReps = evidence.maxWorkingReps,
            requireTwoConsecutive = evidence.requireTwoConsecutive,
        ),
        coreRows = table.coreRows,
        table = table,
        triggerOnlyRow = triggerOnly,
    )
}
