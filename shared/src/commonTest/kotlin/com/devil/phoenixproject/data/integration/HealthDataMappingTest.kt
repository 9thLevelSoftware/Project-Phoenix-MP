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

    // ---- Issue #395: RoutineHealthData construction ----

    @Test
    fun routineHealthDataHasCorrectTitle() {
        val data = buildRoutineHealthData(routineName = "Push Day", totalCalories = 150f)
        assertEquals("Push Day", data.routineName)
    }

    @Test
    fun routineHealthDataNullCaloriesWhenZeroAccumulated() {
        val data = buildRoutineHealthData(routineName = "Pull Day", totalCalories = 0f)
        assertEquals(null, data.totalCalories)
    }

    @Test
    fun routineHealthDataAccumulatesCalories() {
        // Simulate 3 sets with calories
        val accumulated = accumulateCalories(listOf(80f, 95f, null, 75f))
        assertEquals(250f, accumulated)
    }

    @Test
    fun routineHealthDataAccumulatesCaloriesSkipsNullAndZero() {
        val accumulated = accumulateCalories(listOf(null, 0f, -5f, 100f))
        assertEquals(100f, accumulated)
    }

    @Test
    fun routineHealthDataDurationFromStartToEnd() {
        val startMs = 1700000000000L
        val endMs = 1700003600000L // 1 hour later
        val durationMs = endMs - startMs
        val data = buildRoutineHealthData(
            routineName = "Legs",
            startTimeMs = startMs,
            durationMs = durationMs,
            totalCalories = 300f,
        )
        assertEquals(3600000L, data.durationMs)
        assertEquals(startMs, data.startTimeMs)
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

    /** Mirrors the routine health data construction in writeRoutineHealthData() */
    private fun buildRoutineHealthData(
        routineName: String,
        startTimeMs: Long = 1700000000000L,
        durationMs: Long = 60000L,
        totalCalories: Float?,
    ): RoutineHealthData = RoutineHealthData(
        routineName = routineName,
        startTimeMs = startTimeMs,
        durationMs = durationMs,
        totalCalories = totalCalories?.takeIf { it > 0f },
        externalId = "test-routine-session-id",
    )

    /** Mirrors calorie accumulation logic in saveWorkoutSession() */
    private fun accumulateCalories(setCalories: List<Float?>): Float {
        var accumulated = 0f
        for (cal in setCalories) {
            if (cal != null && cal > 0f) {
                accumulated += cal
            }
        }
        return accumulated
    }
}
