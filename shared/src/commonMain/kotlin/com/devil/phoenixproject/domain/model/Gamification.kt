package com.devil.phoenixproject.domain.model

/**
 * Gamification domain models for badges and streaks
 */

/**
 * Badge categories for organization and filtering
 */
enum class BadgeCategory(val displayName: String, val icon: String) {
    CONSISTENCY("Consistency", "fire"),       // Streak-based badges
    STRENGTH("Strength", "trophy"),           // PR and weight milestones
    VOLUME("Volume", "repeat"),               // Total reps/volume milestones
    EXPLORER("Explorer", "compass"),          // Exercise variety badges
    DEDICATION("Dedication", "dumbbell")      // Long-term commitment badges
}

/**
 * Badge tier levels - determines visual appearance and prestige
 */
enum class BadgeTier(val displayName: String, val colorHex: Long) {
    BRONZE("Bronze", 0xFFCD7F32),
    SILVER("Silver", 0xFFC0C0C0),
    GOLD("Gold", 0xFFFFD700),
    PLATINUM("Platinum", 0xFFE5E4E2)
}

/**
 * Sealed class representing different types of badge requirements
 */
sealed class BadgeRequirement {
    /** Consecutive days with at least one workout */
    data class StreakDays(val days: Int) : BadgeRequirement()

    /** Total number of workouts completed */
    data class TotalWorkouts(val count: Int) : BadgeRequirement()

    /** Total number of reps completed across all workouts */
    data class TotalReps(val count: Int) : BadgeRequirement()

    /** Number of personal records achieved */
    data class PRsAchieved(val count: Int) : BadgeRequirement()

    /** Number of different exercises performed */
    data class UniqueExercises(val count: Int) : BadgeRequirement()

    /** Number of workouts in a single week */
    data class WorkoutsInWeek(val count: Int) : BadgeRequirement()

    /** Consecutive weeks with at least one workout */
    data class ConsecutiveWeeks(val weeks: Int) : BadgeRequirement()

    /** Total volume (weight × reps) lifted in kg */
    data class TotalVolume(val kgLifted: Long) : BadgeRequirement()

    /** Volume lifted in a single workout */
    data class SingleWorkoutVolume(val kgLifted: Int) : BadgeRequirement()

    /** Workout completed at a specific time */
    data class WorkoutAtTime(val hourStart: Int, val hourEnd: Int) : BadgeRequirement()

    /** Number of workouts completed at a specific time range */
    data class WorkoutsAtTimeCount(val hourStart: Int, val hourEnd: Int, val count: Int) : BadgeRequirement()

    /** Number of workouts in a specific workout mode */
    data class WorkoutModeCount(val modeName: String, val count: Int) : BadgeRequirement()

    /** Used all available workout modes at least once */
    data object AllWorkoutModes : BadgeRequirement()

    /** Peak power achieved in watts */
    data class PeakPower(val watts: Int) : BadgeRequirement()

    /** Number of unique muscle groups trained */
    data class UniqueMuscleGroups(val count: Int) : BadgeRequirement()

    /** Return to training after a break of N days */
    data class ComebackAfterBreak(val breakDays: Int) : BadgeRequirement()

    /** Complete a workout when streak is at risk */
    data object StreakSaved : BadgeRequirement()

    /** Rebuild a streak of N days after losing a previous streak */
    data class StreakRebuilt(val days: Int) : BadgeRequirement()

    /** Number of workouts on weekend days (Saturday/Sunday) */
    data class WeekendWorkouts(val count: Int) : BadgeRequirement()

    /** Number of complete routines finished */
    data class RoutinesCompleted(val count: Int) : BadgeRequirement()

    /** Number of custom routines created */
    data class RoutinesCreated(val count: Int) : BadgeRequirement()
}

/**
 * Badge definition - describes a badge and how to earn it
 */
