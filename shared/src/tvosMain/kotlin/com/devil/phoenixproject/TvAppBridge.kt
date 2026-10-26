package com.devil.phoenixproject

import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.presentation.tv.TvSessionController
import com.devil.phoenixproject.presentation.tv.createTvMainViewModel
import com.devil.phoenixproject.presentation.tv.TvAccountHooks
import com.devil.phoenixproject.presentation.tv.TvActivityAdmission
import com.devil.phoenixproject.util.ConnectivityChecker
import kotlinx.coroutines.CancellationException
import com.devil.phoenixproject.data.sync.AccountSwitchChoice
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.di.PersistedFileStartupPrerequisite
import com.devil.phoenixproject.di.doInitTvosKoin
import com.devil.phoenixproject.di.isTvosSimulatorFixtureEnabled
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.MachineSafetyUiState
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.tv.TvRemoteInput
import com.devil.phoenixproject.presentation.tv.TV_TELEMETRY_TIMEOUT_MS
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.sync.LinkAccountUiState
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSLog

private data class TvosStartupDependencies(val migrations: MigrationManager, val exercises: ExerciseRepository)
private data class TvosAppDependencies(
    val main: MainViewModel,
    val eula: EulaViewModel,
    val exercises: ExerciseRepository,
    val account: TvAccountHooks?,
) {
    val link get() = account?.link
}

data class TvDeviceRow(val name: String, val address: String, val rssi: Int)
data class TvExerciseRow(val id: String, val name: String, val isBodyweight: Boolean)
data class TvRoutineRow(val id: String, val name: String, val description: String, val exerciseCount: Int)
data class TvRoutineExerciseRow(val name: String, val weightPerCableKg: Float, val reps: String, val setCount: Int, val isBodyweight: Boolean)

data class TvAppSnapshot(
    val startupState: String,
    val startupErrorCode: String?,
    val eulaReady: Boolean,
    val cloudConfigured: Boolean,
    val simulatorFixtureMode: Boolean,
    val eulaAccepted: Boolean,
    val authenticated: Boolean,
    val accountEmail: String?,
    val syncState: String,
    val syncError: String?,
    val accountMismatch: Boolean,
    val previousAccount: String?,
    val newAccount: String?,
    val connectionState: String,
    val connectionMessage: String?,
    val selectedTrainerAddress: String?,
    val devices: List<TvDeviceRow>,
    val exercises: List<TvExerciseRow>,
    val routines: List<TvRoutineRow>,
    val selectedRoutineId: String?,
    val selectedRoutineName: String?,
    val selectedRoutineDescription: String?,
    val selectedRoutineExercises: List<TvRoutineExerciseRow>,
    val routineFlowState: String,
    val routineExerciseName: String?,
    val routineSetNumber: Int,
    val routineSetCount: Int,
    val routineReadyWeightKg: Float,
    val routineReadyReps: Int,
    val routineReadyToStart: Boolean,
    val manualRoutineRestSeconds: Int?,
    val currentSetIsBodyweight: Boolean,
    val exerciseSecondsRemaining: Int?,
    val bodyweightPlannedReps: Int,
    val bodyweightVariants: List<String>,
    val bodyweightSelectedVariant: Int,
    val completionFailed: Boolean,
    val workoutState: String,
    val workoutMessage: String?,
    val exerciseName: String?,
    val selectedExerciseId: String?,
    val weightPerCableKg: Float,
    val measuredTotalLoadKg: Float?,
    val workingReps: Int,
    val restSeconds: Int,
    val configuredRestSeconds: Int,
    val saveFailure: Boolean,
    val sessionOpen: Boolean,
    val exitConfirmationPending: Boolean,
    val teardownState: String,
    val safetyHazardVisible: Boolean,
    val telemetryTimeoutMs: Long,
    val actionError: String?,
)

