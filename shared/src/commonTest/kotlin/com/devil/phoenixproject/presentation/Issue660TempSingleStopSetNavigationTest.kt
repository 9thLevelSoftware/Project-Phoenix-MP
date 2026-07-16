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

    private companion object {
        val TEMP_SINGLE_SET_READY_GUARD = Regex(
            """DefaultWorkoutSessionManager\.TEMP_SINGLE_EXERCISE_PREFIX.*?routineFlowState\s*!is\s+RoutineFlowState\.SetReady""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val SET_READY_NAVIGATION = Regex(
            """is\s+RoutineFlowState\.SetReady\s*->\s*\{.*?navController\.navigate\(NavigationRoutes\.SetReady\.route\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    @Test
    fun `early temp single Stop Set lets SetReady own Idle navigation`() {
        val src = readActiveWorkoutScreenSource()
        assertTrue(
            TEMP_SINGLE_SET_READY_GUARD.containsMatchIn(src),
            "Issue #660: Idle temp_single_ Stop Set returning to SetReady must not call the " +
                "single-exercise navigateUp() completion branch.",
        )
        assertTrue(
            SET_READY_NAVIGATION.containsMatchIn(src),
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
