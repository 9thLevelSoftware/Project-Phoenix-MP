package com.devil.phoenixproject.presentation.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-040 source guard. Both explicit workout exits call `stopWorkout(exitingWorkout = true)`,
 * which saves asynchronously, and then navigate away from ActiveWorkoutScreen at once. A
 * save-failure collector on that route is disposed before the failure is raised, so the
 * Retry offer would never appear. The single collector must live in the app-level
 * scaffold (EnhancedMainScreen), which stays composed across every route.
 */
class WorkoutSaveFailureCollectorGuardTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun screen(name: String): String = File(
        projectRoot,
        "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/$name",
    ).readText()

    @Test
    fun appScaffoldCollectsTheSaveFailureOfferExactlyOnce() {
        val source = screen("EnhancedMainScreen.kt")
        assertEquals(
            1,
            Regex("""workoutSaveFailureSessionId\.collectAsState\(\)""").findAll(source).count(),
            "EnhancedMainScreen must collect workoutSaveFailureSessionId exactly once.",
        )
        assertTrue(
            source.contains("retryWorkoutSave(") && source.contains("dismissWorkoutSaveFailure("),
            "The app-level collector must offer Retry and drain the offer on dismiss.",
        )
    }

    @Test
    fun theActiveWorkoutRouteDoesNotCollectTheSaveFailureOffer() {
        assertFalse(
            screen("ActiveWorkoutScreen.kt").contains("workoutSaveFailureSessionId"),
            "ActiveWorkoutScreen is disposed before an exit save can fail; a collector there " +
                "would hide the Retry offer or show it twice.",
        )
    }
}
