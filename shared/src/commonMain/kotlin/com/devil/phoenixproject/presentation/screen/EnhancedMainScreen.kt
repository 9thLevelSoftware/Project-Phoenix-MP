package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.presentation.components.AddProfileDialog
import com.devil.phoenixproject.presentation.components.ConnectionLostDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.ProfileSidePanel
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.*

/**
 * Enhanced main screen with dynamic top bar and bottom navigation.
 * Provides consistent scaffolding across all screens with:
 * - Dynamic TopAppBar (title, back button, actions, connection status, theme toggle)
 * - Bottom NavigationBar (Analytics, Workouts, Settings)
 * - Conditional visibility based on current route
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedMainScreen(
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
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

    // Profile management
    val scope = rememberCoroutineScope()
    val profileRepository: UserProfileRepository = koinInject()
    val profiles by profileRepository.allProfiles.collectAsState()
    val activeProfile by profileRepository.activeProfile.collectAsState()
    var showAddProfileDialog by remember { mutableStateOf(false) }

    // Ensure default profile exists
    LaunchedEffect(Unit) {
        profileRepository.ensureDefaultProfile()
    }

    // Sync status
    val syncManager: SyncManager = koinInject()
    val syncState by syncManager.syncState.collectAsState()
    val isAuthenticated by syncManager.isAuthenticated.collectAsState()
    val lastSyncTime by syncManager.lastSyncTime.collectAsState()

    // Determine if we're in dark mode for TopAppBar color
    val isDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var currentRoute by remember { mutableStateOf(NavigationRoutes.Home.route) }

    // Track navigation changes
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            currentRoute = backStackEntry.destination.route ?: NavigationRoutes.Home.route
        }
    }

    // Helper function to determine if current route is a "Workouts" route
    val isWorkoutsRoute = remember(currentRoute) {
        currentRoute == NavigationRoutes.Home.route ||
            currentRoute == NavigationRoutes.JustLift.route ||
            currentRoute == NavigationRoutes.SingleExercise.route ||
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
            currentRoute == NavigationRoutes.Settings.route
    }

    // Show back button for all screens except Home
    val showBackButton = remember(currentRoute) {
        currentRoute != NavigationRoutes.Home.route
    }

    // Exit confirmation dialog state for routine flow
    var showExitRoutineConfirmation by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)

        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (shouldShowTopBar) {
                        TopAppBar(
                            modifier = Modifier.statusBarsPadding(),
                            title = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    // Main title - either dynamic or default based on route
                                    Text(
                                        text = if (topBarTitle.isNotEmpty()) {
                                            topBarTitle
                                        } else {
                                            getScreenTitle(
                                                route = currentRoute,
                                                routineName = currentRoutineName,
                                                exerciseName = selectedExerciseName,
                                                cycleName = editingCycleName,
                                            )
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // Subtitle - always show "Project Phoenix" with gradient
                                    Text(
                                        text = "Project Phoenix",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFFF97316), // Orange
                                                    Color(0xFFEF4444), // Red
                                                ),
                                            ),
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            },
                            navigationIcon = {
                                if (showBackButton) {
                                    IconButton(onClick = {
                                        when (currentRoute) {
                                            // Routine flow - needs confirmation
                                            NavigationRoutes.RoutineOverview.route -> {
                                                showExitRoutineConfirmation = true
                                            }

                                            // Set ready - go back to overview
                                            NavigationRoutes.SetReady.route -> {
                                                viewModel.returnToOverview()
                                                navController.navigateUp()
                                            }

                                            // All other screens - standard back or custom action
                                            else -> {
                                                if (topBarBackAction != null) {
                                                    topBarBackAction?.invoke()
                                                } else {
                                                    navController.navigateUp()
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(Res.string.cd_back),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
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
                        NavigationBar(
                            containerColor = if (isDarkMode) Color(0xFF1C1B1F) else Color(0xFFF3F3F3),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.navigationBarsPadding(),
                        ) {
                            // Analytics tab
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.BarChart,
                                        contentDescription = stringResource(Res.string.cd_analytics),
                                    )
                                },
                                label = { Text(stringResource(Res.string.nav_analytics)) },
                                selected = currentRoute == NavigationRoutes.Analytics.route,
                                onClick = {
                                    if (currentRoute != NavigationRoutes.Analytics.route) {
                                        navController.navigate(NavigationRoutes.Analytics.route) {
                                            popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )

                            // Workouts tab (Home) - center
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = stringResource(Res.string.cd_workouts),
                                    )
                                },
                                label = { Text(stringResource(Res.string.nav_workouts)) },
                                selected = isWorkoutsRoute,
                                onClick = {
                                    if (currentRoute != NavigationRoutes.Home.route) {
                                        navController.navigate(NavigationRoutes.Home.route) {
                                            popUpTo(NavigationRoutes.Home.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )

                            // Smart Insights tab
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "Insights",
                                    )
                                },
                                label = { Text("Insights") },
                                selected = currentRoute == NavigationRoutes.SmartInsights.route,
                                onClick = {
                                    if (currentRoute != NavigationRoutes.SmartInsights.route) {
                                        navController.navigate(NavigationRoutes.SmartInsights.route) {
                                            popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )

                            // Settings tab
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(Res.string.cd_settings),
                                    )
                                },
                                label = { Text(stringResource(Res.string.nav_settings)) },
                                selected = currentRoute == NavigationRoutes.Settings.route,
                                onClick = {
                                    if (currentRoute != NavigationRoutes.Settings.route) {
                                        navController.navigate(NavigationRoutes.Settings.route) {
                                            popUpTo(NavigationRoutes.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
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
                        modifier = Modifier.padding(padding),
                    )

                    // Profile side panel (only on Home screen)
                    if (currentRoute == NavigationRoutes.Home.route) {
                        ProfileSidePanel(
                            profiles = profiles,
                            activeProfile = activeProfile,
                            profileRepository = profileRepository,
                            scope = scope,
                            onAddProfile = { showAddProfileDialog = true },
                        )
                    }
                }
            }

            // Show connection lost alert during workout (Issue #43)
            if (connectionLostDuringWorkout) {
                ConnectionLostDialog(
                    onReconnect = {
                        viewModel.dismissConnectionLostAlert()
                        viewModel.ensureConnection(
                            onConnected = {},
                            onFailed = {},
                        )
                    },
                    onDismiss = {
                        viewModel.dismissConnectionLostAlert()
                    },
                )
            }

            // Exit routine confirmation dialog
            if (showExitRoutineConfirmation) {
                AlertDialog(
                    onDismissRequest = { showExitRoutineConfirmation = false },
                    title = { Text(stringResource(Res.string.exit_routine_title)) },
                    text = { Text(stringResource(Res.string.exit_routine_message)) },
                    confirmButton = {
                        Button(onClick = {
                            showExitRoutineConfirmation = false
                            viewModel.exitRoutineFlow()
                            navController.navigateUp()
                        }) { Text(stringResource(Res.string.action_exit)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitRoutineConfirmation = false }) {
                            Text(stringResource(Res.string.action_cancel))
                        }
                    },
                )
            }

            // Add Profile Dialog
            if (showAddProfileDialog) {
                AddProfileDialog(
                    profiles = profiles,
                    profileRepository = profileRepository,
                    scope = scope,
                    onDismiss = { showAddProfileDialog = false },
                )
            }
        } // CompositionLocalProvider
    } // BoxWithConstraints
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
private fun ConnectionStatusIndicator(connectionState: ConnectionState, onToggleConnection: () -> Unit) {
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

    val buttonText = when {
        isConnected -> "Connected"
        isConnecting -> "Connecting..."
        isError -> "Reconnect"
        else -> "Click to Connect"
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

    Box(
        modifier = Modifier
            .height(32.dp)
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(16.dp))
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
            .clickable(
                onClick = onToggleConnection,
                role = Role.Button,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buttonText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Get the screen title based on the current route.
 * Supports dynamic titles for routine, exercise, and cycle flows.
 */
private fun getScreenTitle(route: String, routineName: String = "", exerciseName: String = "", cycleName: String = ""): String = when {
    // Main tabs (static titles)
    route == NavigationRoutes.Home.route -> "Choose Your Workout"

    route == NavigationRoutes.DailyRoutines.route -> "Daily Routines"

    route == NavigationRoutes.TrainingCycles.route -> "Training Cycles"

    route == NavigationRoutes.Analytics.route -> "Analytics"

    route == NavigationRoutes.SmartInsights.route -> "Smart Insights"

    route == NavigationRoutes.Settings.route -> "Settings"

    route == NavigationRoutes.JustLift.route -> "Just Lift"

    route == NavigationRoutes.SingleExercise.route -> "Single Exercise"

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

    route == NavigationRoutes.RoutineComplete.route -> "Complete"

    // Active workout - hidden, but provide fallback
    route == NavigationRoutes.ActiveWorkout.route -> "Workout"

    // Fallback
    else -> "Project Phoenix"
}
