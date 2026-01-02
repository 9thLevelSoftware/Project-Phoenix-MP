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
    object ActiveWorkout : NavigationRoutes("active_workout")
    object TrainingCycles : NavigationRoutes("training_cycles")
    object CycleBuilder : NavigationRoutes("cycle_builder/{cycleId}") {
        fun createRoute(cycleId: String = "new") = "cycle_builder/$cycleId"
    }
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

    object CycleEditor : NavigationRoutes("cycle_editor/{cycleId}?dayCount={dayCount}") {
        fun createRoute(cycleId: String, dayCount: Int? = null): String {
            val base = "cycle_editor/$cycleId"
            return if (dayCount != null) "$base?dayCount=$dayCount" else base
        }
    }

    object DayCountPicker : NavigationRoutes("dayCountPicker")

    object CycleReview : NavigationRoutes("cycleReview/{cycleId}") {
        fun createRoute(cycleId: String) = "cycleReview/$cycleId"
    }

    // Premium/Account routes
    object Auth : NavigationRoutes("auth")
    object Paywall : NavigationRoutes("paywall")
    object Account : NavigationRoutes("account")
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
