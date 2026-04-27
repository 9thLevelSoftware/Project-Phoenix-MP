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
        assertEquals("Standard Push-Up", variants!!.first().first)
        assertEquals(0.64f, variants.first().second)
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
}
