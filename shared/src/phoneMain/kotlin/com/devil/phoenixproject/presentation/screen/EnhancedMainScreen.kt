package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.ConnectionLostDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.ProfileAddDialog
import com.devil.phoenixproject.presentation.components.ProfileColors
import com.devil.phoenixproject.presentation.components.ProfileRecoveryDialog
import com.devil.phoenixproject.presentation.components.ProfileSwitcherSheet
import com.devil.phoenixproject.presentation.components.normalizedProfileColorIndex
import com.devil.phoenixproject.presentation.navigation.BottomNavItem
import com.devil.phoenixproject.presentation.manager.MachineSafetyUiState
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.theme.phoenixBottomNavigationContainerColor
import com.devil.phoenixproject.presentation.theme.phoenixTopAppBarContainerColor
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.presentation.util.rememberPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ProfileOverlayError
import com.devil.phoenixproject.presentation.viewmodel.ProfileSwitcherViewModel
import com.devil.phoenixproject.presentation.viewmodel.RootProfileOperationKind
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.setKeepScreenOn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.cd_analytics
import projectphoenix.shared.generated.resources.cd_back
import projectphoenix.shared.generated.resources.cd_cloud_sync
import projectphoenix.shared.generated.resources.external_workouts
import projectphoenix.shared.generated.resources.cd_home
import projectphoenix.shared.generated.resources.cd_open_profile_switcher
import projectphoenix.shared.generated.resources.cd_profile
import projectphoenix.shared.generated.resources.cd_settings
import projectphoenix.shared.generated.resources.connecting
import projectphoenix.shared.generated.resources.custom_equipment_title
import projectphoenix.shared.generated.resources.cycles_title
import projectphoenix.shared.generated.resources.diagnostics_title
import projectphoenix.shared.generated.resources.equipment_rack_title
import projectphoenix.shared.generated.resources.external_activities_title
import projectphoenix.shared.generated.resources.external_measurements
import projectphoenix.shared.generated.resources.external_programs_title
import projectphoenix.shared.generated.resources.external_routines_title
import projectphoenix.shared.generated.resources.home_action_cycles
import projectphoenix.shared.generated.resources.home_action_routines
import projectphoenix.shared.generated.resources.hrm_status_connected
import projectphoenix.shared.generated.resources.insights_title
import projectphoenix.shared.generated.resources.integrations_data_title
import projectphoenix.shared.generated.resources.just_lift
import projectphoenix.shared.generated.resources.label_equipment
import projectphoenix.shared.generated.resources.main_cd_connected
import projectphoenix.shared.generated.resources.main_cd_connecting
import projectphoenix.shared.generated.resources.main_cd_error
import projectphoenix.shared.generated.resources.main_cd_tap_connect
import projectphoenix.shared.generated.resources.main_connect_action
import projectphoenix.shared.generated.resources.main_connect_connecting
import projectphoenix.shared.generated.resources.main_connect_full_click
import projectphoenix.shared.generated.resources.main_sync_complete
import projectphoenix.shared.generated.resources.main_sync_error_tap
import projectphoenix.shared.generated.resources.main_sync_partial_retry
import projectphoenix.shared.generated.resources.main_syncing
import projectphoenix.shared.generated.resources.nav_achievements
import projectphoenix.shared.generated.resources.nav_activities_short
import projectphoenix.shared.generated.resources.nav_analytics
import projectphoenix.shared.generated.resources.nav_app_fallback
import projectphoenix.shared.generated.resources.nav_assessment_short
import projectphoenix.shared.generated.resources.nav_choose_workout
import projectphoenix.shared.generated.resources.nav_complete
import projectphoenix.shared.generated.resources.nav_cycle_review
import projectphoenix.shared.generated.resources.nav_cycle_short
import projectphoenix.shared.generated.resources.nav_daily_routines
import projectphoenix.shared.generated.resources.nav_data_short
import projectphoenix.shared.generated.resources.nav_edit_routine
import projectphoenix.shared.generated.resources.nav_edit_short
import projectphoenix.shared.generated.resources.nav_exercise
import projectphoenix.shared.generated.resources.nav_insights
import projectphoenix.shared.generated.resources.nav_logs_short
import projectphoenix.shared.generated.resources.nav_metrics_short
import projectphoenix.shared.generated.resources.nav_playground_short
import projectphoenix.shared.generated.resources.nav_portal_short
import projectphoenix.shared.generated.resources.nav_profile
import projectphoenix.shared.generated.resources.nav_program
import projectphoenix.shared.generated.resources.nav_program_playground
import projectphoenix.shared.generated.resources.nav_programs_short
import projectphoenix.shared.generated.resources.nav_rack_short
import projectphoenix.shared.generated.resources.nav_review_short
import projectphoenix.shared.generated.resources.nav_routine
import projectphoenix.shared.generated.resources.nav_single_exercise
import projectphoenix.shared.generated.resources.nav_strength_assessment
import projectphoenix.shared.generated.resources.nav_training_cycle
import projectphoenix.shared.generated.resources.nav_workout
import projectphoenix.shared.generated.resources.phoenix_portal
import projectphoenix.shared.generated.resources.profile_create_failed
import projectphoenix.shared.generated.resources.profile_recovery_retry_failed
import projectphoenix.shared.generated.resources.profile_switch_blocked_during_workout
import projectphoenix.shared.generated.resources.profile_switch_failed
import projectphoenix.shared.generated.resources.reconnect
import projectphoenix.shared.generated.resources.settings_connection_logs
import projectphoenix.shared.generated.resources.settings_integrations
import projectphoenix.shared.generated.resources.settings_title
import projectphoenix.shared.generated.resources.workout_save_failed
import projectphoenix.shared.generated.resources.workout_save_retry_failed

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
    val connectionError by viewModel.connectionError.collectAsState()
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
    val readyProfile = (activeProfileContext as? ActiveProfileContext.Ready)?.profile
    val readyProfileId = readyProfile?.id
    val activeProfileBadgeColor = readyProfile?.let {
        ProfileColors[normalizedProfileColorIndex(it.colorIndex)]
    }
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
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep the notice pending until its snackbar has been displayed and dismissed. The
    // acknowledgement is conditional, so a newer notice merged while this one is visible
    // remains queued for the next snackbar.
    LaunchedEffect(serverDeletionNotice) {
        serverDeletionNotice?.let { notice ->
            snackbarHostState.showSnackbar(
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
        val action = snackbarHostState.showSnackbar(
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
                    snackbarHostState.showSnackbar(
                        message = saveRetryUnavailable,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        } else {
            viewModel.dismissWorkoutSaveFailure(failedSessionId)
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

    // Show back button only on non-bottom-bar sub-screens (never on root 5 tabs)
    val showBackButton = remember(shouldShowBottomBar) {
        !shouldShowBottomBar
    }

    val routeOwnsStaticTitle = remember(currentRoute) {
        currentRoute == NavigationRoutes.LinkAccount.route ||
            currentRoute.startsWith("strength_assessment")
    }
    LaunchedEffect(routeOwnsStaticTitle, currentRoute) {
        if (routeOwnsStaticTitle) {
            viewModel.updateTopBarTitle("")
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
        val platformAccessibilitySettings = rememberPlatformAccessibilitySettings()
        // Issue #1367: global device-local weight text size (SD6). globalSettings is
        // initialized synchronously from the loaded preferences, so the saved choice is
        // applied before first paint (SD10 — no Standard flash on cold start).
        val globalSettings by viewModel.globalSettings.collectAsState()

        CompositionLocalProvider(
            LocalWindowSizeClass provides windowSizeClass,
            LocalPlatformAccessibilitySettings provides platformAccessibilitySettings,
            LocalWeightTextScale provides globalSettings.weightTextScale,
        ) {
            val useCompactTopBar = isCompactAccessibilityLayout() ||
                windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
            val fontScale = LocalDensity.current.fontScale
            val hideTopBarSubtitle = useCompactTopBar || fontScale >= 1.3f
            // Cycle editor and review publish the cycle name through topBarTitle.
            // On those routes that name wins; a blank name uses the static fallback.
            val cycleFallback = stringResource(Res.string.nav_training_cycle)
            val reviewFallback = stringResource(Res.string.nav_cycle_review)
            val compactCycleFallback = stringResource(Res.string.nav_cycle_short)
            val compactReviewFallback = stringResource(Res.string.nav_review_short)
            val fullTopBarTitle = cycleShellTitle(
                route = currentRoute,
                cycleName = topBarTitle,
                cycleFallback = cycleFallback,
                reviewFallback = reviewFallback,
            ) ?: if (!routeOwnsStaticTitle && topBarTitle.isNotEmpty()) {
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
                getCompactScreenTitle(
                    route = currentRoute,
                    title = fullTopBarTitle,
                    fullCycleFallback = cycleFallback,
                    compactCycleFallback = compactCycleFallback,
                    fullReviewFallback = reviewFallback,
                    compactReviewFallback = compactReviewFallback,
                )
            } else {
                fullTopBarTitle
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                },
                topBar = {
                    if (shouldShowTopBar) {
                        TopAppBar(
                            title = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    Text(
                                        text = visibleTopBarTitle,
                                        style = if (hideTopBarSubtitle) {
                                            MaterialTheme.typography.titleMedium
                                        } else {
                                            MaterialTheme.typography.titleLarge
                                        },
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!hideTopBarSubtitle) {
                                        Text(
                                            text = stringResource(Res.string.nav_app_fallback),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                brush = Brush.linearGradient(
                                                    colors = listOf(
                                                        Color(0xFFF97316),
                                                        Color(0xFFEF4444),
                                                    ),
                                                ),
                                                fontWeight = FontWeight.Medium,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
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
                            analyticsLabel = stringResource(Res.string.nav_analytics),
                            analyticsContentDescription = stringResource(Res.string.cd_analytics),
                            homeContentDescription = stringResource(Res.string.cd_home),
                            insightsContentDescription = stringResource(Res.string.nav_insights),
                            profileContentDescription = profileContentDescription,
                            openProfileSwitcherDescription = openProfileSwitcherDescription,
                            settingsContentDescription = stringResource(Res.string.cd_settings),
                            profileBadgeColor = activeProfileBadgeColor,
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

                val contentModifier = if (!shouldShowBottomBar && shouldShowTopBar) {
                    Modifier
                        .padding(padding)
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                } else {
                    Modifier.padding(padding)
                }

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
                        modifier = contentModifier,
                    )
                }
            }

            // Show connection lost alert during workout (Issue #43)
            if (connectionLostDuringWorkout || machineSafetyUiState is MachineSafetyUiState.Visible) {
                ConnectionLostDialog(
                    hazard = (machineSafetyUiState as? MachineSafetyUiState.Visible)?.document,
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

            connectionError?.let { error ->
                ConnectionErrorDialog(
                    message = error,
                    onDismiss = { viewModel.clearConnectionError() },
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

            SharedCsvImportHost(
                viewModel = viewModel,
                navController = navController,
                snackbarHostState = snackbarHostState,
            )
        } // CompositionLocalProvider
    } // BoxWithConstraints
}

@Composable
private fun PhoenixBottomNavigationBar(
    currentRoute: String,
    isHomeRoute: Boolean,
    isCompactHeight: Boolean,
    analyticsLabel: String,
    analyticsContentDescription: String,
    homeContentDescription: String,
    insightsContentDescription: String,
    profileContentDescription: String,
    openProfileSwitcherDescription: String,
    settingsContentDescription: String,
    profileBadgeColor: Color?,
    onAnalyticsClick: () -> Unit,
    onHomeClick: () -> Unit,
    onInsightsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onProfileLongClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val containerColor = phoenixBottomNavigationContainerColor(MaterialTheme.colorScheme)
    val minBarHeight = remember(isCompactHeight) { if (isCompactHeight) 64.dp else 68.dp }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minBarHeight)
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
                val label = when (item) {
                    BottomNavItem.ANALYTICS -> analyticsLabel
                    BottomNavItem.INSIGHTS -> insightsContentDescription
                    BottomNavItem.HOME -> homeContentDescription
                    BottomNavItem.PROFILE -> profileContentDescription
                    BottomNavItem.SETTINGS -> settingsContentDescription
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
                    label = label,
                    contentDescription = contentDescription,
                    selected = selected,
                    testTag = testTag,
                    onClick = onClick,
                    modifier = Modifier.weight(1f),
                    onLongClick = itemLongClick,
                    longClickLabel = openProfileSwitcherDescription.takeIf {
                        item == BottomNavItem.PROFILE
                    },
                    badgeColor = profileBadgeColor.takeIf { item == BottomNavItem.PROFILE },
                    badgeBorderColor = containerColor,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoenixBottomNavigationItem(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    badgeColor: Color? = null,
    badgeBorderColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconColor,
                )
                if (badgeColor != null) {
                    Box(
                        modifier = Modifier
                            .offset(x = 3.dp, y = (-1).dp)
                            .size(8.dp)
                            .background(badgeColor, CircleShape)
                            .border(1.5.dp, badgeBorderColor, CircleShape),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = iconColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val rotation: Float
    if (reduceMotion) {
        rotation = 0f
    } else {
        // Spinning animation for syncing state
        val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
        val animatedRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "syncRotation",
        )
        rotation = animatedRotation
    }

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
            .minimumInteractiveComponentSize()
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
                is SyncState.Syncing -> stringResource(Res.string.main_syncing)
                is SyncState.Success -> stringResource(Res.string.main_sync_complete)
                is SyncState.Error -> stringResource(Res.string.main_sync_error_tap)
                is SyncState.PartialSuccess -> stringResource(Res.string.main_sync_partial_retry)
                else -> stringResource(Res.string.cd_cloud_sync)
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
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val fontScale = LocalDensity.current.fontScale

    // Animated gradient offset for connecting state
    val gradientOffset: Float
    if (reduceMotion) {
        gradientOffset = 0.25f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "connecting")
        val animatedOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "gradientOffset",
        )
        gradientOffset = animatedOffset
    }

    val buttonText = if (compact) {
        when {
            isConnected -> stringResource(Res.string.hrm_status_connected)
            isConnecting -> stringResource(Res.string.main_connect_connecting)
            isError -> stringResource(Res.string.action_retry)
            else -> stringResource(Res.string.main_connect_action)
        }
    } else {
        when {
            isConnected -> stringResource(Res.string.hrm_status_connected)
            isConnecting -> stringResource(Res.string.connecting)
            isError -> stringResource(Res.string.reconnect)
            else -> stringResource(Res.string.main_connect_full_click)
        }
    }

    val contentDescription = when {
        isConnected -> stringResource(Res.string.main_cd_connected)
        isConnecting -> stringResource(Res.string.main_cd_connecting)
        isError -> stringResource(Res.string.main_cd_error)
        else -> stringResource(Res.string.main_cd_tap_connect)
    }

    // High-contrast WCAG AA connection pill fills and inks
    val blueFill = Color(0xFF1D4ED8)
    val connectingGreenStop = Color(0xFF15803D)
    val redFill = Color(0xFFB91C1C)
    val connectedFill = AccessibilityTheme.colors.success
    val connectedInk = AccessibilityTheme.colors.onSuccess
    val textColor = if (isConnected) connectedInk else Color.White

    // Touch target wrapper: minimumInteractiveComponentSize ensures >=48x48dp interactive area
    // (transparent — no visual background here)
    val widthCapModifier = if (fontScale > 1.0f) {
        Modifier
    } else {
        Modifier.widthIn(max = if (compact) 124.dp else 180.dp)
    }

    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .then(widthCapModifier)
            .padding(end = 8.dp)
            .clickable(
                onClick = onToggleConnection,
                role = Role.Button,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        // Visual pill: restored to pre-Phase-1 32dp height.
        // shapes.medium = 20dp radius; with height=32dp the radius exceeds height/2 (16dp),
        // so path corners are clamped to 16dp -> proper stadium/pill shape. Keep shapes.medium.
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
                                    blueFill,
                                    connectingGreenStop,
                                    blueFill,
                                    connectingGreenStop,
                                    blueFill,
                                ),
                                startX = -200f + (gradientOffset * 600f),
                                endX = 200f + (gradientOffset * 600f),
                            ),
                        )
                    } else {
                        // Static background for other states
                        Modifier.background(
                            color = when {
                                isConnected -> connectedFill
                                isError -> redFill
                                else -> blueFill
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
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shell title for the cycle editor and review routes.
 *
 * [cycleName] is the title those screens publish (the editor's cycle name, or the
 * review route's cycle name). A non-blank name is shown trimmed. A blank name uses
 * the static fallback. Returns null for every other route.
 */
internal fun cycleShellTitle(
    route: String,
    cycleName: String,
    cycleFallback: String = "Training Cycle",
    reviewFallback: String = "Cycle Review",
): String? {
    val fallback = when {
        route.startsWith("cycle_editor") -> cycleFallback
        route.startsWith("cycleReview") -> reviewFallback
        else -> return null
    }
    return cycleName.trim().ifEmpty { fallback }
}

/**
 * Compact top-bar label for cycle routes. A real cycle name is kept (the bar
 * ellipsizes it). The static fallbacks stay short.
 */
internal fun compactCycleShellTitle(
    route: String,
    title: String,
    fullCycleFallback: String = "Training Cycle",
    compactCycleFallback: String = "Cycle",
    fullReviewFallback: String = "Cycle Review",
    compactReviewFallback: String = "Review",
): String? = when {
    route.startsWith("cycle_editor") ->
        if (title.isBlank() || title == fullCycleFallback || title == "Training Cycle") {
            compactCycleFallback
        } else {
            title
        }
    route.startsWith("cycleReview") ->
        if (title.isBlank() || title == fullReviewFallback || title == "Cycle Review") {
            compactReviewFallback
        } else {
            title
        }
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
@Composable
private fun getScreenTitle(
    route: String,
    profileTitle: String,
    routineName: String = "",
    exerciseName: String = "",
): String = when {
    // Main tabs (static titles)
    route == NavigationRoutes.Home.route -> stringResource(Res.string.nav_choose_workout)

    route == NavigationRoutes.DailyRoutines.route -> stringResource(Res.string.nav_daily_routines)

    route == NavigationRoutes.TrainingCycles.route -> stringResource(Res.string.cycles_title)

    route == NavigationRoutes.Analytics.route -> stringResource(Res.string.nav_analytics)

    route == NavigationRoutes.SmartInsights.route -> stringResource(Res.string.insights_title)

    route == NavigationRoutes.Profile.route -> profileTitle

    route == NavigationRoutes.Settings.route -> stringResource(Res.string.settings_title)

    route == NavigationRoutes.EquipmentRack.route -> stringResource(Res.string.equipment_rack_title)

    route == NavigationRoutes.CustomEquipment.route -> stringResource(Res.string.custom_equipment_title)

    route == NavigationRoutes.JustLift.route -> stringResource(Res.string.just_lift)

    isSingleExerciseRoute(route) -> stringResource(Res.string.nav_single_exercise)

    // Routine flow (dynamic - uses routine name)
    route == NavigationRoutes.RoutineOverview.route -> routineName.ifEmpty { stringResource(Res.string.nav_routine) }

    route == NavigationRoutes.SetReady.route -> routineName.ifEmpty { stringResource(Res.string.nav_routine) }

    // Exercise detail (dynamic - uses exercise name)
    route.startsWith("exercise_detail") -> exerciseName.ifEmpty { stringResource(Res.string.nav_exercise) }

    // Routine editor (dynamic - uses routine name)
    route.startsWith("routine_editor") -> routineName.ifEmpty { stringResource(Res.string.nav_edit_routine) }

    // Static titles
    route == NavigationRoutes.Badges.route -> stringResource(Res.string.nav_achievements)

    route == NavigationRoutes.ConnectionLogs.route -> stringResource(Res.string.settings_connection_logs)

    route == NavigationRoutes.Diagnostics.route -> stringResource(Res.string.diagnostics_title)

    route == NavigationRoutes.LinkAccount.route -> stringResource(Res.string.phoenix_portal)

    route == NavigationRoutes.Integrations.route -> stringResource(Res.string.settings_integrations)

    route == NavigationRoutes.ExternalIntegrationHub.route -> stringResource(Res.string.integrations_data_title)

    route == NavigationRoutes.ExternalActivities.route -> stringResource(Res.string.external_activities_title)

    route == NavigationRoutes.ExternalRoutines.route -> stringResource(Res.string.external_routines_title)

    route.startsWith("external_routine/") -> stringResource(Res.string.nav_routine)

    route == NavigationRoutes.ExternalPrograms.route -> stringResource(Res.string.external_programs_title)

    route.startsWith("external_program/") -> stringResource(Res.string.nav_program)

    route.startsWith("external_program_playground/") -> stringResource(Res.string.nav_program_playground)

    route == NavigationRoutes.ExternalMeasurementTrends.route -> stringResource(Res.string.external_measurements)

    route.startsWith("strength_assessment") -> stringResource(Res.string.nav_strength_assessment)

    route == NavigationRoutes.RoutineComplete.route -> stringResource(Res.string.nav_complete)

    // Active workout - hidden, but provide fallback
    route == NavigationRoutes.ActiveWorkout.route -> stringResource(Res.string.nav_workout)

    // Fallback
    else -> stringResource(Res.string.nav_app_fallback)
}

@Composable
private fun getCompactScreenTitle(
    route: String,
    title: String,
    fullCycleFallback: String = "Training Cycle",
    compactCycleFallback: String = "Cycle",
    fullReviewFallback: String = "Cycle Review",
    compactReviewFallback: String = "Review",
): String {
    compactCycleShellTitle(
        route = route,
        title = title,
        fullCycleFallback = fullCycleFallback,
        compactCycleFallback = compactCycleFallback,
        fullReviewFallback = fullReviewFallback,
        compactReviewFallback = compactReviewFallback,
    )?.let { return it }
    return when {
        route == NavigationRoutes.Home.route -> stringResource(Res.string.external_workouts)
        route == NavigationRoutes.DailyRoutines.route -> stringResource(Res.string.home_action_routines)
        route == NavigationRoutes.TrainingCycles.route -> stringResource(Res.string.home_action_cycles)
        route == NavigationRoutes.SmartInsights.route -> stringResource(Res.string.nav_insights)
        route == NavigationRoutes.Profile.route -> title
        route == NavigationRoutes.EquipmentRack.route -> stringResource(Res.string.nav_rack_short)
        route == NavigationRoutes.CustomEquipment.route -> stringResource(Res.string.label_equipment)
        route == NavigationRoutes.ConnectionLogs.route -> stringResource(Res.string.nav_logs_short)
        route == NavigationRoutes.LinkAccount.route -> stringResource(Res.string.nav_portal_short)
        route == NavigationRoutes.Integrations.route -> stringResource(Res.string.settings_integrations)
        route == NavigationRoutes.ExternalIntegrationHub.route -> stringResource(Res.string.nav_data_short)
        route == NavigationRoutes.ExternalActivities.route -> stringResource(Res.string.nav_activities_short)
        route == NavigationRoutes.ExternalRoutines.route -> stringResource(Res.string.home_action_routines)
        route.startsWith("external_routine/") -> if (title.isBlank()) stringResource(Res.string.nav_routine) else title
        route == NavigationRoutes.ExternalPrograms.route -> stringResource(Res.string.nav_programs_short)
        route.startsWith("external_program/") -> if (title.isBlank()) stringResource(Res.string.nav_program) else title
        route.startsWith("external_program_playground/") -> stringResource(Res.string.nav_playground_short)
        route == NavigationRoutes.ExternalMeasurementTrends.route -> stringResource(Res.string.nav_metrics_short)
        route.startsWith("strength_assessment") -> stringResource(Res.string.nav_assessment_short)
        isSingleExerciseRoute(route) -> stringResource(Res.string.nav_exercise)
        route.startsWith("routine_editor") -> stringResource(Res.string.nav_edit_short)
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
