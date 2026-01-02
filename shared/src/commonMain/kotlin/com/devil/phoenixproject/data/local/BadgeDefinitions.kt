package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.BadgeCategory.*
import com.devil.phoenixproject.domain.model.BadgeTier.*
import com.devil.phoenixproject.domain.model.BadgeRequirement.*

/**
 * All badge definitions for the gamification system.
 * Badges are grouped by category and tier for easy reference.
 */
object BadgeDefinitions {

    /**
     * All available badges in the system
     */
    val allBadges: List<Badge> = listOf(
        // ==================== CONSISTENCY BADGES (Streak-based) ====================
        Badge(
            id = "streak_3",
            name = "Getting Started",
            description = "Maintain a 3-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = BRONZE,
            requirement = StreakDays(3)
        ),
        Badge(
            id = "streak_7",
            name = "Week Warrior",
            description = "Maintain a 7-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = SILVER,
            requirement = StreakDays(7)
        ),
        Badge(
            id = "streak_14",
            name = "Fortnight Fighter",
            description = "Maintain a 14-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = SILVER,
            requirement = StreakDays(14)
        ),
        Badge(
            id = "streak_30",
            name = "Monthly Dedication",
            description = "Maintain a 30-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = GOLD,
            requirement = StreakDays(30)
        ),
        Badge(
            id = "streak_60",
            name = "Iron Discipline",
            description = "Maintain a 60-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = GOLD,
            requirement = StreakDays(60)
        ),
        Badge(
            id = "streak_100",
            name = "Centurion",
            description = "Maintain a 100-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = PLATINUM,
            requirement = StreakDays(100)
        ),
        Badge(
            id = "streak_365",
            name = "Year of Iron",
            description = "Maintain a 365-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = PLATINUM,
            requirement = StreakDays(365),
            isSecret = true
        ),

        // ==================== DEDICATION BADGES (Workout count) ====================
        Badge(
            id = "workouts_1",
            name = "First Rep",
            description = "Complete your first workout",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = BRONZE,
            requirement = TotalWorkouts(1)
        ),
        Badge(
            id = "workouts_10",
            name = "Getting Hooked",
            description = "Complete 10 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = BRONZE,
            requirement = TotalWorkouts(10)
        ),
        Badge(
            id = "workouts_25",
            name = "Building Habit",
            description = "Complete 25 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = TotalWorkouts(25)
        ),
        Badge(
            id = "workouts_50",
            name = "Regular",
            description = "Complete 50 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = TotalWorkouts(50)
        ),
        Badge(
            id = "workouts_100",
            name = "Committed",
            description = "Complete 100 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = GOLD,
            requirement = TotalWorkouts(100)
        ),
        Badge(
            id = "workouts_250",
            name = "Dedicated",
            description = "Complete 250 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = GOLD,
            requirement = TotalWorkouts(250)
        ),
        Badge(
            id = "workouts_500",
            name = "Iron Will",
            description = "Complete 500 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = PLATINUM,
            requirement = TotalWorkouts(500)
        ),
        Badge(
            id = "workouts_1000",
            name = "Legend",
            description = "Complete 1,000 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = PLATINUM,
            requirement = TotalWorkouts(1000),
            isSecret = true
        ),

        // ==================== STRENGTH BADGES (PR-based) ====================
        Badge(
            id = "pr_1",
            name = "Personal Best",
            description = "Achieve your first personal record",
            category = STRENGTH,
            iconResource = "trophy",
            tier = BRONZE,
            requirement = PRsAchieved(1)
        ),
        Badge(
            id = "pr_5",
            name = "Record Setter",
            description = "Achieve 5 personal records",
            category = STRENGTH,
            iconResource = "trophy",
            tier = BRONZE,
            requirement = PRsAchieved(5)
        ),
        Badge(
            id = "pr_10",
            name = "Record Breaker",
            description = "Achieve 10 personal records",
            category = STRENGTH,
            iconResource = "trophy",
            tier = SILVER,
            requirement = PRsAchieved(10)
        ),
        Badge(
            id = "pr_25",
            name = "Strength Climber",
            description = "Achieve 25 personal records",
            category = STRENGTH,
            iconResource = "trophy",
            tier = SILVER,
            requirement = PRsAchieved(25)
        ),
        Badge(
            id = "pr_50",
            name = "PR Machine",
            description = "Achieve 50 personal records",
            category = STRENGTH,
            iconResource = "trophy",
            tier = GOLD,
            requirement = PRsAchieved(50)
        ),
        Badge(
            id = "pr_100",
            name = "Record Legend",
            description = "Achieve 100 personal records",
            category = STRENGTH,
            iconResource = "trophy",
            tier = PLATINUM,
            requirement = PRsAchieved(100)
        ),

        // ==================== VOLUME BADGES (Rep count) ====================
        Badge(
            id = "reps_100",
            name = "First Century",
            description = "Complete 100 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = BRONZE,
            requirement = TotalReps(100)
        ),
        Badge(
            id = "reps_500",
            name = "Rep Rookie",
            description = "Complete 500 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = BRONZE,
            requirement = TotalReps(500)
        ),
        Badge(
            id = "reps_1000",
            name = "Thousand Club",
            description = "Complete 1,000 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = SILVER,
            requirement = TotalReps(1000)
        ),
        Badge(
            id = "reps_5000",
            name = "Rep Warrior",
            description = "Complete 5,000 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = SILVER,
            requirement = TotalReps(5000)
        ),
        Badge(
            id = "reps_10000",
            name = "Rep Master",
            description = "Complete 10,000 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = GOLD,
            requirement = TotalReps(10000)
        ),
        Badge(
            id = "reps_50000",
            name = "Rep Champion",
            description = "Complete 50,000 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = GOLD,
            requirement = TotalReps(50000)
        ),
        Badge(
            id = "reps_100000",
            name = "Rep Legend",
            description = "Complete 100,000 reps total",
            category = VOLUME,
            iconResource = "repeat",
            tier = PLATINUM,
            requirement = TotalReps(100000)
        ),

        // ==================== EXPLORER BADGES (Exercise variety) ====================
        Badge(
            id = "exercises_5",
            name = "Curious",
            description = "Try 5 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = BRONZE,
            requirement = UniqueExercises(5)
        ),
        Badge(
            id = "exercises_10",
            name = "Experimenter",
            description = "Try 10 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = BRONZE,
            requirement = UniqueExercises(10)
        ),
        Badge(
            id = "exercises_20",
            name = "Adventurer",
            description = "Try 20 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = SILVER,
            requirement = UniqueExercises(20)
        ),
        Badge(
            id = "exercises_35",
            name = "Versatile",
            description = "Try 35 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = SILVER,
            requirement = UniqueExercises(35)
        ),
        Badge(
            id = "exercises_50",
            name = "Explorer",
            description = "Try 50 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = GOLD,
            requirement = UniqueExercises(50)
        ),
        Badge(
            id = "exercises_75",
            name = "Master Explorer",
            description = "Try 75 different exercises",
            category = EXPLORER,
            iconResource = "compass",
            tier = PLATINUM,
            requirement = UniqueExercises(75)
        ),

        // ==================== SECRET BADGES (Hidden until earned) ====================
        Badge(
            id = "early_bird",
            name = "Early Bird",
            description = "Complete a workout before 6 AM",
            category = DEDICATION,
            iconResource = "sun",
            tier = GOLD,
            requirement = WorkoutAtTime(0, 6),
            isSecret = true
        ),
        Badge(
            id = "night_owl",
            name = "Night Owl",
            description = "Complete a workout after 10 PM",
            category = DEDICATION,
            iconResource = "moon",
            tier = GOLD,
            requirement = WorkoutAtTime(22, 24),
            isSecret = true
        ),
        Badge(
            id = "weekend_warrior",
            name = "Weekend Warrior",
            description = "Complete 5 workouts in a single week",
            category = CONSISTENCY,
            iconResource = "calendar",
            tier = SILVER,
            requirement = WorkoutsInWeek(5),
            isSecret = true
        ),
        Badge(
            id = "marathon_session",
            name = "Marathon Session",
            description = "Lift over 5,000 kg in a single workout",
            category = VOLUME,
            iconResource = "weight",
            tier = GOLD,
            requirement = SingleWorkoutVolume(5000),
            isSecret = true
        ),

        // ==================== LIFETIME VOLUME BADGES (Total kg lifted) ====================
        Badge(
            id = "volume_1000",
            name = "Ton Lifter",
            description = "Lift 1,000 kg total (1 metric ton)",
            category = VOLUME,
            iconResource = "weight",
            tier = BRONZE,
            requirement = TotalVolume(1_000)
        ),
        Badge(
            id = "volume_10000",
            name = "Car Crusher",
            description = "Lift 10,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = BRONZE,
            requirement = TotalVolume(10_000)
        ),
        Badge(
            id = "volume_50000",
            name = "Elephant Mover",
            description = "Lift 50,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = SILVER,
            requirement = TotalVolume(50_000)
        ),
        Badge(
            id = "volume_100000",
            name = "Jumbo Jet",
            description = "Lift 100,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = SILVER,
            requirement = TotalVolume(100_000)
        ),
        Badge(
            id = "volume_200000",
            name = "Blue Whale",
            description = "Lift 200,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = GOLD,
            requirement = TotalVolume(200_000)
        ),
        Badge(
            id = "volume_500000",
            name = "Space Shuttle",
            description = "Lift 500,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = GOLD,
            requirement = TotalVolume(500_000)
        ),
        Badge(
            id = "volume_1000000",
            name = "Titanic",
            description = "Lift 1,000,000 kg total",
            category = VOLUME,
            iconResource = "weight",
            tier = PLATINUM,
            requirement = TotalVolume(1_000_000),
            isSecret = true
        ),

        // ==================== WORKOUT MODE BADGES ====================
        Badge(
            id = "mode_oldschool_25",
            name = "Old School Master",
            description = "Complete 25 Old School workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = WorkoutModeCount("Old School", 25)
        ),
        Badge(
            id = "mode_pump_25",
            name = "Pump King",
            description = "Complete 25 Pump mode workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = WorkoutModeCount("Pump", 25)
        ),
        Badge(
            id = "mode_tut_25",
            name = "Time Under Tension",
            description = "Complete 25 TUT workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = WorkoutModeCount("TUT", 25)
        ),
        Badge(
            id = "mode_tutbeast_25",
            name = "Beast Unleashed",
            description = "Complete 25 TUT Beast workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = GOLD,
            requirement = WorkoutModeCount("TUT Beast", 25)
        ),
        Badge(
            id = "mode_eccentric_25",
            name = "Eccentric Expert",
            description = "Complete 25 Eccentric Only workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = WorkoutModeCount("Eccentric Only", 25)
        ),
        Badge(
            id = "mode_echo_25",
            name = "Echo Chamber",
            description = "Complete 25 Echo mode workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = WorkoutModeCount("Echo", 25)
        ),
        Badge(
            id = "mode_explorer",
            name = "Mode Explorer",
            description = "Use all 6 workout modes at least once",
            category = EXPLORER,
            iconResource = "compass",
            tier = GOLD,
            requirement = AllWorkoutModes,
            isSecret = true
        ),

        // ==================== POWER BADGES ====================
        Badge(
            id = "power_500",
            name = "Power Spike",
            description = "Hit 500W peak power in a single rep",
            category = STRENGTH,
            iconResource = "lightning",
            tier = SILVER,
            requirement = PeakPower(500)
        ),
        Badge(
            id = "power_750",
            name = "Power House",
            description = "Hit 750W peak power in a single rep",
            category = STRENGTH,
            iconResource = "lightning",
            tier = GOLD,
            requirement = PeakPower(750)
        ),
        Badge(
            id = "power_1000",
            name = "Lightning Bolt",
            description = "Hit 1000W peak power in a single rep",
            category = STRENGTH,
            iconResource = "lightning",
            tier = PLATINUM,
            requirement = PeakPower(1000),
            isSecret = true
        ),

        // ==================== MUSCLE GROUP BADGES ====================
        Badge(
            id = "muscles_6",
            name = "Well Rounded",
            description = "Train 6 different muscle groups",
            category = EXPLORER,
            iconResource = "body",
            tier = BRONZE,
            requirement = UniqueMuscleGroups(6)
        ),
        Badge(
            id = "muscles_12",
            name = "Full Body Master",
            description = "Train all 12 muscle groups",
            category = EXPLORER,
            iconResource = "body",
            tier = GOLD,
            requirement = UniqueMuscleGroups(12)
        ),

        // ==================== COMEBACK/RESILIENCE BADGES ====================
        Badge(
            id = "comeback_7",
            name = "Phoenix Rising",
            description = "Return to training after a 7+ day break",
            category = CONSISTENCY,
            iconResource = "phoenix",
            tier = SILVER,
            requirement = ComebackAfterBreak(7),
            isSecret = true
        ),
        Badge(
            id = "streak_saved",
            name = "Streak Saver",
            description = "Complete a workout when your streak is at risk",
            category = CONSISTENCY,
            iconResource = "shield",
            tier = SILVER,
            requirement = StreakSaved,
            isSecret = true
        ),
        Badge(
            id = "streak_rebuilt_7",
            name = "Consistency Comeback",
            description = "Rebuild a 7-day streak after losing one",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = GOLD,
            requirement = StreakRebuilt(7),
            isSecret = true
        ),

        // ==================== ADDITIONAL TIME-BASED BADGES ====================
        Badge(
            id = "dawn_patrol_10",
            name = "Dawn Patrol",
            description = "Complete 10 workouts before 7 AM",
            category = DEDICATION,
            iconResource = "sun",
            tier = GOLD,
            requirement = WorkoutsAtTimeCount(0, 7, 10)
        ),
        Badge(
            id = "lunch_lifter",
            name = "Lunch Lifter",
            description = "Complete a workout between 11 AM - 1 PM",
            category = DEDICATION,
            iconResource = "sun",
            tier = BRONZE,
            requirement = WorkoutAtTime(11, 13),
            isSecret = true
        ),
        Badge(
            id = "weekend_workouts_10",
            name = "Weekend Warrior II",
            description = "Complete 10 workouts on weekend days",
            category = DEDICATION,
            iconResource = "calendar",
            tier = GOLD,
            requirement = WeekendWorkouts(10)
        ),

        // ==================== ROUTINE BADGES ====================
        Badge(
            id = "routines_completed_10",
            name = "Routine Regular",
            description = "Complete 10 full routines",
            category = DEDICATION,
            iconResource = "list",
            tier = SILVER,
            requirement = RoutinesCompleted(10)
        ),
        Badge(
            id = "routines_completed_50",
            name = "Routine Master",
            description = "Complete 50 full routines",
            category = DEDICATION,
            iconResource = "list",
            tier = GOLD,
            requirement = RoutinesCompleted(50)
        ),
        Badge(
            id = "routines_created_5",
            name = "Routine Creator",
            description = "Create 5 custom routines",
            category = EXPLORER,
            iconResource = "list",
            tier = SILVER,
            requirement = RoutinesCreated(5)
        ),

        // ==================== INTERMEDIATE MILESTONES (Fill gaps) ====================
        Badge(
            id = "streak_45",
            name = "Six Week Strong",
            description = "Maintain a 45-day workout streak",
            category = CONSISTENCY,
            iconResource = "fire",
            tier = GOLD,
            requirement = StreakDays(45)
        ),
        Badge(
            id = "workouts_75",
            name = "Seventy-Five Strong",
            description = "Complete 75 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = SILVER,
            requirement = TotalWorkouts(75)
        ),
        Badge(
            id = "workouts_150",
            name = "One-Fifty Club",
            description = "Complete 150 workouts",
            category = DEDICATION,
            iconResource = "dumbbell",
            tier = GOLD,
            requirement = TotalWorkouts(150)
        )
    )

    /**
     * Get badge by ID
     */
    fun getBadgeById(id: String): Badge? = allBadges.find { it.id == id }

    /**
     * Get badges by category
     */
    fun getBadgesByCategory(category: BadgeCategory): List<Badge> =
        allBadges.filter { it.category == category }

    /**
     * Get badges by tier
     */
    fun getBadgesByTier(tier: BadgeTier): List<Badge> =
        allBadges.filter { it.tier == tier }

    /**
     * Get non-secret badges (visible when locked)
     */
    fun getVisibleBadges(): List<Badge> = allBadges.filter { !it.isSecret }

    /**
     * Get secret badges (only shown when earned)
     */
    fun getSecretBadges(): List<Badge> = allBadges.filter { it.isSecret }

    /**
     * Total badge count
     */
    val totalBadgeCount: Int = allBadges.size
}
