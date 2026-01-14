package com.devil.phoenixproject.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.presentation.screen.*
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import org.koin.compose.koinInject

/**
 * Main navigation graph for the app.
 * Defines all routes and their composable destinations.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = NavigationRoutes.Home.route,
            modifier = modifier
        ) {
        // Home screen - workout type selection
        composable(NavigationRoutes.Home.route) {
            HomeScreen(
                navController = navController,
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Just Lift screen - quick workout configuration
        composable(
            route = NavigationRoutes.JustLift.route,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            }
        ) {
            JustLiftScreen(
                navController = navController,
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Single Exercise screen - choose one exercise
        composable(NavigationRoutes.SingleExercise.route) {
            SingleExerciseScreen(
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository
            )
        }

        // Daily Routines screen - pre-built routines
        composable(NavigationRoutes.DailyRoutines.route) {
            DailyRoutinesScreen(
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository,
                themeMode = themeMode
            )
        }

        // Active Workout screen - shows workout controls during active workout
        composable(
            route = NavigationRoutes.ActiveWorkout.route,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            ActiveWorkoutScreen(
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository
            )
        }

        // Routine Overview screen - browse exercises before starting
        composable(NavigationRoutes.RoutineOverview.route) {
            RoutineOverviewScreen(
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository
            )
        }

        // Set Ready screen - configure set before starting
        composable(NavigationRoutes.SetReady.route) {
            SetReadyScreen(
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository
            )
        }

        // Routine Complete screen - celebration after finishing
        composable(NavigationRoutes.RoutineComplete.route) {
            RoutineCompleteScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        // Training Cycles screen - new rolling schedule system
        composable(
            route = NavigationRoutes.TrainingCycles.route,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            }
        ) {
            TrainingCyclesScreen(
                navController = navController,
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Analytics screen - history, PRs, trends
        composable(
            route = NavigationRoutes.Analytics.route,
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) }
        ) {
            AnalyticsScreen(
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Exercise Detail screen - drill-down for individual exercise
        composable(
            route = NavigationRoutes.ExerciseDetail.route,
            arguments = listOf(navArgument("exerciseId") { type = NavType.StringType }),
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            }
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments?.read { getStringOrNull("exerciseId") }

            // Handle null/invalid exerciseId - navigate back instead of blank screen
            if (exerciseId.isNullOrBlank()) {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                return@composable
            }

            ExerciseDetailScreen(
                exerciseId = exerciseId,
                navController = navController,
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Settings screen
        composable(
            route = NavigationRoutes.Settings.route,
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) }
        ) {
            val weightUnit by viewModel.weightUnit.collectAsState()
            val userPreferences by viewModel.userPreferences.collectAsState()
            val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
            val connectionError by viewModel.connectionError.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()
            val discoModeActive by viewModel.discoModeActive.collectAsState()
            SettingsTab(
                weightUnit = weightUnit,
                autoplayEnabled = userPreferences.autoplayEnabled,
                stopAtTop = userPreferences.stopAtTop,
                enableVideoPlayback = userPreferences.enableVideoPlayback,
                darkModeEnabled = themeMode == ThemeMode.DARK,
                stallDetectionEnabled = userPreferences.stallDetectionEnabled,
                audioRepCountEnabled = userPreferences.audioRepCountEnabled,
                summaryCountdownSeconds = userPreferences.summaryCountdownSeconds,
                autoStartCountdownSeconds = userPreferences.autoStartCountdownSeconds,
                selectedColorSchemeIndex = userPreferences.colorScheme,
                onWeightUnitChange = { viewModel.setWeightUnit(it) },
                onAutoplayChange = { viewModel.setAutoplayEnabled(it) },
                onStopAtTopChange = { viewModel.setStopAtTop(it) },
                onEnableVideoPlaybackChange = { viewModel.setEnableVideoPlayback(it) },
                onDarkModeChange = { enabled -> onThemeModeChange(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT) },
                onStallDetectionChange = { viewModel.setStallDetectionEnabled(it) },
                onAudioRepCountChange = { viewModel.setAudioRepCountEnabled(it) },
                onSummaryCountdownChange = { viewModel.setSummaryCountdownSeconds(it) },
                onAutoStartCountdownChange = { viewModel.setAutoStartCountdownSeconds(it) },
                onColorSchemeChange = { viewModel.setColorScheme(it) },
                onDeleteAllWorkouts = { viewModel.deleteAllWorkouts() },
                onNavigateToConnectionLogs = { navController.navigate(NavigationRoutes.ConnectionLogs.route) },
                onNavigateToBadges = { navController.navigate(NavigationRoutes.Badges.route) },
                isAutoConnecting = isAutoConnecting,
                connectionError = connectionError,
                onClearConnectionError = { viewModel.clearConnectionError() },
                onCancelAutoConnecting = { viewModel.cancelAutoConnecting() },
                onSetTitle = { viewModel.updateTopBarTitle(it) },
                // Disco mode Easter egg
                discoModeUnlocked = userPreferences.discoModeUnlocked,
                discoModeActive = discoModeActive,
                isConnected = connectionState is com.devil.phoenixproject.domain.model.ConnectionState.Connected,
                onDiscoModeUnlocked = { viewModel.unlockDiscoMode() },
                onDiscoModeToggle = { viewModel.toggleDiscoMode(it) },
                onPlayDiscoSound = { viewModel.emitDiscoSound() },
                onTestSounds = { viewModel.testSounds() },
                // Simulator mode Easter egg
                simulatorModeUnlocked = viewModel.isSimulatorModeUnlocked(),
                simulatorModeEnabled = viewModel.isSimulatorModeUnlocked(),
                onSimulatorModeUnlocked = { viewModel.unlockSimulatorMode() },
                onSimulatorModeToggle = { viewModel.toggleSimulatorMode(it) }
            )
        }

        // Connection Logs screen - debug BLE connections
        composable(NavigationRoutes.ConnectionLogs.route) {
            ConnectionLogsScreen(
                onNavigateBack = { navController.popBackStack() },
                mainViewModel = viewModel
            )
        }

        // Badges screen - achievements and gamification
        composable(
            route = NavigationRoutes.Badges.route,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(200)) },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            BadgesScreen(
                onBack = { navController.popBackStack() },
                mainViewModel = viewModel
            )
        }

        // Routine Editor - create/edit daily routine
        composable(
            route = NavigationRoutes.RoutineEditor.route,
            arguments = listOf(navArgument("routineId") { type = NavType.StringType })
        ) { backStackEntry ->
            val routineId = backStackEntry.arguments?.read { getStringOrNull("routineId") } ?: "new"

            // Collect dependencies from ViewModel/Koin
            val weightUnit by viewModel.weightUnit.collectAsState()
            val enableVideo by viewModel.enableVideoPlayback.collectAsState()

            RoutineEditorScreen(
                routineId = routineId,
                navController = navController,
                viewModel = viewModel,
                exerciseRepository = exerciseRepository,
                weightUnit = weightUnit,
                kgToDisplay = viewModel::kgToDisplay,
                displayToKg = viewModel::displayToKg,
                enableVideoPlayback = enableVideo
            )
        }

        // Cycle Editor - timeline builder for rolling schedules
        composable(
            route = NavigationRoutes.CycleEditor.route,
            arguments = listOf(
                navArgument("cycleId") { type = NavType.StringType },
                navArgument("dayCount") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.read { getStringOrNull("cycleId") } ?: "new"
            val dayCount = backStackEntry.arguments?.read { getStringOrNull("dayCount") }?.toIntOrNull()
            val routines by viewModel.routines.collectAsState()

            CycleEditorScreen(
                cycleId = cycleId,
                navController = navController,
                viewModel = viewModel,
                routines = routines,
                initialDayCount = dayCount
            )
        }

        // Cycle Review - preview before final save
        composable(
            route = NavigationRoutes.CycleReview.route,
            arguments = listOf(navArgument("cycleId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.read { getStringOrNull("cycleId") }

            // Handle null/invalid cycleId - navigate back instead of blank screen
            if (cycleId.isNullOrBlank()) {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                return@composable
            }

            val routines by viewModel.routines.collectAsState()
            val cycleRepository: TrainingCycleRepository = koinInject()

            // Load cycle from repository
            var cycle by remember { mutableStateOf<TrainingCycle?>(null) }
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(cycleId) {
                isLoading = true
                cycle = cycleRepository.getCycleById(cycleId)
                isLoading = false
            }

            when {
                isLoading -> {
                    // Show loading indicator
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                cycle == null -> {
                    // Cycle not found - navigate back with error handling
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                }
                else -> {
                    CycleReviewScreen(
                        cycleName = cycle!!.name,
                        days = cycle!!.days,
                        routines = routines,
                        onBack = { navController.popBackStack() },
                        onSave = {
                            // Cycle is already saved, just navigate back to TrainingCycles
                            navController.navigate(NavigationRoutes.TrainingCycles.route) {
                                popUpTo(NavigationRoutes.TrainingCycles.route) { inclusive = true }
                            }
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}
}
