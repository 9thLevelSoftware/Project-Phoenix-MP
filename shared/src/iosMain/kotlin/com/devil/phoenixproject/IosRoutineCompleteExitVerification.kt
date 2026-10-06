package com.devil.phoenixproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.screen.RoutineCompleteScreen
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import platform.Foundation.NSProcessInfo

/**
 * Issue #1164 iOS exit-capture seam (runtime harness, launch-argument gated).
 *
 * iOS has no Android Back and the in-screen Done footer is the only guaranteed exit
 * (BackHandler is a no-op on iOS), so the merge gate requires a capture of the REAL
 * production completion screen exiting on an iOS runtime without the app terminating.
 *
 * Launch the app with `-PhoenixVerify1164RoutineComplete` to render the PRODUCTION
 * [RoutineCompleteScreen] on the real routine-complete route over a real NavController
 * inside the real app process, with the production Complete state seeded through the
 * real coordinator (the same presentation fixture the reported case uses:
 * beginner / 5 exercises / 12 sets / 20m 14s). Tapping the production Done footer then
 * runs the production exit action (routineExitDestination() -> exitRoutineFlow() ->
 * safePopOrNavigate(dest)) and lands on the origin route.
 *
 * Nothing here changes the database, BLE, portal, session-manager or the iOS BackHandler;
 * it only seeds coordinator state the production screens consume and renders production
 * content. Every state change is logged as `VERIFY1164|...` so a capture can prove the
 * exit cleanup and that the process is still alive.
 */
internal fun isRoutineCompleteExitVerificationLaunch(): Boolean =
    NSProcessInfo.processInfo.arguments.contains("-PhoenixVerify1164RoutineComplete")

@Composable
internal fun IosRoutineCompleteExitVerificationHost(viewModel: MainViewModel) {
    val navController = rememberNavController()

    // Seed the production Complete state through the real coordinator exactly the way
    // a finished routine reaches it: launch origin, completed set/exercise identity, the
    // routine start clock, then showRoutineComplete() (production state computation).
    LaunchedEffect(Unit) {
        val coordinator = viewModel.workoutSessionManager.coordinator
        val routine = verificationRoutine()
        coordinator._loadedRoutine.value = routine
        coordinator.routineLaunchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES
        val keys = mutableSetOf<Pair<Int, Int>>()
        var i = 0
        while (keys.size < 12) {
            keys.add((i % 5) to i)
            i++
        }
        coordinator._completedRoutineSetKeys.value = keys
        coordinator._completedExercises.value = (0 until 5).toSet()
        // Same duration anchor as the runtime harness: the screen formats by truncation,
        // so land 400ms past the target for a stable "20m 14s".
        coordinator.routineStartTime = currentTimeMillis() - (20 * 60_000L + 14_000L) - 400
        viewModel.showRoutineComplete()

        val complete = viewModel.routineFlowState.value
        logVerification("seeded state=${describe(complete)}")
        navController.navigate(NavigationRoutes.DailyRoutines.route)
        navController.navigate(NavigationRoutes.RoutineComplete.route)
    }

    // Alive ticker: proves the app process kept running across the exit capture. If the
    // exit terminated the app these lines stop.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            logVerification("alive tick=$tick")
            tick++
            delay(1_000)
        }
    }

    // Post-exit observer: proves the production cleanup contract on the iOS runtime.
    LaunchedEffect(Unit) {
        var last = "seeding"
        while (true) {
            val coordinator = viewModel.workoutSessionManager.coordinator
            val now = "flow=${describe(coordinator.routineFlowState.value)} " +
                "loadedRoutine=${coordinator.loadedRoutine.value?.name ?: "null"} " +
                "origin=${coordinator.routineLaunchOrigin ?: "null"} " +
                "workout=${describe(coordinator.workoutState.value)}"
            if (now != last) {
                logVerification("state $now")
                last = now
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = NavigationRoutes.Home.route) {
            composable(NavigationRoutes.Home.route) {
                VerificationOriginLabel("HOME (verification start)")
            }
            composable(NavigationRoutes.DailyRoutines.route) {
                VerificationOriginLabel("DAILY ROUTINES (exit destination)")
            }
            composable(NavigationRoutes.TrainingCycles.route) {
                VerificationOriginLabel("TRAINING CYCLES (exit destination)")
            }
            composable(NavigationRoutes.RoutineComplete.route) {
                // Production completion screen: production content, layout and exit action.
                RoutineCompleteScreen(navController, viewModel)
            }
        }
    }
}

@Composable
private fun VerificationOriginLabel(text: String) {
    LaunchedEffect(Unit) { logVerification("route rendered: $text") }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

/** The reported fixture: beginner / 5 exercises / 12 sets. */
private fun verificationRoutine(): Routine = Routine(
    id = "verify-1164-routine",
    name = "beginner",
    exercises = (0 until 5).map { index ->
        RoutineExercise(
            id = "verify-1164-ex-$index",
            exercise = Exercise(name = "Verification Exercise $index", muscleGroup = "Full Body"),
            orderIndex = index,
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 20f,
        )
    },
)

private fun describe(state: Any?): String = when (state) {
    is RoutineFlowState.Complete -> "Complete(${state.routineName}/${state.totalExercises}ex/" +
        "${state.totalSets}sets/${state.totalDurationMs}ms)"
    is RoutineFlowState.NotInRoutine -> "NotInRoutine"
    is RoutineFlowState.Overview -> "Overview"
    is RoutineFlowState.SetReady -> "SetReady"
    is WorkoutState.Idle -> "Idle"
    else -> state?.toString() ?: "null"
}

private fun logVerification(message: String) {
    println("VERIFY1164|$message")
}

/**
 * Captures the NavController the verification host drives so a capture can read the
 * exit destination without reaching into Compose internals.
 */
internal fun verificationNavState(navController: NavHostController): String =
    navController.currentBackStack.value.joinToString(" -> ") { it.destination.route ?: "<null>" }
