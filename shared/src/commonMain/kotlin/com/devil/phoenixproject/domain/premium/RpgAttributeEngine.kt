package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile

/**
 * Pure stateless computation engine for RPG attributes.
 * Follows the ReadinessEngine pattern: stateless object with pure functions,
 * no DB or DI dependencies.
 *
 * Computes five attribute scores (0-100) from aggregate workout data and
 * classifies the user into a CharacterClass based on dominant attribute.
 *
 * Ceiling constants are tuned for the Vitruvian Trainer (max 220kg machine).
 */
object RpgAttributeEngine {

    // -- Ceiling constants for normalization --
    private const val MAX_WEIGHT_CEILING = 200.0
    private const val AVG_WEIGHT_CEILING = 120.0
    private const val POWER_CEILING = 2000.0
    private const val VOLUME_CEILING = 500000.0
    private const val REPS_CEILING = 50000.0
    private const val STREAK_CEILING = 90.0
    private const val FREQUENCY_CEILING = 500.0
    private const val CURRENT_STREAK_CEILING = 30.0
    private const val VARIETY_CEILING = 50.0
    private const val PR_CEILING = 100.0
    private const val BADGE_CEILING = 40.0

    // -- Balanced threshold for character classification --
    private const val BALANCED_THRESHOLD = 15

    /**
     * Compute a full RPG profile from aggregate workout input.
     *
     * @param input Aggregate workout data (volumes, streaks, PRs, etc.)
     * @return [RpgProfile] with five 0-100 attribute scores and a [CharacterClass],
     *         or [RpgProfile.EMPTY] if the user has no workouts.
     */
    fun computeProfile(input: RpgInput): RpgProfile {
        if (input.totalWorkouts < 1) return RpgProfile.EMPTY

        val str = computeStrength(input)
        val pow = computePower(input)
        val sta = computeStamina(input)
        val con = computeConsistency(input)
        val mas = computeMastery(input)
        val characterClass = classifyCharacter(str, pow, sta, con, mas)

        return RpgProfile(
            strength = str,
            power = pow,
            stamina = sta,
            consistency = con,
            mastery = mas,
            characterClass = characterClass
        )
    }

    /**
     * Strength: 70% maxLiftScore + 30% avgWeightScore.
     * Normalized against Vitruvian max (200kg) and typical working weight (120kg).
     */
    internal fun computeStrength(input: RpgInput): Int {
        val maxLiftScore = normalize(input.maxWeightLiftedKg, MAX_WEIGHT_CEILING)
        val avgWeightScore = normalize(input.avgWorkingWeightKg, AVG_WEIGHT_CEILING)
        val raw = 0.70 * maxLiftScore + 0.30 * avgWeightScore
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Power: Normalized peak power watts against ceiling.
     */
    internal fun computePower(input: RpgInput): Int {
        val raw = normalize(input.peakPowerWatts, POWER_CEILING)
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Stamina: 60% volumeScore + 40% repsScore.
     */
    internal fun computeStamina(input: RpgInput): Int {
        val volumeScore = normalize(input.totalVolumeKg, VOLUME_CEILING)
        val repsScore = normalize(input.totalReps.toDouble(), REPS_CEILING)
        val raw = 0.60 * volumeScore + 0.40 * repsScore
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Consistency: 40% streakScore + 40% frequencyScore + 20% currentStreakBonus.
     */
    internal fun computeConsistency(input: RpgInput): Int {
        val streakScore = normalize(input.longestStreak.toDouble(), STREAK_CEILING)
        val frequencyScore = normalize(input.trainingDays.toDouble(), FREQUENCY_CEILING)
        val currentStreakBonus = normalize(input.currentStreak.toDouble(), CURRENT_STREAK_CEILING)
        val raw = 0.40 * streakScore + 0.40 * frequencyScore + 0.20 * currentStreakBonus
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Mastery: 40% varietyScore + 35% prScore + 25% badgeScore.
     */
    internal fun computeMastery(input: RpgInput): Int {
        val varietyScore = normalize(input.uniqueExercises.toDouble(), VARIETY_CEILING)
        val prScore = normalize(input.personalRecords.toDouble(), PR_CEILING)
        val badgeScore = normalize(input.badgesEarned.toDouble(), BADGE_CEILING)
        val raw = 0.40 * varietyScore + 0.35 * prScore + 0.25 * badgeScore
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Classify character based on dominant attribute.
     * If the spread (max - min) is <= [BALANCED_THRESHOLD], returns PHOENIX.
     * Otherwise, the dominant attribute determines the class.
     * Mastery dominant also maps to PHOENIX (well-rounded achiever).
     */
    internal fun classifyCharacter(str: Int, pow: Int, sta: Int, con: Int, mas: Int): CharacterClass {
        val attrs = listOf(str, pow, sta, con, mas)
        val max = attrs.max()
        val min = attrs.min()

        if (max - min <= BALANCED_THRESHOLD) return CharacterClass.PHOENIX

        return when (max) {
            str -> CharacterClass.POWERLIFTER
            pow -> CharacterClass.ATHLETE
            sta -> CharacterClass.IRONMAN
            con -> CharacterClass.MONK
            else -> CharacterClass.PHOENIX // mastery dominant -> PHOENIX
        }
    }

    /**
     * Normalize a value against a ceiling to 0.0-1.0 range.
     * Guards against zero/negative ceiling to prevent division by zero.
     */
    private fun normalize(value: Double, ceiling: Double): Double {
        if (ceiling <= 0.0) return 0.0
        return (value / ceiling).coerceIn(0.0, 1.0)
    }
}
