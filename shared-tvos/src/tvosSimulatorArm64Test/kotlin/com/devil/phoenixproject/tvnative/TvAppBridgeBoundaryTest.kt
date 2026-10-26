package com.devil.phoenixproject.tvnative

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.StartupDiagnosticFailure
import com.devil.phoenixproject.TvAppBridge
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.di.PersistedFileStartupPrerequisite
import com.devil.phoenixproject.di.SecureSettingsQualifier
import com.devil.phoenixproject.di.appModule
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.tv.createTvMainViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.TvosUnavailableDataBackupManager
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvAppBridgeBoundaryTest {
    @Test
    fun switchingProfileSelectionIsContainedAndExplicitReselectionSucceeds() = runTest {
        withBridge { f ->
            f.startReady()
            val before = f.main.workoutParameters.value
            val ready = assertIs<ActiveProfileContext.Ready>(f.context.value)
            f.context.value = ActiveProfileContext.Switching(ready.profile.id)
            runCurrent()
            f.bridge.selectJustLiftExercise("cable")
            runCurrent()
            assertEquals(before, f.main.workoutParameters.value)
            assertEquals(0, f.commands)
            assertTrue(f.uncaught.isEmpty(), "Selection must contain the root launch failure")
            assertEquals(SELECTION_ERROR, f.bridge.snapshot().actionError)
            f.context.value = ready
            runCurrent()
            f.bridge.selectJustLiftExercise("cable")
            runCurrent()
            assertEquals("cable", f.bridge.snapshot().selectedExerciseId)
            assertNull(f.bridge.snapshot().actionError)
            assertEquals(0, f.commands)
        }
    }

    @Test
    fun failedDefaultsReadDoesNotPublishPartialSelectionOrParameters() = runTest {
        withBridge { f ->
            f.startReady()
            val before = f.main.workoutParameters.value
            f.defaultsFailure = IllegalStateException("private defaults failure")
            f.bridge.selectJustLiftExercise("cable")
            runCurrent()
            assertEquals(before, f.main.workoutParameters.value)
            assertNull(f.bridge.snapshot().selectedExerciseId)
            assertEquals(0, f.commands)
            assertTrue(f.uncaught.isEmpty(), "Expected defaults failure escaped the bridge launch")
            assertEquals(SELECTION_ERROR, f.bridge.snapshot().actionError)
            f.defaultsFailure = null
            f.bridge.selectJustLiftExercise("cable")
            runCurrent()
            assertEquals("cable", f.bridge.snapshot().selectedExerciseId)
            assertNull(f.bridge.snapshot().actionError)
        }
    }

    @Test
    fun cancelledDefaultsReadDoesNotPublishSelectionOrTranslateCancellationToError() = runTest {
        withBridge { f ->
            f.startReady()
            val before = f.main.workoutParameters.value
            f.defaultsFailure = CancellationException("cancelled read")
            f.bridge.selectJustLiftExercise("cable")
            runCurrent()
            assertEquals(before, f.main.workoutParameters.value)
            assertNull(f.bridge.snapshot().actionError)
            assertTrue(f.uncaught.isEmpty())
            assertEquals(0, f.commands)
            f.defaultsFailure = null
            f.bridge.selectJustLiftExercise("cable")
            f.bridge.selectJustLiftExercise("other")
            runCurrent()
            assertEquals("other", f.bridge.snapshot().selectedExerciseId)
            assertNull(f.bridge.snapshot().actionError)
            assertEquals(0, f.commands)
        }
    }

    @Test
    fun initialCatalogFailureCreatesNoFeatureGraphAndRetryOwnsExactlyOne() = runTest {
        withBridge { f ->
            f.catalogFailure = true
            f.bridge.start()
            runCurrent()
            assertEquals("failed", f.bridge.snapshot().startupState)
            assertEquals(0, f.mains.size, "A failed catalog attempt must not construct long-lived consumers")
            assertTrue(f.bridge.snapshot().exercises.isEmpty())
            f.catalogFailure = false
            f.startReady()
            assertEquals(1, f.mains.size)
            assertEquals(listOf("cable", "other"), f.bridge.snapshot().exercises.map { it.id })
            f.bridge.start()
            f.bridge.onForeground()
            f.bridge.onForeground()
            runCurrent()
            assertEquals(1, f.mains.size)
            assertFalse(f.bridge.snapshot().cloudConfigured)
            assertEquals(0, f.commands)
        }
    }

    @Test
    fun handledStartupSinkContainsOnlySafeDiagnosticFieldsNotNestedCauses() = runTest {
        withBridge { f ->
            val sensitive = object : IllegalStateException("secret@example.com /private/outer-store"), StartupDiagnosticFailure {
                override val startupDiagnosticCode = "TV_TEST_STARTUP_FAILED"
                override val startupRetryAllowed = true
                override val startupDiagnosticReason = "TV_TEST_SUPPORT"
            }
            f.koin.koin.loadModules(listOf(module {
                single<PersistedFileStartupPrerequisite> {
                    throw IllegalStateException("Bearer sentinel-secret /private/nested-store", sensitive)
                }
            }))
            f.bridge.start()
            runCurrent()
            assertEquals("failed", f.bridge.snapshot().startupState)
            assertEquals("TV_TEST_STARTUP_FAILED", f.bridge.snapshot().startupErrorCode)
            val output = f.startupMessages.joinToString("\n")
            assertTrue(output.contains("TV_TEST_STARTUP_FAILED"), output)
            assertTrue(output.contains("TV_TEST_SUPPORT"), output)
            for (secret in listOf("secret@example.com", "sentinel-secret", "/private/", "IllegalStateException", "TvAppBridgeBoundaryTest")) {
                assertFalse(output.contains(secret), "Handled startup leaked $secret")
            }
            assertEquals(1, f.startupMessages.size, "Log a single structured diagnostic, not cause lines")
            assertEquals(0, f.mains.size)
        }
    }

    private suspend fun TestScope.withBridge(block: suspend TestScope.(BridgeFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = BridgeFixture(this)
        try {
            fixture.prepare()
            block(fixture)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    companion object {
        private const val SELECTION_ERROR = "Phoenix could not load exercise defaults. Please retry selecting the exercise."
    }
}

/** Real bridge/factory/settings/engine; only storage, peripheral and fault inputs are isolated. */
@OptIn(ExperimentalCoroutinesApi::class)
private class BridgeFixture(private val test: TestScope) {
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    val uncaught = mutableListOf<Throwable>()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher + CoroutineExceptionHandler { _, failure -> uncaught += failure })
    private val suite = "com.devil.phoenixproject.tv.boundary.${NSUUID().UUIDString}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val settings = NSUserDefaultsSettings(defaults)
    private val driver = NativeSqliteDriver(PhoenixDatabase.Schema, ":memory:", onConfiguration = {
        it.copy(name = null, inMemory = true,
            extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
    })
    private val phantom = PhantomBleRepository()
    var commands = 0
    private val ble = object : BleRepository by phantom {
        override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
            commands++
            return Result.success(Unit)
        }
        override suspend fun stopWorkout(): Result<Unit> {
            commands++
            return Result.success(Unit)
        }
    }
    var catalogFailure = false
    var defaultsFailure: Throwable? = null
    val context = MutableStateFlow<ActiveProfileContext>(ActiveProfileContext.Switching(null))
    val mains = mutableListOf<MainViewModel>()
    val main get() = mains.single()
    val startupMessages = mutableListOf<String>()
    private lateinit var profileSource: SqlDelightUserProfileRepository
    val koin = koinApplication {
        modules(appModule, module {
            single<SqlDriver> { driver }
            single<Settings> { settings }
            single<Settings>(SecureSettingsQualifier) { settings }
            single { SupabaseConfig("", "") }
            single<BleRepository> { ble }
            single<DataBackupManager> { TvosUnavailableDataBackupManager() }
            single<WorkoutServiceController> { NoOpWorkoutServiceController }
            // Override before resolution: replacing an already-created singleton would
            // leave its module factory retaining the previous fixture's closed driver.
            single<UserProfileRepository> {
                val source = SqlDelightUserProfileRepository(
                    database = get(), profilePreferencesRepository = get(),
                    profileLocalSafetyStore = get(), gamificationRepository = get(),
                    profileMutationBarrier = get(),
                )
                profileSource = source
                object : UserProfileRepository by source { override val activeProfileContext = context }
            }
            single<EquipmentRackRepository> { ProfileEquipmentRackRepository(get(), scope) }
            single { MachineSafetyCoordinator(get<MachineSafetyHazardRepository>(), BleRepositoryMachineSafetyTransport(ble), scope, nowEpochMs = { test.testScheduler.currentTime }) }
            single<ExerciseRepository> {
                val source = SqlDelightExerciseRepository(get(), get(), get())
                object : ExerciseRepository by source {
                    override suspend fun importExercises() = Result.success(Unit)
                    override fun getAllExercises() = flow {
                        if (catalogFailure) error("catalog unavailable")
                        emit(listOf(
                            Exercise(id = "cable", name = "Row", muscleGroup = "Back", equipment = "BAR"),
                            Exercise(id = "other", name = "Press", muscleGroup = "Chest", equipment = "BAR"),
                        ))
                    }
                }
            }
        })
    }
    val bridge = TvAppBridge(
        scope = scope,
        initializeKoin = {},
        getKoin = { koin.koin },
        mainFactory = { container, ownerScope, trigger ->
            createTvMainViewModel(container, ownerScope, trigger).also { mains += it }
        },
        startupLog = { startupMessages += it },
        blockingDispatcher = dispatcher,
    )

    suspend fun prepare() {
        koin.koin.get<MigrationManager>().runMigrationsNow()
        koin.koin.get<MigrationManager>().awaitRequiredMigrations()
        val ready = assertIs<ActiveProfileContext.Ready>(profileSource.activeProfileContext.value)
        val documents = object : Map<String, SingleExerciseDefaultsDocument> by emptyMap() {
            override fun get(key: String): SingleExerciseDefaultsDocument? {
                defaultsFailure?.let { throw it }
                return null
            }
        }
        context.value = ready.copy(preferences = ready.preferences.copy(
            workout = ready.preferences.workout.copy(value = ready.preferences.workout.value.copy(singleExerciseDefaults = documents)),
        ))
    }

    fun startReady() {
        bridge.start()
        test.runCurrent()
        assertEquals("ready", bridge.snapshot().startupState, bridge.snapshot().startupErrorCode)
    }

    suspend fun close() {
        // These are test-owned, never live app/machine scopes. No production disposal API.
        mains.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        owner.cancelAndJoin()
        koin.koin.get<MigrationManager>().close()
        phantom.shutdown()
        koin.close()
        driver.close()
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }
}
