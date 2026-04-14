package com.devil.phoenixproject.data.integration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the data mapping logic shared between Android Health Connect
 * and iOS HealthKit integrations. These validate title formatting, duration
 * clamping, and calorie gating without requiring platform health APIs.
 */
class HealthDataMappingTest {

    // ---- Title formatting (mirrors buildExerciseTitle on both platforms) ----

    @Test
    fun titleWithExerciseNameAndWeight() {
        // Single cable exercise (default when cableCount is unspecified/null)
        val title = buildTitle(exerciseName = "Calf Raise", weightPerCableKg = 80f)
        assertEquals("Calf Raise \u2014 80.0kg", title)
    }

    @Test
    fun titleWithDualCableExercise() {
        // Dual cable exercise - weight is doubled (50kg per cable x 2)
        val title = buildTitle(exerciseName = "Bench Press", weightPerCableKg = 50f, cableCount = 2)
        assertEquals("Bench Press \u2014 100.0kg", title)
    }

    @Test
    fun titleWithNullExerciseNameFallsBackToPhoenixWorkout() {
        val title = buildTitle(exerciseName = null, weightPerCableKg = 30f, cableCount = 2)
        assertEquals("Phoenix Workout \u2014 60.0kg", title)
    }

    @Test
    fun titleWithBlankExerciseNameFallsBackToPhoenixWorkout() {
        val title = buildTitle(exerciseName = "  ", weightPerCableKg = 25f, cableCount = 2)
        assertEquals("Phoenix Workout \u2014 50.0kg", title)
    }

    @Test
    fun titleWithZeroWeightOmitsWeightSuffix() {
        val title = buildTitle(exerciseName = "Deadlift", weightPerCableKg = 0f)
        assertEquals("Deadlift", title)
    }

    @Test
    fun titleWeightIsDoubledForDualCable() {
        // Weight convention: stored per-cable, displayed as total (x2) for dual-cable
        val title = buildTitle(exerciseName = "Squat", weightPerCableKg = 110f, cableCount = 2)
        assertEquals("Squat \u2014 220.0kg", title)
    }

    @Test
    fun titleWeightNotDoubledForSingleCable() {
        val title = buildTitle(exerciseName = "Reverse Lunge", weightPerCableKg = 30f, cableCount = 1)
        assertEquals("Reverse Lunge \u2014 30.0kg", title)
    }

    @Test
    fun titleWeightDefaultsToSingleWhenCableCountNull() {
        // Legacy sessions without cableCount default to single cable (safer assumption)
        // This matches effectiveTotalVolumeKg, InsightCards, and official Vitruvian app behavior
        val title = buildTitle(exerciseName = "Bench Press", weightPerCableKg = 50f, cableCount = null)
        assertEquals("Bench Press \u2014 50.0kg", title)
    }

    // ---- Duration clamping ----

    @Test
    fun durationClampedToMinimumOneSecond() {
        assertEquals(1L, clampDuration(0L))
    }

    @Test
    fun negativeDurationClampedToOne() {
        assertEquals(1L, clampDuration(-5L))
    }

    @Test
    fun positiveDurationPassedThrough() {
        assertEquals(3600L, clampDuration(3600L))
    }

    // ---- Calorie gating ----

    @Test
    fun nullCaloriesExcluded() {
        assertEquals(false, shouldIncludeCalories(null))
    }

    @Test
    fun zeroCaloriesExcluded() {
        assertEquals(false, shouldIncludeCalories(0f))
    }

    @Test
    fun negativeCaloriesExcluded() {
        assertEquals(false, shouldIncludeCalories(-50f))
    }

    @Test
    fun positiveCaloriesIncluded() {
        assertEquals(true, shouldIncludeCalories(250f))
    }

    // ---- Extracted pure functions matching both platform implementations ----

    private fun buildTitle(exerciseName: String?, weightPerCableKg: Float, cableCount: Int? = null): String {
        val name = exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"
        // Default to 1 cable for legacy sessions without cableCount metadata
        val totalWeightKg = weightPerCableKg * (cableCount ?: 1).toFloat()
        return if (totalWeightKg > 0f) {
            val rounded = (totalWeightKg * 10).toInt() / 10.0
            "$name \u2014 ${rounded}kg"
        } else {
            name
        }
    }

    private fun clampDuration(durationSeconds: Long): Long = durationSeconds.coerceAtLeast(1L)

    private fun shouldIncludeCalories(estimatedCalories: Float?): Boolean = estimatedCalories != null && estimatedCalories > 0f
}
