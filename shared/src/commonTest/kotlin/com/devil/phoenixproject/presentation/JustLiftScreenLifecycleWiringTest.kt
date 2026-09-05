package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression coverage for issue #756's Just Lift state-reset race. */
class JustLiftScreenLifecycleWiringTest {

    @Test
    fun prepareForJustLift_usesRememberSaveableGuard_againstScreenRecreation() {
        val source = readJustLiftScreenSource()

        // The effect must use rememberSaveable to guard against re-firing after
        // screen recreation (process death, config change). Without this guard,
        // LaunchedEffect(Unit) re-fires and resetForNewWorkout() invalidates
        // the in-flight auto-start execution.
        assertTrue(
            source.contains("hasPreparedSession by rememberSaveable"),
            "JustLiftScreen must use rememberSaveable to guard prepareForJustLift against screen recreation.",
        )
        assertTrue(
            source.contains("if (!hasPreparedSession)"),
            "prepareForJustLift must be guarded by hasPreparedSession to prevent reset after process death.",
        )
        assertTrue(
            source.contains("hasPreparedSession = true"),
            "hasPreparedSession must be set to true before calling prepareForJustLift.",
        )
    }

    @Test
    fun prepareForJustLift_isNotKeyedOnWorkoutState() {
        val source = readJustLiftScreenSource()

        // prepareForJustLift must not be called inside any workoutState-keyed effect.
        // (Other LaunchedEffect(workoutState) usages for navigation are fine.)
        val workoutStateEffectSections = source.split("LaunchedEffect(workoutState)")
        for (section in workoutStateEffectSections.drop(1)) {
            val blockContent = section.takeWhile { it != '}' }
            assertTrue(
                !blockContent.contains("prepareForJustLift"),
                "prepareForJustLift must not be called by workout-state changes; " +
                    "Initializing/Countdown belong to the active Just Lift execution.",
            )
        }
    }

    @Test
    fun entryAction_documentsTransientStates_areOwnedByCurrentExecution() {
        val source = readJustLiftScreenSource()

        assertTrue(source.contains("Initializing"), "Entry action must document Initializing ownership.")
        assertTrue(source.contains("Countdown"), "Entry action must document Countdown ownership.")
    }

    private fun readJustLiftScreenSource(): String {
        val source = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt",
        )
        assertNotNull(source, "Could not locate JustLiftScreen.kt from the shared project root.")
        return source
    }
}
