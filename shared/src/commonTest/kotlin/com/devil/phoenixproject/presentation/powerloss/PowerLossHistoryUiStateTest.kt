package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1392 P5 — history grouped + single-session views, attempt-only rows,
 * per-rep columns and missing-data semantics (`—` with reason, never fabricated
 * zeros; ui-spec §6).
 */
class PowerLossHistoryUiStateTest {

    private val evidence = PowerLossSetEvidence(
        sessionId = "session-1",
        setId = "set-1",
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = 12,
        activeCables = setOf(PowerCable.A, PowerCable.B),
        activeCableProvenance = "warmup",
        referenceWorkingOrdinal = 2,
        referenceMcpWatts = 300f,
        referenceRomAMm = 500f,
        referenceRomBMm = 500f,
        triggerWorkingOrdinal = 9,
        winnerReason = com.devil.phoenixproject.domain.powerloss.PowerStopReason.POWER_LOSS,
        shortRomObserved = false,
        powerLossObserved = true,
        lossPercentAtDecision = 21f,
        attempts = listOf(
            PowerLossAttemptRecord(1, completed = true, mcpWatts = 285f, lossPercentAtDecision = 0f, referenceWorkingOrdinal = 1),
            PowerLossAttemptRecord(2, completed = true, mcpWatts = 300f, lossPercentAtDecision = 0f, referenceWorkingOrdinal = 2),
            PowerLossAttemptRecord(
                9,
                completed = false,
                mcpWatts = 237f,
                lossPercentAtDecision = 21f,
                referenceWorkingOrdinal = 2,
                eccentricPowerWatts = null,
                quality = SampleQualityMarkers(),
            ),
        ),
    )

    @Test
    fun `single-session view keeps attempt-only trigger rows visible`() {
        val set = powerLossHistorySet(evidence)
        assertTrue(set.coreRows.any { it.startsWith("9 ") || it.startsWith("9 ·") }, set.coreRows.toString())
        assertEquals(
            "Working attempt 9 · Trigger observation — no completed rep recorded",
            set.triggerOnlyRow,
        )
        assertEquals("Target 20% · Max 20 working reps · Consecutive requirement off", set.headerLine)
    }

    @Test
    fun `attempt-only row shows pending status and trigger badge`() {
        val table = powerLossHistorySet(evidence).table
        val triggerRow = table.rows.first { it.attemptLabel == "9" }
        assertEquals("Pending — not confirmed complete", triggerRow.statusText)
        assertTrue(triggerRow.badges.contains("Trigger"), triggerRow.badges.toString())
        assertTrue(triggerRow.badges.contains("Best").not(), "Trigger 21% below best rep 2 is not Best")
    }

    @Test
    fun `missing data shows an em dash with a reason - never fabricated zeros`() {
        val table = powerLossHistorySet(evidence).table
        val triggerRow = table.rows.first { it.attemptLabel == "9" }
        // Interrupted eccentric: null, not 0 W.
        assertEquals("—", triggerRow.eccentricText)
        assertEquals(
            "Not recorded — set ended before lowering completed",
            triggerRow.eccentricMissingReason,
        )
        assertFalse(triggerRow.eccentricText.contains("0 W"))
    }

    @Test
    fun `true measured zero eccentric stays zero watts`() {
        val row = powerLossRepTable(
            attempts = listOf(
                PowerLossAttemptRecord(1, completed = true, mcpWatts = 285f, eccentricPowerWatts = 0f, lossPercentAtDecision = 0f),
            ),
            triggerOrdinal = null,
            bestWorkingOrdinal = 1,
            thresholdPercent = 20,
            nearLimitLossPercent = 15,
            activeCables = setOf(PowerCable.A, PowerCable.B),
        ).rows.single()
        assertEquals("0 W", row.eccentricText)
    }

