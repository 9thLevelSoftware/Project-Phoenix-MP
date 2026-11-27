package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import com.devil.phoenixproject.presentation.components.buildProfileRecentHistory
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.viewmodel.ProfileIdentityMutationKind
import com.devil.phoenixproject.presentation.viewmodel.ProfileViewModel
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
class ProfileScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var profileRepo: FakeUserProfileRepository
    private lateinit var exerciseRepo: FakeExerciseRepository
    private lateinit var workoutRepo: FakeWorkoutRepository
    private lateinit var prRepo: FakePersonalRecordRepository
    private lateinit var viewModel: ProfileViewModel

    @Before
    fun setUp() {
        profileRepo = FakeUserProfileRepository()
        exerciseRepo = FakeExerciseRepository()
        workoutRepo = FakeWorkoutRepository()
        prRepo = FakePersonalRecordRepository()
        val velocityRepo = FakeVelocityOneRepMaxRepository()
        val assessmentRepo = FakeAssessmentRepository()
        val measurementsRepo = FakeExternalMeasurementRepository()

        viewModel = ProfileViewModel(
            profiles = profileRepo,
            exercises = exerciseRepo,
            workouts = workoutRepo,
            personalRecords = prRepo,
            resolveCurrentOneRepMax = ResolveCurrentOneRepMaxUseCase(
                velocityRepository = velocityRepo,
                assessmentRepository = assessmentRepo,
                workoutRepository = workoutRepo,
                exerciseRepository = exerciseRepo,
            ),
            externalMeasurements = measurementsRepo,
        )

        stopKoin()
        startKoin {
            modules(
                module {
                    single<ExerciseRepository> { exerciseRepo }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun switchingContextRendersLoadingIndicator_andReadyContextRendersAchievementsAndPreferences() {
        profileRepo.emitSwitchingForTest("default")

        rule.setContent {
            MaterialTheme {
                ProfileScreen(
                    onOpenProfileSwitcher = {},
                    onNavigateToExerciseDetail = {},
                    onNavigateToEquipmentRack = {},
                    onNavigateToCustomEquipment = {},
                    onNavigateToBadges = {},
                    onProfileRecoveryRequired = {},
                    isConnected = false,
                    discoModeActive = false,
                    onDiscoModeToggle = {},
                    onPlayDiscoUnlockSound = {},
                    onPlayDominatrixUnlockSound = {},
                    enableVideoPlayback = true,
                    isInWorkoutSession = { false },
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepo,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(TestTags.SCREEN_PROFILE).assertIsDisplayed()
        rule.onNodeWithText("Switching profile…").assertIsDisplayed()

        profileRepo.setActiveProfileForTest("default")
        rule.waitForIdle()

        val verticalScrollable = SemanticsMatcher("vertical scrollable") {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        rule.onNodeWithText("Exercise Insights").assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasTestTag(TestTags.ACTION_BADGES))
        rule.onNodeWithTag(TestTags.ACTION_BADGES).assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasText("Equipment Rack"))
        rule.onNodeWithText("Equipment Rack").assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasText("Workout beeps"))
        rule.onNodeWithText("Workout beeps").assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasText("LED"))
        rule.onNodeWithText("LED").assertIsDisplayed()
    }

    @Test
    fun recentHistoryHelperIsDeterministicBoundedAndFinite() {
        val result = buildProfileRecentHistory(
            sessions = listOf(
                WorkoutSession(id = "old", timestamp = 1, totalVolumeKg = 10f, totalReps = 5),
                WorkoutSession(id = "outside", timestamp = 2, totalVolumeKg = 20f, totalReps = 5),
                WorkoutSession(id = "zero", timestamp = 3, totalVolumeKg = 0f, totalReps = 5),
                WorkoutSession(id = "middle", timestamp = 4, totalVolumeKg = 40f, totalReps = 5),
                WorkoutSession(id = "nan", timestamp = 5, totalVolumeKg = Float.NaN, totalReps = 5),
                WorkoutSession(id = "tie-a", timestamp = 6, totalVolumeKg = 50f, totalReps = 5),
                WorkoutSession(id = "tie-b", timestamp = 6, totalVolumeKg = 60f, totalReps = 5),
            ),
            labelForTimestamp = Long::toString,
        )

        assertEquals(
            listOf("tie-b", "tie-a", "nan", "middle", "zero"),
            result.sessionsNewestFirst.map(WorkoutSession::id),
        )
        assertEquals(listOf("4", "6", "6"), result.chartPointsOldestFirst.map { it.label })
        assertEquals(listOf(40f, 50f, 60f), result.chartPointsOldestFirst.map { it.volume })
        assertTrue(result.chartPointsOldestFirst.all { it.volume.isFinite() && it.volume > 0f })
    }

    @Test
    fun identityOverlayOwnershipSurvivesSwitchingRollbackAndFailure() {
        val initial = ProfileIdentityOverlayOwnership(
            deleteTargetProfileId = "a",
            pendingIdentityProfileId = "a",
        )
        val switching = retainProfileIdentityOverlayOwnership(initial, readyProfileId = null)
        assertEquals(initial, switching)

        val rolledBack = retainProfileIdentityOverlayOwnership(switching, readyProfileId = "a")
        assertEquals(initial, rolledBack)

        val failure = applyProfileIdentityFailure(
            ownership = rolledBack,
            profileId = "a",
            kind = ProfileIdentityMutationKind.DELETE,
        )
        assertTrue(failure.showError)
        assertEquals(ProfileIdentityOverlayOwnership(deleteTargetProfileId = "a"), failure.ownership)
        assertEquals(
            ProfileIdentityOverlayOwnership(),
            retainProfileIdentityOverlayOwnership(rolledBack, readyProfileId = "default"),
        )
    }
}
