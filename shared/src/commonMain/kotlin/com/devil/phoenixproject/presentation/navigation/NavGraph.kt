package com.devil.phoenixproject.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
                themeMode = themeMode,
                onNavigateToExerciseDetail = { exerciseId ->
                    navController.navigate(NavigationRoutes.ExerciseDetail.createRoute(exerciseId))
                }
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
            val exerciseId = backStackEntry.arguments?.getString("exerciseId") ?: return@composable
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
            SettingsTab(
                weightUnit = weightUnit,
                autoplayEnabled = userPreferences.autoplayEnabled,
                stopAtTop = userPreferences.stopAtTop,
                enableVideoPlayback = userPreferences.enableVideoPlayback,
                darkModeEnabled = themeMode == ThemeMode.DARK,
                stallDetectionEnabled = userPreferences.stallDetectionEnabled,
                onWeightUnitChange = { viewModel.setWeightUnit(it) },
                onAutoplayChange = { viewModel.setAutoplayEnabled(it) },
                onStopAtTopChange = { viewModel.setStopAtTop(it) },
                onEnableVideoPlaybackChange = { viewModel.setEnableVideoPlayback(it) },
                onDarkModeChange = { enabled -> onThemeModeChange(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT) },
                onStallDetectionChange = { viewModel.setStallDetectionEnabled(it) },
                onColorSchemeChange = { viewModel.setColorScheme(it) },
                onDeleteAllWorkouts = { viewModel.deleteAllWorkouts() },
                onNavigateToConnectionLogs = { navController.navigate(NavigationRoutes.ConnectionLogs.route) },
                onNavigateToBadges = { navController.navigate(NavigationRoutes.Badges.route) },
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
                onBack = { navController.popBackStack() }
            )
        }

        // Routine Editor - create/edit daily routine
        composable(
            route = NavigationRoutes.RoutineEditor.route,
            arguments = listOf(navArgument("routineId") { type = NavType.StringType })
        ) { backStackEntry ->
            val routineId = backStackEntry.arguments?.getString("routineId") ?: "new"
            
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
            arguments = listOf(navArgument("cycleId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cycleId = backStackEntry.arguments?.getString("cycleId") ?: "new"
            val routines by viewModel.routines.collectAsState()

            CycleEditorScreen(
                cycleId = cycleId,
                navController = navController,
                viewModel = viewModel,
                routines = routines
            )
        }
    }
}
}
