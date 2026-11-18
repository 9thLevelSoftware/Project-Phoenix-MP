package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.domain.model.VelocityResult
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.METRIC_UNIT_VERSION_LEGACY
import com.devil.phoenixproject.util.METRIC_UNIT_VERSION_MM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #1400 F1: the completion journal is explicitly metric-unit versioned. A v1
 * payload (pre-unit-fix, cm / cm-s) decodes scaled to mm / mm-s exactly once — the
 * same field families migration 65 scales — and a v2 payload round-trips unchanged.
 */
class Issue1400CompletionJournalUnitVersionTest {

    @Test
    fun `v2 payload round-trips at the mm metric unit version`() {
        val write = legacyWrite().scaledFromLegacyMetricUnits()
        val decoded = assertNotNull(CompletionJournalCodec.decode(CompletionJournalCodec.encode(write)))
        assertEquals(METRIC_UNIT_VERSION_MM, decoded.metricUnitVersion)
        assertEquals(450f, decoded.session.avgMcvMmS)
        assertEquals(350f, decoded.metrics.single().positionA)
        assertEquals(700.0, decoded.metrics.single().velocityA, 0.001)
        assertEquals(700f, decoded.repMetrics.single().peakVelocity)
        assertEquals(350f, decoded.repMetrics.single().rangeOfMotionMm)
        assertEquals(700f, decoded.repBiomechanics.single().velocity.meanConcentricVelocityMmS)
    }

    @Test
    fun `v1 payload scales unit fields exactly once on decode`() {
        // A pre-#1400 writer encoded with v=1 and no metricUnitVersion field.
        val legacy = legacyWrite()
        val payload = CompletionJournalCodec.encode(legacy)
        assertEquals(METRIC_UNIT_VERSION_LEGACY, legacy.metricUnitVersion)

        val decoded = assertNotNull(CompletionJournalCodec.decode(payload))
        assertEquals(METRIC_UNIT_VERSION_MM, decoded.metricUnitVersion)

        // Same field families as migration 65: x10 exactly once.
        assertEquals(450f, decoded.session.avgMcvMmS)
        val metric = decoded.metrics.single()
        assertEquals(350f, metric.positionA)
        assertEquals(360f, metric.positionB)
        assertEquals(700.0, metric.velocityA, 0.001)
        assertEquals(710.0, metric.velocityB, 0.001)
        val rep = decoded.repMetrics.single()
        assertEquals(700f, rep.peakVelocity)
        assertEquals(600f, rep.avgVelocityConcentric)
        assertEquals(500f, rep.avgVelocityEccentric)
        assertEquals(350f, rep.rangeOfMotionMm)
        assertEquals(14000f, rep.peakPowerWatts)
        assertEquals(12000f, rep.avgPowerWatts)
        // The per-sample curves scale with the scalars (same field families as migration 65's
        // Kotlin-side journal recovery); load curves stay untouched.
        assertEquals(350f, rep.concentricPositions.single())
        assertEquals(100f, rep.eccentricPositions.single())
        assertEquals(700f, rep.concentricVelocities.single())
        assertEquals(600f, rep.eccentricVelocities.single())
        assertEquals(20.5f, rep.concentricLoadsA.single())
        val biomech = decoded.repBiomechanics.single()
        assertEquals(700f, biomech.velocity.meanConcentricVelocityMmS)
        assertEquals(800f, biomech.velocity.peakVelocityMmS)

        // Loads, percentages and counts are untouched.
        assertEquals(20.5f, metric.loadA)
        assertEquals(20.5f, decoded.repMetrics.single().peakForceA)
        assertEquals(25f, biomech.velocity.velocityLossPercent)

        // Scaling is applied exactly once: re-encoding the decoded (mm) write and
        // decoding again must not scale again.
        val reEncoded = CompletionJournalCodec.encode(decoded)
        val decodedAgain = assertNotNull(CompletionJournalCodec.decode(reEncoded))
        assertEquals(decoded.session.avgMcvMmS, decodedAgain.session.avgMcvMmS)
        assertEquals(decoded.metrics.single().positionA, decodedAgain.metrics.single().positionA)
    }

    @Test
    fun `unknown journal versions are rejected`() {
        val payload = CompletionJournalCodec.encode(legacyWrite()).replace("\"v\":1", "\"v\":99")
        assertNull(CompletionJournalCodec.decode(payload))
    }

    // ==================== fixtures ====================

    private fun legacyWrite() = PendingChildWrite(
        session = WorkoutSession(
            id = "s-1",
            timestamp = 1_700_000_000_000L,
            mode = "Old School",
            reps = 5,
            weightPerCableKg = 20f,
            duration = 60_000L,
            totalReps = 5,
            warmupReps = 0,
            workingReps = 5,
            avgMcvMmS = 45f,
        ),
        metrics = listOf(
            WorkoutMetric(
                timestamp = 1_700_000_010_000L,
                loadA = 20.5f,
                loadB = 21f,
                positionA = 35f,
                positionB = 36f,
                velocityA = 70.0,
                velocityB = 71.0,
            ),
        ),
        completedSet = null,
        repMetrics = listOf(repMetric()),
        repBiomechanics = listOf(repBiomechanics()),
        metricUnitVersion = METRIC_UNIT_VERSION_LEGACY,
    )

    private fun repMetric() = RepMetricData(
        repNumber = 1,
        isWarmup = false,
        startTimestamp = 1L,
        endTimestamp = 2L,
        durationMs = 1L,
        concentricDurationMs = 1L,
        concentricPositions = floatArrayOf(35f),
        concentricLoadsA = floatArrayOf(20.5f),
        concentricLoadsB = floatArrayOf(21f),
        concentricVelocities = floatArrayOf(70f),
        concentricTimestamps = longArrayOf(0L),
        eccentricDurationMs = 1L,
        eccentricPositions = floatArrayOf(10f),
        eccentricLoadsA = floatArrayOf(20.5f),
        eccentricLoadsB = floatArrayOf(21f),
        eccentricVelocities = floatArrayOf(60f),
        eccentricTimestamps = longArrayOf(1L),
        peakForceA = 20.5f,
        peakForceB = 21f,
        avgForceConcentricA = 20f,
        avgForceConcentricB = 21f,
        avgForceEccentricA = 20f,
        avgForceEccentricB = 21f,
        peakVelocity = 70f,
        avgVelocityConcentric = 60f,
        avgVelocityEccentric = 50f,
        rangeOfMotionMm = 35f,
        peakPowerWatts = 1400f,
        avgPowerWatts = 1200f,
    )

    private fun repBiomechanics() = BiomechanicsRepResult(
        velocity = VelocityResult(
            meanConcentricVelocityMmS = 70f,
            peakVelocityMmS = 80f,
            zone = BiomechanicsVelocityZone.GRIND,
            velocityLossPercent = 25f,
            estimatedRepsRemaining = 3,
            shouldStopSet = false,
            repNumber = 1,
        ),
        forceCurve = ForceCurveResult(
            normalizedForceN = floatArrayOf(1f),
            normalizedPositionPct = floatArrayOf(0f),
            stickingPointPct = null,
            strengthProfile = StrengthProfile.FLAT,
            repNumber = 1,
        ),
        asymmetry = AsymmetryResult(
            asymmetryPercent = 1f,
            dominantSide = "A",
            avgLoadA = 20f,
            avgLoadB = 21f,
            repNumber = 1,
        ),
        repNumber = 1,
        timestamp = 2L,
    )
}
