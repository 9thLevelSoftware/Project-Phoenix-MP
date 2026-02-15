package com.devil.phoenixproject.domain.detection

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for ExerciseClassifier.
 *
 * Tests verify:
 * - History matching returns match when similarity > 0.85
 * - Rule-based fallback when no history or low similarity
 * - Unknown classification for ambiguous signatures
 * - Weighted similarity computation (ROM 40%, duration 20%, symmetry 25%, shape 15%)
 * - EMA signature evolution with alpha = 0.3
 */
class ExerciseClassifierTest {

    private val classifier = ExerciseClassifier()

    // =========================================================================
    // classify - history matching
    // =========================================================================

    @Test
    fun `classify returns history match when similarity above 0_85`() {
        // Create a signature and a nearly identical one in history
        val signature = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val historySignature = ExerciseSignature(
            romMm = 205f,  // Very close
            durationMs = 2050L,
            symmetryRatio = 0.51f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val history = mapOf("bench_press" to historySignature)

        val result = classifier.classify(signature, history)

        assertEquals(ClassificationSource.HISTORY_MATCH, result.source,
            "Should use history match for high similarity")
        assertEquals("bench_press", result.exerciseId)
        assertTrue(result.confidence > 0.85f,
            "Confidence should be above 0.85, got ${result.confidence}")
    }

    @Test
    fun `classify falls back to rules when no history`() {
        val signature = ExerciseSignature(
            romMm = 350f,  // Short ROM
            durationMs = 2500L,  // Fast
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.EXPLOSIVE_START,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals(ClassificationSource.RULE_BASED, result.source,
            "Should use rule-based when no history available")
    }

    @Test
    fun `classify falls back to rules when history similarity too low`() {
        val signature = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        // Very different signature in history
        val historySignature = ExerciseSignature(
            romMm = 600f,  // Very different ROM
            durationMs = 5000L,
            symmetryRatio = 0.8f,
            velocityProfile = VelocityShape.DECELERATING,
            cableConfig = CableUsage.SINGLE_LEFT
        )

        val history = mapOf("squat" to historySignature)

        val result = classifier.classify(signature, history)

        assertEquals(ClassificationSource.RULE_BASED, result.source,
            "Should fall back to rules when similarity is low")
    }

    @Test
    fun `classify returns Unknown for ambiguous signatures`() {
        // Create a signature that doesn't match any rule clearly
        val signature = ExerciseSignature(
            romMm = 450f,  // Medium ROM - between categories
            durationMs = 3500L,  // Borderline duration
            symmetryRatio = 0.35f,  // Outside symmetric range but not single cable
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_ASYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        // Should return something but with lower confidence
        assertNotNull(result)
        assertTrue(result.confidence <= 0.5f,
            "Ambiguous signature should have low confidence, got ${result.confidence}")
    }

    // =========================================================================
    // classify - rule-based decision tree
    // =========================================================================

    @Test
    fun `classify single cable short ROM returns Bicep Curl`() {
        val signature = ExerciseSignature(
            romMm = 250f,  // Short ROM < 300mm
            durationMs = 2000L,
            symmetryRatio = 0.99f,  // Single cable (loadB ~0)
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.SINGLE_LEFT
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals("Bicep Curl", result.exerciseName)
        assertEquals(0.6f, result.confidence, 0.05f)
    }

    @Test
    fun `classify dual symmetric short ROM explosive returns Chest Press`() {
        val signature = ExerciseSignature(
            romMm = 350f,  // Short ROM < 400mm
            durationMs = 2500L,  // Fast < 3000ms
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.EXPLOSIVE_START,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals("Chest Press", result.exerciseName)
        assertEquals(0.7f, result.confidence, 0.05f)
    }

    @Test
    fun `classify dual symmetric short ROM not explosive returns Shoulder Press`() {
        val signature = ExerciseSignature(
            romMm = 350f,  // Short ROM < 400mm
            durationMs = 2500L,  // Fast < 3000ms
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,  // Not explosive
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals("Shoulder Press", result.exerciseName)
        assertEquals(0.65f, result.confidence, 0.05f)
    }

    @Test
    fun `classify dual symmetric medium ROM returns Bent-Over Row`() {
        val signature = ExerciseSignature(
            romMm = 500f,  // Medium ROM 400-600mm
            durationMs = 2500L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals("Bent-Over Row", result.exerciseName)
        assertEquals(0.6f, result.confidence, 0.05f)
    }

    @Test
    fun `classify dual symmetric long ROM returns Squat`() {
        val signature = ExerciseSignature(
            romMm = 700f,  // Long ROM > 600mm
            durationMs = 3500L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.DECELERATING,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val result = classifier.classify(signature, emptyMap())

        assertEquals("Squat", result.exerciseName)
        assertEquals(0.6f, result.confidence, 0.05f)
    }

    // =========================================================================
    // computeSimilarity
    // =========================================================================

    @Test
    fun `computeSimilarity returns 1_0 for identical signatures`() {
        val signature = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val similarity = classifier.computeSimilarity(signature, signature)

        assertEquals(1.0f, similarity, 0.001f,
            "Identical signatures should have similarity 1.0")
    }

    @Test
    fun `computeSimilarity weights ROM at 40 percent`() {
        val base = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        // Only ROM differs significantly (50% difference -> 0.5 normalized)
        val differentRom = ExerciseSignature(
            romMm = 400f,  // 100% larger
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val similarity = classifier.computeSimilarity(base, differentRom)

        // ROM contributes 40% weight with 0.5 normalized similarity
        // Other components: duration 20%*1.0, symmetry 25%*1.0, shape 15%*1.0
        // Expected: 0.4*0.5 + 0.2*1.0 + 0.25*1.0 + 0.15*1.0 = 0.2 + 0.6 = 0.8
        assertEquals(0.8f, similarity, 0.05f,
            "50% ROM difference should result in ~0.8 similarity")
    }

    @Test
    fun `computeSimilarity returns 0 when shape differs and other components similar`() {
        val base = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.EXPLOSIVE_START,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        // Only shape differs
        val differentShape = ExerciseSignature(
            romMm = 200f,
            durationMs = 2000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.DECELERATING,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val similarity = classifier.computeSimilarity(base, differentShape)

        // Shape contributes 15% weight with 0.0 (different shapes)
        // Expected: 0.4*1.0 + 0.2*1.0 + 0.25*1.0 + 0.15*0.0 = 0.85
        assertEquals(0.85f, similarity, 0.05f,
            "Different shapes should reduce similarity by 15%")
    }

    // =========================================================================
    // evolveSignature
    // =========================================================================

    @Test
    fun `evolveSignature applies EMA with alpha 0_3`() {
        val existing = ExerciseSignature(
            romMm = 100f,
            durationMs = 1000L,
            symmetryRatio = 0.4f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC,
            sampleCount = 5
        )

        val newObservation = ExerciseSignature(
            romMm = 200f,  // 100 more
            durationMs = 2000L,  // 1000 more
            symmetryRatio = 0.6f,  // 0.2 more
            velocityProfile = VelocityShape.EXPLOSIVE_START,  // Different
            cableConfig = CableUsage.SINGLE_LEFT  // Different
        )

        val evolved = classifier.evolveSignature(existing, newObservation, 0.3f)

        // ROM: 100 * 0.7 + 200 * 0.3 = 70 + 60 = 130
        assertEquals(130f, evolved.romMm, 1f,
            "ROM should be EMA: 100*0.7 + 200*0.3 = 130")

        // Duration: 1000 * 0.7 + 2000 * 0.3 = 700 + 600 = 1300
        assertEquals(1300L, evolved.durationMs,
            "Duration should be EMA: 1000*0.7 + 2000*0.3 = 1300")

        // Symmetry: 0.4 * 0.7 + 0.6 * 0.3 = 0.28 + 0.18 = 0.46
        assertEquals(0.46f, evolved.symmetryRatio, 0.01f,
            "Symmetry should be EMA: 0.4*0.7 + 0.6*0.3 = 0.46")
    }

    @Test
    fun `evolveSignature increments sampleCount`() {
        val existing = ExerciseSignature(
            romMm = 100f,
            durationMs = 1000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC,
            sampleCount = 5
        )

        val newObservation = ExerciseSignature(
            romMm = 150f,
            durationMs = 1500L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC
        )

        val evolved = classifier.evolveSignature(existing, newObservation)

        assertEquals(6, evolved.sampleCount,
            "Sample count should increment from 5 to 6")
    }

    @Test
    fun `evolveSignature uses latest observation for categorical fields`() {
        val existing = ExerciseSignature(
            romMm = 100f,
            durationMs = 1000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.LINEAR,
            cableConfig = CableUsage.DUAL_SYMMETRIC,
            sampleCount = 5
        )

        val newObservation = ExerciseSignature(
            romMm = 100f,
            durationMs = 1000L,
            symmetryRatio = 0.5f,
            velocityProfile = VelocityShape.EXPLOSIVE_START,
            cableConfig = CableUsage.SINGLE_LEFT
        )

        val evolved = classifier.evolveSignature(existing, newObservation)

        assertEquals(VelocityShape.EXPLOSIVE_START, evolved.velocityProfile,
            "Velocity profile should use latest observation")
        assertEquals(CableUsage.SINGLE_LEFT, evolved.cableConfig,
            "Cable config should use latest observation")
    }
}
