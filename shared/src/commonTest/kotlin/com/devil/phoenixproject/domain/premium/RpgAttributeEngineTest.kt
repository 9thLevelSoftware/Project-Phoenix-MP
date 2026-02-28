package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgAttributeEngineTest {

    // ---- Helper factory ----

    private fun input(
        maxWeightLiftedKg: Double = 0.0,
        totalVolumeKg: Double = 0.0,
        totalWorkouts: Int = 1,
        totalReps: Int = 0,
        uniqueExercises: Int = 0,
        personalRecords: Int = 0,
        peakPowerWatts: Double = 0.0,
        avgWorkingWeightKg: Double = 0.0,
        currentStreak: Int = 0,
        longestStreak: Int = 0,
        trainingDays: Int = 0,
        badgesEarned: Int = 0
    ) = RpgInput(
        maxWeightLiftedKg = maxWeightLiftedKg,
        totalVolumeKg = totalVolumeKg,
        totalWorkouts = totalWorkouts,
        totalReps = totalReps,
        uniqueExercises = uniqueExercises,
        personalRecords = personalRecords,
        peakPowerWatts = peakPowerWatts,
        avgWorkingWeightKg = avgWorkingWeightKg,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        trainingDays = trainingDays,
        badgesEarned = badgesEarned
    )

    // ==========================================================
    // Empty / Guard Tests
    // ==========================================================

    @Test
    fun emptyInputReturnsEmptyProfile() {
        val result = RpgAttributeEngine.computeProfile(
            input(totalWorkouts = 0)
        )
        assertEquals(RpgProfile.EMPTY, result)
    }

    // ==========================================================
    // Individual Attribute Tests
    // ==========================================================

    @Test
    fun strengthComputationWithHighMaxWeight() {
        val result = RpgAttributeEngine.computeProfile(
            input(maxWeightLiftedKg = 180.0, avgWorkingWeightKg = 100.0)
        )
        assertTrue(
            result.strength >= 60,
            "Strength should be >= 60 for 180kg max / 100kg avg, got ${result.strength}"
        )
    }

    @Test
    fun powerComputationWithHighPeakPower() {
        val result = RpgAttributeEngine.computeProfile(
            input(peakPowerWatts = 1800.0)
        )
        assertTrue(
            result.power >= 80,
            "Power should be >= 80 for 1800W peak, got ${result.power}"
        )
    }

    @Test
    fun staminaComputationWithHighVolume() {
        val result = RpgAttributeEngine.computeProfile(
            input(totalVolumeKg = 400000.0, totalReps = 40000)
        )
        assertTrue(
            result.stamina >= 70,
            "Stamina should be >= 70 for 400k volume / 40k reps, got ${result.stamina}"
        )
    }

    @Test
    fun consistencyWithLongStreak() {
        val result = RpgAttributeEngine.computeProfile(
            input(longestStreak = 80, trainingDays = 400, currentStreak = 30)
        )
        assertTrue(
            result.consistency >= 70,
            "Consistency should be >= 70 for 80-day streak / 400 training days, got ${result.consistency}"
        )
    }

    @Test
    fun masteryWithHighVariety() {
        val result = RpgAttributeEngine.computeProfile(
            input(uniqueExercises = 45, personalRecords = 90, badgesEarned = 35)
        )
        assertTrue(
            result.mastery >= 75,
            "Mastery should be >= 75 for 45 exercises / 90 PRs / 35 badges, got ${result.mastery}"
        )
    }

    // ==========================================================
    // Character Classification Tests
    // ==========================================================

    @Test
    fun balancedAttributesClassifyAsPhoenix() {
        // All attributes within 15pt spread -> PHOENIX
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 100.0,
                avgWorkingWeightKg = 60.0,
                peakPowerWatts = 1000.0,
                totalVolumeKg = 250000.0,
                totalReps = 25000,
                longestStreak = 45,
                trainingDays = 250,
                currentStreak = 15,
                uniqueExercises = 25,
                personalRecords = 50,
                badgesEarned = 20
            )
        )
        assertEquals(
            CharacterClass.PHOENIX,
            result.characterClass,
            "Balanced attributes should classify as PHOENIX, got ${result.characterClass} " +
                "(str=${result.strength}, pow=${result.power}, sta=${result.stamina}, " +
                "con=${result.consistency}, mas=${result.mastery})"
        )
    }

    @Test
    fun dominantStrengthClassifiesAsPowerlifter() {
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 200.0,
                avgWorkingWeightKg = 120.0,
                peakPowerWatts = 200.0,
                totalVolumeKg = 10000.0,
                totalReps = 1000,
                longestStreak = 5,
                trainingDays = 20,
                currentStreak = 2,
                uniqueExercises = 3,
                personalRecords = 5,
                badgesEarned = 2
            )
        )
        assertEquals(
            CharacterClass.POWERLIFTER,
            result.characterClass,
            "Dominant strength should classify as POWERLIFTER, got ${result.characterClass} " +
                "(str=${result.strength}, pow=${result.power}, sta=${result.stamina}, " +
                "con=${result.consistency}, mas=${result.mastery})"
        )
    }

    @Test
    fun dominantPowerClassifiesAsAthlete() {
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 30.0,
                avgWorkingWeightKg = 20.0,
                peakPowerWatts = 1900.0,
                totalVolumeKg = 10000.0,
                totalReps = 1000,
                longestStreak = 5,
                trainingDays = 20,
                currentStreak = 2,
                uniqueExercises = 3,
                personalRecords = 5,
                badgesEarned = 2
            )
        )
        assertEquals(
            CharacterClass.ATHLETE,
            result.characterClass,
            "Dominant power should classify as ATHLETE, got ${result.characterClass} " +
                "(str=${result.strength}, pow=${result.power}, sta=${result.stamina}, " +
                "con=${result.consistency}, mas=${result.mastery})"
        )
    }

    @Test
    fun dominantStaminaClassifiesAsIronman() {
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 30.0,
                avgWorkingWeightKg = 20.0,
                peakPowerWatts = 200.0,
                totalVolumeKg = 490000.0,
                totalReps = 49000,
                longestStreak = 5,
                trainingDays = 20,
                currentStreak = 2,
                uniqueExercises = 3,
                personalRecords = 5,
                badgesEarned = 2
            )
        )
        assertEquals(
            CharacterClass.IRONMAN,
            result.characterClass,
            "Dominant stamina should classify as IRONMAN, got ${result.characterClass} " +
                "(str=${result.strength}, pow=${result.power}, sta=${result.stamina}, " +
                "con=${result.consistency}, mas=${result.mastery})"
        )
    }

    @Test
    fun dominantConsistencyClassifiesAsMonk() {
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 30.0,
                avgWorkingWeightKg = 20.0,
                peakPowerWatts = 200.0,
                totalVolumeKg = 10000.0,
                totalReps = 1000,
                longestStreak = 89,
                trainingDays = 490,
                currentStreak = 29,
                uniqueExercises = 3,
                personalRecords = 5,
                badgesEarned = 2
            )
        )
        assertEquals(
            CharacterClass.MONK,
            result.characterClass,
            "Dominant consistency should classify as MONK, got ${result.characterClass} " +
                "(str=${result.strength}, pow=${result.power}, sta=${result.stamina}, " +
                "con=${result.consistency}, mas=${result.mastery})"
        )
    }

    // ==========================================================
    // Edge Cases
    // ==========================================================

    @Test
    fun allAttributesCoercedTo0To100() {
        // Extreme inputs far above ceilings
        val result = RpgAttributeEngine.computeProfile(
            input(
                maxWeightLiftedKg = 500.0,
                avgWorkingWeightKg = 300.0,
                peakPowerWatts = 5000.0,
                totalVolumeKg = 2000000.0,
                totalReps = 200000,
                longestStreak = 500,
                trainingDays = 3000,
                currentStreak = 200,
                uniqueExercises = 200,
                personalRecords = 500,
                badgesEarned = 200
            )
        )
        assertTrue(result.strength in 0..100, "Strength out of range: ${result.strength}")
        assertTrue(result.power in 0..100, "Power out of range: ${result.power}")
        assertTrue(result.stamina in 0..100, "Stamina out of range: ${result.stamina}")
        assertTrue(result.consistency in 0..100, "Consistency out of range: ${result.consistency}")
        assertTrue(result.mastery in 0..100, "Mastery out of range: ${result.mastery}")
    }

    @Test
    fun normalizeHandlesZeroCeiling() {
        // When all inputs are zero but totalWorkouts >= 1, engine should not crash
        val result = RpgAttributeEngine.computeProfile(
            input(totalWorkouts = 1)
        )
        // Should return all zeros (normalize with zero values = 0) without NaN/crash
        assertTrue(result.strength in 0..100, "Strength should be valid: ${result.strength}")
        assertTrue(result.power in 0..100, "Power should be valid: ${result.power}")
        assertTrue(result.stamina in 0..100, "Stamina should be valid: ${result.stamina}")
        assertTrue(result.consistency in 0..100, "Consistency should be valid: ${result.consistency}")
        assertTrue(result.mastery in 0..100, "Mastery should be valid: ${result.mastery}")
    }
}
