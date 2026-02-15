package com.devil.phoenixproject.domain.detection

import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SignatureExtractor.
 *
 * Tests verify:
 * - Minimum data requirement (30 samples for ~3 reps at 10Hz)
 * - ROM calculation from position valleys
 * - Single vs dual cable detection
 * - Rep duration calculation from valley timing
 * - Velocity profile classification
 */
class SignatureExtractorTest {

    private val extractor = SignatureExtractor()

    // =========================================================================
    // Minimum data requirements
    // =========================================================================

    @Test
    fun `extractSignature returns null for insufficient data`() {
        // Less than 30 samples (not enough for ~3 reps at 10Hz)
        val metrics = List(20) { i ->
            WorkoutMetric(
                timestamp = i * 100L,
                loadA = 50f,
                loadB = 50f,
                positionA = 100f,
                positionB = 100f
            )
        }

        val result = extractor.extractSignature(metrics)

        assertNull(result, "Should return null for fewer than 30 samples")
    }

    @Test
    fun `extractSignature returns null for empty list`() {
        val result = extractor.extractSignature(emptyList())

        assertNull(result, "Should return null for empty metrics list")
    }

    // =========================================================================
    // ROM calculation
    // =========================================================================

    @Test
    fun `extractSignature calculates ROM from position valleys`() {
        // Create synthetic data with clear valley pattern:
        // Valley at 100mm, peak at 300mm -> ROM = 200mm
        // 3 reps worth of data (50 samples)
        val metrics = buildSyntheticReps(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 20
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result, "Should extract signature from valid data")
        assertEquals(200f, result.romMm, 20f, // Allow 10% tolerance
            "ROM should be approximately 200mm (peak - valley)")
    }

    @Test
    fun `extractSignature handles varying ROM across reps`() {
        // 3 reps with slightly different ROMs: 180, 200, 220mm
        // Average should be ~200mm
        val metrics = mutableListOf<WorkoutMetric>()
        var timestamp = 0L

        // Rep 1: valley=100, peak=280 (ROM=180)
        metrics.addAll(buildSingleRep(100f, 280f, timestamp, 20))
        timestamp += 2000L

        // Rep 2: valley=100, peak=300 (ROM=200)
        metrics.addAll(buildSingleRep(100f, 300f, timestamp, 20))
        timestamp += 2000L

        // Rep 3: valley=100, peak=320 (ROM=220)
        metrics.addAll(buildSingleRep(100f, 320f, timestamp, 20))

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(200f, result.romMm, 25f,
            "ROM should average to ~200mm across varying reps")
    }

    // =========================================================================
    // Cable usage detection
    // =========================================================================

