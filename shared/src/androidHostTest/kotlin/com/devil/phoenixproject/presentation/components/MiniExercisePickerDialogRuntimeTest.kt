package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.SqlDelightExerciseRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestDriver
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1225: the Custom chip in the Tag exercise picker, driven through real taps on a
 * mounted [MiniExercisePickerDialog] (Robolectric + Compose runtime). The wide window keeps
 * every leading chip composed in the shelf's LazyRow.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniExercisePickerDialogRuntimeTest {

    @get:Rule
    val rule = createComposeRule()

    private val bench = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Glutes")
    private val taggedCustom = Exercise(id = "custom_1", name = "Tagged Custom Row", muscleGroup = "Glutes", isCustom = true)
    private val neverTaggedCustom =
        Exercise(id = "custom_2", name = "Never Tagged Custom", muscleGroup = "Glutes", isCustom = true)

    private val events = mutableListOf<String>()
    private var open by mutableStateOf(true)

    private fun mount(repository: ExerciseRepository, recentIds: List<String>) {
        rule.setContent {
            if (open) {
                MiniExercisePickerDialog(
                    exerciseRepository = repository,
                    onDismiss = {
                        events += "dismiss"
                        open = false
                    },
                    onExerciseSelected = { events += "select:${it.id}" },
                    recentExerciseIds = recentIds,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun chip(label: String) = rule.onNodeWithText(label)
    private fun row(name: String) = rule.onNodeWithText(name)

    private fun FakeExerciseRepository.seed(vararg exercises: Exercise) = exercises.forEach(::addExercise)

    @Test
    fun customOn_clearsAutoSelectedRecent_andLateEmissionCannotReselectIt() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = listOf("bench"))

        // One-shot Recent default hides the never-tagged custom.
        chip("Recent").assertIsSelected()
        row("Bench Press").assertExists()
        row("Never Tagged Custom").assertDoesNotExist()

        chip("Custom").performClick()
        rule.waitForIdle()
        chip("Custom").assertIsSelected()
        chip("Recent").assertIsNotSelected()
        row("Never Tagged Custom").assertExists()
        row("Bench Press").assertDoesNotExist()

        // A later library emission must not re-arm Recent.
        repo.addExercise(taggedCustom)
        rule.waitForIdle()
        chip("Recent").assertIsNotSelected()
        row("Tagged Custom Row").assertExists()

        // Custom off: Recent stays off, the full list returns.
        chip("Custom").performClick()
        rule.waitForIdle()
        chip("Custom").assertIsNotSelected()
        chip("Recent").assertIsNotSelected()
        row("Bench Press").assertExists()
        row("Never Tagged Custom").assertExists()
    }

    @Test
    fun customTappedBeforeLibraryDelivery_winsOverTheLateRecentDefault() {
        val repo = FakeExerciseRepository()
        mount(repo, recentIds = listOf("bench"))
        rule.onAllNodes(hasText("Recent")).fetchSemanticsNodes().let {
            assertEquals("Recent is hidden until the library names a recent id", 0, it.size)
        }

        chip("Custom").performClick()
        rule.waitForIdle()

        repo.seed(bench, neverTaggedCustom)
        rule.waitForIdle()

        chip("Recent").assertIsNotSelected()
        chip("Custom").assertIsSelected()
        row("Never Tagged Custom").assertExists()
        row("Bench Press").assertDoesNotExist()
    }

    @Test
    fun customAlone_recomputesTheListOnEachTap() {
        // No recent ids: only showCustomOnly changes, so this fails if it is not a remember key.
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = emptyList())

        row("Bench Press").assertExists()
        chip("Custom").performClick()
        rule.waitForIdle()
        row("Bench Press").assertDoesNotExist()
        row("Never Tagged Custom").assertExists()

        chip("Custom").performClick()
        rule.waitForIdle()
        row("Bench Press").assertExists()
        row("Never Tagged Custom").assertExists()
    }

    @Test
    fun clear_resetsCustom_withoutReArmingRecent() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = listOf("bench"))

        chip("Custom").performClick()
        rule.waitForIdle()
        chip("Clear").performClick()
        rule.waitForIdle()

        chip("Custom").assertIsNotSelected()
        chip("Recent").assertIsNotSelected()
        row("Bench Press").assertExists()
        row("Never Tagged Custom").assertExists()

        repo.addExercise(taggedCustom)
        rule.waitForIdle()
        chip("Recent").assertIsNotSelected()
    }

    @Test
    fun customAndRecent_intersect() {
        val repo = FakeExerciseRepository().apply { seed(bench, taggedCustom, neverTaggedCustom) }
        mount(repo, recentIds = listOf("custom_1", "bench"))

        chip("Custom").performClick()
        rule.waitForIdle()
        chip("Recent").performClick()
        rule.waitForIdle()

        chip("Custom").assertIsSelected()
        chip("Recent").assertIsSelected()
        row("Tagged Custom Row").assertExists()
        row("Never Tagged Custom").assertDoesNotExist()
        row("Bench Press").assertDoesNotExist()
    }

    @Test
    fun archivedOnlyCustoms_showNoCustomExercisesYet_withoutCreateAffordance() {
        // Real repository: the archived custom is excluded by selectAllExercises' SQL.
        val database = PhoenixDatabase(createTestDriver())
        database.insert(bench, archived = false)
        database.insert(neverTaggedCustom.copy(name = "Archived Custom"), archived = true)
        val repo = SqlDelightExerciseRepository(database, ExerciseImporter(database), FakePreferencesManager())
        mount(repo, recentIds = emptyList())
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Bench Press")).fetchSemanticsNodes().isNotEmpty() }

        chip("Custom").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("No custom exercises yet")).fetchSemanticsNodes().isNotEmpty()
        }
        row("Archived Custom").assertDoesNotExist()
        rule.onNodeWithText("Create your own exercises", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Create Exercise").assertDoesNotExist()
    }

    @Test
    fun searchWithNoMatch_withExistingCustoms_showsSearchEmptyState() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = emptyList())

        chip("Custom").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.waitForIdle()

        rule.onNodeWithText("No exercises found").assertExists()
        rule.onNodeWithText("No custom exercises yet").assertDoesNotExist()
    }

    @Test
    fun reopen_startsWithCustomOff_andRecentDefaultAgain() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = listOf("bench"))

        chip("Custom").performClick()
        rule.waitForIdle()
        open = false
        rule.waitForIdle()
        open = true
        rule.waitForIdle()

        chip("Custom").assertIsNotSelected()
        chip("Recent").assertIsSelected()
    }

    @Test
    fun rowTapUnderCustom_selectsThenDismisses() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = listOf("bench"))

        chip("Custom").performClick()
        rule.waitForIdle()
        row("Never Tagged Custom").performClick()
        rule.waitForIdle()

        assertEquals(listOf("select:custom_2", "dismiss"), events)
    }

    @Test
    fun backDismissesWithoutSelecting() {
        val repo = FakeExerciseRepository().apply { seed(bench, neverTaggedCustom) }
        mount(repo, recentIds = listOf("bench"))

        chip("Custom").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        assertEquals(listOf("dismiss"), events)
    }

    private fun PhoenixDatabase.insert(exercise: Exercise, archived: Boolean) {
        phoenixDatabaseQueries.insertExercise(
            id = exercise.id!!,
            name = exercise.name,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = exercise.muscleGroup,
            muscleGroups = exercise.muscleGroups,
            muscles = null,
            equipment = exercise.equipment,
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = if (archived) 1L else 0L,
            isFavorite = 0L,
            isCustom = if (exercise.isCustom) 1L else 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
