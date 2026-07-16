package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #660 regression guard for a direct timed bodyweight session.
 *
 * A SingleExerciseScreen launch uses a `temp_single_` routine. An early Stop Set
 * intentionally returns that routine to SetReady, but ActiveWorkoutScreen must
 * not interpret the transient Idle state as completion and navigate home first.
 *
 * This is a source-level navigation test because the defect is a Compose
 * navigation race/wiring invariant and this module has no Compose UI harness
 * for ActiveWorkoutScreen.
 */
class Issue660TempSingleStopSetNavigationTest {

    @Test
    fun `early temp single Stop Set lets SetReady own Idle navigation`() {
        val src = readActiveWorkoutScreenSource()
        val tempSingleIdleBranch = src.substringAfter(
            "loadedRoutine?.id?.startsWith(\n                            DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX,",
        ).substringBefore("workoutState is WorkoutState.Error")

        assertTrue(
            tempSingleIdleBranch.contains("routineFlowState !is RoutineFlowState.SetReady"),
            "Issue #660: Idle temp_single_ Stop Set returning to SetReady must not call the " +
                "single-exercise navigateUp() completion branch.",
        )
        assertTrue(
            src.contains("RoutineFlowState.SetReady + Idle - navigating to SetReady"),
            "Issue #660: the existing SetReady destination must own the early Stop Set transition.",
        )
    }

    private fun readActiveWorkoutScreenSource(): String {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
        )
        assertNotNull(src, "Could not locate ActiveWorkoutScreen.kt")
        return src
    }
}
