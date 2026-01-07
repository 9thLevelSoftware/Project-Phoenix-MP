package com.devil.phoenixproject.e2e

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devil.phoenixproject.App
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeCsvExporter
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.util.CsvExporter
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

@RunWith(AndroidJUnit4::class)
class AppE2ETest : KoinTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            modules(testModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun splashThenHomeContentAppears() {
        launchApp()

        composeRule.onNodeWithText("PROJECT PHOENIX").assertIsDisplayed()

        advancePastSplash()

        composeRule.onNodeWithText("Recent Activity").assertIsDisplayed()
        composeRule.onNodeWithText("Click to Connect").assertIsDisplayed()
        composeRule.onNodeWithText("PROJECT PHOENIX").assertDoesNotExist()
    }

    @Test
    fun bottomNavNavigatesToSettings() {
        launchApp()
        advancePastSplash()

        composeRule.onNode(hasText("Settings") and hasClickAction()).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Like My Work?").assertIsDisplayed()
    }

    private fun launchApp() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            App()
        }
    }

    private fun advancePastSplash() {
        composeRule.mainClock.advanceTimeBy(SPLASH_DURATION_MS)
        composeRule.waitForIdle()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 3000L
    }
}

private val testModule = module {
    single<Settings> { MapSettings() }
    single<PreferencesManager> { FakePreferencesManager() }
    single<BleRepository> { FakeBleRepository() }
    single<WorkoutRepository> { FakeWorkoutRepository() }
    single<ExerciseRepository> { FakeExerciseRepository() }
    single<PersonalRecordRepository> { FakePersonalRecordRepository() }
    single<GamificationRepository> { FakeGamificationRepository() }
    single<TrainingCycleRepository> { FakeTrainingCycleRepository() }
    single<UserProfileRepository> { FakeUserProfileRepository() }
    single<CsvExporter> { FakeCsvExporter() }
    single { RepCounterFromMachine() }
    factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { ThemeViewModel(get()) }
}
