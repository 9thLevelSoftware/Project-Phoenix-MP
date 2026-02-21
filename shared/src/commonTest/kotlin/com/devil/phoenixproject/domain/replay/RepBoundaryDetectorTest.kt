package com.devil.phoenixproject.domain.replay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RepBoundaryDetector.
 *
 * Tests verify:
 * - Minimum data requirement (15 samples)
 * - Single rep boundary detection
 * - Multiple rep boundary detection
 * - Edge valley handling (start/end of data)
 * - Peak position identification for phase split
 * - Concentric/eccentric phase partitioning
 * - Noisy data handling with smoothing
 * - Minimum valley separation to prevent false positives
 */
class RepBoundaryDetectorTest {

    private val detector = RepBoundaryDetector()

    // =========================================================================
    // Test 1: Insufficient data returns empty
    // =========================================================================

    @Test
    fun `detectBoundaries returns empty for insufficient data - less than 15 samples`() {
        // Less than 15 samples - not enough for a single rep
        val positions = FloatArray(10) { 100f }

        val result = detector.detectBoundaries(positions)

        assertTrue(result.isEmpty(), "Should return empty list for fewer than 15 samples")
    }

    @Test
    fun `detectBoundaries returns empty for empty array`() {
        val positions = FloatArray(0)

        val result = detector.detectBoundaries(positions)

        assertTrue(result.isEmpty(), "Should return empty list for empty array")
    }

    // =========================================================================
    // Test 2: Single rep boundary detection
    // =========================================================================

    @Test
    fun `detectBoundaries finds single rep boundaries correctly`() {
        // Single rep: valley at 100mm, peak at 300mm, valley at 100mm
        // 20 samples total
        val positions = buildSingleRepPositions(
            valleyMm = 100f,
            peakMm = 300f,
            sampleCount = 20
        )

        val result = detector.detectBoundaries(positions)

        assertEquals(1, result.size, "Should detect exactly 1 rep")
        assertEquals(1, result[0].repNumber, "Rep should be numbered 1")
        assertTrue(result[0].startIndex >= 0, "Start index should be valid")
        assertTrue(result[0].endIndex <= positions.size - 1, "End index should be valid")
        assertTrue(result[0].peakIndex > result[0].startIndex, "Peak should be after start")
        assertTrue(result[0].peakIndex < result[0].endIndex, "Peak should be before end")
    }

    // =========================================================================
    // Test 3: Multiple rep boundary detection
    // =========================================================================

