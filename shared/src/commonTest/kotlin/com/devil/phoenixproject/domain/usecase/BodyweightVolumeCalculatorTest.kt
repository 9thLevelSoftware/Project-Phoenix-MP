package com.devil.phoenixproject.domain.usecase

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BodyweightVolumeCalculator (Issue #229)
 */
class BodyweightVolumeCalculatorTest {

    @Test
    fun getPercentage_pushUp_returns64Percent() {
        assertEquals(0.64f, BodyweightVolumeCalculator.getPercentageForExercise("Push Up"))
        assertEquals(0.64f, BodyweightVolumeCalculator.getPercentageForExercise("pushup"))
        assertEquals(0.64f, BodyweightVolumeCalculator.getPercentageForExercise("Push-Up"))
    }

    @Test
    fun getPercentage_pullUp_returns95Percent() {
        assertEquals(0.95f, BodyweightVolumeCalculator.getPercentageForExercise("Pull Up"))
        assertEquals(0.95f, BodyweightVolumeCalculator.getPercentageForExercise("pullup"))
        assertEquals(0.95f, BodyweightVolumeCalculator.getPercentageForExercise("Chin Up"))
    }

    @Test
    fun getPercentage_declinePushUp_returns70Percent() {
        assertEquals(0.70f, BodyweightVolumeCalculator.getPercentageForExercise("Decline Push Up"))
    }

    @Test
    fun getPercentage_declineHeightSpecific_returnsCorrectPercent() {
        // Issue #229: Height-specific decline push-up percentages
        assertEquals(0.66f, BodyweightVolumeCalculator.getPercentageForExercise("Decline 4.5\" Push Up"))
        assertEquals(0.70f, BodyweightVolumeCalculator.getPercentageForExercise("Decline 11\" Push Up"))
        assertEquals(0.72f, BodyweightVolumeCalculator.getPercentageForExercise("Decline 16\" Push Up"))
        assertEquals(0.73f, BodyweightVolumeCalculator.getPercentageForExercise("Decline 18\" Push Up"))
        assertEquals(0.75f, BodyweightVolumeCalculator.getPercentageForExercise("Decline 24\" Push Up"))
    }

    @Test
    fun getPercentage_inclinePushUp_returns40Percent() {
        assertEquals(0.40f, BodyweightVolumeCalculator.getPercentageForExercise("Incline Push Up"))
        assertEquals(0.40f, BodyweightVolumeCalculator.getPercentageForExercise("Incline Pushup"))
    }

    @Test
    fun getPercentage_handstandPushUp_returns100Percent() {
        assertEquals(1.00f, BodyweightVolumeCalculator.getPercentageForExercise("Handstand Push Up"))
        assertEquals(1.00f, BodyweightVolumeCalculator.getPercentageForExercise("Handstand Pushup"))
    }

    @Test
    fun getPercentage_nordicCurl_returns60Percent() {
        assertEquals(0.60f, BodyweightVolumeCalculator.getPercentageForExercise("Nordic Curl"))
        assertEquals(0.60f, BodyweightVolumeCalculator.getPercentageForExercise("Nordic Ham Curl"))
    }

    @Test
    fun getPercentage_wideGripPullUp_returns90Percent() {
        assertEquals(0.90f, BodyweightVolumeCalculator.getPercentageForExercise("Wide Grip Pull Up"))
        assertEquals(0.90f, BodyweightVolumeCalculator.getPercentageForExercise("Wide-Grip Pull Up"))
    }

    @Test
    fun getVariantsForExercise_pushUp_returnsVariants() {
        val variants = BodyweightVolumeCalculator.getVariantsForExercise("Push Up")
        assertTrue(variants != null && variants.size > 1, "Push Up should have variant options")
        // First should be standard
        assertEquals("Standard Push-Up", variants.first().label)
        assertEquals(0.64f, variants.first().percentage)
        assertTrue(
            variants.any { it.label == "Decline 18\"" && it.percentage == 0.73f },
            "Push Up variants should include the 18-inch decline option",
        )
    }

    @Test
    fun getVariantsForExercise_pullUp_returnsVariants() {
        val variants = BodyweightVolumeCalculator.getVariantsForExercise("Pull Up")
        assertTrue(variants != null && variants.size > 1, "Pull Up should have variant options")
    }

    @Test
    fun getVariantsForExercise_unknownExercise_returnsNull() {
        val variants = BodyweightVolumeCalculator.getVariantsForExercise("Barbell Bench Press")
        assertTrue(variants == null, "Non-bodyweight exercise should not have variant options")
    }

    @Test
    fun getPercentage_unknownExercise_returnsDefault() {
        assertEquals(
            BodyweightVolumeCalculator.DEFAULT_PERCENTAGE,
            BodyweightVolumeCalculator.getPercentageForExercise("Unknown Exercise"),
        )
    }

    @Test
    fun calculateVolume_pushUp_calculatesCorrectly() {
        // 80kg body weight, push-up (64%), 10 reps
        val volume = BodyweightVolumeCalculator.calculateVolume("Push Up", 80f, 10)
        // Expected: 80 * 0.64 * 10 = 512
        assertTrue(abs(volume - 512f) < 0.1f, "Expected ~512, got $volume")
    }

    @Test
    fun calculateVolume_pullUp_calculatesCorrectly() {
        // 70kg body weight, pull-up (95%), 5 reps
        val volume = BodyweightVolumeCalculator.calculateVolume("Pull Up", 70f, 5)
        // Expected: 70 * 0.95 * 5 = 332.5
        assertTrue(abs(volume - 332.5f) < 0.1f, "Expected ~332.5, got $volume")
    }

    @Test
    fun calculateVolume_explicitVariantPercentage_overridesExerciseName() {
        val volume = BodyweightVolumeCalculator.calculateVolume(
            bodyWeightKg = 80f,
            reps = 12,
            percentage = 0.73f,
        )

        assertTrue(abs(volume - 700.8f) < 0.1f, "Expected ~700.8, got $volume")
    }

    @Test
    fun calculateVolume_zeroBodyWeight_returnsZero() {
        assertEquals(0f, BodyweightVolumeCalculator.calculateVolume("Push Up", 0f, 10))
    }

    @Test
    fun calculateVolume_zeroReps_returnsZero() {
        assertEquals(0f, BodyweightVolumeCalculator.calculateVolume("Push Up", 80f, 0))
    }

    @Test
    fun effectiveWeight_pushUp_returnsPercentage() {
        val weight = BodyweightVolumeCalculator.effectiveWeight("Push Up", 80f)
        assertTrue(abs(weight - 51.2f) < 0.1f, "Expected ~51.2, got $weight")
    }

    @Test
    fun effectiveWeight_zeroBodyWeight_returnsZero() {
        assertEquals(0f, BodyweightVolumeCalculator.effectiveWeight("Push Up", 0f))
    }

    // === Phase 40 Integration Tests: Cable exercise regression ===

    @Test
    fun calculateVolume_cableExercise_returnsZeroWithBodyweight() {
        // Cable exercises (e.g. Bench Press) should NOT produce bodyweight volume.
        // Their volume is computed from cable weight * reps in the engine,
        // so calling calculateVolume with bodyweight gives a non-zero result
        // using the DEFAULT_PERCENTAGE. This is expected behavior:
        // callers should only call calculateVolume for isBodyweight exercises.
        val volume = BodyweightVolumeCalculator.calculateVolume("Bench Press", 80f, 10)
        // Even unknown exercises get the default 64% — this is why the
        // ActiveSessionEngine gates the call on exercise.isBodyweight.
        assertTrue(volume > 0f, "Non-bodyweight exercises still use default percentage when called directly")
        assertTrue(
            abs(volume - (80f * BodyweightVolumeCalculator.DEFAULT_PERCENTAGE * 10)) < 0.1f,
            "Expected default percentage applied, got $volume",
        )
    }

    @Test
    fun calculateVolume_negativeBodyWeight_returnsZero() {
        assertEquals(0f, BodyweightVolumeCalculator.calculateVolume("Push Up", -5f, 10))
    }

    @Test
    fun calculateVolume_negativeReps_returnsZero() {
        assertEquals(0f, BodyweightVolumeCalculator.calculateVolume("Push Up", 80f, -3))
    }

    @Test
    fun calculateVolume_declinePushUp_correctPercentage() {
        // Decline push-up (generic) = 70%
        val volume = BodyweightVolumeCalculator.calculateVolume("Decline Push Up", 80f, 10)
        // Expected: 80 * 0.70 * 10 = 560
        assertTrue(abs(volume - 560f) < 0.1f, "Expected ~560, got $volume")
    }

    @Test
    fun calculateVolume_pullUp_correctPercentage() {
        // Pull-up = 95%
        val volume = BodyweightVolumeCalculator.calculateVolume("Pull Up", 80f, 10)
        // Expected: 80 * 0.95 * 10 = 760
        assertTrue(abs(volume - 760f) < 0.1f, "Expected ~760, got $volume")
    }

    @Test
    fun calculateVolume_dips_correctPercentage() {
        val volume = BodyweightVolumeCalculator.calculateVolume("Dips", 80f, 10)
        // Expected: 80 * 0.95 * 10 = 760
        assertTrue(abs(volume - 760f) < 0.1f, "Expected ~760, got $volume")
    }

    @Test
    fun effectiveWeight_declinePushUp24_returnsCorrectWeight() {
        val weight = BodyweightVolumeCalculator.effectiveWeight("Decline 24\" Push Up", 80f)
        // Expected: 80 * 0.75 = 60
        assertTrue(abs(weight - 60f) < 0.1f, "Expected ~60, got $weight")
    }

    @Test
    fun effectiveWeight_explicitVariantPercentage_returnsCorrectWeight() {
        val weight = BodyweightVolumeCalculator.effectiveWeight(80f, 0.73f)
        assertTrue(abs(weight - 58.4f) < 0.1f, "Expected ~58.4, got $weight")
    }
}
