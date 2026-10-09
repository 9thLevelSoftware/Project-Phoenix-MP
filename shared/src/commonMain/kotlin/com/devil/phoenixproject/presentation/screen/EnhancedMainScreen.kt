package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.csv.CsvImportDeliveryResult
import com.devil.phoenixproject.data.csv.CsvImportIntakePhase
import com.devil.phoenixproject.data.csv.acknowledgeCsvImportOffer
import com.devil.phoenixproject.data.csv.csvImportOffers
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.ConnectionLostDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.ProfileAddDialog
import com.devil.phoenixproject.presentation.components.ProfileRecoveryDialog
import com.devil.phoenixproject.presentation.components.ProfileSwitcherSheet
import com.devil.phoenixproject.presentation.components.RoutineCsvImportDialog
import com.devil.phoenixproject.presentation.navigation.BottomNavItem
import com.devil.phoenixproject.presentation.manager.MachineSafetyUiState
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.theme.phoenixBottomNavigationContainerColor
import com.devil.phoenixproject.presentation.theme.phoenixTopAppBarContainerColor
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.presentation.util.rememberPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ProfileOverlayError
import com.devil.phoenixproject.presentation.viewmodel.ProfileSwitcherViewModel
import com.devil.phoenixproject.presentation.viewmodel.RootProfileOperationKind
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportHostResult
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportUiState
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.setKeepScreenOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_ok
import projectphoenix.shared.generated.resources.action_open
import projectphoenix.shared.generated.resources.routine_csv_import_busy
import projectphoenix.shared.generated.resources.routine_csv_imported_builder
import projectphoenix.shared.generated.resources.routine_csv_imported_builder_more
import projectphoenix.shared.generated.resources.routine_csv_saved_daily
import projectphoenix.shared.generated.resources.stop_before_editing
import projectphoenix.shared.generated.resources.workout_in_progress
import projectphoenix.shared.generated.resources.workout_save_retry_failed
import projectphoenix.shared.generated.resources.workout_save_failed
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.cd_analytics
import projectphoenix.shared.generated.resources.cd_back
import projectphoenix.shared.generated.resources.cd_home
import projectphoenix.shared.generated.resources.cd_open_profile_switcher
import projectphoenix.shared.generated.resources.cd_profile
import projectphoenix.shared.generated.resources.cd_settings
import projectphoenix.shared.generated.resources.nav_insights
import projectphoenix.shared.generated.resources.nav_profile
import projectphoenix.shared.generated.resources.profile_create_failed
import projectphoenix.shared.generated.resources.profile_recovery_retry_failed
import projectphoenix.shared.generated.resources.profile_switch_blocked_during_workout
import projectphoenix.shared.generated.resources.profile_switch_failed

