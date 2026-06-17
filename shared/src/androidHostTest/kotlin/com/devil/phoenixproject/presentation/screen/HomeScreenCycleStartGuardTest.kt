package com.devil.phoenixproject.presentation.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the Home cycle start race fixed alongside TrainingCyclesScreen #541/#544.
 *
 * `loadRoutineFromCycle` launches async PR% weight resolution. Calling `startWorkout()`
 * immediately after (without waiting for `loadedRoutine`) sends stale BLE params — wrong
 * weights on percentage-based routines.
 */
class HomeScreenCycleStartGuardTest {

    private val homeScreenSource: String by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        File(
            dir,
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt",
        ).readText()
    }

    @Test
    fun homeScreen_doesNotStartWorkoutImmediatelyAfterLoadRoutineFromCycle() {
        val racePattern = Regex(
            """loadRoutineFromCycle\([^)]+\)\s*\n\s*viewModel\.startWorkout\(\)""",
            RegexOption.MULTILINE,
        )
        assertFalse(
            racePattern.containsMatchIn(homeScreenSource),
            "GUARD VIOLATION: HomeScreen calls startWorkout() immediately after loadRoutineFromCycle. " +
                "Wait for loadedRoutine and route through enterSetReady (see TrainingCyclesScreen #544).",
        )
    }

    @Test
    fun homeScreen_waitsForLoadedRoutineBeforeEnterSetReady() {
        assertTrue(
            homeScreenSource.contains("loadedRoutine.first"),
            "HomeScreen must wait for loadedRoutine before enterSetReady on cycle start paths.",
        )
        assertTrue(
            homeScreenSource.contains("enterSetReady(0, 0)"),
            "HomeScreen fresh cycle start must call enterSetReady(0, 0) after routine load.",
        )
    }

    @Test
    fun homeScreen_resumeRoutesThroughSetReady() {
        assertTrue(
            homeScreenSource.contains("enterSetReady(exIdx, setIdx)"),
            "HomeScreen cycle resume must route through SetReady at persisted (ex, set) indices.",
        )
    }
}
