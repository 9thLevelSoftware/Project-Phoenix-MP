package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Component-level SD10 behavior of [ExerciseMediaAttachments], the gate section
 * the create/edit dialog hosts: `Add photos` follows the `enableAttach` input
 * (the USER's Exercise Demos preference), and the preference-OFF notice shows
 * exactly when attach is disabled.
 *
 * Scope note (issue #1358 test hygiene, N1): these tests deliberately claim
 * ONLY component behavior. They mount the gate with a caller-supplied
 * `enableAttach` constant and say nothing about how any host wires that input —
 * the previous `routineEditorHost_*`/`workoutAddHost_*` names claimed host
 * coverage that mounting this component cannot deliver (all five old tests
 * kept passing with `enablePhotoAttach = enableVideoPlayback` put back in
 * ExercisePicker.kt). Real forwarding coverage lives in
 * [ExercisePickerDialogPhotoAttachForwardingTest], which renders the actual
 * [ExercisePickerDialog], opens the create dialog and fails under that
 * mutation; the source-string drift guard `ExerciseMediaWiringTest
 * .photoAttachGateIsDecoupledFromListThumbnailFlagAtBothHosts` holds the
 * remaining wiring shapes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseMediaAttachGateTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var mediaRoot: File

    @Before
    fun setUp() {
        mediaRoot = File.createTempFile("picker-photo-attach-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        mediaRoot.deleteRecursively()
    }

    private fun attachStore() = AndroidExerciseMediaStore(
        mediaRoot = File(mediaRoot, "media"),
        openPickedStream = { handle -> File(handle).takeIf { it.isFile }?.inputStream() },
    )

    /** The gate section exactly as the create/edit dialog hosts it (SD2/SD10). */
    private fun mountAttachGate(enableAttach: Boolean) {
        rule.setContent {
            ExerciseMediaAttachments(
                coordinator = ExerciseMediaSaveCoordinator(attachStore()),
                savedImages = emptyList(),
                enableAttach = enableAttach,
                onRequestPick = {},
            )
        }
        rule.waitForIdle()
    }

    private fun addPhotosButton() = rule.onNodeWithText("Add photos")

    private fun preferenceOffNotice() =
        rule.onNodeWithText("Exercise Demos is off. Enable it in Settings to add or view photos.")

    @Test
    fun gateOn_addPhotosEnabled_noPreferenceOffNotice() {
        mountAttachGate(enableAttach = true)
        addPhotosButton().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    @Test
    fun gateOff_addPhotosDisabled_preferenceOffNoticeShown() {
        mountAttachGate(enableAttach = false)
        addPhotosButton().assertIsNotEnabled()
        preferenceOffNotice().assertIsDisplayed()
    }
}
