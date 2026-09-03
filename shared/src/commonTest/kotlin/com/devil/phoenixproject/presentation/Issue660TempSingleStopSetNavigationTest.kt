package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #660 regression guard for a direct timed bodyweight session.
 *
 * A SingleExerciseScreen launch uses a `temp_single_` routine. An early Stop Set
 * must retain the Stop Set guard until ActiveWorkoutScreen routes the idle state
 * back to SetReady; normal temp-single completion must still navigate away. The
 * two Compose observers otherwise race independently.
 *
 * This is source-level because this module has no Compose UI harness for the
 * two independent ActiveWorkoutScreen observers.
 */
class Issue660TempSingleStopSetNavigationTest {

    @Test
    fun `early temp single Stop Set cannot teardown Idle during SetReady transition`() {
        val stopSet = between(
            readActiveSessionEngineSource(),
            "fun stopAndReturnToSetReady()",
            "fun stopAndSkipCurrentExercise()",
        )
        val setReady = stopSet.indexOf("flowDelegate?.enterSetReady")
        val idle = stopSet.indexOf("coordinator._workoutState.value = WorkoutState.Idle")
        val stopGuardRelease = stopSet.lastIndexOf("coordinator.stopWorkoutInProgress.value = false")
        val returnedToSetReady = stopSet.indexOf("returnedToSetReady = true")
        val releaseOnFailure = stopSet.indexOf("if (!returnedToSetReady)")
        assertTrue(
            setReady >= 0 &&
                idle >= 0 &&
                setReady < idle &&
                returnedToSetReady > idle &&
                releaseOnFailure > returnedToSetReady &&
                stopGuardRelease > releaseOnFailure,
            "Issue #660: only a failed Stop Set may release its guard; successful SetReady return must retain it.",
        )

        val startWorkout = between(
            readActiveSessionEngineSource(),
            "fun startWorkout(",
            "fun stopWorkout(",
        )
        assertTrue(
            startWorkout.contains("coordinator.stopWorkoutInProgress.value = false"),
            "Issue #660: the next SetReady start must release the preserved Stop Set guard.",
        )

        val activeWorkout = readActiveWorkoutScreenSource()
        val tempSingleIdleBranch = between(
            activeWorkout,
            "loadedRoutine == null ||",
            "workoutState is WorkoutState.Error",
        )
        val tempSingle = tempSingleIdleBranch.indexOf("DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX")
        val stoppingGuard = tempSingleIdleBranch.indexOf("!viewModel.isStoppingWorkout()")
        val staleSetReadyGuard = tempSingleIdleBranch.indexOf("routineFlowState !is RoutineFlowState.SetReady")
        assertTrue(
            tempSingle >= 0 && stoppingGuard > tempSingle && staleSetReadyGuard < 0,
            "Issue #660: only an in-flight Stop Set may suppress temp_single_ Idle teardown; " +
                "normal completion must retain its existing navigate-away behavior.",
        )

        val setReadyObserver = between(
            activeWorkout,
            "LaunchedEffect(routineFlowState, workoutState)",
            "// Use the new state holder pattern",
        )
        assertTrue(
            setReadyObserver.contains("is RoutineFlowState.SetReady") &&
                setReadyObserver.contains("navController.navigate(NavigationRoutes.SetReady.route)"),
            "Issue #660: the flow observer must own the deferred SetReady destination.",
        )
    }

    private fun between(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex)
        assertTrue(startIndex >= 0 && endIndex > startIndex, "Could not find $start bounded by $end")
        return source.substring(startIndex, endIndex)
    }

    private fun readActiveSessionEngineSource(): String = readSource(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt",
    )

    private fun readActiveWorkoutScreenSource(): String = readSource(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
    )

    private fun readSource(path: String): String {
        val src = readProjectFile(path)
        assertNotNull(src, "Could not locate $path")
        return src
    }
}
