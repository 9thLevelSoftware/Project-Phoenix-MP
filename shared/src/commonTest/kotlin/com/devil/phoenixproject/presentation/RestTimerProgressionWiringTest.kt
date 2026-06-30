package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #604 source-level guards for Rest Timer next-set Weight Change / Rep wiring.
 *
 * The repo already uses source-level UI wiring tests for SetReady/JustLift when a
 * full Compose UI harness would be heavier than the regression being guarded.
 */
class RestTimerProgressionWiringTest {

    @Test
    fun workoutTab_passesProgressionToRestTimer() {
        val src = readWorkoutTabSource()

        assertTrue(
            src.contains("nextExerciseProgressionKg = if (showNextProgression) workoutParameters.progressionRegressionKg else null"),
            "WorkoutTab.kt must pass WorkoutParameters.progressionRegressionKg into RestTimerCard during WorkoutState.Resting.",
        )
        assertTrue(
            src.contains("workoutParameters.copy(progressionRegressionKg = newValueKg)"),
            "Rest Timer progression edits must update WorkoutParameters through onUpdateParameters(...copy(progressionRegressionKg=...)).",
        )
        assertTrue(
            src.contains("workoutParameters.programMode != ProgramMode.Echo"),
            "WorkoutTab.kt must guard Rest Timer progression for non-Echo next sets only.",
        )
    }

    @Test
    fun restTimerCard_rendersProgressionInNextSetConfiguration() {
        val src = readRestTimerCardSource()

        assertTrue(
            src.contains("WeightChangePerRepControl"),
            "RestTimerCard.kt must render the shared Weight Change / Rep control in NEXT SET CONFIGURATION.",
        )
        assertTrue(
            src.contains("nextExerciseProgressionKg != null"),
            "RestTimerCard.kt must render Weight Change / Rep only when a progression value is supplied.",
        )
        assertTrue(
            src.contains("onUpdateProgressionKg.invoke(newValueKg)"),
            "RestTimerCard.kt must call the progression update callback when the control changes.",
        )
    }

    @Test
    fun restTimerCard_preservesTimerAndActionControls() {
        val src = readRestTimerCardSource()

        listOf(
            "liveRegion = LiveRegionMode.Polite",
            "rest_next_set_config",
            "cd_add_30_seconds",
            "rest_pause",
            "rest_resume",
            "cd_reset_timer",
            "cd_skip_rest",
            "cd_end_workout",
        ).forEach { required ->
            assertTrue(
                src.contains(required),
                "RestTimerCard.kt must preserve timer/accessibility/action wiring: missing $required",
            )
        }
    }

    private fun readWorkoutTabSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt")
        assertNotNull(src, "Could not locate WorkoutTab.kt")
        return src
    }

    private fun readRestTimerCardSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt")
        assertNotNull(src, "Could not locate RestTimerCard.kt")
        return src
    }
}