/** Native SwiftUI boundary. Shared Kotlin remains authoritative for all domain and machine commands. */
class TvAppBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var deps: TvosAppDependencies? = null
    private var eulaViewModel: EulaViewModel? = null
    private var started = false
    private var startupState = "not_started"
    private var startupError: String? = null
    private var eulaReady = false
    private var cloudConfigured = false
    private var cachedExercises: List<Exercise> = emptyList()
    private var selectedRoutineId: String? = null
    private var controller: TvSessionController? = null
    private val activityAdmission = TvActivityAdmission()
    private var foregroundPending = false
    private var isForeground = true
    private var koinReady = false
    private var fixtureStorageInstalled = false
    private var selectedJustLiftExerciseId: String? = null
    private var exerciseSelectionJob: Job? = null
    private var actionError: String? = null
    private var telemetryJob: Job? = null

    fun start() {
        if (started && startupState != "failed") return
        started = true
        startupError = null
        val bundle = NSBundle.mainBundle
        val supabaseUrl = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String
        val supabaseKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String
        cloudConfigured = !fixtureStorageInstalled && !supabaseUrl.isNullOrBlank() && !supabaseKey.isNullOrBlank()
        startupState = "initializing"
        scope.launch {
            try {
                if (!koinReady) {
                    doInitTvosKoin()
                    koinReady = true
                    fixtureStorageInstalled = installTvosIsolatedFixture()
                    if (fixtureStorageInstalled) cloudConfigured = false
                }
                eulaViewModel = KoinPlatform.getKoin().get()
                eulaReady = true
            } catch (_: Throwable) {
                startupState = "failed"
                startupError = "KOIN_INITIALIZATION_FAILED"
                return@launch
            }
            when (val result = prepareAppHostDependencies(
                resolveStartupOnly = {
                    val koin = KoinPlatform.getKoin()
                    koin.get<PersistedFileStartupPrerequisite>()
                    TvosStartupDependencies(koin.get(), koin.get())
                },
                prepareRequired = { startup ->
                    startup.migrations.runRequiredMigrations()
                    startup.migrations.awaitRequiredMigrations()
                    startup.exercises.importExercises().getOrThrow()
                    if (fixtureStorageInstalled) seedTvosFixtureRoutine()
                },
                resolveFeatures = { startup ->
                    val koin = KoinPlatform.getKoin()
                    val account = if (cloudConfigured) TvAccountHooks(
                        link = koin.get(), syncManager = koin.get(),
                        isOnline = koin.get<ConnectivityChecker>()::isOnline,
                        migrations = startup.migrations,
                        sessionOpen = { controller?.sessionOpen == true },
                        admission = activityAdmission,
                    ) else null
                    TvosAppDependencies(
                        main = createTvMainViewModel(koin, scope, account?.syncTrigger
                            ?: SyncTriggerManager(koin.get<com.devil.phoenixproject.data.sync.SyncManager>(), { false })),
                        eula = koin.get(),
                        exercises = startup.exercises,
                        account = account,
                    )
                },
                blockingDispatcher = Dispatchers.Default,
            )) {
                is StartupDependencyResolution.Ready -> {
                    try {
                        cachedExercises = result.dependencies.exercises.getAllExercises().first()
                        deps = result.dependencies
                        val main = result.dependencies.main
                        controller = TvSessionController(main.workoutSessionManager, main.bleConnectionManager, scope,
                            admitTraining = activityAdmission::tryTraining,
                            releaseTraining = activityAdmission::releaseTraining,
                        ) {
                            // The TV-only runtime view is manual regardless of cloud preference updates.
                            // Never rewrite the user's synced autoplay settings.
                            combine(main.userPreferences, main.autoplayEnabled) { prefs, autoplay ->
                                !autoplay && prefs.summaryCountdownSeconds == 0 && !prefs.autoStartRoutine && !prefs.motionStartEnabled
                            }.first { it }
                        }
                        if (!isForeground) controller?.onBackground()
                        startupState = "ready"
                        startTelemetryWatchdog(main)
                        if (foregroundPending && isForeground) onForeground()
                    } catch (_: Throwable) {
                        startupState = "failed"
                        startupError = "EXERCISE_LIBRARY_UNAVAILABLE"
                    }
                }
                is StartupDependencyResolution.Failed -> {
                    result.cause.stackTraceToString().lineSequence().forEach { NSLog("tvOS dependency startup: $it") }
                    startupState = "failed"
                    startupError = result.diagnosticCode
                }
            }
        }
    }

    fun snapshot(): TvAppSnapshot {
        val main = deps?.main
        val link = deps?.link
        val state = main?.workoutState?.value
        val connection = main?.connectionState?.value
        val sync = link?.syncState?.value
        val mismatch = sync as? SyncState.AccountMismatch
        val routine = main?.routines?.value?.firstOrNull { it.id == selectedRoutineId }
            ?: main?.loadedRoutine?.value?.takeIf { it.id == selectedRoutineId }
        val routineFlow = main?.routineFlowState?.value
        val routineReady = routineFlow as? RoutineFlowState.SetReady
        val params = main?.workoutParameters?.value
        val metric = main?.currentMetric?.value
        val count = main?.repCount?.value
        val teardown = main?.machineTeardownState?.value
        val authError = (link?.uiState?.value as? LinkAccountUiState.Error)?.message
        val syncError = when (sync) {
            is SyncState.Error -> sync.message
            is SyncState.PartialSuccess -> sync.pullError
            else -> authError
        }
        val connectionName = when (connection) {
            is ConnectionState.Connected -> "connected"
            is ConnectionState.Connecting -> "connecting"
            is ConnectionState.Scanning -> "scanning"
            is ConnectionState.Error -> "error"
            else -> "disconnected"
        }
        val workoutName = when (state) {
            is WorkoutState.Active -> "active"
            is WorkoutState.Countdown -> "countdown"
            is WorkoutState.Initializing -> "initializing"
            is WorkoutState.Paused -> "paused"
            is WorkoutState.Resting -> "resting"
            is WorkoutState.SetSummary -> "set_summary"
            is WorkoutState.Completed -> "completed"
            is WorkoutState.RoutineComplete -> "routine_complete"
            is WorkoutState.Error -> "error"
            is WorkoutState.BodyweightRepEntry -> "bodyweight_entry"
            else -> "idle"
        }
        val sessionIsOpen = controller?.sessionOpen == true
        val teardownName = when (teardown) {
            is MachineTeardownState.TearingDown -> "stopping"
            is MachineTeardownState.RecoveryRequired -> "recovery_required"
            else -> "ready"
        }
        val activeIndex = main?.currentExerciseIndex?.value ?: 0
        val exerciseName = cachedExercises.firstOrNull { it.id == params?.selectedExerciseId }?.name
            ?: routine?.exercises?.getOrNull(activeIndex)?.exercise?.name
        val syncName = when (sync) {
            is SyncState.Syncing, is SyncState.SyncingWithProgress -> "syncing"
            is SyncState.Success -> "success"
            is SyncState.PartialSuccess -> "partial_success"
            is SyncState.Error -> "error"
            is SyncState.NotAuthenticated -> "not_authenticated"
            is SyncState.NotPremium -> "not_premium"
            is SyncState.AccountMismatch -> "account_mismatch"
            is SyncState.OwnershipConflict -> "ownership_conflict"
            else -> "idle"
        }
        val message = when (connection) {
            is ConnectionState.Connected -> connection.deviceName
            is ConnectionState.Error -> connection.message
            else -> main?.bleConnectionManager?.connectionError?.value
        }
        return TvAppSnapshot(
            startupState = startupState,
            startupErrorCode = startupError,
            eulaReady = eulaReady,
            cloudConfigured = cloudConfigured,
            simulatorFixtureMode = isTvosSimulatorFixtureEnabled(),
            eulaAccepted = eulaViewModel?.eulaAccepted?.value == true,
            authenticated = link?.isAuthenticated?.value == true,
            accountEmail = link?.currentUser?.value?.email,
            syncState = syncName,
            syncError = syncError,
            accountMismatch = mismatch != null,
            previousAccount = mismatch?.previousUserLabel,
            newAccount = mismatch?.newUserLabel,
            connectionState = connectionName,
            connectionMessage = message,
            selectedTrainerAddress = (connection as? ConnectionState.Connected)?.deviceAddress,
            devices = main?.scannedDevices?.value.orEmpty().map { TvDeviceRow(it.name, it.address, it.rssi) },
            exercises = cachedExercises.mapNotNull { exercise ->
                exercise.id?.let { TvExerciseRow(it, exercise.name, exercise.isBodyweight) }
            },
            routines = main?.routines?.value.orEmpty().map { TvRoutineRow(it.id, it.name, it.description, it.exercises.size) },
            selectedRoutineId = routine?.id ?: selectedRoutineId,
            selectedRoutineName = routine?.name,
            selectedRoutineDescription = routine?.description,
            selectedRoutineExercises = routine?.exercises.orEmpty().map {
                TvRoutineExerciseRow(it.exercise.name, it.weightPerCableKg, it.setReps.firstOrNull()?.toString() ?: "AMRAP", it.setReps.size, it.exercise.isBodyweight)
            },
            routineFlowState = when (routineFlow) {
                is RoutineFlowState.Overview -> "overview"
                is RoutineFlowState.SetReady -> "set_ready"
                is RoutineFlowState.Complete -> "complete"
                else -> "none"
            },
            routineExerciseName = (routine?.exercises?.getOrNull(routineReady?.exerciseIndex ?: 0))?.exercise?.name,
            routineSetNumber = (routineReady?.setIndex ?: 0) + 1,
            routineSetCount = routine?.exercises?.getOrNull(routineReady?.exerciseIndex ?: 0)?.setReps?.size ?: 0,
            routineReadyWeightKg = routineReady?.adjustedWeight ?: 0f,
            routineReadyReps = routineReady?.adjustedReps ?: 0,
            routineReadyToStart = controller?.routineReadyToStart == true,
            manualRoutineRestSeconds = controller?.manualRoutineRestSeconds,
            currentSetIsBodyweight = controller?.currentSetIsBodyweight == true,
            exerciseSecondsRemaining = controller?.exerciseSecondsRemaining,
            bodyweightPlannedReps = controller?.bodyweightEntry?.plannedReps ?: 0,
            bodyweightVariants = controller?.bodyweightEntry?.variants.orEmpty().map { it.label },
            bodyweightSelectedVariant = controller?.bodyweightEntry?.let { it.variants.indexOf(it.selectedVariant) } ?: 0,
            completionFailed = controller?.completionFailed == true,
            workoutState = when {
                controller?.completionFailed == true -> "error"
                controller?.preparing == true -> "initializing"
                else -> workoutName
            },
            workoutMessage = if (controller?.completionFailed == true) controller?.error else (state as? WorkoutState.Error)?.message,
            exerciseName = exerciseName,
            selectedExerciseId = params?.selectedExerciseId,
            weightPerCableKg = params?.weightPerCableKg ?: 0f,
            measuredTotalLoadKg = metric?.totalLoad,
            workingReps = (state as? WorkoutState.SetSummary)?.repCount ?: count?.workingReps ?: 0,
            restSeconds = (state as? WorkoutState.Resting)?.restSecondsRemaining ?: main?.justLiftRestCountdown?.value ?: 0,
            configuredRestSeconds = params?.justLiftRestSeconds ?: 0,
            saveFailure = main?.workoutSaveFailureOffer?.value != null,
            sessionOpen = sessionIsOpen,
            exitConfirmationPending = controller?.exitConfirmationPending == true,
            teardownState = teardownName,
            safetyHazardVisible = main?.machineSafetyCoordinator?.uiState?.value is MachineSafetyUiState.Visible,
            telemetryTimeoutMs = TV_TELEMETRY_TIMEOUT_MS,
            actionError = controller?.error ?: actionError ?: main?.bleConnectionManager?.connectionError?.value ?: (state as? WorkoutState.Error)?.message ?: syncError,
        )
    }

    fun acceptEula() {
        val eula = eulaViewModel ?: return fail("Terms are unavailable until Phoenix initializes.")
        eula.acceptEula()
        if (!eula.eulaAccepted.value) fail("Phoenix could not save EULA acceptance.")
    }
    fun startScanning() { actionError = null; deps?.main?.startScanning() ?: fail("Phoenix core is unavailable.") }

    fun connectSelectedTrainer(address: String) {
        actionError = null
        controller?.connectSelectedTrainer(address) ?: fail("Phoenix core is unavailable.")
    }

    fun disconnectTrainer() {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        if (controller?.machineMayBeRunning == true) {
            controller?.stopSet()
            return fail("Stopping through Phoenix before disconnecting. Select Disconnect again after the stop completes.")
        }
        if (main.machineTeardownState.value !is MachineTeardownState.Ready) return fail("Complete safe stop recovery before disconnecting.")
        main.disconnect()
        actionError = null
    }

    fun selectJustLiftExercise(id: String) {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val exercise = cachedExercises.firstOrNull { it.id == id } ?: return fail("Choose an exercise from Phoenix's library.")
        if (exercise.isBodyweight) return fail("Apple TV currently supports cable exercises only.")
        if (controller?.machineMayBeRunning == true) return fail("Stop the current set before changing exercises.")
        val exerciseId = exercise.id ?: return fail("That exercise cannot be selected because Phoenix has no saved exercise id.")
        exerciseSelectionJob?.cancel()
        exerciseSelectionJob = scope.launch {
            val saved = main.getSingleExerciseDefaults(exerciseId)
            val defaults = main.getJustLiftDefaults()
            val params = main.workoutParameters.value.copy(
                programMode = saved?.toProgramMode() ?: ProgramMode.fromModeValue(defaults.workoutModeId),
                reps = saved?.setReps?.firstOrNull() ?: 0,
                weightPerCableKg = saved?.weightPerCableKg ?: defaults.weightPerCableKg,
                isJustLift = true,
                useAutoStart = false,
                selectedExerciseId = exerciseId,
                stallDetectionEnabled = defaults.stallDetectionEnabled,
                repCountTiming = runCatching { RepCountTiming.valueOf(defaults.repCountTimingName) }.getOrDefault(RepCountTiming.TOP),
                eccentricLoad = saved?.getEccentricLoad() ?: EccentricLoad.LOAD_100,
                echoLevel = saved?.getEchoLevel() ?: EchoLevel.HARDER,
                justLiftRestSeconds = saved?.setRestSeconds?.firstOrNull() ?: defaults.restSeconds,
            )
            selectedJustLiftExerciseId = exerciseId
            main.updateWorkoutParameters(params)
            main.adjustWeight(params.weightPerCableKg, sendToMachine = false)
            actionError = null
        }
    }

    fun adjustJustLiftWeight(weightKg: Float) {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        main.adjustWeight(weightKg, sendToMachine = false)
        actionError = null
    }

    fun adjustJustLiftRest(seconds: Int) {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val allowed = if (seconds <= 0) 0 else seconds.coerceIn(5, 300) / 5 * 5
        main.updateWorkoutParameters(main.workoutParameters.value.copy(justLiftRestSeconds = allowed))
        actionError = null
    }

    fun startJustLift() {
        actionError = null
        controller?.startJustLift() ?: fail("Phoenix core is unavailable.")
    }

    fun openRoutineOverview(id: String) {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val routine = main.routines.value.firstOrNull { it.id == id } ?: return fail("That saved routine is no longer available.")
        if (controller?.sessionOpen == true) return fail("End the current session before choosing another routine.")
        // Overview is read-only. enterRoutineOverview loads a session asynchronously;
        // loading here makes Back swallow navigation and races Begin Routine.
        selectedRoutineId = routine.id
        actionError = null
    }

    fun beginSelectedRoutine() {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val routine = main.routines.value.firstOrNull { it.id == selectedRoutineId } ?: return fail("Choose a saved routine.")
        actionError = null
        controller?.beginRoutine(routine) ?: fail("Phoenix core is unavailable.")
    }

    fun startRoutineSet() {
        actionError = null
        controller?.startRoutineSet() ?: fail("Phoenix core is unavailable.")
    }

    fun confirmBodyweightReps(reps: Int, variantIndex: Int) {
        actionError = null
        controller?.confirmBodyweightReps(reps, variantIndex) ?: fail("Phoenix core is unavailable.")
    }

    fun continueRoutine() {
        actionError = null
        controller?.continueRoutine() ?: fail("Phoenix core is unavailable.")
    }

    fun startNextJustLiftSet() {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val id = selectedJustLiftExerciseId ?: return fail("Select an exercise before starting another set.")
        // Shared Just Lift completion clears the tag; restore only the explicit TV selection.
        main.updateWorkoutParameters(main.workoutParameters.value.copy(selectedExerciseId = id, useAutoStart = false))
        actionError = null
        controller?.startJustLift()
    }

    fun stopActiveSet() {
        actionError = null
        controller?.stopSet() ?: fail("Phoenix core is unavailable.")
    }

    fun retryWorkoutSave() {
        val main = deps?.main ?: return
        val offer = main.workoutSaveFailureOffer.value ?: return
        if (!main.retryWorkoutSave(offer.sessionId)) fail("The save could not be retried. Keep this session open and try again.")
    }

    fun tagJustLiftSet() {
        val main = deps?.main ?: return fail("Phoenix core is unavailable.")
        val summary = main.workoutState.value as? WorkoutState.SetSummary ?: return fail("A completed Just Lift set is required.")
        val exercise = cachedExercises.firstOrNull { it.id == main.workoutParameters.value.selectedExerciseId }
            ?: return fail("The selected exercise is unavailable in Phoenix's library.")
        val sessionId = summary.sessionId ?: return fail("Phoenix has not committed a session id yet.")
        scope.launch { main.tagJustLiftSessionExercise(sessionId, exercise, isAmrap = false); actionError = null }
    }

    fun endSession() {
        actionError = null
        controller?.requestExit() ?: fail("Phoenix core is unavailable.")
    }

    fun confirmSessionExit() {
        if (controller?.confirmExit() == true) {
            selectedRoutineId = null
            selectedJustLiftExerciseId = null
            actionError = null
        }
    }

    fun cancelSessionExit() { controller?.cancelExit(); actionError = null }
    fun retrySafeStopRecovery() { deps?.main?.retryWorkoutTeardown() ?: fail("Phoenix core is unavailable.") }
    fun handlePlayPause() { controller?.handleRemote(TvRemoteInput.PLAY_PAUSE) }
    fun handleBackDuringSession() { controller?.handleRemote(TvRemoteInput.MENU) }

    fun login(email: String, password: String) {
        val account = deps?.account ?: return fail("Cloud account service is unavailable in this build.")
        actionError = null
        if (!account.login(email, password)) fail(account.error ?: "Sign-in unavailable.")
    }

    fun resolveAccountMismatch(uploadNeverSynced: Boolean) {
        val account = deps?.account ?: return fail("Account service is unavailable.")
        actionError = null
        if (!account.resolveAccountMismatch(if (uploadNeverSynced) AccountSwitchChoice.UPLOAD_NEVER_SYNCED else AccountSwitchChoice.EXCLUDE_ALL_EXISTING)) {
            fail(account.error ?: "Identity resolution unavailable.")
        }
    }

    fun syncNow() {
        val account = deps?.account ?: return fail("Cloud Sync is unavailable in this build.")
        actionError = null
        if (!account.syncNow()) fail(account.error ?: "Sync unavailable.")
    }

    fun signOut() {
        val account = deps?.account ?: return fail("Account service is unavailable.")
        actionError = null
        if (!account.signOut()) fail(account.error ?: "Sign-out unavailable.")
    }

    fun onForeground() {
        isForeground = true
        foregroundPending = true
        controller?.onForeground()
        val dependencies = deps ?: return // Replay after required migrations and dependency startup.
        foregroundPending = false
        val account = dependencies.account ?: return
        scope.launch {
            try { account.onForeground() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("Phoenix foreground sync did not complete. Use Sync Now to retry.") }
        }
    }

    fun onBackground() {
        isForeground = false
        foregroundPending = false
        exerciseSelectionJob?.cancel()
        controller?.onBackground()
    }

    private fun startTelemetryWatchdog(main: MainViewModel) {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            val origin = TimeSource.Monotonic.markNow()
            while (true) {
                controller?.checkTelemetry(origin.elapsedNow().inWholeMilliseconds)
                delay(250)
            }
        }
    }

    private fun fail(message: String) { actionError = message }
}
