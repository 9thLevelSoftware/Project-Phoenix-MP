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
    fun getPercentage_unknownExercise_returnsDefault() {
        assertEquals(
            BodyweightVolumeCalculator.DEFAULT_PERCENTAGE,
            BodyweightVolumeCalculator.getPercentageForExercise("Unknown Exercise")
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
