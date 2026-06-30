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

        listOf(
            "Current bodyweight",
            "Used for bodyweight effective load and volume in this session.",
            "Add bodyweight to calculate bodyweight effective load and volume for this session.",
            "Use for this session",
            "Use stored",
            "Not now",
            "Save for session",
            "Save to profile for next time",
            "Edit current bodyweight",
        ).forEach { expected ->
            assertTrue(
                expected in src,
                "SetReadyScreen.kt must contain issue #600 prompt copy: $expected",
            )
        }
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
            src.contains("resolvedBodyWeightKg * selectedVariant.percentage"),
            "Bodyweight effective-load preview must use the resolved session bodyweight value.",
        )
        assertTrue(
            src.contains("Text(\"Edit current bodyweight\")"),
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
}
