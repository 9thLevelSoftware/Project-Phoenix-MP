package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #660 regression guard for a direct timed bodyweight session.
 *
 * A SingleExerciseScreen launch uses a `temp_single_` routine. An early Stop Set
 * must publish SetReady before Idle so ActiveWorkoutScreen's Idle observer sees
 * that SetReady owns navigation, regardless of cross-flow observer scheduling.
 *
 * This is source-level because this module has no Compose UI harness for the
 * two independent ActiveWorkoutScreen observers.
 */
class Issue660TempSingleStopSetNavigationTest {

    @Test
    fun `early temp single Stop Set publishes SetReady before Idle navigation`() {
        val stopSet = between(
            readActiveSessionEngineSource(),
            "fun stopAndReturnToSetReady()",
            "fun stopAndSkipCurrentExercise()",
        )
        val setReady = stopSet.indexOf("flowDelegate?.enterSetReady")
        val idle = stopSet.indexOf("coordinator._workoutState.value = WorkoutState.Idle")
        assertTrue(
            setReady >= 0 && idle >= 0 && setReady < idle,
            "Issue #660: Stop Set must publish SetReady before Idle so no Idle observer can " +
                "navigate a temp_single_ routine home first.",
        )

        val tempSingleIdleBranch = between(
            readActiveWorkoutScreenSource(),
            "loadedRoutine == null ||",
            "workoutState is WorkoutState.Error",
        )
        val tempSingle = tempSingleIdleBranch.indexOf("DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX")
        val setReadyGuard = tempSingleIdleBranch.indexOf("routineFlowState !is RoutineFlowState.SetReady")
        assertTrue(
            tempSingle >= 0 && setReadyGuard > tempSingle,
            "Issue #660: the temp_single_ Idle completion branch must defer to the SetReady observer.",
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