    @Test
    fun `detectBoundaries finds multiple rep boundaries correctly`() {
        // 3 reps: each with valley at 100mm, peak at 300mm
        val positions = buildMultiRepPositions(
            repCount = 3,
            valleyMm = 100f,
            peakMm = 300f,
            samplesPerRep = 20
        )

        val result = detector.detectBoundaries(positions)

        assertEquals(3, result.size, "Should detect exactly 3 reps")

        // Verify rep numbering
        assertEquals(1, result[0].repNumber, "First rep should be numbered 1")
        assertEquals(2, result[1].repNumber, "Second rep should be numbered 2")
        assertEquals(3, result[2].repNumber, "Third rep should be numbered 3")

        // Verify boundaries are sequential
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].endIndex <= result[i + 1].startIndex,
                "Rep ${i + 1} end should not overlap with rep ${i + 2} start"
            )
        }
    }

    // =========================================================================
    // Test 4: Edge valley handling (start/end of data)
    // =========================================================================

    @Test
    fun `detectBoundaries handles edge valleys at start of data`() {
        // Data starts at a valley (low position)
        val positions = buildMultiRepPositions(
            repCount = 2,
            valleyMm = 50f,  // Starts at low position
            peakMm = 250f,
            samplesPerRep = 20
        )

        val result = detector.detectBoundaries(positions)

        assertTrue(result.isNotEmpty(), "Should detect reps even when starting at valley")
        assertTrue(result[0].startIndex <= 2, "First rep should start near beginning")
    }

    @Test
    fun `detectBoundaries handles edge valleys at end of data`() {
        // Build data that ends at a valley
        val positions = buildMultiRepPositions(
            repCount = 2,
            valleyMm = 50f,
            peakMm = 250f,
            samplesPerRep = 20
        )

        val result = detector.detectBoundaries(positions)

        assertTrue(result.isNotEmpty(), "Should detect reps even when ending at valley")
        val lastRep = result.last()
        assertTrue(
            lastRep.endIndex >= positions.size - 3,
            "Last rep should end near array end"
        )
    }

    // =========================================================================
    // Test 5: Peak position identification for phase split
    // =========================================================================

    @Test
    fun `detectBoundaries identifies peak position for phase split`() {
        val positions = buildSingleRepPositions(
            valleyMm = 100f,
            peakMm = 300f,
            sampleCount = 20
        )

        val result = detector.detectBoundaries(positions)

        assertEquals(1, result.size)
        val rep = result[0]

        // Peak should be at the highest position in the rep
        val peakPosition = positions[rep.peakIndex]
        val repPositions = positions.slice(rep.startIndex..rep.endIndex)

        assertEquals(
            repPositions.maxOrNull(),
            peakPosition,
            "Peak index should point to maximum position in rep"
        )
    }

    // =========================================================================
    // Test 6: Concentric and eccentric indices partition rep correctly
    // =========================================================================

    @Test
    fun `concentricIndices and eccentricIndices partition rep correctly`() {
        val positions = buildSingleRepPositions(
            valleyMm = 100f,
            peakMm = 300f,
            sampleCount = 20
        )

        val result = detector.detectBoundaries(positions)

        assertEquals(1, result.size)
        val rep = result[0]

        // Concentric: startIndex until peakIndex
        assertEquals(rep.startIndex, rep.concentricIndices.first)
        assertTrue(rep.concentricIndices.last < rep.peakIndex || rep.concentricIndices.last == rep.peakIndex - 1)

        // Eccentric: peakIndex until endIndex
        assertEquals(rep.peakIndex, rep.eccentricIndices.first)
        assertTrue(rep.eccentricIndices.last <= rep.endIndex)

        // No gap between phases
        val concentricEnd = rep.concentricIndices.last
        val eccentricStart = rep.eccentricIndices.first
        assertTrue(
            eccentricStart - concentricEnd <= 1,
            "There should be no gap between concentric end and eccentric start"
        )

        // Concentric should have increasing positions (moving up)
        val concentricPositions = rep.concentricIndices.map { positions[it] }
        for (i in 1 until concentricPositions.size) {
            assertTrue(
                concentricPositions[i] >= concentricPositions[i - 1] - 5f, // Allow small smoothing variance
                "Concentric phase should have generally increasing positions"
            )
        }

        // Eccentric should have decreasing positions (moving down)
        val eccentricPositions = rep.eccentricIndices.map { positions[it] }
        for (i in 1 until eccentricPositions.size) {
            assertTrue(
                eccentricPositions[i] <= eccentricPositions[i - 1] + 5f, // Allow small smoothing variance
                "Eccentric phase should have generally decreasing positions"
            )
        }
    }

    // =========================================================================
    // Test 7: Noisy data with smoothing still detects correct valleys
    // =========================================================================

    @Test
    fun `noisy data with smoothing still detects correct valleys`() {
        // Build clean data first
        val cleanPositions = buildMultiRepPositions(
            repCount = 3,
            valleyMm = 100f,
            peakMm = 300f,
            samplesPerRep = 20
        )

        // Add noise (+/- 3mm random variation)
        val noisyPositions = FloatArray(cleanPositions.size) { i ->
            cleanPositions[i] + (Random.nextFloat() - 0.5f) * 6f
        }

        val result = detector.detectBoundaries(noisyPositions)

        // Should still detect 3 reps despite noise
        assertEquals(3, result.size, "Smoothing should handle noise and detect 3 reps")
    }

    // =========================================================================
    // Test 8: Minimum valley separation prevents false positives
    // =========================================================================

    @Test
    fun `minimum valley separation prevents false positives`() {
        // Create positions with a small dip in the middle that shouldn't be detected as a valley
        // because it's too close to the real valleys
        val positions = FloatArray(30) { i ->
            when {
                i < 5 -> 100f                    // Start valley
                i < 10 -> 100f + (i - 5) * 40f   // Rising
                i == 12 -> 280f                  // Small dip (should be ignored)
                i < 15 -> 300f                   // Peak
                i < 25 -> 300f - (i - 15) * 20f  // Falling
                else -> 100f                    // End valley
            }
        }

        val result = detector.detectBoundaries(positions)

        // Should detect only 1 rep, not 2 (the small dip shouldn't create a false boundary)
        assertEquals(1, result.size, "Small dip should not create false rep boundary")
    }

    @Test
    fun `valleys too close together are filtered out`() {
        // Create data with valleys only 5 samples apart (less than minimum 8)
        val positions = FloatArray(40) { i ->
            when {
                i < 3 -> 100f
                i < 5 -> 200f
                i < 8 -> 100f      // Valley at ~6, too close to start
                i < 20 -> 100f + ((i - 8) * 20f).coerceAtMost(200f)
                i < 30 -> 300f - ((i - 20) * 20f).coerceAtLeast(0f)
                else -> 100f
            }
        }

        val result = detector.detectBoundaries(positions)

        // Should have at most 1 rep (the close valleys should be filtered)
        assertTrue(result.size <= 1, "Valleys closer than 8 samples should be filtered")
    }

    // =========================================================================
    // Additional edge case tests
    // =========================================================================

    @Test
    fun `detectBoundaries handles flat data gracefully`() {
        // All positions are the same - no valleys or peaks
        val positions = FloatArray(30) { 150f }

        val result = detector.detectBoundaries(positions)

        assertTrue(result.isEmpty(), "Flat data with no valleys should return empty")
    }

    @Test
    fun `detectBoundaries handles single incomplete rep`() {
        // Data that goes valley -> peak but never returns to valley
        val positions = FloatArray(15) { i ->
            100f + i * 15f  // Steadily increasing, no return
        }

        val result = detector.detectBoundaries(positions)

        // Should return empty or at most partial rep indication
        assertTrue(
            result.isEmpty() || result.all { it.endIndex == positions.size - 1 },
            "Incomplete rep (no return) should be handled gracefully"
        )
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Build a single rep position curve: valley -> peak -> valley
     */
    private fun buildSingleRepPositions(
        valleyMm: Float,
        peakMm: Float,
        sampleCount: Int
    ): FloatArray {
        val halfCount = sampleCount / 2
        return FloatArray(sampleCount) { i ->
            if (i <= halfCount) {
                // Concentric: valley to peak
                val progress = i.toFloat() / halfCount
                valleyMm + (peakMm - valleyMm) * progress
            } else {
                // Eccentric: peak to valley
                val progress = (i - halfCount).toFloat() / (sampleCount - halfCount - 1)
                peakMm - (peakMm - valleyMm) * progress
            }
        }
    }

    /**
     * Build multiple rep position curves: repeating valley -> peak -> valley pattern
     */
    private fun buildMultiRepPositions(
        repCount: Int,
        valleyMm: Float,
        peakMm: Float,
        samplesPerRep: Int
    ): FloatArray {
        val totalSamples = repCount * samplesPerRep
        return FloatArray(totalSamples) { i ->
            val positionInRep = i % samplesPerRep
            val halfRep = samplesPerRep / 2

            if (positionInRep <= halfRep) {
                // Concentric: valley to peak
                val progress = positionInRep.toFloat() / halfRep
                valleyMm + (peakMm - valleyMm) * progress
            } else {
                // Eccentric: peak to valley
                val progress = (positionInRep - halfRep).toFloat() / (samplesPerRep - halfRep - 1)
                peakMm - (peakMm - valleyMm) * progress
            }
        }
    }
}
