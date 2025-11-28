package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.devil.phoenixproject.presentation.components.ThemeToggle
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.TopAppBarDark
import com.devil.phoenixproject.ui.theme.TopAppBarLight
import com.devil.phoenixproject.ui.theme.TextPrimary

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
        currentRoute == NavigationRoutes.WeeklyPrograms.route ||
        currentRoute.startsWith(NavigationRoutes.ProgramBuilder.route.replace("/{programId}", ""))
    }

    // Always show TopBar
    val shouldShowTopBar = true

    // Show BottomBar only for main tabs
    val shouldShowBottomBar = remember(currentRoute) {
        currentRoute == NavigationRoutes.Home.route ||
        currentRoute == NavigationRoutes.DailyRoutines.route ||
        currentRoute == NavigationRoutes.WeeklyPrograms.route ||
        currentRoute == NavigationRoutes.Analytics.route ||
        currentRoute == NavigationRoutes.Settings.route
    }

    // Show back button for all screens except main tabs
    val showBackButton = remember(currentRoute) {
        currentRoute != NavigationRoutes.Home.route &&
        currentRoute != NavigationRoutes.Analytics.route &&
        currentRoute != NavigationRoutes.Settings.route
    }

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
                                text = if (topBarTitle.isNotEmpty()) topBarTitle else getScreenTitle(currentRoute),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Subtitle - always show "Vitruvian Project Phoenix" with gradient
                            Text(
                                text = "Vitruvian Project Phoenix",
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
                                if (topBarBackAction != null) {
                                    topBarBackAction?.invoke()
                                } else {
                                    navController.navigateUp()
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
                        containerColor = if (isDarkMode) TopAppBarDark else TopAppBarLight,
                        titleContentColor = TextPrimary,
                        actionIconContentColor = TextPrimary
                    ),
                    actions = {
                        // Dynamic Actions from Screens
                        topBarActions.forEach { action ->
                            IconButton(onClick = action.onClick) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.description,
                                    tint = TextPrimary
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

                        // Theme toggle
                        ThemeToggle(
                            mode = themeMode,
                            onModeChange = onThemeModeChange
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
        // Use proper padding to account for TopAppBar and system bars
        NavGraph(
            navController = navController,
            viewModel = viewModel,
            exerciseRepository = exerciseRepository,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            modifier = Modifier.padding(padding)
        )
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
}

/**
 * Connection status indicator with icon and label.
 * Tappable to toggle connection state.
 */
@Composable
private fun ConnectionStatusIndicator(
    connectionState: ConnectionState,
    onToggleConnection: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                onClick = onToggleConnection,
                role = Role.Button
            )
    ) {
        Icon(
            imageVector = when (connectionState) {
                is ConnectionState.Connected -> Icons.Default.Bluetooth
                is ConnectionState.Connecting -> Icons.AutoMirrored.Filled.BluetoothSearching
                is ConnectionState.Disconnected -> Icons.Default.BluetoothDisabled
                is ConnectionState.Scanning -> Icons.AutoMirrored.Filled.BluetoothSearching
                is ConnectionState.Error -> Icons.Default.BluetoothDisabled
            },
            contentDescription = when (connectionState) {
                is ConnectionState.Connected -> "Connected to machine. Tap to disconnect"
                is ConnectionState.Connecting -> "Connecting to machine"
                is ConnectionState.Disconnected -> "Disconnected. Tap to connect"
                is ConnectionState.Scanning -> "Scanning for machine"
                is ConnectionState.Error -> "Connection error. Tap to retry"
            },
            tint = when (connectionState) {
                is ConnectionState.Connected -> Color(0xFF22C55E) // green-500
                is ConnectionState.Connecting -> Color(0xFFFBBF24) // yellow-400
                is ConnectionState.Disconnected -> Color(0xFFEF4444) // red-500
                is ConnectionState.Scanning -> Color(0xFF3B82F6) // blue-500
                is ConnectionState.Error -> Color(0xFFEF4444) // red-500
            },
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = when (connectionState) {
                is ConnectionState.Connected -> "Connected"
                is ConnectionState.Connecting -> "Connecting"
                is ConnectionState.Disconnected -> "Disconnected"
                is ConnectionState.Scanning -> "Scanning"
                is ConnectionState.Error -> "Error"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = when (connectionState) {
                is ConnectionState.Connected -> Color(0xFF22C55E)
                is ConnectionState.Connecting -> Color(0xFFFBBF24)
                is ConnectionState.Disconnected -> Color(0xFFEF4444)
                is ConnectionState.Scanning -> Color(0xFF3B82F6)
                is ConnectionState.Error -> Color(0xFFEF4444)
            },
            maxLines = 1
        )
    }
}

/**
 * Get the screen title based on the current route.
 */
private fun getScreenTitle(route: String): String {
    return when {
        route == NavigationRoutes.Home.route -> "Choose Your Workout"
        route == NavigationRoutes.Analytics.route -> "Analytics"
        route == NavigationRoutes.Settings.route -> "Settings"
        route == NavigationRoutes.JustLift.route -> "Just Lift"
        route == NavigationRoutes.SingleExercise.route -> "Single Exercise"
        route == NavigationRoutes.DailyRoutines.route -> "Daily Routines"
        route == NavigationRoutes.WeeklyPrograms.route -> "Weekly Programs"
        route == NavigationRoutes.ActiveWorkout.route -> "Active Workout"
        route == NavigationRoutes.ConnectionLogs.route -> "Connection Logs"
        route.startsWith("program_builder") -> "Program Builder"
        else -> "Choose Your Workout"
    }
}
