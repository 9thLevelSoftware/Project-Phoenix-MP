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
        val title = buildTitle(exerciseName = "Bench Press", weightPerCableKg = 50f)
        assertEquals("Bench Press \u2014 100.0kg", title)
    }

    @Test
    fun titleWithNullExerciseNameFallsBackToPhoenixWorkout() {
        val title = buildTitle(exerciseName = null, weightPerCableKg = 30f)
        assertEquals("Phoenix Workout \u2014 60.0kg", title)
    }

    @Test
    fun titleWithBlankExerciseNameFallsBackToPhoenixWorkout() {
        val title = buildTitle(exerciseName = "  ", weightPerCableKg = 25f)
        assertEquals("Phoenix Workout \u2014 50.0kg", title)
    }

    @Test
    fun titleWithZeroWeightOmitsWeightSuffix() {
        val title = buildTitle(exerciseName = "Deadlift", weightPerCableKg = 0f)
        assertEquals("Deadlift", title)
    }

    @Test
    fun titleWeightIsDoubledForDualCable() {
        // Weight convention: stored per-cable, displayed as total (x2)
        val title = buildTitle(exerciseName = "Squat", weightPerCableKg = 110f)
        assertEquals("Squat \u2014 220.0kg", title)
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

    private fun buildTitle(exerciseName: String?, weightPerCableKg: Float): String {
        val name = exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"
        val totalWeightKg = weightPerCableKg * 2f
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
