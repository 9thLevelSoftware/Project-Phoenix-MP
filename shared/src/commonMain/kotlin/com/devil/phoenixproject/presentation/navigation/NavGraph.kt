package com.devil.phoenixproject.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
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
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.presentation.screen.*
import com.devil.phoenixproject.presentation.viewmodel.AssessmentViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.insights_title
import vitruvianprojectphoenix.shared.generated.resources.nav_profile

internal sealed interface AssessmentProfileDestinationState {
    data class Bound(val profileId: String) : AssessmentProfileDestinationState

    data object Invalidated : AssessmentProfileDestinationState
}

internal fun resolveAssessmentProfileDestination(
    routeProfileId: String,
    context: ActiveProfileContext,
): AssessmentProfileDestinationState = if (
    routeProfileId.isNotBlank() &&
    context is ActiveProfileContext.Ready &&
    context.profile.id == routeProfileId
) {
    AssessmentProfileDestinationState.Bound(routeProfileId)
} else {
    AssessmentProfileDestinationState.Invalidated
}

@Composable
private fun AssessmentDestination(
    routeProfileId: String,
    exerciseId: String?,
    themeMode: ThemeMode,
    metricsFlow: StateFlow<WorkoutMetric?>,
    onNavigateBack: () -> Unit,
) {
    val profileRepository: UserProfileRepository = koinInject()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
    val destinationState = resolveAssessmentProfileDestination(
        routeProfileId = routeProfileId,
        context = activeProfileContext,
    )
    LaunchedEffect(destinationState) {
        if (destinationState == AssessmentProfileDestinationState.Invalidated) {
            onNavigateBack()
        }
    }

    when (destinationState) {
        is AssessmentProfileDestinationState.Bound -> {
            val assessmentViewModel: AssessmentViewModel = koinViewModel()
            AssessmentWizardScreen(
                viewModel = assessmentViewModel,
                profileId = destinationState.profileId,
                exerciseId = exerciseId,
                themeMode = themeMode,
                onNavigateBack = onNavigateBack,
                metricsFlow = metricsFlow,
            )
        }

        AssessmentProfileDestinationState.Invalidated -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(LoadingIndicatorSize.Large)
        }
    }
}

