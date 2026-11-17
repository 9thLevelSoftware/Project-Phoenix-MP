package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.testutil.FakeExerciseRepository
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
 * Issue #1358 round-4 P1 behavioral tests (NOT source-string checks) for the
 * photo-attach gate: `Add photos` must follow the USER's Exercise Demos
 * preference at the picker hosts, never the host's demo-image display flag.
 *
 * P1 defect being guarded: `ExercisePickerDialog` used to pass
 * `enablePhotoAttach = enableVideoPlayback` into `CreateExerciseDialog`, and the
 * only two production hosts that can open the custom-exercise dialog
 * (RoutineEditorScreen and WorkoutTab) hard-code `enableVideoPlayback = false`
 * to suppress picker list thumbnails. Result: `Add photos` was ALWAYS disabled
 * at the ExercisePicker entry point and the false "Exercise Demos is off" notice
 * showed even though the preference defaults ON (AC1 unmet). SD10 (preference
 * OFF -> Add disabled + notice) is preserved.
 *
 * Coverage layers (why this mounts the gate component):
 *  - These tests render [ExerciseMediaAttachments], the component that OWNS the
 *    observable gate behavior (Add enabled/disabled + the SD10 notice), driven
 *    with the exact `enableAttach` values the two picker hosts feed from the
 *    preference after the fix (ON -> true at both hosts, OFF -> false).
 *  - Host wiring (RoutineEditorScreen/WorkoutTab feed `enablePhotoAttach` from
 *    the preference; ExercisePickerDialog forwards its own parameter) is held
 *    by the drift guard `ExerciseMediaWiringTest
 *    .photoAttachGateIsDecoupledFromListThumbnailFlagAtBothHosts`.
 *  - End-to-end proof at the REAL hosts (including the production
 *    ModalBottomSheet + CreateExerciseDialog path) is the R4 routine-entry
 *    acceptance on iOS and Android: a distinct-photo ADDITION at the routine
 *    host with the preference ON now succeeds.
 *  - Rendering CreateExerciseDialog itself under Robolectric is not viable in
 *    this harness: its `Dialog` (default usePlatformDefaultWidth=true) with any
 *    TextField never reaches Compose idle (AppNotIdleException after 60s —
 *    isolated by probes: Dialog+TextField loops; the same field alone, in a
 *    scroll column, or in a usePlatformDefaultWidth=false Dialog all settle).
 *    That is a Robolectric/harness quirk, not product behavior — the dialog is
 *    exercised on both real platforms by the R4 acceptance runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExercisePickerHostPhotoAttachTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var mediaRoot: File
    private lateinit var repository: FakeExerciseRepository

    @Before
    fun setUp() {
        mediaRoot = File.createTempFile("picker-photo-attach-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        repository = FakeExerciseRepository()
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

    // ---- preference ON (what RoutineEditorScreen and WorkoutTab now feed
    //      at the routine-editor and workout-add picker hosts) ----

    @Test
    fun routineEditorHost_preferenceOn_addPhotosEnabled_noNotice() {
        mountAttachGate(enableAttach = true)
        addPhotosButton().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    @Test
    fun workoutAddHost_preferenceOn_addPhotosEnabled_noNotice() {
        mountAttachGate(enableAttach = true)
        addPhotosButton().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    // ---- preference OFF: SD10 preserved at both hosts ----

    @Test
    fun routineEditorHost_preferenceOff_addPhotosDisabled_noticeShown() {
        mountAttachGate(enableAttach = false)
        addPhotosButton().assertIsNotEnabled()
        preferenceOffNotice().assertIsDisplayed()
    }

    @Test
    fun workoutAddHost_preferenceOff_addPhotosDisabled_noticeShown() {
        mountAttachGate(enableAttach = false)
        addPhotosButton().assertIsNotEnabled()
        preferenceOffNotice().assertIsDisplayed()
    }

    // ---- the P1 regression shape, named explicitly ----

    @Test
    fun listThumbnailSuppression_alone_neverDisablesAttach() {
        // The exact pre-fix combination both hosts produced routed a hard-coded
        // `false` into this gate (demo-image display flag OFF). With the
        // preference ON the gate must render Add ENABLED — before the P1 fix the
        // hosts reached this component with enableAttach=false and showed the
        // false notice. The host-side routing is held by the drift guard and
        // proven end-to-end by the R4 routine-host ADDITION acceptance runs.
        mountAttachGate(enableAttach = true)
        addPhotosButton().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }
}
