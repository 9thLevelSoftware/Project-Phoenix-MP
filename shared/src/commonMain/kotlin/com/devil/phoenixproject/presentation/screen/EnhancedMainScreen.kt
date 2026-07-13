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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.ConnectionLostDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.ProfileAddDialog
import com.devil.phoenixproject.presentation.components.ProfileRecoveryDialog
import com.devil.phoenixproject.presentation.components.ProfileSwitcherSheet
import com.devil.phoenixproject.presentation.navigation.BottomNavItem
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
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.setKeepScreenOn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.cd_analytics
import vitruvianprojectphoenix.shared.generated.resources.cd_back
import vitruvianprojectphoenix.shared.generated.resources.cd_home
import vitruvianprojectphoenix.shared.generated.resources.cd_open_profile_switcher
import vitruvianprojectphoenix.shared.generated.resources.cd_profile
import vitruvianprojectphoenix.shared.generated.resources.cd_settings
import vitruvianprojectphoenix.shared.generated.resources.nav_insights
import vitruvianprojectphoenix.shared.generated.resources.nav_profile
import vitruvianprojectphoenix.shared.generated.resources.profile_create_failed
import vitruvianprojectphoenix.shared.generated.resources.profile_recovery_retry_failed
import vitruvianprojectphoenix.shared.generated.resources.profile_switch_failed

/**
 * Enhanced main screen with dynamic top bar and bottom navigation.
 * Provides consistent scaffolding across all screens with:
 * - Dynamic TopAppBar (title, back button, actions, connection status, theme toggle)
 * - Compact bottom navigation (Analytics, Workouts, Insights, Settings)
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
    val topBarTitle by viewModel.topBarTitle.collectAsState()
    val topBarActions by viewModel.topBarActions.collectAsState()
    val topBarBackAction by viewModel.topBarBackAction.collectAsState()

    // Dynamic title sources
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val currentRoutineName = loadedRoutine?.name ?: ""

    // Cycle name display: ViewModel does not currently expose editingCycle state,
    // so the top bar will show an empty cycle name. This is acceptable because
    // cycle editing navigates to its own screen with its own title.
    val editingCycleName = ""

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
            val fullTopBarTitle = if (topBarTitle.isNotEmpty()) {
                topBarTitle
            } else {
                getScreenTitle(
                    route = currentRoute,
                    profileTitle = profileTitle,
                    routineName = currentRoutineName,
                    exerciseName = selectedExerciseName,
                    cycleName = editingCycleName,
                )
            }
            val visibleTopBarTitle = if (useCompactTopBar) {
                getCompactScreenTitle(currentRoute, fullTopBarTitle)
            } else {
                fullTopBarTitle
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                // Dynamic Actions from Screens
                                topBarActions.forEach { action ->
                                    IconButton(onClick = action.onClick) {
                                        Icon(
                                            imageVector = action.icon,
                                            contentDescription = action.description,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }

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
            if (connectionLostDuringWorkout) {
                ConnectionLostDialog(
                    onReconnect = {
                        viewModel.reconnectInterruptedWorkout()
                    },
                    onDismiss = {
                        viewModel.dismissConnectionLostAlert()
                    },
                )
            }

            if (switcherState.showSwitcher) {
                ProfileSwitcherSheet(
                    profiles = profiles,
                    activeProfileId = readyProfileId,
                    switchingInFlight = switchingInFlight,
                    switchingTargetProfileId = switchingTargetProfileId,
                    errorMessage = switchFailedMessage.takeIf {
                        switcherState.error == ProfileOverlayError.SWITCH_FAILED
                    },
                    onSelectProfile = { profile ->
                        profileSwitcherViewModel.switchProfile(profile.id)
                    },
                    onAddProfile = profileSwitcherViewModel::openAddDialog,
                    onDismiss = profileSwitcherViewModel::dismissSwitcher,
                )
            }

            if (switcherState.showAddDialog) {
                ProfileAddDialog(
                    existingProfileCount = profiles.size,
                    isSubmitting = switchingInFlight,
                    errorMessage = createFailedMessage.takeIf {
                        switcherState.error == ProfileOverlayError.CREATE_FAILED
                    },
                    onConfirm = profileSwitcherViewModel::createAndActivateProfile,
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

/**
 * Get the screen title based on the current route.
 * Supports dynamic titles for routine, exercise, and cycle flows.
 */
private fun isSingleExerciseRoute(route: String): Boolean = route == NavigationRoutes.SingleExercise.route ||
    route.startsWith("${NavigationRoutes.SingleExercise.route}/")

internal fun shouldKeepScreenOnForRoute(route: String, isInWorkoutSession: Boolean): Boolean =
    isInWorkoutSession || route == NavigationRoutes.JustLift.route

private fun getScreenTitle(
    route: String,
    profileTitle: String,
    routineName: String = "",
    exerciseName: String = "",
    cycleName: String = "",
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

    // Cycle flow (dynamic - uses cycle name)
    route.startsWith("cycle_editor") -> cycleName.ifEmpty { "Training Cycle" }

    route.startsWith("cycleReview") -> cycleName.ifEmpty { "Cycle Review" }

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

private fun getCompactScreenTitle(route: String, title: String): String = when {
    route == NavigationRoutes.Home.route -> "Workouts"
    route == NavigationRoutes.DailyRoutines.route -> "Routines"
    route == NavigationRoutes.TrainingCycles.route -> "Cycles"
    route == NavigationRoutes.SmartInsights.route -> "Insights"
    route == NavigationRoutes.Profile.route -> title
    route == NavigationRoutes.EquipmentRack.route -> "Rack"
    isSingleExerciseRoute(route) -> "Exercise"
    route.startsWith("cycle_editor") -> "Cycle"
    route.startsWith("cycleReview") -> "Review"
    route.startsWith("routine_editor") -> "Edit"
    else -> title
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