@Composable
private fun readyAssessmentProfileId(): String? {
    val profileRepository: UserProfileRepository = koinInject()
    val activeProfileContext by profileRepository.activeProfileContext.collectAsState()
    return (activeProfileContext as? ActiveProfileContext.Ready)?.profile?.id
}

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
    dynamicColorAvailable: Boolean,
    dynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    onOpenProfileSwitcher: () -> Unit,
    onProfileRecoveryRequired: (ProfileContextRecoveryException) -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = NavigationRoutes.Home.route,
            modifier = modifier,
        ) {
            // Home screen - workout type selection
            composable(NavigationRoutes.Home.route) {
                HomeScreen(
                    navController = navController,
                    viewModel = viewModel,
                )
            }

            // Just Lift screen - quick workout configuration
            composable(
                route = NavigationRoutes.JustLift.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                JustLiftScreen(
                    navController = navController,
                    viewModel = viewModel,
                    themeMode = themeMode,
                )
            }

            // Single Exercise screen - choose one exercise
            composable(NavigationRoutes.SingleExercise.route) {
                SingleExerciseScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                    themeMode = themeMode,
                )
            }

            // Single Exercise screen - reopen a recent activity exercise for another set
            composable(
                route = NavigationRoutes.SingleExerciseForExercise.route,
                arguments = listOf(navArgument("exerciseId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val exerciseId = backStackEntry.arguments?.read { getStringOrNull("exerciseId") }

                SingleExerciseScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                    themeMode = themeMode,
                    initialExerciseId = exerciseId,
                )
            }

            // Daily Routines screen - pre-built routines
            composable(NavigationRoutes.DailyRoutines.route) {
                DailyRoutinesScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                    themeMode = themeMode,
                )
            }

            // Active Workout screen - shows workout controls during active workout
            composable(
                route = NavigationRoutes.ActiveWorkout.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    ) + fadeIn(animationSpec = tween(300))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    ) + fadeOut(animationSpec = tween(300))
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    ) + fadeIn(animationSpec = tween(300))
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    ) + fadeOut(animationSpec = tween(300))
                },
            ) {
                ActiveWorkoutScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                )
            }

            // Routine Overview screen - browse exercises before starting
            composable(NavigationRoutes.RoutineOverview.route) {
                RoutineOverviewScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                )
            }

            // Set Ready screen - configure set before starting
            composable(NavigationRoutes.SetReady.route) {
                SetReadyScreen(
                    navController = navController,
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                )
            }

            // Routine Complete screen - celebration after finishing
            composable(NavigationRoutes.RoutineComplete.route) {
                RoutineCompleteScreen(
                    navController = navController,
                    viewModel = viewModel,
                )
            }

            // Training Cycles screen - new rolling schedule system
            composable(
                route = NavigationRoutes.TrainingCycles.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                TrainingCyclesScreen(
                    navController = navController,
                    viewModel = viewModel,
                    themeMode = themeMode,
                )
            }

            // Analytics screen - history, PRs, trends
            composable(
                route = NavigationRoutes.Analytics.route,
                enterTransition = { NavTransitions.tabFadeEnter() },
                exitTransition = { NavTransitions.tabFadeExit() },
                popEnterTransition = { NavTransitions.tabFadeEnter() },
                popExitTransition = { NavTransitions.tabFadeExit() },
            ) {
                val assessmentProfileId = readyAssessmentProfileId()
                AnalyticsScreen(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    assessmentProfileId = assessmentProfileId,
                    onNavigateToStrengthAssessment = { profileId ->
                        navController.navigate(
                            NavigationRoutes.StrengthAssessmentPicker.createRoute(profileId),
                        )
                    },
                )
            }

            // Smart Insights screen - training suggestions and readiness
            composable(
                route = NavigationRoutes.SmartInsights.route,
                enterTransition = { NavTransitions.tabFadeEnter() },
                exitTransition = { NavTransitions.tabFadeExit() },
                popEnterTransition = { NavTransitions.tabFadeEnter() },
                popExitTransition = { NavTransitions.tabFadeExit() },
            ) {
                // Route never set a title, so the top bar showed whatever the
                // previous screen left behind (stale-title bug).
                val insightsTitle = stringResource(Res.string.insights_title)
                LaunchedEffect(insightsTitle) {
                    viewModel.updateTopBarTitle(insightsTitle)
                }
                SmartInsightsTab()
            }

            composable(
                route = NavigationRoutes.Profile.route,
                enterTransition = { NavTransitions.tabFadeEnter() },
                exitTransition = { NavTransitions.tabFadeExit() },
                popEnterTransition = { NavTransitions.tabFadeEnter() },
                popExitTransition = { NavTransitions.tabFadeExit() },
            ) {
                val profileTitle = stringResource(Res.string.nav_profile)
                val userPreferences by viewModel.userPreferences.collectAsState()
                val connectionState by viewModel.connectionState.collectAsState()
                val discoModeActive by viewModel.discoModeActive.collectAsState()
                LaunchedEffect(profileTitle) {
                    viewModel.updateTopBarTitle(profileTitle)
                }
                ProfileScreen(
                    onOpenProfileSwitcher = onOpenProfileSwitcher,
                    onNavigateToExerciseDetail = { exerciseId ->
                        navController.navigate(
                            NavigationRoutes.ExerciseDetail.createRoute(exerciseId),
                        )
                    },
                    onNavigateToEquipmentRack = {
                        navController.navigate(NavigationRoutes.EquipmentRack.route)
                    },
                    onNavigateToBadges = {
                        navController.navigate(NavigationRoutes.Badges.route)
                    },
                    onProfileRecoveryRequired = onProfileRecoveryRequired,
                    isConnected = connectionState is ConnectionState.Connected,
                    discoModeActive = discoModeActive,
                    onDiscoModeToggle = viewModel::toggleDiscoMode,
                    onPlayDiscoUnlockSound = viewModel::emitDiscoSound,
                    onPlayDominatrixUnlockSound = viewModel::emitDominatrixUnlockSound,
                    enableVideoPlayback = userPreferences.enableVideoPlayback,
                    themeMode = themeMode,
                )
            }

            // Exercise Detail screen - drill-down for individual exercise
            composable(
                route = NavigationRoutes.ExerciseDetail.route,
                arguments = listOf(navArgument("exerciseId") { type = NavType.StringType }),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                val exerciseId = backStackEntry.arguments?.read { getStringOrNull("exerciseId") }

                // Handle null/invalid exerciseId - navigate back instead of blank screen
                if (exerciseId.isNullOrBlank()) {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                    return@composable
                }

                val assessmentProfileId = readyAssessmentProfileId()
                ExerciseDetailScreen(
                    exerciseId = exerciseId,
                    navController = navController,
                    viewModel = viewModel,
                    themeMode = themeMode,
                    assessmentProfileId = assessmentProfileId,
                    onNavigateToStrengthAssessment = { profileId ->
                        navController.navigate(
                            NavigationRoutes.StrengthAssessment.createRoute(profileId, exerciseId),
                        )
                    },
                )
            }

            // Settings screen
            composable(
                route = NavigationRoutes.Settings.route,
                enterTransition = { NavTransitions.tabFadeEnter() },
                exitTransition = { NavTransitions.tabFadeExit() },
                popEnterTransition = { NavTransitions.tabFadeEnter() },
                popExitTransition = { NavTransitions.tabFadeExit() },
            ) {
                val globalSettings by viewModel.globalSettings.collectAsState()
                val connectionError by viewModel.connectionError.collectAsState()
                val backupStats by viewModel.backupStats.collectAsState()
                // Refresh backup stats when Settings screen is displayed
                LaunchedEffect(Unit) { viewModel.refreshBackupStats() }
                SettingsTab(
                    enableVideoPlayback = globalSettings.enableVideoPlayback,
                    themeMode = themeMode,
                    dynamicColorAvailable = dynamicColorAvailable,
                    dynamicColorEnabled = dynamicColorEnabled,
                    onEnableVideoPlaybackChange = viewModel::setEnableVideoPlayback,
                    onThemeModeChange = onThemeModeChange,
                    onDynamicColorEnabledChange = onDynamicColorEnabledChange,
                    onDeleteAllWorkouts = viewModel::deleteAllWorkouts,
                    onNavigateToConnectionLogs = {
                        navController.navigate(NavigationRoutes.ConnectionLogs.route)
                    },
                    onNavigateToDiagnostics = {
                        navController.navigate(NavigationRoutes.Diagnostics.route)
                    },
                    onNavigateToLinkAccount = {
                        navController.navigate(NavigationRoutes.LinkAccount.route)
                    },
                    onNavigateToIntegrations = {
                        navController.navigate(NavigationRoutes.Integrations.route)
                    },
                    connectionError = connectionError,
                    onClearConnectionError = viewModel::clearConnectionError,
                    onSetTitle = viewModel::updateTopBarTitle,
                    onTestSounds = viewModel::testSounds,
                    bleCompatibilityMode = globalSettings.bleCompatibilityMode,
                    onBleCompatibilityModeChange = viewModel::setBleCompatibilityMode,
                    autoBackupEnabled = globalSettings.autoBackupEnabled,
                    onAutoBackupEnabledChange = viewModel::setAutoBackupEnabled,
                    backupStats = backupStats,
                    onOpenBackupFolder = viewModel::openBackupFolder,
                    backupDestination = globalSettings.backupDestination,
                    onBackupDestinationChange = viewModel::setBackupDestination,
                    selectedLanguage = globalSettings.language,
                    onLanguageChange = viewModel::setLanguage,
                )
            }

            // Equipment Rack screen - local accessories and active load context
            composable(
                route = NavigationRoutes.EquipmentRack.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                EquipmentRackScreen(viewModel = viewModel)
            }

            // Connection Logs screen - debug BLE connections
            composable(
                route = NavigationRoutes.ConnectionLogs.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ConnectionLogsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    mainViewModel = viewModel,
                )
            }

            // Diagnostics screen - official-style machine diagnostics
            composable(
                route = NavigationRoutes.Diagnostics.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                DiagnosticsScreen(
                    mainViewModel = viewModel,
                )
            }

            // Badges screen - achievements and gamification
            composable(
                route = NavigationRoutes.Badges.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                BadgesScreen(
                    onBack = { navController.popBackStack() },
                    mainViewModel = viewModel,
                )
            }

            // Routine Editor - create/edit daily routine
            composable(
                route = NavigationRoutes.RoutineEditor.route,
                arguments = listOf(navArgument("routineId") { type = NavType.StringType }),
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
                    enableVideoPlayback = enableVideo,
                )
            }

            // Cycle Editor - timeline builder for rolling schedules
            composable(
                route = NavigationRoutes.CycleEditor.route,
                arguments = listOf(
                    navArgument("cycleId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val cycleId = backStackEntry.arguments?.read { getStringOrNull("cycleId") } ?: "new"
                val routines by viewModel.routines.collectAsState()

                CycleEditorScreen(
                    cycleId = cycleId,
                    navController = navController,
                    viewModel = viewModel,
                    routines = routines,
                )
            }

            // Cycle Review - preview before final save
            composable(
                route = NavigationRoutes.CycleReview.route,
                arguments = listOf(navArgument("cycleId") { type = NavType.StringType }),
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
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator(LoadingIndicatorSize.Large)
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
                            viewModel = viewModel,
                        )
                    }
                }
            }

            // Strength Assessment Picker - no exercise pre-selected
            composable(
                route = NavigationRoutes.StrengthAssessmentPicker.route,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                val profileId =
                    backStackEntry.arguments?.read { getStringOrNull("profileId") }.orEmpty()
                AssessmentDestination(
                    routeProfileId = profileId,
                    exerciseId = null,
                    themeMode = themeMode,
                    onNavigateBack = { navController.popBackStack() },
                    metricsFlow = viewModel.currentMetric,
                )
            }

            // Strength Assessment with pre-selected exercise
            composable(
                route = NavigationRoutes.StrengthAssessment.route,
                arguments = listOf(
                    navArgument("profileId") { type = NavType.StringType },
                    navArgument("exerciseId") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                val profileId =
                    backStackEntry.arguments?.read { getStringOrNull("profileId") }.orEmpty()
                val exerciseId =
                    backStackEntry.arguments?.read { getStringOrNull("exerciseId") }.orEmpty()
                AssessmentDestination(
                    routeProfileId = profileId,
                    exerciseId = exerciseId,
                    themeMode = themeMode,
                    onNavigateBack = { navController.popBackStack() },
                    metricsFlow = viewModel.currentMetric,
                )
            }

            // Link Account screen - cloud sync with Phoenix Portal
            composable(
                route = NavigationRoutes.LinkAccount.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                LinkAccountScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // Integrations screen - third-party app connections and CSV import/export
            composable(
                route = NavigationRoutes.Integrations.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                val weightUnit by viewModel.weightUnit.collectAsState()
                IntegrationsScreen(
                    weightUnit = weightUnit,
                    onNavigateToExternalData = {
                        navController.navigate(NavigationRoutes.ExternalIntegrationHub.route)
                    },
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            // External integration data hub
            composable(
                route = NavigationRoutes.ExternalIntegrationHub.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ExternalIntegrationHubScreen(
                    onNavigateToActivities = { navController.navigate(NavigationRoutes.ExternalActivities.route) },
                    onNavigateToRoutines = { navController.navigate(NavigationRoutes.ExternalRoutines.route) },
                    onNavigateToPrograms = { navController.navigate(NavigationRoutes.ExternalPrograms.route) },
                    onNavigateToMeasurements = { navController.navigate(NavigationRoutes.ExternalMeasurementTrends.route) },
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            // External Activities screen - list of imported workouts from third-party apps
            composable(
                route = NavigationRoutes.ExternalActivities.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ExternalActivitiesScreen(
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalRoutines.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ExternalRoutinesScreen(
                    onRoutineClick = { provider, externalId ->
                        navController.navigate(NavigationRoutes.ExternalRoutineDetail.createRoute(provider.key, externalId))
                    },
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalRoutineDetail.route,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("externalRoutineId") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                ExternalRoutineDetailScreen(
                    providerKey = backStackEntry.arguments?.read { getStringOrNull("provider") } ?: "",
                    externalRoutineId = backStackEntry.arguments?.read { getStringOrNull("externalRoutineId") } ?: "",
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalPrograms.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ExternalProgramsScreen(
                    onProgramClick = { provider, externalId ->
                        navController.navigate(NavigationRoutes.ExternalProgramDetail.createRoute(provider.key, externalId))
                    },
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalProgramDetail.route,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("externalProgramId") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                ExternalProgramDetailScreen(
                    providerKey = backStackEntry.arguments?.read { getStringOrNull("provider") } ?: "",
                    externalProgramId = backStackEntry.arguments?.read { getStringOrNull("externalProgramId") } ?: "",
                    onNavigateToPlayground = { provider, externalId ->
                        navController.navigate(NavigationRoutes.ExternalProgramPlayground.createRoute(provider.key, externalId))
                    },
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalProgramPlayground.route,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("externalProgramId") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) { backStackEntry ->
                ExternalProgramPlaygroundScreen(
                    providerKey = backStackEntry.arguments?.read { getStringOrNull("provider") } ?: "",
                    externalProgramId = backStackEntry.arguments?.read { getStringOrNull("externalProgramId") } ?: "",
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }

            composable(
                route = NavigationRoutes.ExternalMeasurementTrends.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    )
                },
            ) {
                ExternalMeasurementTrendsScreen(
                    onSetTitle = { viewModel.updateTopBarTitle(it) },
                )
            }
        }
    }
}
