package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KD-9 plan item 5: every weight slider is bounded by a [com.devil.phoenixproject.util.CommandLimits]
 * ceiling rather than a model-agnostic constant, and the capped notice actually reaches a screen.
 *
 * These are source-wiring assertions in the style of `RestTimerProgressionWiringTest`, because the
 * screens are Compose and the shared suite has no Compose runtime. Without them a regression back to
 * `Constants.MAX_WEIGHT_PER_CABLE_KG` would leave a V-Form owner a 110 kg slider and the whole suite
 * would stay green: the command is still clamped at resolution, so the only symptom is a silently
 * lighter set plus a notice on every set.
 */
class CommandLimitsWiringTest {

    @Test
    fun liveSlidersUseTheConnectedModelCeiling() {
        val setReady = read("presentation/screen/SetReadyScreen.kt")
        assertTrue(
            setReady.contains("CommandLimits.maxWeightPerCableKg(link.hardwareModel)"),
            "SetReadyScreen must bound its weight slider by the CONNECTED model's ceiling.",
        )
        assertTrue(
            setReady.contains("CommandLimits.planningMaxWeightPerCableKg(userPreferences.lastConnectedModel)"),
            "SetReadyScreen must fall back to the planning ceiling while disconnected.",
        )

        val workoutTab = read("presentation/screen/WorkoutTab.kt")
        assertTrue(
            workoutTab.contains("maxWeightPerCableKg = CommandLimits.maxWeightPerCableKg("),
            "WorkoutTab must pass the connected model's ceiling into RestTimerCard.",
        )

        val justLift = read("presentation/screen/JustLiftScreen.kt")
        assertTrue(
            justLift.contains("CommandLimits.maxWeightPerCableKg("),
            "JustLiftScreen must bound its weight slider by the connected model's ceiling.",
        )
        assertFalse(
            justLift.contains("if (weightUnit == WeightUnit.LB) 242f else 110f"),
            "JustLiftScreen must not hardcode the Trainer+ ceiling for every trainer.",
        )
    }

    @Test
    fun planningSlidersUseTheLastConnectedModelCeiling() {
        listOf(
            "presentation/screen/RoutineEditorScreen.kt",
            "presentation/screen/SingleExerciseScreen.kt",
            "presentation/screen/RoutineOverviewScreen.kt",
        ).forEach { path ->
            assertTrue(
                read(path).contains("CommandLimits.planningMaxWeightPerCableKg("),
                "$path must bound its planning slider by the last connected model's ceiling.",
            )
        }

        val editor = read("presentation/screen/ExerciseEditBottomSheet.kt")
        assertTrue(
            editor.contains("kgToDisplay(planningMaxWeightPerCableKg, weightUnit)"),
            "The editor weight slider must use the planning ceiling, not a hardcoded 110/242.",
        )
        assertTrue(
            editor.contains("kgToDisplay(CommandLimits.MAX_PROGRESSION_KG, weightUnit)"),
            "The editor progression slider range must come from CommandLimits, not a literal 10.",
        )
    }

    @Test
    fun theCappedNoticeSurvivesTheScreenTransitionThatFollowsTheSend() {
        // Just Lift skips the countdown and only navigates to ActiveWorkoutScreen once the state
        // turns Active, i.e. AFTER the command was sent. A replay-0 SharedFlow emission would be
        // dropped with no subscriber, so the notice is held as drainable state instead.
        val coordinator = read("presentation/manager/WorkoutCoordinator.kt")
        assertTrue(
            coordinator.contains("val commandLimitNotice: StateFlow<String?>") &&
                coordinator.contains("fun consumeCommandLimitNotice()"),
            "The capped notice must be drainable state on the coordinator, not a fire-and-forget event.",
        )

        val engine = read("presentation/manager/ActiveSessionEngine.kt")
        assertTrue(
            engine.contains("coordinator._commandLimitNotice.value = notice"),
            "The engine must publish the capped notice as state.",
        )
        assertFalse(
            engine.contains("_userFeedbackEvents.tryEmit(notice)"),
            "The capped notice must not go out on the replay-0 feedback flow, where it is dropped.",
        )

        val activeWorkout = read("presentation/screen/ActiveWorkoutScreen.kt")
        assertTrue(
            activeWorkout.contains("viewModel.commandLimitNotice.collectAsState()") &&
                activeWorkout.contains("viewModel.consumeCommandLimitNotice()"),
            "ActiveWorkoutScreen must show and drain the capped notice when it arrives.",
        )
        // Draining the notice changes the LaunchedEffect key and cancels its coroutine, and a
        // cancelled showSnackbar dismisses itself, so the snackbar must outlive that effect.
        val afterDrain = activeWorkout.substringAfter("viewModel.consumeCommandLimitNotice()")
            .substringBefore("snackbarHostState.showSnackbar")
        assertTrue(
            afterDrain.contains("snackbarScope.launch"),
            "The capped notice must be shown on snackbarScope, not inside the effect that drains it.",
        )
    }

    private fun read(relativeToKotlinRoot: String): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/$relativeToKotlinRoot")
        assertNotNull(src, "Could not locate $relativeToKotlinRoot")
        return src
    }
}
