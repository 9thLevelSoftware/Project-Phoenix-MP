package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.BatchedBadgeCelebrationDialog
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.PRCelebrationDialog
import org.koin.compose.koinInject
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.setKeepScreenOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Active Workout screen - displays workout controls and metrics during an active workout.
 * This screen is shown when a workout is in progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val workoutState by viewModel.workoutState.collectAsState()
    val currentMetric by viewModel.currentMetric.collectAsState()
    val currentHeuristicKgMax by viewModel.currentHeuristicKgMax.collectAsState()
    val workoutParameters by viewModel.workoutParameters.collectAsState()
    val repCount by viewModel.repCount.collectAsState()
    val repRanges by viewModel.repRanges.collectAsState()
    val autoStopState by viewModel.autoStopState.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val currentExerciseIndex by viewModel.currentExerciseIndex.collectAsState()
    val currentSetIndex by viewModel.currentSetIndex.collectAsState()
    val hapticEvents = viewModel.hapticEvents
    val connectionState by viewModel.connectionState.collectAsState()
    // Load baseline for base tension subtraction (~4kg per cable)
    val loadBaselineA by viewModel.loadBaselineA.collectAsState()
    val loadBaselineB by viewModel.loadBaselineB.collectAsState()
    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()
    val routineFlowState by viewModel.routineFlowState.collectAsState()

    // State for confirmation dialog
    var showExitConfirmation by remember { mutableStateOf(false) }

    // PR Celebration state
    var prCelebrationEvent by remember { mutableStateOf<PRCelebrationEvent?>(null) }
    LaunchedEffect(Unit) {
        viewModel.prCelebrationEvent.collect { event ->
            prCelebrationEvent = event
        }
    }

    // Badge Celebration state
    val gamificationRepository: GamificationRepository = koinInject()
    var earnedBadges by remember { mutableStateOf<List<Badge>>(emptyList()) }
    LaunchedEffect(Unit) {
        viewModel.badgeEarnedEvents.collect { badges ->
            earnedBadges = badges
        }
    }

    // Issue #172: Snackbar for user feedback messages (e.g., navigation blocked)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.userFeedbackEvents.collect { message ->
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Keep screen on during workout
    DisposableEffect(Unit) {
        setKeepScreenOn(true)
        onDispose {
            setKeepScreenOn(false)
        }
    }

    // Dynamic title based on workout type
    val screenTitle = remember(loadedRoutine, workoutParameters.isJustLift) {
        when {
            loadedRoutine != null -> loadedRoutine?.name ?: "Routine"
            workoutParameters.isJustLift -> "Just Lift"
            else -> "Single Exercise"
        }
    }

    // Set global title
    LaunchedEffect(screenTitle) {
        viewModel.updateTopBarTitle(screenTitle)
    }

    // Handle Back Button (System + Top Bar)
    LaunchedEffect(Unit) {
        val onBack: () -> Unit = {
            // Show confirmation if workout is active
            if (viewModel.workoutState.value is WorkoutState.Active ||
                viewModel.workoutState.value is WorkoutState.Resting ||
                viewModel.workoutState.value is WorkoutState.Countdown
            ) {
                showExitConfirmation = true
            } else {
                navController.navigateUp()
            }
        }
        viewModel.setTopBarBackAction(onBack)
    }

    // Clean up back action
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearTopBarBackAction()
        }
    }

    // Note: HapticFeedbackEffect is now global in EnhancedMainScreen
    // No need for local haptic effect here

    // Navigation guard to prevent double navigateUp() calls (Issue #204)
    // The LaunchedEffect can re-trigger if workoutParameters changes during navigation,
    // causing navigateUp() to be called twice (ActiveWorkout → JustLift → Home)
    var hasNavigatedAway by remember { mutableStateOf(false) }

    // Watch for workout completion and navigate back
    // For Just Lift, navigate back when state becomes Idle (after auto-reset)
    // Key only on workoutState to avoid re-triggering on workoutParameters changes
    LaunchedEffect(workoutState) {
        // Guard against double navigation
        if (hasNavigatedAway) return@LaunchedEffect

        Logger.d { "ActiveWorkoutScreen: workoutState=$workoutState, isJustLift=${workoutParameters.isJustLift}" }
        when {
            workoutState is WorkoutState.Completed -> {
                Logger.d { "ActiveWorkoutScreen: Workout completed, navigating back in 2s" }
                delay(2000)
                hasNavigatedAway = true
                navController.navigateUp()
            }
            workoutState is WorkoutState.Idle && workoutParameters.isJustLift -> {
                // Just Lift completed and reset to Idle - navigate back to Just Lift screen
                Logger.d { "ActiveWorkoutScreen: Just Lift idle, navigating back to JustLiftScreen" }
                hasNavigatedAway = true
                navController.navigateUp()
            }
            workoutState is WorkoutState.Idle && (loadedRoutine == null || loadedRoutine?.id?.startsWith(MainViewModel.TEMP_SINGLE_EXERCISE_PREFIX) == true) -> {
                // Single Exercise completed and reset to Idle - navigate back to SingleExerciseScreen
                Logger.d { "ActiveWorkoutScreen: Single Exercise idle, navigating back" }
                hasNavigatedAway = true
                navController.navigateUp()
            }
            workoutState is WorkoutState.Error -> {
                // Show error for 3 seconds then navigate back
                Logger.e { "ActiveWorkoutScreen: Error state, navigating back in 3s" }
                delay(3000)
                hasNavigatedAway = true
                navController.navigateUp()
            }
        }
    }

    // Watch for routine flow state changes to navigate to SetReady or Complete screens
    // This handles the autoplay OFF case where Summary -> SetReady (no rest timer)
    // IMPORTANT: Only navigate when workout is Idle - otherwise we create a navigation loop
    // because startSetFromReady() sets workoutState to Countdown/Active but keeps routineFlowState
    // as SetReady. SetReadyScreen navigates here on workoutState change, and if we navigate back
    // immediately based on routineFlowState still being SetReady, we get infinite flickering.
    LaunchedEffect(routineFlowState, workoutState) {
        if (hasNavigatedAway) return@LaunchedEffect

        // Only navigate to SetReady when workout has finished (Idle state)
        // During Active/Countdown/Summary/Resting, we should stay on this screen
        // Issue #142: Added SetSummary and Resting to prevent immediate navigation away
        // before user can see the set summary screen with countdown
        val isWorkoutActive = workoutState is WorkoutState.Active ||
                              workoutState is WorkoutState.Countdown ||
                              workoutState is WorkoutState.Initializing ||
                              workoutState is WorkoutState.SetSummary ||
                              workoutState is WorkoutState.Resting

        when (routineFlowState) {
            is RoutineFlowState.SetReady -> {
                if (!isWorkoutActive) {
                    Logger.d { "ActiveWorkoutScreen: RoutineFlowState.SetReady + Idle - navigating to SetReady" }
                    hasNavigatedAway = true
                    navController.navigate(NavigationRoutes.SetReady.route) {
                        popUpTo(NavigationRoutes.RoutineOverview.route) { inclusive = false }
                    }
                }
            }
            is RoutineFlowState.Complete -> {
                Logger.d { "ActiveWorkoutScreen: RoutineFlowState.Complete - navigating to RoutineComplete" }
                hasNavigatedAway = true
                navController.navigate(NavigationRoutes.RoutineComplete.route) {
                    popUpTo(NavigationRoutes.RoutineOverview.route) { inclusive = false }
                }
            }
            else -> {}
        }
    }

    // Use the new state holder pattern for cleaner API
    // Issue #53: Compute canGoBack/canSkipForward based on routine and exercise index
    val canGoBack = loadedRoutine != null && currentExerciseIndex > 0
    val canSkipForward = loadedRoutine != null && currentExerciseIndex < (loadedRoutine?.exercises?.size ?: 0) - 1

    // Issue #167: autoplayEnabled now derived from summaryCountdownSeconds
    // 0 (Unlimited) = autoplay OFF, != 0 (-1 or 5-30) = autoplay ON
    val autoplayEnabled = userPreferences.summaryCountdownSeconds != 0

    val workoutUiState = remember(
        connectionState, workoutState, currentMetric, currentHeuristicKgMax, workoutParameters,
        repCount, repRanges, autoStopState, weightUnit, enableVideoPlayback,
        loadedRoutine, currentExerciseIndex, currentSetIndex, autoplayEnabled,
        userPreferences.summaryCountdownSeconds, loadBaselineA, loadBaselineB, canGoBack, canSkipForward
    ) {
        WorkoutUiState(
            connectionState = connectionState,
            workoutState = workoutState,
            currentMetric = currentMetric,
            currentHeuristicKgMax = currentHeuristicKgMax,
            workoutParameters = workoutParameters,
            repCount = repCount,
            repRanges = repRanges,
            autoStopState = autoStopState,
            weightUnit = weightUnit,
            enableVideoPlayback = enableVideoPlayback,
            loadedRoutine = loadedRoutine,
            currentExerciseIndex = currentExerciseIndex,
            currentSetIndex = currentSetIndex,
            autoplayEnabled = autoplayEnabled,
            summaryCountdownSeconds = userPreferences.summaryCountdownSeconds,
            isWorkoutSetupDialogVisible = false,
            showConnectionCard = false,
            showWorkoutSetupCard = false,
            loadBaselineA = loadBaselineA,
            loadBaselineB = loadBaselineB,
            canGoBack = canGoBack,
            canSkipForward = canSkipForward
        )
    }

    val workoutActions = remember(viewModel) {
        workoutActions(
            onScan = { viewModel.startScanning() },
            onCancelScan = { viewModel.cancelScanOrConnection() },
            onDisconnect = { viewModel.disconnect() },
            onStartWorkout = {
                viewModel.ensureConnection(
                    onConnected = { viewModel.startWorkout() },
                    onFailed = { /* Error shown via StateFlow */ }
                )
            },
            onStopWorkout = { showExitConfirmation = true },
            onSkipRest = { viewModel.skipRest() },
            onSkipCountdown = { viewModel.skipCountdown() },
            onProceedFromSummary = { viewModel.proceedFromSummary() },
            onRpeLogged = { rpe -> viewModel.logRpeForCurrentSet(rpe) },
            onResetForNewWorkout = { viewModel.resetForNewWorkout() },
            onStartNextExercise = { viewModel.advanceToNextExercise() },
            onJumpToExercise = { viewModel.jumpToExercise(it) },
            onUpdateParameters = { viewModel.updateWorkoutParameters(it) },
            onShowWorkoutSetupDialog = { /* Not used in ActiveWorkoutScreen */ },
            onHideWorkoutSetupDialog = { /* Not used in ActiveWorkoutScreen */ },
            kgToDisplay = viewModel::kgToDisplay,
            displayToKg = viewModel::displayToKg,
            formatWeight = viewModel::formatWeight
        )
    }

    // Issue #172: Scaffold wrapper for Snackbar support (user feedback messages)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        WorkoutTab(
            state = workoutUiState,
            actions = workoutActions,
            exerciseRepository = exerciseRepository,
            hapticEvents = hapticEvents,
            modifier = Modifier.padding(paddingValues)
        )
    }

    // Exit confirmation dialog
    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Exit Workout?") },
            text = { Text("The workout is currently active. Are you sure you want to exit?") },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            confirmButton = {
                Button(
                    onClick = {
                        // Use exitingWorkout=true to reset state to Idle and clear routine context
                        // This prevents stale SetSummary state from blocking editing after exit
                        viewModel.stopWorkout(exitingWorkout = true)
                        showExitConfirmation = false

                        // Smart navigation: if in routine flow, go back to DailyRoutines
                        // to avoid blank RoutineOverviewScreen (which returns early when routine is null)
                        if (routineFlowState != RoutineFlowState.NotInRoutine) {
                            navController.popBackStack(NavigationRoutes.DailyRoutines.route, inclusive = false)
                        } else {
                            navController.navigateUp()
                        }
                    }
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
    connectionError?.let { error ->
        ConnectionErrorDialog(
            message = error,
            onDismiss = { viewModel.clearConnectionError() }
        )
    }

    // PR Celebration Dialog
    prCelebrationEvent?.let { event ->
        PRCelebrationDialog(
            show = true,
            exerciseName = event.exerciseName,
            weight = "${viewModel.formatWeight(event.weightPerCableKg, weightUnit)}/cable × ${event.reps} reps",
            workoutMode = event.workoutMode,
            onDismiss = { prCelebrationEvent = null },
            onSoundTrigger = { viewModel.emitPRSound() }
        )
    }

    // Batched Badge Celebration Dialog
    if (earnedBadges.isNotEmpty()) {
        val scope = rememberCoroutineScope()
        BatchedBadgeCelebrationDialog(
            badges = earnedBadges,
            onDismiss = { earnedBadges = emptyList() },
            onMarkAllCelebrated = { badgeIds ->
                scope.launch {
                    gamificationRepository.markBadgesCelebrated(badgeIds)
                }
            },
            onSoundTrigger = {}  // Empty - sound is now handled by ViewModel
        )
    }
}
