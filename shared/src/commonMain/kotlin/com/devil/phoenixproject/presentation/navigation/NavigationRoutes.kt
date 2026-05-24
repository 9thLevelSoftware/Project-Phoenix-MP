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

    // Smart Insights - training suggestions and readiness
    object SmartInsights : NavigationRoutes("smart_insights")

    // Cloud Sync routes
    object LinkAccount : NavigationRoutes("link_account")

    // Strength Assessment - VBT-based 1RM estimation with BLE velocity capture
    object StrengthAssessment : NavigationRoutes("strength_assessment/{exerciseId}") {
        fun createRoute(exerciseId: String) = "strength_assessment/$exerciseId"
    }
    object StrengthAssessmentPicker : NavigationRoutes("strength_assessment_picker")

    // Integration routes
    object Integrations : NavigationRoutes("integrations")
    object ExternalIntegrationHub : NavigationRoutes("external_integration_hub")
    object ExternalActivities : NavigationRoutes("external_activities")
    object ExternalRoutines : NavigationRoutes("external_routines")
    object ExternalRoutineDetail : NavigationRoutes("external_routine/{provider}/{externalRoutineId}") {
        fun createRoute(provider: String, externalRoutineId: String) =
            "external_routine/${provider.encodeRouteSegment()}/${externalRoutineId.encodeRouteSegment()}"
    }
    object ExternalPrograms : NavigationRoutes("external_programs")
    object ExternalProgramDetail : NavigationRoutes("external_program/{provider}/{externalProgramId}") {
        fun createRoute(provider: String, externalProgramId: String) =
            "external_program/${provider.encodeRouteSegment()}/${externalProgramId.encodeRouteSegment()}"
    }
    object ExternalProgramPlayground : NavigationRoutes("external_program_playground/{provider}/{externalProgramId}") {
        fun createRoute(provider: String, externalProgramId: String) =
            "external_program_playground/${provider.encodeRouteSegment()}/${externalProgramId.encodeRouteSegment()}"
    }
    object ExternalMeasurementTrends : NavigationRoutes("external_measurements")
}

private fun String.encodeRouteSegment(): String = buildString {
    for (c in this@encodeRouteSegment) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
            else -> {
                for (b in c.toString().encodeToByteArray()) {
                    append('%')
                    append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                    append((b.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
    }
}

/**
 * Bottom navigation items.
 * Only 3 items are shown in the bottom navigation bar.
 */
enum class BottomNavItem(val route: String, val label: String) {
    WORKOUT("home", "Workout"),
    ANALYTICS("analytics", "Analytics"),
    SETTINGS("settings", "Settings"),
}
