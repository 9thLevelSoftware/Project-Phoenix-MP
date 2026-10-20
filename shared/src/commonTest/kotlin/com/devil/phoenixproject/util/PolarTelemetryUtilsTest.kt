package com.devil.phoenixproject.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolarTelemetryUtilsTest {

    private val toleranceDeg = 0.5

    @Test
    fun standingUprightProducesNearZeroPitchAndRollAndZeroDynamicAcc() {
        // Upright: 1G pointing straight down along -X
        val x = -1000.0
        val y = 0.0
        val z = 0.0

        val metrics = PolarTelemetryUtils.computeTorsoBiomechanics(x, y, z)

        assertTrue(abs(metrics.pitchDeg) < toleranceDeg, "Standing upright pitch should be ~0°, got ${metrics.pitchDeg}")
        assertTrue(abs(metrics.rollDeg) < toleranceDeg, "Standing upright roll should be ~0°, got ${metrics.rollDeg}")
        assertEquals(0, metrics.dynamicAccMg, "Static upright dynamic acceleration should be 0 mG")
    }

    @Test
    fun fortyFiveDegreeForwardHingeProducesFortyFiveDegreesPitch() {
        // 45° forward hinge: gravity pulls along -X (707 mG) and -Z (707 mG)
        val x = -707.106
        val y = 0.0
        val z = -707.106

        val metrics = PolarTelemetryUtils.computeTorsoBiomechanics(x, y, z)

        assertTrue(abs(metrics.pitchDeg - 45.0) < toleranceDeg, "45° forward hinge pitch should be ~45°, got ${metrics.pitchDeg}")
        assertTrue(abs(metrics.rollDeg) < toleranceDeg, "45° forward hinge roll should be ~0°, got ${metrics.rollDeg}")
        assertEquals(0, metrics.dynamicAccMg, "Static hinge dynamic acceleration should be ~0 mG")
    }

    @Test
    fun backwardLeanProducesNegativePitch() {
        // Leaning backward: gravity pulls along +Z
        val x = -707.106
        val y = 0.0
        val z = 707.106

        val metrics = PolarTelemetryUtils.computeTorsoBiomechanics(x, y, z)

        assertTrue(abs(metrics.pitchDeg - (-45.0)) < toleranceDeg, "Backward lean pitch should be ~ -45°, got ${metrics.pitchDeg}")
    }

    @Test
    fun horizontalHingeProducesNinetyDegreesPitch() {
        // 90° horizontal bend (torso parallel to ground): gravity entirely along -Z
        val x = 0.0
        val y = 0.0
        val z = -1000.0

        val metrics = PolarTelemetryUtils.computeTorsoBiomechanics(x, y, z)

        assertTrue(abs(metrics.pitchDeg - 90.0) < toleranceDeg, "Horizontal hinge pitch should be ~90°, got ${metrics.pitchDeg}")
        assertTrue(abs(metrics.rollDeg) < toleranceDeg, "Horizontal hinge roll should be ~0°, got ${metrics.rollDeg}")
    }

    @Test
    fun lateralTiltProducesAccurateRoll() {
        // 30° lateral tilt to right: Y = 500 mG, X = -866 mG, Z = 0
        val x = -866.025
        val y = 500.0
        val z = 0.0

        val metrics = PolarTelemetryUtils.computeTorsoBiomechanics(x, y, z)

        assertTrue(abs(metrics.rollDeg - 30.0) < toleranceDeg, "Lateral tilt roll should be ~30°, got ${metrics.rollDeg}")
        assertTrue(abs(metrics.pitchDeg) < toleranceDeg, "Lateral tilt pitch should be ~0°, got ${metrics.pitchDeg}")
        assertEquals(0, metrics.dynamicAccMg, "Static roll dynamic acceleration should be 0 mG")
    }

    @Test
    fun dynamicAccMeasuresTrunkJerkAboveOneG() {
        // Dynamic jerk/heave: total magnitude = 1500 mG (0.5G above static 1G)
        val x = -1500.0
        val y = 0.0
        val z = 0.0

        val dynamicAcc = PolarTelemetryUtils.calculateDynamicAccMg(x, y, z)
        assertEquals(500, dynamicAcc, "Dynamic acceleration should be 500 mG")
    }

    @Test
    fun standingCalibrationTareCompensatesInitialAngle() {
        // Sensor mounted with a natural 5° forward pitch when standing upright
        val initialRawX = -996.19
        val initialRawY = 0.0
        val initialRawZ = -87.16 // ~5° angle with inverted Z

        val initialPitch = PolarTelemetryUtils.calculateTorsoPitchDeg(initialRawX, initialRawY, initialRawZ, tarePitchDeg = 0.0)
        assertTrue(abs(initialPitch - 5.0) < toleranceDeg)

        // With tare applied
        val taredPitch = PolarTelemetryUtils.calculateTorsoPitchDeg(initialRawX, initialRawY, initialRawZ, tarePitchDeg = initialPitch)
        assertTrue(abs(taredPitch) < 0.001, "Tared standing pitch should be exactly 0°")
    }

    @Test
    fun lowPassFilterSmoothsSuccessiveSamples() {
        val filter = PolarAccFilter(alpha = 0.2)

        // First sample initializes filter
        val first = filter.processSample(-1000, 0, 0)
        assertEquals(-1000.0, first.filteredXMg)

        // Sudden transient spike to -1500
        val second = filter.processSample(-1500, 0, 0)
        // Expected: 0.2 * -1500 + 0.8 * -1000 = -300 - 800 = -1100.0
        assertEquals(-1100.0, second.filteredXMg)

        // Next spike sample
        val third = filter.processSample(-1500, 0, 0)
        // Expected: 0.2 * -1500 + 0.8 * -1100 = -300 - 880 = -1180.0
        assertEquals(-1180.0, third.filteredXMg)
    }

    @Test
    fun rmssdCalculationComputesAccurateRootMeanSquare() {
        val rrs = listOf(800.0, 820.0, 810.0, 830.0)
        // diffs: +20, -10, +20
        // squared diffs: 400, 100, 400 -> sum = 900
        // mean: 900 / 3 = 300
        // sqrt: sqrt(300) = 17.3205
        val rmssd = PolarTelemetryUtils.calculateRmssdMs(rrs)
        assertTrue(rmssd != null && abs(rmssd - 17.32) < 0.05)
    }
}
