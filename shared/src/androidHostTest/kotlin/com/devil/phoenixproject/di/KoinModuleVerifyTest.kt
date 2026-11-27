package com.devil.phoenixproject.di


import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.devil.phoenixproject.data.garmin.GarminCompanionTransport
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.GamificationViewModel
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.Settings
import io.mockk.mockk
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(KoinExperimentalAPI::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KoinModuleVerifyTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDownKoin() {
        stopKoin()
    }

    @Test
    fun verifyAppModule() {
        appModule.verify(
            extraTypes = listOf(
                // Types provided by platformModule (not included in appModule)
                DriverFactory::class,
                Settings::class,
                BleRepository::class,
                CsvExporter::class,
                CsvImporter::class,
                DataBackupManager::class,
                ConnectivityChecker::class,
                SupabaseConfig::class,
                // Platform-provided factories
                SafeWordListenerFactory::class,
                // Garmin companion transport (issue #1391): lives in platformModule
                // (Android/iOS/tvOS actuals), never in appModule.
                GarminCompanionTransport::class,
                // Platform-provided health integration (Android: HealthIntegration(context), iOS: HealthIntegration())
                HealthIntegration::class,
                HealthWorkoutWriter::class,
                WorkoutServiceController::class,
                // Composed inside MainViewModel (the single session manager, integration
                // model §5) and never a bean; GarminSessionController's constructor type is
                // reflected by verify() even though the instance is pulled pre-composed
                // from GarminSessionControllerHolder.
                DefaultWorkoutSessionManager::class,
                CoroutineScope::class,
                // Issue #1358: platform-provided exercise media store (phoneMain)
                com.devil.phoenixproject.data.media.ExerciseMediaStore::class,
                // Lambda types used in constructor injection (e.g. PortalApiClient tokenProvider)
                Function0::class,
                // Function-typed collaborators of ComputeVelocityOneRepMaxUseCase (issue #517):
                // supplied as lambdas inside the module, not resolved from the graph.
                Function2::class,
                Function3::class,
                Function4::class,
                Function5::class,
            ),
        )
    }

    @Test
    fun cycleAndGamificationViewModelsResolveFromViewModelStore_andLinkAccountIsFactory() {
        stopKoin()
        val koinApp = startKoin {
            modules(
                module {
                    single<TrainingCycleRepository> { FakeTrainingCycleRepository() }
                    single<UserProfileRepository> { FakeUserProfileRepository() }
                    single<GamificationRepository> { FakeGamificationRepository() }
                    single<SyncManager> { mockk(relaxed = true) }
                    single<AuthRepository> { mockk(relaxed = true) }
                },
                presentationModule,
            )
        }

        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        var cycle1: CycleEditorViewModel? = null
        var cycle2: CycleEditorViewModel? = null
        var gamification1: GamificationViewModel? = null
        var gamification2: GamificationViewModel? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                cycle1 = koinViewModel<CycleEditorViewModel>()
                cycle2 = koinViewModel<CycleEditorViewModel>()
                gamification1 = koinViewModel<GamificationViewModel>()
                gamification2 = koinViewModel<GamificationViewModel>()
            }
        }
        composeRule.waitForIdle()

        assertSame(cycle1, cycle2, "CycleEditorViewModel must resolve from ViewModelStore")
        assertSame(gamification1, gamification2, "GamificationViewModel must resolve from ViewModelStore")

        store.clear()

        // LinkAccountViewModel is registered as a factory and cleared by its host DisposableEffect
        val link1 = koinApp.koin.get<LinkAccountViewModel>()
        val link2 = koinApp.koin.get<LinkAccountViewModel>()
        assertNotSame(link1, link2, "LinkAccountViewModel must remain a Koin factory")
        link1.clear()
        link2.clear()
    }
}
