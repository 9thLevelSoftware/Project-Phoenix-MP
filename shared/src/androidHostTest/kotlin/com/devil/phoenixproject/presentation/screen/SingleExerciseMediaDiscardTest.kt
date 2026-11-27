package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.media.AndroidExerciseMediaStore
import com.devil.phoenixproject.data.media.ExerciseMediaStore
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCategory
import com.devil.phoenixproject.presentation.components.CreateExerciseDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import java.io.File
import kotlin.test.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingleExerciseMediaDiscardTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var mediaRoot: File
    private lateinit var discardedSessions: MutableList<String>
    private lateinit var fixture: RoutineCompleteRuntimeFixture

    @Before
    fun setUp() {
        root = File.createTempFile("single-ex-media-test", "").apply {
            delete()
            mkdirs()
        }
        mediaRoot = File(root, "media")
        discardedSessions = mutableListOf()
        val baseStore = AndroidExerciseMediaStore(
            mediaRoot = mediaRoot,
            openPickedStream = { path -> File(path).takeIf { it.isFile }?.inputStream() },
        )
        val trackingStore = object : ExerciseMediaStore by baseStore {
            override fun discardStaging(sessionId: String) {
                discardedSessions.add(sessionId)
                baseStore.discardStaging(sessionId)
            }
        }
        fixture = RoutineCompleteRuntimeFixture()
        stopKoin()
        startKoin {
            modules(
                module {
                    single<ExerciseMediaStore> { trackingStore }
                    single<ExerciseRepository> { fixture.fakeExerciseRepository }
                    single<PersonalRecordRepository> { fixture.fakePersonalRecordRepository }
                    single<ProfileExerciseBaselineRepository> { fixture.fakeBaselineRepository }
                    single<VelocityOneRepMaxRepository> { FakeVelocityOneRepMaxRepository() }
                    single<UserProfileRepository> { fixture.fakeUserProfileRepository }
                },
            )
        }
    }

    @After
    fun tearDown() {
        fixture.close()
        stopKoin()
        root.deleteRecursively()
    }

    @Test
    fun createExerciseDialog_keysNameAndMuscleGroupOnExistingExercise() {
        val exerciseA = Exercise(
            id = "U_custom_a",
            name = "Custom Press",
            muscleGroup = ExerciseCategory.CHEST.displayName,
            muscleGroups = ExerciseCategory.CHEST.displayName,
            equipment = "HANDLES",
            isCustom = true,
        )
        val exerciseB = Exercise(
            id = "U_custom_b",
            name = "Custom Row",
            muscleGroup = ExerciseCategory.BACK.displayName,
            muscleGroups = ExerciseCategory.BACK.displayName,
            equipment = "HANDLES",
            isCustom = true,
        )
        var current by mutableStateOf(exerciseA)

        rule.setContent {
            MaterialTheme {
                CreateExerciseDialog(
                    existingExercise = current,
                    exerciseRepository = FakeExerciseRepository(),
                    dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Custom Press").assertIsDisplayed()
        rule.onNodeWithText(ExerciseCategory.CHEST.displayName).assertIsDisplayed()

        current = exerciseB
        rule.waitForIdle()
        rule.onNodeWithText("Custom Row").assertIsDisplayed()
        rule.onNodeWithText(ExerciseCategory.BACK.displayName).assertIsDisplayed()
        rule.onNodeWithText("Custom Press").assertDoesNotExist()
    }

    @Test
    fun singleExerciseScreen_onViewHistory_discardsStagedMediaAndNavigates() {
        val customExercise = Exercise(
            id = "U_custom_hist",
            name = "Cable Halo",
            muscleGroup = "Shoulders",
            muscleGroups = "Shoulders",
            equipment = "HANDLES",
            isCustom = true,
        )
        fixture.fakeExerciseRepository.addExercise(customExercise)

        var navigatedExerciseId: String? = null

        rule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = NavigationRoutes.SingleExercise.route,
                ) {
                    composable(NavigationRoutes.SingleExercise.route) {
                        SingleExerciseScreen(
                            navController = navController,
                            viewModel = fixture.viewModel,
                            exerciseRepository = fixture.fakeExerciseRepository,
                            createDialogProperties = DialogProperties(usePlatformDefaultWidth = false),
                        )
                    }
                    composable(NavigationRoutes.ExerciseDetail.route) { entry ->
                        navigatedExerciseId = entry.arguments?.getString("exerciseId")
                    }
                }
            }
        }
        rule.waitForIdle()

        // Open edit dialog for custom exercise via long press semantics action on row
        rule.onNodeWithText("Cable Halo").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()

        assertEquals(0, discardedSessions.size)

        // Click History button inside CreateExerciseDialog -> must discard staging & navigate
        rule.onNodeWithContentDescription("View exercise history and 1RM").performClick()
        rule.waitForIdle()

        assertEquals("U_custom_hist", navigatedExerciseId)
        assertEquals(1, discardedSessions.size)
    }
}
