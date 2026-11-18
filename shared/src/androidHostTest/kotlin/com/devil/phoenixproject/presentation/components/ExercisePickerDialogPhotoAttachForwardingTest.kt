package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.data.media.ExerciseMediaStore
import com.devil.phoenixproject.domain.model.Exercise
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
 * Issue #1358 test hygiene (N1): REAL behavioral coverage of the round-4 P1
 * forwarding in [ExercisePickerDialog] — the create dialog's photo-attach gate
 * must follow `enablePhotoAttach` (the USER's Exercise Demos preference), never
 * the host's list-thumbnail flag `enableVideoPlayback`.
 *
 * These tests render the actual [ExercisePickerDialog], tap "Create Custom
 * Exercise" (the real user gesture) and assert the gate state inside the real
 * [CreateExerciseDialog]. They FAIL when the call site is mutated back to the
 * P1 defect shape `enablePhotoAttach = enableVideoPlayback` (discrimination
 * proven by the mutation run recorded in the PR), which the component-level
 * [ExerciseMediaAttachGateTest] cannot catch.
 *
 * Harness notes (issue #1358 N1, "do not change production behavior"):
 *  - [CreateExerciseDialog] hosts its body in a default `Dialog`
 *    (`usePlatformDefaultWidth = true`), which under Robolectric combined with
 *    any TextField never reaches Compose idle (AppNotIdleException after 60s —
 *    the same field settles alone, in a scroll column, or in a
 *    `usePlatformDefaultWidth = false` Dialog). The test-only
 *    `createDialogProperties`/`dialogProperties` seams (both default to
 *    production `DialogProperties()`) let these tests opt into the settling
 *    variant. No production call site passes them; production rendering is
 *    byte-identical.
 *  - `fullScreen = true` keeps the picker shell in its own
 *    `usePlatformDefaultWidth = false` Dialog (the harness-settling shape). The
 *    forwarding line under test is shell-independent — it runs before the
 *    fullScreen/bottom-sheet branch.
 *  - [ExercisePickerDialog] resolves its `ExerciseMediaStore` from Koin, so the
 *    tests start a minimal Koin graph over a temp-dir store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExercisePickerDialogPhotoAttachForwardingTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var mediaRoot: File
    private lateinit var repository: FakeExerciseRepository

    @Before
    fun setUp() {
        mediaRoot = File.createTempFile("picker-forwarding-test", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        repository = FakeExerciseRepository().apply {
            addExercise(Exercise(id = "bench", name = "Bench Press", muscleGroup = "Glutes"))
        }
        startKoin {
            modules(
                module {
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

    /**
     * Mounts the real [ExercisePickerDialog] with the two flags decoupled and
     * opens the create dialog through the real "Create Custom Exercise" tap.
     */
    private fun openCreateDialog(enableVideoPlayback: Boolean, enablePhotoAttach: Boolean) {
        rule.setContent {
            ExercisePickerDialog(
                showDialog = true,
                onDismiss = {},
                onExerciseSelected = {},
                exerciseRepository = repository,
                enableVideoPlayback = enableVideoPlayback,
                enablePhotoAttach = enablePhotoAttach,
                // Harness-only seam (see class doc): production default kept.
                createDialogProperties = DialogProperties(usePlatformDefaultWidth = false),
                fullScreen = true,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Create Custom Exercise").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Create Exercise").assertIsDisplayed()
    }

    private fun addPhotosButton() = rule.onNodeWithText("Add photos")

    private fun preferenceOffNotice() =
        rule.onNodeWithText("Exercise Demos is off. Enable it in Settings to add or view photos.")

    @Test
    fun preferenceOn_thumbnailsSuppressed_addPhotosStillEnabled_noNotice() {
        // The exact pre-fix host combination: list thumbnails hard-suppressed
        // (enableVideoPlayback = false) while the user's preference is ON. The
        // P1 defect routed the thumbnail flag into the gate and disabled Add.
        openCreateDialog(enableVideoPlayback = false, enablePhotoAttach = true)
        addPhotosButton().assertIsEnabled()
        preferenceOffNotice().assertDoesNotExist()
    }

    @Test
    fun preferenceOff_thumbnailsShown_addPhotosDisabled_noticeShown() {
        // Mirror direction: thumbnails shown but the user's preference is OFF
        // (SD10 preserved at this entry point). The same mutation would wrongly
        // re-enable Add here.
        openCreateDialog(enableVideoPlayback = true, enablePhotoAttach = false)
        addPhotosButton().assertIsNotEnabled()
        preferenceOffNotice().assertIsDisplayed()
    }
}
