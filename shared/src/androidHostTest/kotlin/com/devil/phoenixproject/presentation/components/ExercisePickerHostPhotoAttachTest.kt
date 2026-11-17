package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.data.media.ExerciseMediaStore
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1358 round-4 P1 behavioral tests (NOT source-string checks): the
 * ExercisePicker-host photo-attach gate must follow the USER's Exercise Demos
 * preference, never the host's demo-image display flag.
 *
 * P1 defect being guarded: `ExercisePickerDialog` used to pass
 * `enablePhotoAttach = enableVideoPlayback` into `CreateExerciseDialog`, and the
 * only two production hosts that can open the custom-exercise dialog
 * (RoutineEditorScreen.kt and WorkoutTab.kt) hard-code `enableVideoPlayback =
 * false` to suppress picker list thumbnails. Result: `Add photos` was ALWAYS
 * disabled at those entry points and the false "Exercise Demos is off" notice
 * showed even though the preference defaults ON (AC1 unmet).
 *
 * Each mount below mirrors ONE host's exact `ExercisePickerDialog` call shape
 * (its `enableVideoPlayback = false` thumbnail suppression included) and varies
 * only `enablePhotoAttach`, which the hosts feed from the preference:
 *  - routine-editor host: RoutineEditorScreen.kt (picker reached from
 *    Routines -> Edit Routine -> Add Exercise FAB)
 *  - workout-add host: WorkoutTab.kt (add-to-workout picker)
 * SD10 (preference OFF -> Add disabled + notice) is preserved.
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
        startKoin {
            modules(
                module {
                    // Resolve exactly what the picker's create/edit block injects.
                    single<ExerciseMediaStore> {
                        AndroidExerciseMediaStore(
                            mediaRoot = File(mediaRoot, "media"),
                            openPickedStream = { handle ->
                                File(handle).takeIf { it.isFile }?.inputStream()
                            },
                        )
                    }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        mediaRoot.deleteRecursively()
    }

    /** RoutineEditorScreen's exact picker call shape (thumbnail flag hard-off). */
    private fun mountRoutineEditorHostPicker(enablePhotoAttach: Boolean) {
        rule.setContent {
            ExercisePickerDialog(
                showDialog = true,
                onDismiss = {},
                onExerciseSelected = {},
                exerciseRepository = repository,
                enableVideoPlayback = false, // host suppresses list demo thumbnails
                enablePhotoAttach = enablePhotoAttach, // host feeds the preference
                enablePreviouslyCompletedFilter = true,
                completedExerciseIds = emptySet(),
                completedExerciseIdsLoading = false,
            )
        }
        rule.waitForIdle()
    }

    /** WorkoutTab's exact picker call shape (thumbnail flag hard-off). */
    private fun mountWorkoutAddHostPicker(enablePhotoAttach: Boolean) {
        rule.setContent {
            ExercisePickerDialog(
                showDialog = true,
                onDismiss = {},
                onExerciseSelected = {},
                exerciseRepository = repository,
                enableVideoPlayback = false, // host suppresses list demo thumbnails
                enablePhotoAttach = enablePhotoAttach, // host feeds the preference
            )
        }
        rule.waitForIdle()
    }

    private fun openCreateDialog() {
        rule.onNodeWithText("Create Custom Exercise").performClick()
        rule.waitForIdle()
    }

    private fun addPhotosButton() = rule.onNodeWithText("Add photos")

    private fun preferenceOffNotice() =
        rule.onNodeWithText("Exercise Demos is off. Enable it in Settings to add or view photos.")

    // ---- routine-editor host (RoutineEditorScreen) ----

    @Test
    fun routineEditorHost_preferenceOn_addPhotosEnabled_noNotice() {
        mountRoutineEditorHostPicker(enablePhotoAttach = true)
        openCreateDialog()
        addPhotosButton().performScrollTo().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    @Test
    fun routineEditorHost_preferenceOff_addPhotosDisabled_noticeShown() {
        mountRoutineEditorHostPicker(enablePhotoAttach = false)
        openCreateDialog()
        addPhotosButton().performScrollTo().assertIsNotEnabled()
        preferenceOffNotice().performScrollTo().assertIsDisplayed()
    }

    // ---- workout-add host (WorkoutTab) ----

    @Test
    fun workoutAddHost_preferenceOn_addPhotosEnabled_noNotice() {
        mountWorkoutAddHostPicker(enablePhotoAttach = true)
        openCreateDialog()
        addPhotosButton().performScrollTo().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    @Test
    fun workoutAddHost_preferenceOff_addPhotosDisabled_noticeShown() {
        mountWorkoutAddHostPicker(enablePhotoAttach = false)
        openCreateDialog()
        addPhotosButton().performScrollTo().assertIsNotEnabled()
        preferenceOffNotice().performScrollTo().assertIsDisplayed()
    }

    // ---- the P1 regression shape, named explicitly ----

    @Test
    fun listThumbnailSuppression_alone_neverDisablesAttach() {
        // The exact pre-fix combination both hosts produced: demo-image display
        // flag OFF. With the preference ON the gate must still enable Add —
        // before the P1 fix this failed ("Add photos" disabled + false notice).
        mountRoutineEditorHostPicker(enablePhotoAttach = true)
        openCreateDialog()
        addPhotosButton().performScrollTo().assertIsEnabled()
    }
}