    @Test
    fun `extractSignature detects single cable usage left`() {
        // loadB always < 1kg -> SINGLE_LEFT
        val metrics = buildSyntheticReps(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 20,
            loadA = 50f,
            loadB = 0.5f
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(CableUsage.SINGLE_LEFT, result.cableConfig,
            "Should detect SINGLE_LEFT when loadB is always < 1kg")
    }

    @Test
    fun `extractSignature detects single cable usage right`() {
        // loadA always < 1kg -> SINGLE_RIGHT
        val metrics = buildSyntheticReps(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 20,
            loadA = 0.5f,
            loadB = 50f
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(CableUsage.SINGLE_RIGHT, result.cableConfig,
            "Should detect SINGLE_RIGHT when loadA is always < 1kg")
    }

    @Test
    fun `extractSignature detects dual symmetric usage`() {
        // loadA ~= loadB (symmetry 0.4-0.6) -> DUAL_SYMMETRIC
        val metrics = buildSyntheticReps(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 20,
            loadA = 50f,
            loadB = 50f
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(CableUsage.DUAL_SYMMETRIC, result.cableConfig,
            "Should detect DUAL_SYMMETRIC when loadA ~= loadB")
        assertEquals(0.5f, result.symmetryRatio, 0.05f,
            "Symmetry ratio should be ~0.5")
    }

    @Test
    fun `extractSignature detects dual asymmetric usage`() {
        // loadA != loadB significantly (symmetry outside 0.4-0.6) -> DUAL_ASYMMETRIC
        val metrics = buildSyntheticReps(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 20,
            loadA = 70f,
            loadB = 30f
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(CableUsage.DUAL_ASYMMETRIC, result.cableConfig,
            "Should detect DUAL_ASYMMETRIC when load ratio is outside 0.4-0.6")
        assertEquals(0.7f, result.symmetryRatio, 0.05f,
            "Symmetry ratio should be ~0.7 (70/100)")
    }

    // =========================================================================
    // Rep duration calculation
    // =========================================================================

    @Test
    fun `extractSignature calculates rep duration from valley timing`() {
        // Valleys at t=0, t=2000, t=4000 -> average duration ~2000ms
        val metrics = mutableListOf<WorkoutMetric>()

        // Rep 1: 0-1900ms
        metrics.addAll(buildSingleRep(100f, 300f, startTime = 0L, samplesPerRep = 20))

        // Rep 2: 2000-3900ms
        metrics.addAll(buildSingleRep(100f, 300f, startTime = 2000L, samplesPerRep = 20))

        // Rep 3: 4000-5900ms
        metrics.addAll(buildSingleRep(100f, 300f, startTime = 4000L, samplesPerRep = 20))

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        // Allow 10% tolerance (200ms)
        val expectedDuration = 2000L
        val tolerance = 200L
        assertTrue(
            result.durationMs in (expectedDuration - tolerance)..(expectedDuration + tolerance),
            "Rep duration should be approximately 2000ms, got ${result.durationMs}"
        )
    }

    // =========================================================================
    // Velocity profile classification
    // =========================================================================

    @Test
    fun `extractSignature classifies explosive start velocity profile`() {
        // First third has highest velocity -> EXPLOSIVE_START
        val metrics = buildSyntheticRepsWithVelocity(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 21, // Divisible by 3 for clean thirds
            velocityPattern = VelocityPattern.EXPLOSIVE_START
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(VelocityShape.EXPLOSIVE_START, result.velocityProfile,
            "Should detect EXPLOSIVE_START when first third has highest velocity")
    }

    @Test
    fun `extractSignature classifies linear velocity profile`() {
        // Velocity roughly equal throughout -> LINEAR
        val metrics = buildSyntheticRepsWithVelocity(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 21,
            velocityPattern = VelocityPattern.LINEAR
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(VelocityShape.LINEAR, result.velocityProfile,
            "Should detect LINEAR when velocity is roughly constant")
    }

    @Test
    fun `extractSignature classifies decelerating velocity profile`() {
        // Last third has lowest velocity -> DECELERATING
        val metrics = buildSyntheticRepsWithVelocity(
            valleyPosition = 100f,
            peakPosition = 300f,
            repCount = 3,
            samplesPerRep = 21,
            velocityPattern = VelocityPattern.DECELERATING
        )

        val result = extractor.extractSignature(metrics)

        assertNotNull(result)
        assertEquals(VelocityShape.DECELERATING, result.velocityProfile,
            "Should detect DECELERATING when last third has lowest velocity")
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private enum class VelocityPattern {
        EXPLOSIVE_START, LINEAR, DECELERATING
    }

    /**
     * Build synthetic rep data with a clear valley-peak-valley pattern.
     */
    private fun buildSyntheticReps(
        valleyPosition: Float,
        peakPosition: Float,
        repCount: Int,
        samplesPerRep: Int,
        loadA: Float = 50f,
        loadB: Float = 50f
    ): List<WorkoutMetric> {
        val metrics = mutableListOf<WorkoutMetric>()
        var timestamp = 0L

        repeat(repCount) {
            metrics.addAll(
                buildSingleRep(valleyPosition, peakPosition, timestamp, samplesPerRep, loadA, loadB)
            )
            timestamp += samplesPerRep * 100L // 10Hz = 100ms between samples
        }

        return metrics
    }

    /**
     * Build a single rep: valley -> peak -> valley pattern.
     * Uses (samplesPerRep - 1) to ensure we reach both extremes.
     */
    private fun buildSingleRep(
        valleyPosition: Float,
        peakPosition: Float,
        startTime: Long,
        samplesPerRep: Int,
        loadA: Float = 50f,
        loadB: Float = 50f
    ): List<WorkoutMetric> {
        val metrics = mutableListOf<WorkoutMetric>()
        val halfRep = samplesPerRep / 2

        // Concentric phase: valley -> peak (inclusive of both endpoints)
        for (i in 0..halfRep) {
            val progress = i.toFloat() / halfRep
            val position = valleyPosition + (peakPosition - valleyPosition) * progress
            metrics.add(
                WorkoutMetric(
                    timestamp = startTime + i * 100L,
                    loadA = loadA,
                    loadB = loadB,
                    positionA = position,
                    positionB = position,
                    velocityA = 0.5, // Default velocity
                    velocityB = 0.5
                )
            )
        }

        // Eccentric phase: peak -> valley (start after peak, include valley)
        val eccentricSamples = samplesPerRep - halfRep - 1
        for (i in 1..eccentricSamples) {
            val progress = i.toFloat() / eccentricSamples
            val position = peakPosition - (peakPosition - valleyPosition) * progress
            metrics.add(
                WorkoutMetric(
                    timestamp = startTime + (halfRep + i) * 100L,
                    loadA = loadA,
                    loadB = loadB,
                    positionA = position,
                    positionB = position,
                    velocityA = 0.5,
                    velocityB = 0.5
                )
            )
        }

        return metrics
    }

    /**
     * Build synthetic reps with specific velocity patterns.
     */
    private fun buildSyntheticRepsWithVelocity(
        valleyPosition: Float,
        peakPosition: Float,
        repCount: Int,
        samplesPerRep: Int,
        velocityPattern: VelocityPattern,
        loadA: Float = 50f,
        loadB: Float = 50f
    ): List<WorkoutMetric> {
        val metrics = mutableListOf<WorkoutMetric>()
        var timestamp = 0L

        repeat(repCount) {
            metrics.addAll(
                buildSingleRepWithVelocity(
                    valleyPosition, peakPosition, timestamp, samplesPerRep,
                    velocityPattern, loadA, loadB
                )
            )
            timestamp += samplesPerRep * 100L
        }

        return metrics
    }

    /**
     * Build a single rep with specific velocity pattern during concentric phase.
     */
    private fun buildSingleRepWithVelocity(
        valleyPosition: Float,
        peakPosition: Float,
        startTime: Long,
        samplesPerRep: Int,
        velocityPattern: VelocityPattern,
        loadA: Float = 50f,
        loadB: Float = 50f
    ): List<WorkoutMetric> {
        val metrics = mutableListOf<WorkoutMetric>()
        val halfRep = samplesPerRep / 2
        val third = halfRep / 3

        // Concentric phase: valley -> peak with velocity pattern
        for (i in 0 until halfRep) {
            val progress = i.toFloat() / halfRep
            val position = valleyPosition + (peakPosition - valleyPosition) * progress

            // Determine velocity based on pattern and position in third
            // Velocity patterns designed to be clearly distinguishable:
            // EXPLOSIVE_START: 1.0 -> 0.5 -> 0.5 (first third distinctly higher)
            // LINEAR: 0.6 -> 0.6 -> 0.6 (all equal)
            // DECELERATING: 0.6 -> 0.6 -> 0.2 (last third distinctly lower, first two equal)
            val velocity = when (velocityPattern) {
                VelocityPattern.EXPLOSIVE_START -> when {
                    i < third -> 1.0       // First third: distinctly high
                    else -> 0.5            // Rest: lower and equal
                }
                VelocityPattern.LINEAR -> 0.6  // Constant throughout
                VelocityPattern.DECELERATING -> when {
                    i >= 2 * third -> 0.2  // Last third: distinctly low
                    else -> 0.6            // First two thirds: equal
                }
            }

            metrics.add(
                WorkoutMetric(
                    timestamp = startTime + i * 100L,
                    loadA = loadA,
                    loadB = loadB,
                    positionA = position,
                    positionB = position,
                    velocityA = velocity,
                    velocityB = velocity
                )
            )
        }

        // Eccentric phase: peak -> valley (velocity doesn't matter for classification)
        for (i in halfRep until samplesPerRep) {
            val progress = (i - halfRep).toFloat() / (samplesPerRep - halfRep)
            val position = peakPosition - (peakPosition - valleyPosition) * progress
            metrics.add(
                WorkoutMetric(
                    timestamp = startTime + i * 100L,
                    loadA = loadA,
                    loadB = loadB,
                    positionA = position,
                    positionB = position,
                    velocityA = 0.5,
                    velocityB = 0.5
                )
            )
        }

        return metrics
    }
}
