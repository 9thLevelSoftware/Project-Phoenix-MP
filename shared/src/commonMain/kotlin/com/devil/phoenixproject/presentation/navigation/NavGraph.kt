package com.devil.phoenixproject.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.presentation.screen.*
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode

/**
 * Main navigation graph for the app.
 * Defines all routes and their composable destinations.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavigationRoutes.Home.route,
        modifier = modifier
    ) {
        // Home screen - workout type selection
        composable(NavigationRoutes.Home.route) {
            HomeScreen(
                onWorkoutModeSelected = { mode ->
                    // Navigate to JustLift for all workout modes
                    // The mode will be passed to the workout configuration
                    navController.navigate(NavigationRoutes.JustLift.route)
                }
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

        // Weekly Programs screen - view and manage programs
        composable(NavigationRoutes.WeeklyPrograms.route) {
            WeeklyProgramsScreen(
                navController = navController,
                viewModel = viewModel,
                themeMode = themeMode
            )
        }

        // Program Builder screen - create/edit weekly program
        composable(
            route = NavigationRoutes.ProgramBuilder.route,
            arguments = listOf(navArgument("programId") { type = NavType.StringType })
        ) { backStackEntry ->
            val programId = backStackEntry.arguments?.getString("programId") ?: "new"
            ProgramBuilderScreen(
                navController = navController,
                viewModel = viewModel,
                programId = programId,
                exerciseRepository = exerciseRepository,
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
            SettingsTab(
                weightUnit = weightUnit,
                autoplayEnabled = userPreferences.autoplayEnabled,
                stopAtTop = userPreferences.stopAtTop,
                enableVideoPlayback = userPreferences.enableVideoPlayback,
                onWeightUnitChange = { viewModel.setWeightUnit(it) },
                onAutoplayChange = { viewModel.setAutoplayEnabled(it) },
                onStopAtTopChange = { viewModel.setStopAtTop(it) },
                onEnableVideoPlaybackChange = { viewModel.setEnableVideoPlayback(it) },
                onColorSchemeChange = { viewModel.setColorScheme(it) },
                onDeleteAllWorkouts = { viewModel.deleteAllWorkouts() },
                onNavigateToConnectionLogs = { navController.navigate(NavigationRoutes.ConnectionLogs.route) },
                isAutoConnecting = isAutoConnecting,
                connectionError = connectionError,
                onClearConnectionError = { viewModel.clearConnectionError() },
                onCancelAutoConnecting = { viewModel.cancelAutoConnecting() },
                onSetTitle = { viewModel.updateTopBarTitle(it) }
            )
        }

        // Connection Logs screen - debug BLE connections
        composable(NavigationRoutes.ConnectionLogs.route) {
            ConnectionLogsScreen(
                onNavigateBack = { navController.popBackStack() },
                mainViewModel = viewModel
            )
        }
    }
}
