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
    object WeeklyPrograms : NavigationRoutes("weekly_programs")
    object ProgramBuilder : NavigationRoutes("program_builder/{programId}") {
        fun createRoute(programId: String = "new") = "program_builder/$programId"
    }
    object Analytics : NavigationRoutes("analytics")
    object Settings : NavigationRoutes("settings")
    object ConnectionLogs : NavigationRoutes("connection_logs")
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
