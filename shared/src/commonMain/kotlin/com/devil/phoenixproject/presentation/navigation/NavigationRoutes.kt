package com.devil.phoenixproject.presentation.navigation

/**
 * Navigation routes for the app.
 * These sealed classes define all possible navigation destinations.
 */
sealed class NavigationRoutes(val route: String) {
    object Home : NavigationRoutes("home")
    object JustLift : NavigationRoutes("just_lift")
    object SingleExercise : NavigationRoutes("single_exercise")
    object DailyRoutines : NavigationRoutes("daily_routines")
    object RoutineOverview : NavigationRoutes("routine_overview")
    object SetReady : NavigationRoutes("set_ready")
    object RoutineComplete : NavigationRoutes("routine_complete")
    object ActiveWorkout : NavigationRoutes("active_workout")
    object TrainingCycles : NavigationRoutes("training_cycles")
    object Analytics : NavigationRoutes("analytics")
    object ExerciseDetail : NavigationRoutes("exercise_detail/{exerciseId}") {
        fun createRoute(exerciseId: String) = "exercise_detail/$exerciseId"
    }
    object Settings : NavigationRoutes("settings")
    object ConnectionLogs : NavigationRoutes("connection_logs")
    object Badges : NavigationRoutes("badges")
    object RoutineEditor : NavigationRoutes("routine_editor/{routineId}") {
        fun createRoute(routineId: String) = "routine_editor/$routineId"
    }

    object CycleEditor : NavigationRoutes("cycle_editor/{cycleId}") {
        fun createRoute(cycleId: String) = "cycle_editor/$cycleId"
    }

    object CycleReview : NavigationRoutes("cycleReview/{cycleId}") {
        fun createRoute(cycleId: String) = "cycleReview/$cycleId"
    }

    // Premium/Account routes
    object Auth : NavigationRoutes("auth")
    object Paywall : NavigationRoutes("paywall")
    object Account : NavigationRoutes("account")

    // Smart Insights route (Elite-gated)
    object SmartInsights : NavigationRoutes("smart_insights")

    // Cloud Sync routes
    object LinkAccount : NavigationRoutes("link_account")

    // Strength Assessment routes
    object StrengthAssessment : NavigationRoutes("strength_assessment/{exerciseId}") {
        fun createRoute(exerciseId: String) = "strength_assessment/$exerciseId"
    }
    object StrengthAssessmentPicker : NavigationRoutes("strength_assessment_picker")
}

/**
 * Bottom navigation items.
 * Only 3 items are shown in the bottom navigation bar.
 */
enum class BottomNavItem(val route: String, val label: String) {
    WORKOUT("home", "Workout"),
    ANALYTICS("analytics", "Analytics"),
    SETTINGS("settings", "Settings")
}
