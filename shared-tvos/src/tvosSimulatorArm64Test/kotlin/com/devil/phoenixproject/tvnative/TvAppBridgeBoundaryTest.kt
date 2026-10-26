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
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
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
import kotlinx.coroutines.flow.first
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
            assertEquals("EXERCISE_LIBRARY_UNAVAILABLE", f.bridge.snapshot().startupErrorCode)
            assertEquals(0, f.commands)
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
    fun hostReadyConstructionFailureHasDistinctSafeDiagnosticAfterCatalogAndFeaturesSucceed() = runTest {
        withBridge { f ->
            f.controllerFailure = IllegalStateException("secret@example.com Bearer sentinel-secret /private/host-store")
            f.bridge.start()
            runCurrent()
            val snapshot = f.bridge.snapshot()
            assertEquals(1, f.mains.size, "Feature resolution must succeed before this fault")
            assertEquals(1, f.controllerConstructions, "Fail synchronously inside the actual Ready branch")
            assertEquals(listOf("cable", "other"), snapshot.exercises.map { it.id })
            assertEquals("failed", snapshot.startupState)
            assertEquals(0, f.commands)
            assertTrue(f.uncaught.isEmpty())
            val output = snapshot.toString() + f.startupMessages.joinToString("\n")
            for (secret in listOf("secret@example.com", "sentinel-secret", "/private/host-store", "IllegalStateException")) {
                assertFalse(output.contains(secret), "Host-ready failure leaked $secret")
            }
            assertEquals("APP_HOST_READY_FAILED", snapshot.startupErrorCode)
        }
    }

    @Test
    fun hostReadyConstructionCancellationIsNotTranslatedToStartupFailure() = runTest {
        withBridge { f ->
            val cancellation = CancellationException("cancelled host construction")
            f.controllerFailure = cancellation
            val startup = f.launchStartup()
            var completion: Throwable? = null
            startup.invokeOnCompletion { completion = it }
            runCurrent()
            assertTrue(startup.isCancelled)
            assertSame(cancellation, completion, "The Ready-branch catch must rethrow cancellation")
            assertEquals(1, f.mains.size)
            assertEquals(1, f.controllerConstructions)
            assertEquals(listOf("cable", "other"), f.bridge.snapshot().exercises.map { it.id })
            assertEquals("initializing", f.bridge.snapshot().startupState)
            assertNull(f.bridge.snapshot().startupErrorCode)
            assertTrue(f.startupMessages.isEmpty())
            assertTrue(f.uncaught.isEmpty())
            assertEquals(0, f.commands)
        }
    }

    @Test
    fun admittedRetryClearsTransientFeedbackButFailedAndTimedOutResetKeepSnapshotRecovery() = runTest {
        withBridge { f ->
            f.startCableSet()
            f.stopResult = { Result.failure(IllegalStateException("initial RESET failed")) }
            f.bridge.stopActiveSet()
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState)
            assertEquals(1, f.stopCalls)
            val programs = f.programCommands.size

            val failedReset = CompletableDeferred<Result<Unit>>()
            f.stopResult = { failedReset.await() }
            f.bridge.selectJustLiftExercise("not-in-library")
            assertEquals(LIBRARY_SELECTION_ERROR, f.bridge.snapshot().actionError)
            f.bridge.retrySafeStopRecovery()
            assertEquals("stopping", f.bridge.snapshot().teardownState, "Admission is not RESET success")
            assertNull(f.bridge.snapshot().actionError, "Only stale action feedback clears on admission")
            runCurrent()
            assertEquals(2, f.stopCalls)
            assertTrue(f.bridge.snapshot().sessionOpen)
            assertFalse(f.bridge.snapshot().exitConfirmationPending)
            assertEquals(programs, f.programCommands.size)
            failedReset.complete(Result.failure(IllegalStateException("retry RESET failed")))
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState)
            assertNull(f.bridge.snapshot().actionError, "Recovery authority must not depend on actionError")

            val timedOutReset = CompletableDeferred<Result<Unit>>()
            f.stopResult = { timedOutReset.await() }
            f.bridge.selectJustLiftExercise("not-in-library")
            f.bridge.retrySafeStopRecovery()
            assertEquals("stopping", f.bridge.snapshot().teardownState)
            assertNull(f.bridge.snapshot().actionError)
            runCurrent()
            assertEquals(3, f.stopCalls)
            advanceTimeBy(com.devil.phoenixproject.util.BleConstants.GATT_OPERATION_TIMEOUT_MS)
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState)
            assertNull(f.bridge.snapshot().actionError)
            assertTrue(f.bridge.snapshot().sessionOpen)
            assertEquals(programs, f.programCommands.size)

            val successfulReset = CompletableDeferred<Result<Unit>>()
            f.stopResult = { successfulReset.await() }
            f.bridge.retrySafeStopRecovery()
            assertEquals("stopping", f.bridge.snapshot().teardownState)
            runCurrent()
            assertEquals(4, f.stopCalls)
            successfulReset.complete(Result.success(Unit))
            f.awaitStopped()
            assertEquals("ready", f.bridge.snapshot().teardownState)
            assertTrue(f.bridge.snapshot().sessionOpen, "Successful explicit recovery does not exit the session")
            assertFalse(f.bridge.snapshot().exitConfirmationPending)
            assertNotEquals("active", f.bridge.snapshot().workoutState)
            assertEquals(programs, f.programCommands.size, "Successful recovery must not automatically apply load")
            assertTrue(f.uncaught.isEmpty())
        }
    }

    @Test
    fun disconnectedAndDuplicateRetryCannotBypassSafetyOrRearmAbandonedExitConsent() = runTest {
        withBridge { f ->
            f.startCableSet()
            f.stopResult = { Result.failure(IllegalStateException("initial RESET failed")) }
            f.bridge.stopActiveSet()
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState)
            val programs = f.programCommands.size
            f.connection.value = ConnectionState.Disconnected
            runCurrent()
            f.bridge.selectJustLiftExercise("not-in-library")
            f.bridge.retrySafeStopRecovery()
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState)
            assertEquals(LIBRARY_SELECTION_ERROR, f.bridge.snapshot().actionError)
            assertEquals(1, f.stopCalls, "Disconnected retry must not issue RESET")

            f.connection.value = ConnectionState.Connected("Vee_Boundary", "boundary")
            runCurrent()
            assertEquals("recovery_required", f.bridge.snapshot().teardownState, "Reconnect alone is not recovery")
            val reset = CompletableDeferred<Result<Unit>>()
            f.stopResult = { reset.await() }
            f.bridge.retrySafeStopRecovery()
            assertEquals("stopping", f.bridge.snapshot().teardownState)
            assertNull(f.bridge.snapshot().actionError)
            f.bridge.retrySafeStopRecovery()
            runCurrent()
            f.bridge.retrySafeStopRecovery()
            runCurrent()
            assertEquals(2, f.stopCalls, "Repeated retry must not duplicate an in-flight RESET")

            f.bridge.startNextJustLiftSet()
            f.bridge.confirmSessionExit()
            runCurrent()
            assertEquals(programs, f.programCommands.size, "Start must remain blocked until RESET succeeds")
            assertTrue(f.bridge.snapshot().sessionOpen, "Confirmed exit cannot bypass teardown")
            assertEquals("stopping", f.bridge.snapshot().teardownState)
            val safetyError = assertNotNull(f.bridge.snapshot().actionError)
            f.bridge.handleBackDuringSession()
            runCurrent()
            assertFalse(f.bridge.snapshot().exitConfirmationPending)
            f.bridge.onBackground()
            f.bridge.onForeground()
            runCurrent()
            reset.complete(Result.success(Unit))
            f.awaitStopped()
            assertEquals("ready", f.bridge.snapshot().teardownState)
            assertEquals(safetyError, f.bridge.snapshot().actionError, "Recovery must not erase controller feedback")
            assertTrue(f.bridge.snapshot().sessionOpen)
            assertFalse(f.bridge.snapshot().exitConfirmationPending, "Foreground/Ready must not rearm abandoned consent")
            assertEquals(programs, f.programCommands.size)
            assertNotEquals("active", f.bridge.snapshot().workoutState)
            f.bridge.handleBackDuringSession()
            runCurrent()
            assertTrue(f.bridge.snapshot().exitConfirmationPending, "A fresh explicit Menu request is still allowed")
            assertTrue(f.bridge.snapshot().sessionOpen)
            assertEquals(2, f.stopCalls)
            assertTrue(f.uncaught.isEmpty())
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
        private const val LIBRARY_SELECTION_ERROR = "Choose an exercise from Phoenix's library."
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
    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val programCommands = mutableListOf<ByteArray>()
    var stopCalls = 0
        private set
    val commands get() = programCommands.size + stopCalls
    var stopResult: suspend () -> Result<Unit> = { Result.success(Unit) }
    private val ble = object : BleRepository by phantom {
        override val connectionState = connection
        override val scannedDevices = MutableStateFlow(listOf(ScannedDevice("Vee_Boundary", "boundary", -40)))
        override suspend fun connect(device: ScannedDevice): Result<Unit> {
            connection.value = ConnectionState.Connected(device.name, device.address)
            return Result.success(Unit)
        }
        override suspend fun setColorScheme(schemeIndex: Int) = Result.success(Unit)
        override fun startActiveWorkoutPolling() = Unit
        override suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit> {
            programCommands += command.copyOf()
            return Result.success(Unit)
        }
        override suspend fun stopWorkout(): Result<Unit> {
            stopCalls++
            return stopResult()
        }
    }
    var controllerFailure: Throwable? = null
    var controllerConstructions = 0
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
        controllerFactory = { construct ->
            controllerConstructions++
            controllerFailure?.let { throw it }
            construct()
        },
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

    fun launchStartup(): Job {
        val before = owner.children.toSet()
        bridge.start()
        return (owner.children.toSet() - before).single()
    }

    fun startReady() {
        bridge.start()
        test.runCurrent()
        assertEquals("ready", bridge.snapshot().startupState, bridge.snapshot().startupErrorCode)
    }

    suspend fun startCableSet() {
        startReady()
        bridge.connectSelectedTrainer("boundary")
        test.runCurrent()
        assertEquals("connected", bridge.snapshot().connectionState)
        bridge.selectJustLiftExercise("cable")
        test.runCurrent()
        assertEquals("cable", bridge.snapshot().selectedExerciseId)
        bridge.adjustJustLiftWeight(20f)
        bridge.startJustLift()
        // The production safety store uses Dispatchers.IO; runCurrent alone cannot drain it.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { main.workoutState.first { it is WorkoutState.Active } }
        }
        test.runCurrent()
        assertEquals("active", bridge.snapshot().workoutState, bridge.snapshot().toString())
        assertTrue(bridge.snapshot().sessionOpen)
        assertEquals(1, programCommands.size, "Start through the bridge must reach the real engine's CONFIG write")
        assertEquals(0, stopCalls)
    }

    suspend fun awaitStopped() {
        // Await real arm-row cleanup and completion persistence without advancing a fake IO clock.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                main.machineTeardownState.first { it is MachineTeardownState.Ready }
                main.workoutState.first { it is WorkoutState.SetSummary }
            }
        }
        test.runCurrent()
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