/**
 * Enhanced main screen with dynamic top bar and bottom navigation.
 * Provides consistent scaffolding across all screens with:
 * - Dynamic TopAppBar (title, back button, connection status, theme toggle)
 * - Compact bottom navigation (Analytics, Insights, Home, Profile, Settings)
 * - Home opens the existing Workouts destination
 * - Conditional visibility based on current route
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedMainScreen(
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColorAvailable: Boolean,
    dynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    profileSwitcherViewModel: ProfileSwitcherViewModel = koinViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val workoutState by viewModel.workoutState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionLostDuringWorkout by viewModel.connectionLostDuringWorkout.collectAsState()
    val machineSafetyUiState by viewModel.machineSafetyUiState.collectAsState()
    val topBarTitle by viewModel.topBarTitle.collectAsState()
    val topBarBackAction by viewModel.topBarBackAction.collectAsState()

    // Dynamic title sources
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val currentRoutineName = loadedRoutine?.name ?: ""

    // For exercise detail - derive from loaded routine and current exercise index
    val currentExerciseIndex by viewModel.currentExerciseIndex.collectAsState()
    val selectedExerciseName = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)?.exercise?.name ?: ""

    // Root-owned profile switching and recovery
    val profileRepository: UserProfileRepository = koinInject()
    val profiles by profileRepository.allProfiles.collectAsState()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
    val switcherState by profileSwitcherViewModel.uiState.collectAsState()
    val readyProfileId =
        (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id
    val repositorySwitching = activeProfileContext is ActiveProfileContext.Switching
    val repositorySwitchTarget =
        (activeProfileContext as? ActiveProfileContext.Switching)?.targetProfileId
    val localSwitchTarget = switcherState.operation
        ?.takeIf { it.kind == RootProfileOperationKind.SWITCH }
        ?.targetProfileId
    val localOperationInFlight = switcherState.operation != null
    val switchingInFlight = repositorySwitching || localOperationInFlight
    val switchingTargetProfileId = repositorySwitchTarget ?: localSwitchTarget
    val hapticFeedback = LocalHapticFeedback.current

    // Ensure default profile exists
    LaunchedEffect(Unit) {
        profileRepository.ensureDefaultProfile()
    }

    // Sync status
    val syncManager: SyncManager = koinInject()
    val syncState by syncManager.syncState.collectAsState()
    val isAuthenticated by syncManager.isAuthenticated.collectAsState()
    val lastSyncTime by syncManager.lastSyncTime.collectAsState()
    val serverDeletionNotice by syncManager.serverDeletionNotice.collectAsState()
    val serverDeletionNoticeSnackbarHostState = remember { SnackbarHostState() }

    // Keep the notice pending until its snackbar has been displayed and dismissed. The
    // acknowledgement is conditional, so a newer notice merged while this one is visible
    // remains queued for the next snackbar.
    LaunchedEffect(serverDeletionNotice) {
        serverDeletionNotice?.let { notice ->
            serverDeletionNoticeSnackbarHostState.showSnackbar(
                message = notice.message,
                duration = SnackbarDuration.Long,
            )
            syncManager.clearServerDeletionNotice(notice)
        }
    }

    // F-040: a failed set commit offers Retry. Collected HERE, at the app-level
    // scaffold, because both explicit exits save asynchronously after navigating
    // away from ActiveWorkoutScreen — a collector there would be disposed before
    // the failure is raised. This is the only collector, so the offer is shown once;
    // retry/dismiss drain it with compareAndSet, leaving another session's offer intact.
    // Keyed on the OFFER, not the id: a Retry that fails again re-offers the same id
    // with a new attempt, and only a distinct value restarts the effect.
    val saveFailureOffer by viewModel.workoutSaveFailureOffer.collectAsState()
    val saveFailedMessage = stringResource(Res.string.workout_save_failed)
    val saveRetryLabel = stringResource(Res.string.action_retry)
    val saveRetryUnavailable = stringResource(Res.string.workout_save_retry_failed)
    val saveFailureScope = rememberCoroutineScope()
    LaunchedEffect(saveFailureOffer) {
        val failedSessionId = saveFailureOffer?.sessionId ?: return@LaunchedEffect
        // Indefinite: losing a set is not a message to miss.
        val action = serverDeletionNoticeSnackbarHostState.showSnackbar(
            message = saveFailedMessage,
            actionLabel = saveRetryLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (action == SnackbarResult.ActionPerformed) {
            // retryWorkoutSave drains the flow, which cancels this effect, so the
            // follow-up message runs on a scope that outlives it.
            if (!viewModel.retryWorkoutSave(failedSessionId)) {
                saveFailureScope.launch {
                    serverDeletionNoticeSnackbarHostState.showSnackbar(
                        message = saveRetryUnavailable,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        } else {
            viewModel.dismissWorkoutSaveFailure(failedSessionId)
        }
    }

    // ===== CSV import intake (#1242) =====
    // Activity-scoped import ViewModel (the bridge offers land here); Daily Routines keeps its
    // own nav-scoped instance for the in-app preview-always import, which is unchanged.
    val csvImportViewModel: RoutineCsvViewModel = koinViewModel()
    val csvImportState by csvImportViewModel.importState.collectAsState()
    val csvImportSnackbarHostState = remember { SnackbarHostState() }
    val csvImportScope = rememberCoroutineScope()
    var showCsvWorkoutDialog by remember { mutableStateOf(false) }
    var csvImportJob by remember { mutableStateOf<Job?>(null) }
    // Per-delivery consent from the dirty-editor leave gate (B2): once the user consented to
    // discard the draft for THIS delivery, navigation does not ask a second time.
    var csvLeaveConsent by remember { mutableStateOf(false) }

    /**
     * Resolves the imported routine's readiness to show (B3): the wait observes committed
     * content pinned to the plan's profile — never just an id — and a profile change cancels
     * navigation instead of retargeting it.
     */
    fun csvReadiness(routines: List<Routine>, expected: RoutineCsvImportHostResult, currentProfileId: String): CsvImportReadiness =
        csvImportNavigationReadiness(routines, expected, currentProfileId)

    // Truthful post-commit result when the builder cannot be shown: the routine WAS saved.
    suspend fun showCsvSavedDailySnackbar(result: RoutineCsvImportHostResult) {
        val message = getString(Res.string.routine_csv_saved_daily, result.firstName)
        val action = csvImportSnackbarHostState.showSnackbar(
            message = message,
            actionLabel = getString(Res.string.action_open),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (action == SnackbarResult.ActionPerformed) {
            // Open re-invokes the gate (P4): the draft may still be there and dirty.
            RoutineEditorLeaveGate.requestLeave(
                onLeft = {
                    RoutineEditorLeaveGate.registeredRoutineId()?.let { staleId ->
                        navController.popBackStack(NavigationRoutes.RoutineEditor.createRoute(staleId), inclusive = true)
                    }
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute(result.firstRoutineId))
                    csvImportScope.launch {
                        csvImportSnackbarHostState.showSnackbar(
                            getString(Res.string.routine_csv_imported_builder, result.firstName),
                            duration = SnackbarDuration.Long,
                        )
                    }
                },
                onCancelled = {
                    csvImportScope.launch {
                        csvImportSnackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
                    }
                },
            )
        }
    }

    // Opens the imported routine in the builder: a stale editor entry for a replaced routine is
    // removed only after the successful commit, and the editor that opens is freshly initialized.
    suspend fun showCsvBuilderSnackbar(result: RoutineCsvImportHostResult) {
        val message = if (result.routineCount > 1) {
            getString(Res.string.routine_csv_imported_builder_more, result.firstName, result.routineCount - 1)
        } else {
            getString(Res.string.routine_csv_imported_builder, result.firstName)
        }
        csvImportSnackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
    }

    suspend fun openImportedBuilder(result: RoutineCsvImportHostResult) {
        val open = {
            if (result.overwrite) {
                navController.popBackStack(NavigationRoutes.RoutineEditor.createRoute(result.firstRoutineId), inclusive = true)
            }
            navController.navigate(NavigationRoutes.RoutineEditor.createRoute(result.firstRoutineId))
        }
        if (!csvLeaveConsent && RoutineEditorLeaveGate.isDirty()) {
            RoutineEditorLeaveGate.requestLeave(
                onLeft = {
                    csvLeaveConsent = true
                    RoutineEditorLeaveGate.registeredRoutineId()?.let { staleId ->
                        navController.popBackStack(NavigationRoutes.RoutineEditor.createRoute(staleId), inclusive = true)
                    }
                    open()
                    csvImportScope.launch { showCsvBuilderSnackbar(result) }
                },
                onCancelled = {
                    csvImportScope.launch { showCsvSavedDailySnackbar(result) }
                },
            )
        } else {
            open()
            showCsvBuilderSnackbar(result)
        }
    }

    suspend fun handleCsvImportDelivery(result: CsvImportDeliveryResult) {
        val ownership = csvImportViewModel.intakeOwnership
        // A host result left over from a cancelled earlier delivery must never drive navigation.
        csvImportViewModel.clearHostResult()
        ownership.moveTo(CsvImportIntakePhase.WAITING_STARTUP)
        // The main graph already gates this screen (EULA/splash/migrations, then the BLE
        // permission gate); the remaining wait is the active profile.
        withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) {
            profileRepository.activeProfileContext.first { it is ActiveProfileContext.Ready }
        }
        // Workout state is checked at acceptance AND immediately before any commit.
        if (viewModel.isWorkoutActive || viewModel.isInWorkoutSessionNow()) {
            showCsvWorkoutDialog = true
            return
        }
        when (result) {
            is CsvImportDeliveryResult.Read -> {
                ownership.moveTo(CsvImportIntakePhase.PREVIEW)
                csvImportViewModel.importIncoming(result.content)
                // Clean creates commit here; a collision/issue preview suspends until the user
                // confirms (commit) or dismisses (cancellation consumes the delivery).
                val committed = csvImportViewModel.hostResult.filterNotNull().first()
                csvImportViewModel.clearHostResult()
                ownership.moveTo(CsvImportIntakePhase.RESULT)
                if (viewModel.isWorkoutActive || viewModel.isInWorkoutSessionNow()) {
                    showCsvWorkoutDialog = true
                    return
                }
                var readiness = csvReadiness(viewModel.routines.value, committed, viewModel.activeProfileId.value)
                if (readiness == CsvImportReadiness.WAIT) {
                    readiness = withTimeoutOrNull(NAV_READY_TIMEOUT_MS) {
                        viewModel.routines
                            .map { csvReadiness(it, committed, viewModel.activeProfileId.value) }
                            .first { it != CsvImportReadiness.WAIT }
                    } ?: CsvImportReadiness.WAIT
                }
                when (readiness) {
                    CsvImportReadiness.READY -> openImportedBuilder(committed)
                    // Persistence succeeded but display readiness never arrived: say the
                    // routine was saved, never that nothing was written, and never retry.
                    CsvImportReadiness.WAIT -> showCsvSavedDailySnackbar(committed)
                    CsvImportReadiness.PROFILE_CHANGED -> csvImportViewModel.onProfileChangedDuringWait()
                }
            }

            CsvImportDeliveryResult.TooLarge -> {
                csvImportViewModel.onFileTooLarge()
                awaitCancellation()
            }

            CsvImportDeliveryResult.Unreadable -> {
                csvImportViewModel.onFileUnreadable()
                awaitCancellation()
            }

            CsvImportDeliveryResult.Busy -> Unit
        }
    }

    // Dismiss of the intent-hosted dialog: writes nothing and consumes the delivery.
    fun csvDismissImport() {
        csvImportViewModel.dismissImport()
        csvImportJob?.cancel()
    }

    // Confirm of the intent-hosted preview dialog. A dirty same-id overwrite resolves the
    // editor leave gate BEFORE the commit (B2); cancel writes nothing and keeps the draft.
    fun csvConfirmImport() {
        if (viewModel.isWorkoutActive || viewModel.isInWorkoutSessionNow()) {
            csvDismissImport()
            showCsvWorkoutDialog = true
            return
        }
        val preview = csvImportViewModel.importState.value as? RoutineCsvImportUiState.Preview ?: return
        val staleId = RoutineEditorLeaveGate.registeredRoutineId()
        if (staleId != null && staleId in preview.plan.overwriteRoutineIds && RoutineEditorLeaveGate.isDirty()) {
            RoutineEditorLeaveGate.requestLeave(
                onLeft = {
                    csvLeaveConsent = true
                    csvImportViewModel.confirmImport()
                },
                onCancelled = { /* nothing written; the draft stays */ },
            )
        } else {
            csvImportViewModel.confirmImport()
        }
    }

    LaunchedEffect(Unit) {
        csvImportOffers().collect { offer ->
            val ownership = csvImportViewModel.intakeOwnership
            val result = offer.result
            if (result is CsvImportDeliveryResult.Busy) {
                acknowledgeCsvImportOffer(offer.deliveryId)
                csvImportSnackbarHostState.showSnackbar(getString(Res.string.routine_csv_import_busy))
                return@collect
            }
            // One active intake state machine: a second delivery is rejected with a localized
            // message requiring a re-share, and a consumed delivery never re-commits (B4).
            if (!ownership.begin(offer.deliveryId)) {
                acknowledgeCsvImportOffer(offer.deliveryId)
                if (!ownership.isConsumed(offer.deliveryId)) {
                    csvImportSnackbarHostState.showSnackbar(getString(Res.string.routine_csv_import_busy))
                }
                return@collect
            }
            acknowledgeCsvImportOffer(offer.deliveryId)
            csvLeaveConsent = false
            csvImportJob?.cancel()
            csvImportJob = csvImportScope.launch {
                try {
                    handleCsvImportDelivery(result)
                } finally {
                    ownership.markConsumed(offer.deliveryId)
                }
            }
        }
    }

    var currentRoute by remember(navController) {
        mutableStateOf(navController.currentBackStackEntry?.destination?.route ?: NavigationRoutes.Home.route)
    }
    // Track navigation changes
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            currentRoute = backStackEntry.destination.route ?: NavigationRoutes.Home.route
        }
    }

    LaunchedEffect(currentRoute, workoutState, navController) {
        // Issue #627: Guard against bouncing back to ActiveWorkout during async stop teardown.
        // stopWorkout() sets stopWorkoutInProgress synchronously (before the scope.launch), but
        // clears workoutState only inside that coroutine (~ActiveSessionEngine line 3104). During
        // that async window, shouldResumeActiveWorkout() is still true, so without this guard the
        // observer would navigate back to a dead screen after the pop.
        // When exitingWorkout=true, once the coroutine completes, workoutState becomes Idle and
        // shouldResumeActiveWorkout() returns false. When exitingWorkout=false (Just Lift), the
        // teardown lands on SetSummary (still resumable), so the guard stays load-bearing until
        // the next startWorkout() resets it.
        //
        // Legitimate resume path (backgrounded mid-set, no stop pending):
        // stopWorkoutInProgress is false → guard passes → navigation fires correctly.
        //
        // Note: isStoppingWorkout() is read imperatively here, not as an effect key. Any reset
        // path that doesn't also change workoutState or route won't re-trigger this effect; keep
        // resets paired with state transitions to avoid missed re-evaluations.
        if (shouldResumeActiveWorkout(workoutState) && currentRoute != NavigationRoutes.ActiveWorkout.route && !viewModel.isStoppingWorkout()) {
            navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                launchSingleTop = true
            }
        }
    }

    // Issue #348: Session-scoped wake lock — keeps screen on across ALL workout screens
    // (ActiveWorkout, SetReady, rest timers) instead of just ActiveWorkoutScreen.
    // Just Lift also keeps the screen awake on its ready/setup screen because
    // it is armed for handle-based auto-start before workoutState becomes Active.
    val isInWorkoutSession by viewModel.isInWorkoutSession.collectAsState(initial = false)
    val shouldKeepScreenOn = shouldKeepScreenOnForRoute(currentRoute, isInWorkoutSession)
    DisposableEffect(shouldKeepScreenOn) {
        setKeepScreenOn(shouldKeepScreenOn)
        onDispose {
            setKeepScreenOn(false)
        }
    }

    val profileTitle = stringResource(Res.string.nav_profile)
    val profileContentDescription = stringResource(Res.string.cd_profile)
    val openProfileSwitcherDescription = stringResource(Res.string.cd_open_profile_switcher)
    val switchFailedMessage = stringResource(Res.string.profile_switch_failed)
    val switchBlockedDuringWorkoutMessage = stringResource(Res.string.profile_switch_blocked_during_workout)
    val createFailedMessage = stringResource(Res.string.profile_create_failed)
    val recoveryRetryFailedMessage = stringResource(Res.string.profile_recovery_retry_failed)

    // Helper function to determine if the Home tab owns the current workout-flow route
    val isHomeRoute = remember(currentRoute) {
        currentRoute == NavigationRoutes.Home.route ||
            currentRoute == NavigationRoutes.JustLift.route ||
            isSingleExerciseRoute(currentRoute) ||
            currentRoute == NavigationRoutes.DailyRoutines.route ||
            currentRoute == NavigationRoutes.ActiveWorkout.route ||
            currentRoute == NavigationRoutes.TrainingCycles.route ||
            currentRoute.startsWith(NavigationRoutes.CycleEditor.route.replace("/{cycleId}", ""))
    }

    // Always show TopBar unless in Active Workout or RoutineComplete (HUD handles it)
    val shouldShowTopBar = remember(currentRoute) {
        currentRoute != NavigationRoutes.ActiveWorkout.route &&
            currentRoute != NavigationRoutes.RoutineComplete.route
    }

    // Show BottomBar only for main tabs
    val shouldShowBottomBar = remember(currentRoute) {
        currentRoute == NavigationRoutes.Home.route ||
            currentRoute == NavigationRoutes.DailyRoutines.route ||
            currentRoute == NavigationRoutes.TrainingCycles.route ||
            currentRoute == NavigationRoutes.Analytics.route ||
            currentRoute == NavigationRoutes.SmartInsights.route ||
            currentRoute == NavigationRoutes.Profile.route ||
            currentRoute == NavigationRoutes.Settings.route
    }

    // Show back button for all screens except Home
    val showBackButton = remember(currentRoute) {
        currentRoute != NavigationRoutes.Home.route
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
        val platformAccessibilitySettings = rememberPlatformAccessibilitySettings()

        CompositionLocalProvider(
            LocalWindowSizeClass provides windowSizeClass,
            LocalPlatformAccessibilitySettings provides platformAccessibilitySettings,
        ) {
            val useCompactTopBar = isCompactAccessibilityLayout() ||
                windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
            // Cycle editor and review publish the cycle name through topBarTitle.
            // On those routes that name wins; a blank name uses the static fallback.
            val fullTopBarTitle = cycleShellTitle(currentRoute, topBarTitle)
                ?: if (topBarTitle.isNotEmpty()) {
                    topBarTitle
                } else {
                    getScreenTitle(
                        route = currentRoute,
                        profileTitle = profileTitle,
                        routineName = currentRoutineName,
                        exerciseName = selectedExerciseName,
                    )
                }
            val visibleTopBarTitle = if (useCompactTopBar) {
                getCompactScreenTitle(currentRoute, fullTopBarTitle)
            } else {
                fullTopBarTitle
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    Box {
                        SnackbarHost(hostState = serverDeletionNoticeSnackbarHostState)
                        // Feature snackbar host (#1242): a separate host, never the
                        // server-deletion scaffold host above.
                        SnackbarHost(hostState = csvImportSnackbarHostState)
                    }
                },
                topBar = {
                    if (shouldShowTopBar) {
                        TopAppBar(
                            modifier = Modifier.statusBarsPadding(),
                            title = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    Text(
                                        text = visibleTopBarTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Project Phoenix",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFFF97316),
                                                    Color(0xFFEF4444),
                                                ),
                                            ),
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            },
                            navigationIcon = {
                                if (showBackButton) {
                                    IconButton(
                                        onClick = {
                                            when (currentRoute) {
                                                NavigationRoutes.RoutineOverview.route -> {
                                                    // Action is null during registration race windows (first
                                                    // frame / nav-out); fall back so the tap is never swallowed.
                                                    topBarBackAction?.invoke() ?: navController.navigateUp()
                                                }

                                                NavigationRoutes.SetReady.route -> {
                                                    viewModel.returnToOverview()
                                                    navController.navigateUp()
                                                }

                                                else -> {
                                                    if (topBarBackAction != null) {
                                                        topBarBackAction?.invoke()
                                                    } else {
                                                        navController.navigateUp()
                                                    }
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(Res.string.cd_back),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = phoenixTopAppBarContainerColor(MaterialTheme.colorScheme),
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            actions = {
                                // Cloud sync status icon
                                SyncStatusIcon(
                                    syncState = syncState,
                                    isAuthenticated = isAuthenticated,
                                    lastSyncTime = lastSyncTime,
                                    onErrorTap = {
                                        navController.navigate(NavigationRoutes.LinkAccount.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                )

                                // Connection status icon with text label
                                ConnectionStatusIndicator(
                                    connectionState = connectionState,
                                    compact = useCompactTopBar,
                                    onToggleConnection = {
                                        if (connectionState is ConnectionState.Connected) {
                                            viewModel.disconnect()
                                        } else {
                                            viewModel.ensureConnection(
                                                onConnected = {},
                                                onFailed = {},
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
                bottomBar = {
                    if (shouldShowBottomBar) {
                        PhoenixBottomNavigationBar(
                            currentRoute = currentRoute,
                            isHomeRoute = isHomeRoute,
                            isCompactHeight = useCompactTopBar,
                            analyticsContentDescription = stringResource(Res.string.cd_analytics),
                            homeContentDescription = stringResource(Res.string.cd_home),
                            insightsContentDescription = stringResource(Res.string.nav_insights),
                            profileContentDescription = profileContentDescription,
                            openProfileSwitcherDescription = openProfileSwitcherDescription,
                            settingsContentDescription = stringResource(Res.string.cd_settings),
                            onAnalyticsClick = {
                                if (currentRoute != NavigationRoutes.Analytics.route) {
                                    navController.navigate(NavigationRoutes.Analytics.route) {
                                        popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            onHomeClick = {
                                if (currentRoute != NavigationRoutes.Home.route) {
                                    navController.navigate(NavigationRoutes.Home.route) {
                                        popUpTo(NavigationRoutes.Home.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onInsightsClick = {
                                if (currentRoute != NavigationRoutes.SmartInsights.route) {
                                    navController.navigate(NavigationRoutes.SmartInsights.route) {
                                        popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            onProfileClick = {
                                if (currentRoute != NavigationRoutes.Profile.route) {
                                    navController.navigate(NavigationRoutes.Profile.route) {
                                        popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            onProfileLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                profileSwitcherViewModel.openSwitcher()
                            },
                            onSettingsClick = {
                                if (currentRoute != NavigationRoutes.Settings.route) {
                                    navController.navigate(NavigationRoutes.Settings.route) {
                                        popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                // Global haptic feedback effect - ensures sounds/haptics work on all screens
                HapticFeedbackEffect(hapticEvents = viewModel.hapticEvents)

                Box(modifier = Modifier.fillMaxSize()) {
                    // Use proper padding to account for TopAppBar and system bars
                    NavGraph(
                        navController = navController,
                        viewModel = viewModel,
                        exerciseRepository = exerciseRepository,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        dynamicColorAvailable = dynamicColorAvailable,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onDynamicColorEnabledChange = onDynamicColorEnabledChange,
                        onOpenProfileSwitcher = profileSwitcherViewModel::openSwitcher,
                        onProfileRecoveryRequired = profileSwitcherViewModel::requireRecovery,
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            // Show connection lost alert during workout (Issue #43)
            if (connectionLostDuringWorkout || machineSafetyUiState is MachineSafetyUiState.Visible) {
                ConnectionLostDialog(
                    onReconnect = {
                        viewModel.requestMachineSafetyRecovery(
                            (machineSafetyUiState as? MachineSafetyUiState.Visible)?.identity,
                        )
                    },
                    onDismiss = {
                        viewModel.dismissMachineSafetyWarning()
                        viewModel.dismissConnectionLostAlert()
                    },
                    onAcknowledgeUnloaded = (machineSafetyUiState as? MachineSafetyUiState.Visible)?.let { visible ->
                        {
                            viewModel.acknowledgeMachineSafetyUnloaded(visible.identity)
                            viewModel.dismissConnectionLostAlert()
                        }
                    },
                )
            }

            if (switcherState.showSwitcher) {
                ProfileSwitcherSheet(
                    profiles = profiles,
                    activeProfileId = readyProfileId,
                    switchingInFlight = switchingInFlight,
                    switchingTargetProfileId = switchingTargetProfileId,
                    errorMessage = when (switcherState.error) {
                        ProfileOverlayError.SWITCH_FAILED -> switchFailedMessage
                        ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT -> switchBlockedDuringWorkoutMessage
                        else -> null
                    },
                    onSelectProfile = { profile ->
                        profileSwitcherViewModel.switchProfile(profile.id, viewModel::isInWorkoutSessionNow)
                    },
                    onAddProfile = profileSwitcherViewModel::openAddDialog,
                    onDismiss = profileSwitcherViewModel::dismissSwitcher,
                )
            }

            if (switcherState.showAddDialog) {
                ProfileAddDialog(
                    existingProfileCount = profiles.size,
                    isSubmitting = switchingInFlight,
                    errorMessage = when (switcherState.error) {
                        ProfileOverlayError.CREATE_FAILED -> createFailedMessage
                        ProfileOverlayError.SWITCH_BLOCKED_DURING_WORKOUT -> switchBlockedDuringWorkoutMessage
                        else -> null
                    },
                    onConfirm = { name, colorIndex ->
                        profileSwitcherViewModel.createAndActivateProfile(name, colorIndex, viewModel::isInWorkoutSessionNow)
                    },
                    onDismiss = profileSwitcherViewModel::dismissAddDialog,
                )
            }

            if (switcherState.recoveryRequired) {
                ProfileRecoveryDialog(
                    isRetrying = switcherState.operation?.kind == RootProfileOperationKind.RECOVERY,
                    errorMessage = recoveryRetryFailedMessage.takeIf {
                        switcherState.error == ProfileOverlayError.RECOVERY_RETRY_FAILED
                    },
                    onRetry = profileSwitcherViewModel::retryRecovery,
                )
            }

            // CSV import intake dialogs (#1242): Unreadable and Preview only. The intent host
            // never shows the Imported count dialog — the feature snackbar carries the result.
            val csvImportDialogState = csvImportState
            if (csvImportDialogState != null && csvImportDialogState !is RoutineCsvImportUiState.Imported) {
                RoutineCsvImportDialog(
                    state = csvImportDialogState,
                    onSelectMode = csvImportViewModel::selectMode,
                    onConfirm = ::csvConfirmImport,
                    onDismiss = ::csvDismissImport,
                )
            }

            // Active-workout refusal for intent imports (#1242): existing copy, nothing
            // imported, the delivery is consumed with no auto-retry when the workout ends.
            if (showCsvWorkoutDialog) {
                AlertDialog(
                    onDismissRequest = { showCsvWorkoutDialog = false },
                    title = { Text(stringResource(Res.string.workout_in_progress)) },
                    text = { Text(stringResource(Res.string.stop_before_editing)) },
                    confirmButton = {
                        TextButton(onClick = { showCsvWorkoutDialog = false }) {
                            Text(stringResource(Res.string.action_ok))
                        }
                    },
                )
            }
        } // CompositionLocalProvider
    } // BoxWithConstraints
}

@Composable
private fun PhoenixBottomNavigationBar(
    currentRoute: String,
    isHomeRoute: Boolean,
    isCompactHeight: Boolean,
    analyticsContentDescription: String,
    homeContentDescription: String,
    insightsContentDescription: String,
    profileContentDescription: String,
    openProfileSwitcherDescription: String,
    settingsContentDescription: String,
    onAnalyticsClick: () -> Unit,
    onHomeClick: () -> Unit,
    onInsightsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onProfileLongClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val containerColor = phoenixBottomNavigationContainerColor(MaterialTheme.colorScheme)
    val barHeight = remember(isCompactHeight) { if (isCompactHeight) 56.dp else 60.dp }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem.entries.forEach { item ->
                val icon = when (item) {
                    BottomNavItem.ANALYTICS -> Icons.Default.BarChart
                    BottomNavItem.INSIGHTS -> Icons.Default.AutoAwesome
                    BottomNavItem.HOME -> Icons.Default.Home
                    BottomNavItem.PROFILE -> Icons.Default.Person
                    BottomNavItem.SETTINGS -> Icons.Default.Settings
                }
                val contentDescription = when (item) {
                    BottomNavItem.ANALYTICS -> analyticsContentDescription
                    BottomNavItem.INSIGHTS -> insightsContentDescription
                    BottomNavItem.HOME -> homeContentDescription
                    BottomNavItem.PROFILE -> profileContentDescription
                    BottomNavItem.SETTINGS -> settingsContentDescription
                }
                val selected = when (item) {
                    BottomNavItem.ANALYTICS -> currentRoute == NavigationRoutes.Analytics.route
                    BottomNavItem.INSIGHTS -> currentRoute == NavigationRoutes.SmartInsights.route
                    BottomNavItem.HOME -> isHomeRoute
                    BottomNavItem.PROFILE -> currentRoute == NavigationRoutes.Profile.route
                    BottomNavItem.SETTINGS -> currentRoute == NavigationRoutes.Settings.route
                }
                val testTag = when (item) {
                    BottomNavItem.ANALYTICS -> com.devil.phoenixproject.presentation.util.TestTags.NAV_ANALYTICS
                    BottomNavItem.INSIGHTS -> com.devil.phoenixproject.presentation.util.TestTags.NAV_INSIGHTS
                    BottomNavItem.HOME -> com.devil.phoenixproject.presentation.util.TestTags.NAV_HOME
                    BottomNavItem.PROFILE -> com.devil.phoenixproject.presentation.util.TestTags.NAV_PROFILE
                    BottomNavItem.SETTINGS -> com.devil.phoenixproject.presentation.util.TestTags.NAV_SETTINGS
                }
                val onClick = when (item) {
                    BottomNavItem.ANALYTICS -> onAnalyticsClick
                    BottomNavItem.INSIGHTS -> onInsightsClick
                    BottomNavItem.HOME -> onHomeClick
                    BottomNavItem.PROFILE -> onProfileClick
                    BottomNavItem.SETTINGS -> onSettingsClick
                }
                val itemLongClick = when (item) {
                    BottomNavItem.PROFILE -> onProfileLongClick
                    BottomNavItem.ANALYTICS,
                    BottomNavItem.INSIGHTS,
                    BottomNavItem.HOME,
                    BottomNavItem.SETTINGS,
                    -> null
                }
                PhoenixBottomNavigationItem(
                    icon = icon,
                    contentDescription = contentDescription,
                    selected = selected,
                    testTag = testTag,
                    onClick = onClick,
                    modifier = Modifier.weight(1f),
                    onLongClick = itemLongClick,
                    longClickLabel = openProfileSwitcherDescription.takeIf {
                        item == BottomNavItem.PROFILE
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoenixBottomNavigationItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val selectedContainerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        Color.Transparent
    }
    val iconColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .background(selectedContainerColor)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Tab
                this.selected = selected
                this.onClick(label = contentDescription) {
                    onClick()
                    true
                }
                if (onLongClick != null && longClickLabel != null) {
                    this.onLongClick(label = longClickLabel) {
                        onLongClick()
                        true
                    }
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = iconColor,
        )
    }
}

/**
 * Subtle cloud sync status icon for the TopAppBar.
 * Shows sync state as a small icon with no text.
 *
 * Visibility rules:
 * - Hidden when not authenticated
 * - Hidden when never synced (lastSyncTime == 0) unless currently syncing
 * - Visible otherwise with state-dependent icon and color
 */
@Composable
private fun SyncStatusIcon(syncState: SyncState, isAuthenticated: Boolean, lastSyncTime: Long, onErrorTap: () -> Unit) {
    // Track a local display state to handle Success -> Idle transition with delay
    var displayState by remember { mutableStateOf(syncState) }

    // Keep displayState in sync with external syncState, but delay Success -> Idle
    LaunchedEffect(syncState) {
        displayState = syncState
        if (syncState is SyncState.Success) {
            kotlinx.coroutines.delay(2000)
            displayState = SyncState.Idle
        }
    }

    // Visibility: hidden when not authenticated, or never synced and not actively syncing
    val isVisible = isAuthenticated &&
        (lastSyncTime > 0L || syncState is SyncState.Syncing || syncState is SyncState.Success)
    if (!isVisible) return

    // Spinning animation for syncing state
    val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "syncRotation",
    )

    // Success pulse animation (brief green pulse that fades)
    val successAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (displayState is SyncState.Success) 1f else 0f,
        animationSpec = if (displayState is SyncState.Success) {
            tween(durationMillis = 300)
        } else {
            tween(durationMillis = 600)
        },
        label = "successPulse",
    )

    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val accentColor = MaterialTheme.colorScheme.primary
    val successColor = AccessibilityTheme.colors.success
    val errorColor = AccessibilityTheme.colors.error

    val icon: ImageVector
    val tint: Color
    val applyRotation: Boolean
    val clickable: Boolean

    when (displayState) {
        is SyncState.Syncing -> {
            icon = Icons.Default.Sync
            tint = accentColor
            applyRotation = true
            clickable = false
        }

        is SyncState.Success -> {
            icon = Icons.Default.CloudDone
            // Interpolate between success green and muted based on pulse
            tint = if (successAlpha > 0.5f) successColor else mutedColor
            applyRotation = false
            clickable = false
        }

        is SyncState.Error -> {
            icon = Icons.Default.CloudOff
            tint = errorColor
            applyRotation = false
            clickable = true
        }

        is SyncState.PartialSuccess -> {
            // Partial success (push OK, pull failed) -- show warning indicator
            icon = Icons.Default.CloudDone // Push succeeded, so show "done" but in warning color
            tint = MaterialTheme.colorScheme.tertiary
            applyRotation = false
            clickable = true // Allow tap to retry pull
        }

        else -> {
            // Idle, NotAuthenticated, NotPremium -- show muted cloud checkmark
            icon = Icons.Default.CloudDone
            tint = mutedColor
            applyRotation = false
            clickable = false
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .then(
                if (clickable) {
                    Modifier.clickable(
                        onClick = onErrorTap,
                        role = Role.Button,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (displayState) {
                is SyncState.Syncing -> "Syncing"
                is SyncState.Success -> "Sync complete"
                is SyncState.Error -> "Sync error, tap to fix"
                is SyncState.PartialSuccess -> "Partial sync, tap to retry"
                else -> "Cloud sync"
            },
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .then(
                    if (applyRotation) {
                        Modifier.graphicsLayer { rotationZ = rotation }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * Connection status button with clear text labels and animated gradient when connecting.
 * States:
 * 1. Blue "Click to Connect" - disconnected/idle
 * 2. Animated blue-green gradient "Connecting..." - connecting/scanning
 * 3. Green "Connected" - connected
 * 4. Red "Reconnect" - error or connection lost
 */
@Composable
private fun ConnectionStatusIndicator(
    connectionState: ConnectionState,
    compact: Boolean,
    onToggleConnection: () -> Unit,
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Scanning
    val isError = connectionState is ConnectionState.Error

    // Animated gradient offset for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "connecting")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gradientOffset",
    )

    val buttonText = if (compact) {
        when {
            isConnected -> "Connected"
            isConnecting -> "Connecting"
            isError -> "Retry"
            else -> "Connect"
        }
    } else {
        when {
            isConnected -> "Connected"
            isConnecting -> "Connecting..."
            isError -> "Reconnect"
            else -> "Click to Connect"
        }
    }

    val contentDescription = when {
        isConnected -> "Connected to machine. Tap to disconnect"
        isConnecting -> "Connecting to machine"
        isError -> "Connection error. Tap to reconnect"
        else -> "Tap to connect to machine"
    }

    // Connection status colors from AccessibilityTheme
    val blueColor = Color(0xFF3B82F6) // Blue -- informational, not semantic status
    val greenColor = AccessibilityTheme.colors.success
    val redColor = AccessibilityTheme.colors.error

    // Touch target wrapper: minimumInteractiveComponentSize ensures ≥48×48dp interactive area
    // (transparent — no visual background here)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .widthIn(max = if (compact) 124.dp else 180.dp)
            .padding(end = 8.dp)
            .clickable(
                onClick = onToggleConnection,
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Visual pill: restored to pre-Phase-1 32dp height.
        // shapes.medium = 20dp radius; with height=32dp the radius exceeds height/2 (16dp),
        // so path corners are clamped to 16dp → proper stadium/pill shape. Keep shapes.medium.
        Box(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(MaterialTheme.shapes.medium)
                .then(
                    if (isConnecting) {
                        // Animated gradient background for connecting state
                        Modifier.background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    blueColor,
                                    greenColor,
                                    blueColor,
                                    greenColor,
                                    blueColor,
                                ),
                                startX = -200f + (gradientOffset * 600f),
                                endX = 200f + (gradientOffset * 600f),
                            ),
                        )
                    } else {
                        // Static background for other states
                        Modifier.background(
                            color = when {
                                isConnected -> greenColor
                                isError -> redColor
                                else -> blueColor
                            },
                        )
                    },
                )
                .padding(horizontal = if (compact) 8.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = buttonText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** B3 navigation-wait outcome for an imported routine. */
internal enum class CsvImportReadiness { READY, WAIT, PROFILE_CHANGED }

/**
 * The navigation wait observes committed CONTENT, not just an id (B3): for a new routine the
 * list must contain the committed id; for an overwrite the emitted routine must reflect the
 * committed content/revision. Profile identity is pinned to the plan's profile — a profile
 * change cancels navigation instead of retargeting it.
 */
internal fun csvImportNavigationReadiness(
    routines: List<Routine>,
    expected: RoutineCsvImportHostResult,
    currentProfileId: String,
): CsvImportReadiness {
    if (currentProfileId != expected.profileId) return CsvImportReadiness.PROFILE_CHANGED
    val routine = routines.firstOrNull { it.id == expected.firstRoutineId } ?: return CsvImportReadiness.WAIT
    val freshEnough = (routine.updatedAt ?: 0L) >= expected.committedUpdatedAt
    return if (freshEnough && routine.name == expected.firstName) {
        CsvImportReadiness.READY
    } else {
        CsvImportReadiness.WAIT
    }
}

/** Finite display-readiness bound; its failure path is "saved but could not be opened" (B3). */
private const val NAV_READY_TIMEOUT_MS = 5_000L

/** How long an intake waits for the active profile before the profile-switching message. */
private const val PROFILE_READY_TIMEOUT_MS = 15_000L

/**
 * Shell title for the cycle editor and review routes.
 *
 * [cycleName] is the title those screens publish (the editor's cycle name, or the
 * review route's cycle name). A non-blank name is shown trimmed. A blank name uses
 * the static fallback. Returns null for every other route.
 */
internal fun cycleShellTitle(route: String, cycleName: String): String? {
    val fallback = when {
        route.startsWith("cycle_editor") -> "Training Cycle"
        route.startsWith("cycleReview") -> "Cycle Review"
        else -> return null
    }
    return cycleName.trim().ifEmpty { fallback }
}

/**
 * Compact top-bar label for cycle routes. A real cycle name is kept (the bar
 * ellipsizes it). The static fallbacks stay short.
 */
internal fun compactCycleShellTitle(route: String, title: String): String? = when {
    route.startsWith("cycle_editor") ->
        if (title.isBlank() || title == "Training Cycle") "Cycle" else title
    route.startsWith("cycleReview") ->
        if (title.isBlank() || title == "Cycle Review") "Review" else title
    else -> null
}

private fun isSingleExerciseRoute(route: String): Boolean = route == NavigationRoutes.SingleExercise.route ||
    route.startsWith("${NavigationRoutes.SingleExercise.route}/")

internal fun shouldKeepScreenOnForRoute(route: String, isInWorkoutSession: Boolean): Boolean =
    isInWorkoutSession || route == NavigationRoutes.JustLift.route

/**
 * Screen title for routes that do not publish one.
 * Routine and exercise flows fill in the loaded name. Cycle editor and review
 * titles are resolved earlier by [cycleShellTitle].
 */
private fun getScreenTitle(
    route: String,
    profileTitle: String,
    routineName: String = "",
    exerciseName: String = "",
): String = when {
    // Main tabs (static titles)
    route == NavigationRoutes.Home.route -> "Choose Your Workout"

    route == NavigationRoutes.DailyRoutines.route -> "Daily Routines"

    route == NavigationRoutes.TrainingCycles.route -> "Training Cycles"

    route == NavigationRoutes.Analytics.route -> "Analytics"

    route == NavigationRoutes.SmartInsights.route -> "Smart Insights"

    route == NavigationRoutes.Profile.route -> profileTitle

    route == NavigationRoutes.Settings.route -> "Settings"

    route == NavigationRoutes.EquipmentRack.route -> "Equipment Rack"

    route == NavigationRoutes.JustLift.route -> "Just Lift"

    isSingleExerciseRoute(route) -> "Single Exercise"

    // Routine flow (dynamic - uses routine name)
    route == NavigationRoutes.RoutineOverview.route -> routineName.ifEmpty { "Routine" }

    route == NavigationRoutes.SetReady.route -> routineName.ifEmpty { "Routine" }

    // Exercise detail (dynamic - uses exercise name)
    route.startsWith("exercise_detail") -> exerciseName.ifEmpty { "Exercise" }

    // Routine editor (dynamic - uses routine name)
    route.startsWith("routine_editor") -> routineName.ifEmpty { "Edit Routine" }

    // Static titles
    route == NavigationRoutes.Badges.route -> "Achievements"

    route == NavigationRoutes.ConnectionLogs.route -> "Connection Logs"

    route == NavigationRoutes.Diagnostics.route -> "Diagnostics"

    route == NavigationRoutes.RoutineComplete.route -> "Complete"

    // Active workout - hidden, but provide fallback
    route == NavigationRoutes.ActiveWorkout.route -> "Workout"

    // Fallback
    else -> "Project Phoenix"
}

private fun getCompactScreenTitle(route: String, title: String): String {
    compactCycleShellTitle(route, title)?.let { return it }
    return when {
        route == NavigationRoutes.Home.route -> "Workouts"
        route == NavigationRoutes.DailyRoutines.route -> "Routines"
        route == NavigationRoutes.TrainingCycles.route -> "Cycles"
        route == NavigationRoutes.SmartInsights.route -> "Insights"
        route == NavigationRoutes.Profile.route -> title
        route == NavigationRoutes.EquipmentRack.route -> "Rack"
        isSingleExerciseRoute(route) -> "Exercise"
        route.startsWith("routine_editor") -> "Edit"
        else -> title
    }
}

private fun shouldResumeActiveWorkout(workoutState: WorkoutState): Boolean = when (workoutState) {
    is WorkoutState.Initializing,
    is WorkoutState.Countdown,
    is WorkoutState.Active,
    is WorkoutState.Resting,
    is WorkoutState.SetSummary,
    is WorkoutState.BodyweightRepEntry,
    -> true

    else -> false
}
