package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
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
        assertTrue(
            src.contains("valueKg = nextExerciseProgressionKg"),
            "RestTimerCard.kt must pass the latest parent value into WeightChangePerRepControl.",
        )
        assertFalse(
            src.contains("editedProgressionKg"),
            "RestTimer progression must be controlled by WorkoutParameters, not duplicated in local component state.",
        )
    }

    @Test
    fun activeSessionEngine_preservesRestProgressionEditsWhenAdvancing() {
        val src = readActiveSessionEngineSource()

        assertTrue(
            src.contains("val preserveRestEdits = coordinator._userAdjustedWeightDuringRest"),
            "ActiveSessionEngine must snapshot rest-screen user edits before advancing.",
        )
        assertTrue(
            src.contains("val nextProgressionKg = if (preserveRestEdits)") &&
                src.contains("currentParams.progressionRegressionKg") &&
                src.contains("progressionRegressionKg = nextProgressionKg"),
            "Routine rest advance must preserve WorkoutParameters.progressionRegressionKg when the user edits Rest Timer config.",
        )
        assertTrue(
            src.contains("val setProgressionKg = if (coordinator._userAdjustedWeightDuringRest)") &&
                src.contains("progressionRegressionKg = setProgressionKg"),
            "Single-exercise rest advance must preserve WorkoutParameters.progressionRegressionKg when the user edits Rest Timer config.",
        )
        assertTrue(
            src.contains("clampUpcomingProgressionKg(nextExercise.progressionKg)") &&
                src.contains("clampUpcomingProgressionKg(exerciseForNextSet.progressionKg)"),
            "Rest Timer defaults must be clamped to the signed-off control range before display/advance.",
        )
    }

    @Test
    fun weightChangeControlSyncsClampedDisplayValueBackToParent() {
        val src = readWeightChangeControlSource()

        assertTrue(
            src.contains("val clampedDisplay = kgToDisplay(valueKg, weightUnit).coerceIn"),
            "WeightChangePerRepControl must clamp in display units.",
        )
        assertTrue(
            src.contains("val clampedValueKg = displayToKg(clampedDisplay, weightUnit)"),
            "WeightChangePerRepControl must convert the displayed clamp back to kg.",
        )
        assertTrue(
            src.contains("LaunchedEffect(clampedValueKg, valueKg, weightUnit)") &&
                src.contains("onValueChangeKg(clampedValueKg)"),
            "WeightChangePerRepControl must sync out-of-range parent values back to the displayed kg value.",
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

    private fun readActiveSessionEngineSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt")
        assertNotNull(src, "Could not locate ActiveSessionEngine.kt")
        return src
    }

    private fun readWeightChangeControlSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightChangePerRepControl.kt")
        assertNotNull(src, "Could not locate WeightChangePerRepControl.kt")
        return src
    }
}
