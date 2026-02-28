package com.devil.phoenixproject.domain.model

/**
 * RPG attribute types representing different aspects of training performance.
 * Each attribute is scored 0-100 based on aggregate workout data.
 */
enum class RpgAttribute(val displayName: String, val description: String) {
    STRENGTH("Strength", "Peak load lifted"),
    POWER("Power", "Explosive force generation"),
    STAMINA("Stamina", "Training volume endurance"),
    CONSISTENCY("Consistency", "Training regularity"),
    MASTERY("Mastery", "Exercise variety and technique")
}

/**
 * Character class auto-assigned from dominant attribute ratio.
 * PHOENIX is the balanced fallback when no single attribute dominates.
 */
enum class CharacterClass(val displayName: String, val description: String) {
    POWERLIFTER("Powerlifter", "Dominant in raw strength"),
    ATHLETE("Athlete", "Dominant in explosive power"),
    IRONMAN("Ironman", "Dominant in volume endurance"),
    MONK("Monk", "Dominant in training discipline"),
    PHOENIX("Phoenix", "Balanced across all attributes")
}

/**
 * RPG profile containing computed attribute scores and character classification.
 * All attribute values are integers in the range 0-100.
 */
data class RpgProfile(
    val strength: Int,
    val power: Int,
    val stamina: Int,
    val consistency: Int,
    val mastery: Int,
    val characterClass: CharacterClass,
    val lastComputed: Long = 0
) {
    companion object {
        val EMPTY = RpgProfile(0, 0, 0, 0, 0, CharacterClass.PHOENIX)
    }
}

/**
 * Aggregate workout data used as input for RPG attribute computation.
 * All values are sourced from WorkoutSession, RepMetric, GamificationStats,
 * PersonalRecord, and EarnedBadge tables.
 */
data class RpgInput(
    val maxWeightLiftedKg: Double,
    val totalVolumeKg: Double,
    val totalWorkouts: Int,
    val totalReps: Int,
    val uniqueExercises: Int,
    val personalRecords: Int,
    val peakPowerWatts: Double,
    val avgWorkingWeightKg: Double,
    val currentStreak: Int,
    val longestStreak: Int,
    val trainingDays: Int,
    val badgesEarned: Int
)
