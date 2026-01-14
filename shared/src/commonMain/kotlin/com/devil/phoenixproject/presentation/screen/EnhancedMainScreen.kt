package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.presentation.components.ConnectionLostDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.presentation.components.AddProfileDialog
import com.devil.phoenixproject.presentation.components.ProfileSidePanel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.runtime.CompositionLocalProvider
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
import androidx.compose.foundation.layout.BoxWithConstraints

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
    navController: NavHostController = rememberNavController()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionLostDuringWorkout by viewModel.connectionLostDuringWorkout.collectAsState()
    val topBarTitle by viewModel.topBarTitle.collectAsState()
    val topBarActions by viewModel.topBarActions.collectAsState()
    val topBarBackAction by viewModel.topBarBackAction.collectAsState()

    // Dynamic title sources
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val currentRoutineName = loadedRoutine?.name ?: ""

    // For cycle screens - check if editingCycle exists in ViewModel
    // If not, leave empty for now - we'll handle it when updating cycle screens
    val editingCycleName = "" // TODO: Add viewModel.editingCycle if needed

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
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Main title - either dynamic or default based on route
                            Text(
                                text = if (topBarTitle.isNotEmpty()) topBarTitle
                                       else getScreenTitle(
                                           route = currentRoute,
                                           routineName = currentRoutineName,
                                           exerciseName = selectedExerciseName,
                                           cycleName = editingCycleName
                                       ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Subtitle - always show "Project Phoenix" with gradient
                            Text(
                                text = "Project Phoenix",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFF97316), // Orange
                                            Color(0xFFEF4444)  // Red
                                        )
                                    ),
                                    fontWeight = FontWeight.Medium
                                )
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
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        // Dynamic Actions from Screens
                        topBarActions.forEach { action ->
                            IconButton(onClick = action.onClick) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.description,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Connection status icon with text label
                        ConnectionStatusIndicator(
                            connectionState = connectionState,
                            onToggleConnection = {
                                if (connectionState is ConnectionState.Connected) {
                                    viewModel.disconnect()
                                } else {
                                    viewModel.ensureConnection(
                                        onConnected = {},
                                        onFailed = {}
                                    )
                                }
                            }
                        )
                    }
                )
            }
        },
        bottomBar = {
            if (shouldShowBottomBar) {
                NavigationBar(
                    containerColor = if (isDarkMode) Color(0xFF1C1B1F) else Color(0xFFF3F3F3),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    // Analytics tab
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = "Analytics"
                            )
                        },
                        label = { Text("Analytics") },
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
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    // Workouts tab (Home) - center
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Workouts"
                            )
                        },
                        label = { Text("Workouts") },
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
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    // Settings tab
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        },
                        label = { Text("Settings") },
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
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
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
                modifier = Modifier.padding(padding)
            )

            // Profile side panel (only on Home screen)
            if (currentRoute == NavigationRoutes.Home.route) {
                ProfileSidePanel(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    profileRepository = profileRepository,
                    scope = scope,
                    onAddProfile = { showAddProfileDialog = true }
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
                            onFailed = {}
                        )
                    },
                    onDismiss = {
                        viewModel.dismissConnectionLostAlert()
                    }
                )
            }

            // Exit routine confirmation dialog
            if (showExitRoutineConfirmation) {
                AlertDialog(
                    onDismissRequest = { showExitRoutineConfirmation = false },
                    title = { Text("Exit Routine?") },
                    text = { Text("Progress will be saved.") },
                    confirmButton = {
                        Button(onClick = {
                            showExitRoutineConfirmation = false
                            viewModel.exitRoutineFlow()
                            navController.navigateUp()
                        }) { Text("Exit") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitRoutineConfirmation = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Add Profile Dialog
            if (showAddProfileDialog) {
                AddProfileDialog(
                    profiles = profiles,
                    profileRepository = profileRepository,
                    scope = scope,
                    onDismiss = { showAddProfileDialog = false }
                )
            }
        } // CompositionLocalProvider
    } // BoxWithConstraints
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
    onToggleConnection: () -> Unit
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
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
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

    // Static colors for non-connecting states
    val blueColor = Color(0xFF3B82F6)
    val greenColor = Color(0xFF22C55E)
    val redColor = Color(0xFFEF4444)

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
                                blueColor
                            ),
                            startX = -200f + (gradientOffset * 600f),
                            endX = 200f + (gradientOffset * 600f)
                        )
                    )
                } else {
                    // Static background for other states
                    Modifier.background(
                        color = when {
                            isConnected -> greenColor
                            isError -> redColor
                            else -> blueColor
                        }
                    )
                }
            )
            .clickable(
                onClick = onToggleConnection,
                role = Role.Button
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = buttonText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1
        )
    }
}

/**
 * Get the screen title based on the current route.
 * Supports dynamic titles for routine, exercise, and cycle flows.
 */
private fun getScreenTitle(
    route: String,
    routineName: String = "",
    exerciseName: String = "",
    cycleName: String = ""
): String {
    return when {
        // Main tabs (static titles)
        route == NavigationRoutes.Home.route -> "Choose Your Workout"
        route == NavigationRoutes.DailyRoutines.route -> "Daily Routines"
        route == NavigationRoutes.TrainingCycles.route -> "Training Cycles"
        route == NavigationRoutes.Analytics.route -> "Analytics"
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
}