    @Test
    fun `per-rep columns carry attempt mcp loss reference rom con-ecc and status`() {
        val row = powerLossRepTable(
            attempts = listOf(
                PowerLossAttemptRecord(
                    workingConcentricOrdinal = 3,
                    completed = true,
                    mcpWatts = 294f,
                    lossPercentAtDecision = 2f,
                    referenceWorkingOrdinal = 2,
                    romAMm = 500f,
                    romBMm = 500f,
                    romAPercentOfReference = 100f,
                    romBPercentOfReference = 100f,
                    romTrustworthy = true,
                    concentricDurationMs = 820L,
                    eccentricPowerWatts = 210f,
                    eccentricDurationMs = 1100L,
                ),
            ),
            triggerOrdinal = null,
            bestWorkingOrdinal = 2,
            thresholdPercent = 20,
            nearLimitLossPercent = 15,
            activeCables = setOf(PowerCable.A, PowerCable.B),
        ).rows.single()
        assertEquals("3", row.attemptLabel)
        assertEquals("294 W", row.mcpText)
        assertEquals("2%", row.lossAtDecisionText)
        assertEquals("2", row.referenceAtDecisionText)
        assertEquals("500 mm · 100% of reference", row.romAText)
        assertEquals("500 mm · 100% of reference", row.romBText)
        assertEquals("0.8 s", row.concentricText)
        assertEquals("210 W", row.eccentricText)
        assertEquals("Completed", row.statusText)
    }

    @Test
    fun `untrusted rom and inactive cables use their explicit labels`() {
        val row = powerLossRepTable(
            attempts = listOf(
                PowerLossAttemptRecord(
                    workingConcentricOrdinal = 1,
                    completed = true,
                    mcpWatts = 285f,
                    lossPercentAtDecision = 0f,
                    romAMm = 480f,
                    romAPercentOfReference = null,
                    romTrustworthy = false,
                ),
            ),
            triggerOrdinal = null,
            bestWorkingOrdinal = 1,
            thresholdPercent = 20,
            nearLimitLossPercent = 15,
            activeCables = setOf(PowerCable.A),
        ).rows.single()
        assertEquals("480 mm · Unavailable — reference not established", row.romAText)
        assertEquals("Not active", row.romBText)
    }

    @Test
    fun `invalid power row carries the quality reason and invalid badge`() {
        val row = powerLossRepTable(
            attempts = listOf(
                PowerLossAttemptRecord(
                    workingConcentricOrdinal = 6,
                    completed = true,
                    mcpWatts = null,
                    lossPercentAtDecision = null,
                    quality = SampleQualityMarkers(gapBefore = true),
                ),
            ),
            triggerOrdinal = null,
            bestWorkingOrdinal = null,
            thresholdPercent = 20,
            nearLimitLossPercent = 15,
            activeCables = setOf(PowerCable.A, PowerCable.B),
        ).rows.single()
        assertEquals("—", row.mcpText)
        assertEquals("sample gap", row.mcpMissingReason)
        assertTrue(row.badges.contains("Invalid"))
    }

    @Test
    fun `best and near-limit are text badges not color only`() {
        val table = powerLossRepTable(
            attempts = listOf(
                PowerLossAttemptRecord(2, completed = true, mcpWatts = 300f, lossPercentAtDecision = 0f, referenceWorkingOrdinal = 2),
                PowerLossAttemptRecord(7, completed = true, mcpWatts = 252f, lossPercentAtDecision = 16f, referenceWorkingOrdinal = 2),
            ),
            triggerOrdinal = null,
            bestWorkingOrdinal = 2,
            thresholdPercent = 20,
            nearLimitLossPercent = 15,
            activeCables = setOf(PowerCable.A, PowerCable.B),
        )
        assertTrue(table.rows[0].badges.contains("Best"))
        assertTrue(table.rows[1].badges.contains("Near-limit"))
    }

    @Test
    fun `grouped view aggregates sessions without dropping attempt rows`() {
        val second = evidence.copy(sessionId = "session-2", setId = "set-2", triggerWorkingOrdinal = 5)
        val grouped = listOf(evidence, second).map { powerLossHistorySet(it) }
        assertEquals(2, grouped.size)
        grouped.forEach { set ->
            assertTrue(set.table.rows.isNotEmpty())
            assertTrue(set.coreRows.size == set.table.rows.size)
        }
    }
}