data class Badge(
    val id: String,
    val name: String,
    val description: String,
    val category: BadgeCategory,
    val iconResource: String,
    val tier: BadgeTier,
    val requirement: BadgeRequirement,
    val isSecret: Boolean = false  // Hidden until earned
) {
    /**
     * Get progress description for this badge
     */
    fun getProgressDescription(currentValue: Int): String {
        return when (val req = requirement) {
            is BadgeRequirement.StreakDays -> "$currentValue/${req.days} days"
            is BadgeRequirement.TotalWorkouts -> "$currentValue/${req.count} workouts"
            is BadgeRequirement.TotalReps -> "$currentValue/${req.count} reps"
            is BadgeRequirement.PRsAchieved -> "$currentValue/${req.count} PRs"
            is BadgeRequirement.UniqueExercises -> "$currentValue/${req.count} exercises"
            is BadgeRequirement.WorkoutsInWeek -> "$currentValue/${req.count} this week"
            is BadgeRequirement.ConsecutiveWeeks -> "$currentValue/${req.weeks} weeks"
            is BadgeRequirement.TotalVolume -> "${currentValue}/${req.kgLifted} kg"
            is BadgeRequirement.SingleWorkoutVolume -> "${currentValue}/${req.kgLifted} kg"
            is BadgeRequirement.WorkoutAtTime -> if (currentValue > 0) "Completed!" else "Pending"
            is BadgeRequirement.WorkoutsAtTimeCount -> "$currentValue/${req.count} workouts"
            is BadgeRequirement.WorkoutModeCount -> "$currentValue/${req.count} workouts"
            is BadgeRequirement.AllWorkoutModes -> "$currentValue/6 modes"
            is BadgeRequirement.PeakPower -> "${currentValue}/${req.watts}W"
            is BadgeRequirement.UniqueMuscleGroups -> "$currentValue/${req.count} groups"
            is BadgeRequirement.ComebackAfterBreak -> if (currentValue > 0) "Completed!" else "Pending"
            is BadgeRequirement.StreakSaved -> if (currentValue > 0) "Completed!" else "Pending"
            is BadgeRequirement.StreakRebuilt -> "$currentValue/${req.days} days"
            is BadgeRequirement.WeekendWorkouts -> "$currentValue/${req.count} workouts"
            is BadgeRequirement.RoutinesCompleted -> "$currentValue/${req.count} routines"
            is BadgeRequirement.RoutinesCreated -> "$currentValue/${req.count} routines"
        }
    }

    /**
     * Get target value for this badge
     */
    fun getTargetValue(): Int {
        return when (val req = requirement) {
            is BadgeRequirement.StreakDays -> req.days
            is BadgeRequirement.TotalWorkouts -> req.count
            is BadgeRequirement.TotalReps -> req.count
            is BadgeRequirement.PRsAchieved -> req.count
            is BadgeRequirement.UniqueExercises -> req.count
            is BadgeRequirement.WorkoutsInWeek -> req.count
            is BadgeRequirement.ConsecutiveWeeks -> req.weeks
            is BadgeRequirement.TotalVolume -> req.kgLifted.toInt()
            is BadgeRequirement.SingleWorkoutVolume -> req.kgLifted
            is BadgeRequirement.WorkoutAtTime -> 1
            is BadgeRequirement.WorkoutsAtTimeCount -> req.count
            is BadgeRequirement.WorkoutModeCount -> req.count
            is BadgeRequirement.AllWorkoutModes -> 6
            is BadgeRequirement.PeakPower -> req.watts
            is BadgeRequirement.UniqueMuscleGroups -> req.count
            is BadgeRequirement.ComebackAfterBreak -> 1
            is BadgeRequirement.StreakSaved -> 1
            is BadgeRequirement.StreakRebuilt -> req.days
            is BadgeRequirement.WeekendWorkouts -> req.count
            is BadgeRequirement.RoutinesCompleted -> req.count
            is BadgeRequirement.RoutinesCreated -> req.count
        }
    }
}

/**
 * Represents a badge earned by the user
 */
data class EarnedBadge(
    val id: Long = 0,
    val badgeId: String,
    val earnedAt: Long,          // Timestamp when badge was earned
    val celebratedAt: Long? = null  // Timestamp when user saw the celebration (null if not seen)
) {
    val hasBeenCelebrated: Boolean
        get() = celebratedAt != null
}

/**
 * Streak tracking information
 */
data class StreakInfo(
    val currentStreak: Int,
    val longestStreak: Int,
    val streakStartDate: Long?,
    val lastWorkoutDate: Long?,
    val isAtRisk: Boolean = false  // True if no workout today and streak would break tomorrow
) {
    companion object {
        val EMPTY = StreakInfo(
            currentStreak = 0,
            longestStreak = 0,
            streakStartDate = null,
            lastWorkoutDate = null,
            isAtRisk = false
        )
    }
}

/**
 * Aggregate gamification statistics
 */
data class GamificationStats(
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Long = 0,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val uniqueExercisesUsed: Int = 0,
    val prsAchieved: Int = 0,
    val badgesEarned: Int = 0,
    val lastUpdated: Long = 0
) {
    companion object {
        val EMPTY = GamificationStats()
    }
}

/**
 * Event emitted when a badge is earned
 */
data class BadgeEarnedEvent(
    val badge: Badge,
    val earnedAt: Long
)
