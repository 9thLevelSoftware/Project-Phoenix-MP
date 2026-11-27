package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.devil.phoenixproject.data.ble.HrmBleManager
import com.devil.phoenixproject.data.ble.HrmCapabilities
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.OwnershipTransferRepository
import com.devil.phoenixproject.data.repository.ProfileRecoveryRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HrMeasurement
import com.devil.phoenixproject.domain.model.WeightTextScale
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.viewmodel.ProfileViewModel
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeDataBackupManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.DataBackupManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
class ProfileSettingsSeparationUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var profileRepo: FakeUserProfileRepository
    private lateinit var exerciseRepo: FakeExerciseRepository
    private lateinit var profileViewModel: ProfileViewModel

    @Before
    fun setUp() {
        profileRepo = FakeUserProfileRepository().apply {
            setActiveProfileForTest("default")
        }
        exerciseRepo = FakeExerciseRepository()
        val workoutRepo = FakeWorkoutRepository()
        val prRepo = FakePersonalRecordRepository()
        val velocityRepo = FakeVelocityOneRepMaxRepository()
        val assessmentRepo = FakeAssessmentRepository()
        val measurementsRepo = FakeExternalMeasurementRepository()

        profileViewModel = ProfileViewModel(
            profiles = profileRepo,
            exercises = exerciseRepo,
            workouts = workoutRepo,
            personalRecords = prRepo,
            resolveCurrentOneRepMax = ResolveCurrentOneRepMaxUseCase(
                velocityRepository = velocityRepo,
                assessmentRepository = assessmentRepo,
                workoutRepository = workoutRepo,
            ),
            externalMeasurements = measurementsRepo,
        )

        val syncTriggerManager = mockk<SyncTriggerManager>(relaxed = true).also {
            every { it.hasPersistentError } returns MutableStateFlow(false)
        }
        val recoveryRepo = mockk<ProfileRecoveryRepository>(relaxed = true)
        val ownershipRepo = mockk<OwnershipTransferRepository>(relaxed = true)
        val authRepo = mockk<AuthRepository>(relaxed = true)
        val hrmBleManager = mockk<HrmBleManager>(relaxed = true).also {
            every { it.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
            every { it.scannedDevices } returns MutableStateFlow<List<ScannedDevice>>(emptyList())
            every { it.batteryLevel } returns MutableStateFlow<Int?>(null)
            every { it.hrMeasurement } returns MutableStateFlow<HrMeasurement?>(null)
            every { it.capabilities } returns MutableStateFlow(HrmCapabilities())
            every { it.rememberedAddress } returns MutableStateFlow<String?>(null)
            every { it.rememberedName } returns MutableStateFlow<String?>(null)
            every { it.isAutoReconnectEnabled } returns MutableStateFlow(false)
            every { it.isRawEcgStorageEnabled } returns MutableStateFlow(false)
            every { it.torsoPitchDeg } returns MutableStateFlow<Double?>(null)
            every { it.torsoRollDeg } returns MutableStateFlow<Double?>(null)
            every { it.dynamicAccMg } returns MutableStateFlow<Int?>(null)
        }

        stopKoin()
        startKoin {
            modules(
                module {
                    single<DataBackupManager> { FakeDataBackupManager() }
                    single<ExerciseRepository> { exerciseRepo }
                    single<UserProfileRepository> { profileRepo }
                    single { syncTriggerManager }
                    single { recoveryRepo }
                    single { ownershipRepo }
                    single { authRepo }
                    single { hrmBleManager }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun settingsTab_rendersGlobalAppControlsOnly_andExcludesProfileOwnedControls() {
        rule.setContent {
            MaterialTheme {
                SettingsTab(
                    enableVideoPlayback = true,
                    themeMode = ThemeMode.SYSTEM,
                    dynamicColorAvailable = false,
                    dynamicColorEnabled = false,
                    onEnableVideoPlaybackChange = {},
                    onThemeModeChange = {},
                    onDynamicColorEnabledChange = {},
                    onDeleteAllWorkouts = {},
                    onNavigateToConnectionLogs = {},
                    onNavigateToDiagnostics = {},
                    onNavigateToLinkAccount = {},
                    onNavigateToIntegrations = {},
                    onSetTitle = {},
                    onTestSounds = {},
                    bleCompatibilityMode = BleCompatibilitySetting.AUTO,
                    onBleCompatibilityModeChange = {},
                    autoBackupEnabled = false,
                    onAutoBackupEnabledChange = {},
                    includeRawTelemetryInBackups = false,
                    onIncludeRawTelemetryInBackupsChange = {},
                    backupStats = null,
                    onOpenBackupFolder = {},
                    backupDestination = BackupDestination.Default,
                    onBackupDestinationChange = {},
                    selectedLanguage = "en",
                    onLanguageChange = {},
                    weightTextScale = WeightTextScale.STANDARD,
                    onWeightTextScaleChange = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Cloud Sync").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Language").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Exercise Demos").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Workout control size").performScrollTo().assertIsDisplayed()

        // Profile-owned controls must remain on ProfileScreen, never SettingsTab
        rule.onNodeWithText("Equipment Rack").assertDoesNotExist()
        rule.onNodeWithTag(TestTags.ACTION_BADGES).assertDoesNotExist()
        rule.onNodeWithText("Disco Mode").assertDoesNotExist()
        rule.onNodeWithText("Workout beeps").assertDoesNotExist()
    }

    @Test
    fun profileScreen_ownsAchievementsRackAndPerProfilePreferences() {
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
                    viewModel = profileViewModel,
                    exerciseRepository = exerciseRepo,
                )
            }
        }
        rule.waitForIdle()

        val verticalScrollable = SemanticsMatcher("vertical scrollable") {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        rule.onNode(verticalScrollable).performScrollToNode(hasText("Equipment Rack"))
        rule.onNodeWithText("Equipment Rack").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.ACTION_BADGES).assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasText("LED"))
        rule.onNodeWithText("LED").assertIsDisplayed()
        rule.onNode(verticalScrollable).performScrollToNode(hasText("Workout beeps"))
        rule.onNodeWithText("Workout beeps").assertIsDisplayed()

        // Global-only app settings must not appear on ProfileScreen
        rule.onNodeWithText("Exercise Demos").assertDoesNotExist()
        rule.onNodeWithText("Workout control size").assertDoesNotExist()
    }
}
