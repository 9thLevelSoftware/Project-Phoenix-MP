package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract for production call-site ownership of the Issue #1366 exit. */
class ActiveWorkoutJustLiftExitContractTest {
    @Test
    fun exitCallbackAndIdleObserverShareTheGuardedJustLiftTransition() {
        val source = readActiveWorkoutScreen()
        val idleBranch = between(
            source,
            "workoutState is WorkoutState.Idle && workoutParameters.isJustLift",
            "workoutState is WorkoutState.Idle &&\n                (",
        )
        assertTrue(idleBranch.contains("navController.exitJustLift(activeWorkoutEntryId)"))

        val exitDialog = between(
            source,
            "title = { Text(stringResource(Res.string.exit_workout_title)) }",
            "connectionError?.let { error ->",
        )
        val staleEntryGuard = exitDialog.indexOf("isCurrentActiveWorkoutEntry(activeWorkoutEntryId)")
        val stopWorkout = exitDialog.indexOf("viewModel.stopWorkout(exitingWorkout = true)")
        assertTrue(staleEntryGuard >= 0 && stopWorkout > staleEntryGuard)
        assertTrue(exitDialog.contains("navController.exitJustLift(activeWorkoutEntryId)"))
        assertTrue(exitDialog.contains("navController.navigateUp()"))
        assertTrue(exitDialog.contains("return@Button"))

        val cancelButton = between(
            source,
            "dismissButton = {",
            "connectionError?.let { error ->",
        )
        assertTrue(cancelButton.contains("showExitConfirmation = false"))
        assertTrue(!cancelButton.contains("stopWorkout"))
    }

    private fun between(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex)
        assertTrue("Could not find $start bounded by $end", startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun readActiveWorkoutScreen(): String {
        val source = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
        )
        assertNotNull("Could not locate ActiveWorkoutScreen.kt", source)
        return source!!
    }
}
