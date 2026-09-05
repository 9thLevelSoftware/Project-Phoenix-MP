package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression coverage for issue #756's Just Lift state-reset race. */
class JustLiftScreenLifecycleWiringTest {

    @Test
    fun prepareForJustLift_isAnEntryAction_notAWorkoutStateReaction() {
        val source = readJustLiftScreenSource()

        assertTrue(
            source.contains(
                "LaunchedEffect(Unit) {\n        viewModel.prepareForJustLift()\n    }",
            ),
            "JustLiftScreen must prepare the session once on screen entry.",
        )
        assertTrue(
            !source.contains("LaunchedEffect(workoutState) {\n        if (workoutState !is WorkoutState.Idle") &&
                !source.contains("viewModel.prepareForJustLift()\n        }"),
            "prepareForJustLift must not be called by workout-state changes; " +
                "Initializing/Countdown belong to the active Just Lift execution.",
        )
    }

    @Test
    fun entryAction_documentsTransientStates_areOwnedByCurrentExecution() {
        val source = readJustLiftScreenSource()

        val entryComment = source.substringBefore("LaunchedEffect(Unit) {\n        viewModel.prepareForJustLift()")
            .substringAfterLast("// Prepare the workout once when entering Just Lift.")
        assertTrue(entryComment.contains("Initializing"), "Entry action must document Initializing ownership.")
        assertTrue(entryComment.contains("Countdown"), "Entry action must document Countdown ownership.")
    }

    private fun readJustLiftScreenSource(): String {
        val source = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt",
        )
        assertNotNull(source, "Could not locate JustLiftScreen.kt from the shared project root.")
        return source
    }
}
