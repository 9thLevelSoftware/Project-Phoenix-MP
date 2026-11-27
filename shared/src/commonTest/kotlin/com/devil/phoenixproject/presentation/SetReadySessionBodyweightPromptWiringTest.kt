package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Source-level UI wiring guard for issue #600 session bodyweight prompt. */
class SetReadySessionBodyweightPromptWiringTest {

    @Test
    fun promptCopy_isPresentInSetReadySource() {
        val src = readSetReadyScreenSource()
        val stringsXml = readProjectFile("src/commonMain/composeResources/values/strings.xml")
        assertNotNull(stringsXml, "Could not locate values/strings.xml")

        listOf(
            "set_ready_current_bodyweight" to "Current bodyweight",
            "set_ready_bodyweight_has_saved" to "Used for bodyweight effective load and volume in this session.",
            "set_ready_bodyweight_missing" to "Add bodyweight to calculate bodyweight effective load and volume for this session.",
            "set_ready_use_for_session" to "Use for this session",
            "set_ready_not_now" to "Not now",
            "set_ready_save_for_session" to "Save for session",
            "set_ready_save_to_profile" to "Save to profile for next time",
            "set_ready_edit_bodyweight" to "Edit current bodyweight",
        ).forEach { (key, expected) ->
            assertTrue(
                "Res.string.$key" in src,
                "SetReadyScreen.kt must reference issue #600 resource key: Res.string.$key",
            )
            assertTrue(
                """<string name="$key">$expected</string>""" in stringsXml,
                "values/strings.xml must preserve issue #600 prompt copy: $expected",
            )
        }
        assertTrue(
            "\"Use stored\"" !in src,
            "SetReadyScreen.kt must not render a redundant Use stored action when Use for this session already confirms the saved value.",
        )
    }

    @Test
    fun saveToProfileCheckbox_usesClickableRowTouchTarget() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains(".clickable { saveSessionBodyweightToProfile = !saveSessionBodyweightToProfile }"),
            "The Save to profile row must be clickable so users do not have to precisely tap the checkbox.",
        )
    }

    @Test
    fun directBodyweightSingleExercise_routesThroughSetReadyPrompt() {
        val src = readSingleExerciseScreenSource()

        assertTrue(
            src.contains("if (configuredExercise.exercise.isBodyweight)"),
            "SingleExerciseScreen.kt must special-case direct bodyweight launches after loading the temp routine.",
        )
        assertTrue(
            src.contains("viewModel.enterSetReady(0, 0)"),
            "Direct bodyweight single-exercise launches must enter Set Ready so the current-bodyweight prompt can be handled before the set starts.",
        )
        assertTrue(
            src.contains("navController.navigate(NavigationRoutes.SetReady.route)"),
            "Direct bodyweight single-exercise launches must navigate to Set Ready instead of bypassing the prompt.",
        )
    }

    @Test
    fun promptGate_usesRoutineLevelSessionState() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains("val sessionBodyweightState by viewModel.sessionBodyweightState.collectAsState()"),
            "SetReadyScreen.kt must collect sessionBodyweightState from MainViewModel.",
        )
        assertTrue(
            src.contains("val bodyweightPromptPending = sessionBodyweightState.routineHasBodyweight &&\n        !sessionBodyweightState.promptHandled"),
            "SetReadyScreen.kt must gate the prompt on routineHasBodyweight, not only current exercise bodyweight.",
        )
        assertTrue(
            src.contains("enabled = connectionState is ConnectionState.Connected && !bodyweightPromptPending"),
            "START must stay disabled until the current-bodyweight prompt is confirmed, edited, or skipped.",
        )
    }

    @Test
    fun promptCard_isTaggedForFutureComposeTests() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains("testTag(SetReadyTestTags.SESSION_BODYWEIGHT_CARD)"),
            "The issue #600 Current bodyweight card must have a stable test tag.",
        )
        assertTrue(
            src.contains("\"set_ready_session_bodyweight_card\""),
            "SetReadyTestTags.SESSION_BODYWEIGHT_CARD must keep a stable string value.",
        )
    }

    @Test
    fun effectiveLoadPreview_usesResolvedBodyweight() {
        val src = readSetReadyScreenSource()

        assertTrue(
            src.contains("val resolvedBodyWeightKg = sessionBodyweightState.sessionBodyWeightKg ?: userPreferences.bodyWeightKg"),
            "SetReadyScreen.kt must resolve session override before falling back to saved Settings bodyweight.",
        )
        assertTrue(
            src.contains("BodyweightVolumeCalculator.effectiveWeight(") &&
                src.contains("bodyWeightKg = resolvedBodyWeightKg,"),
            "Bodyweight effective-load preview must use the resolved session bodyweight value.",
        )
        assertTrue(
            src.contains("Text(stringResource(Res.string.set_ready_edit_bodyweight))"),
            "Handled bodyweight sessions must expose an edit affordance from the bodyweight setup area.",
        )
    }

    private fun readSetReadyScreenSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate SetReadyScreen.kt on disk. Run from the shared/ module root or project root.",
        )
        return src
    }

    private fun readSingleExerciseScreenSource(): String {
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt"
        val src = readProjectFile(relativePath)
        assertNotNull(
            src,
            "Could not locate SingleExerciseScreen.kt on disk. Run from the shared/ module root or project root.",
        )
        return src
    }
}
