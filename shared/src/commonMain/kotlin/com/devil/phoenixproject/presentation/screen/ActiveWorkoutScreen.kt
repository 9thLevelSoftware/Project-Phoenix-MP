package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.BadgeCelebrationQueue
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.PRCelebrationDialog
import org.koin.compose.koinInject
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
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

    // Use the new state holder pattern for cleaner API
    val workoutUiState = remember(
        connectionState, workoutState, currentMetric, currentHeuristicKgMax, workoutParameters,
        repCount, repRanges, autoStopState, weightUnit, enableVideoPlayback,
        loadedRoutine, currentExerciseIndex, currentSetIndex, userPreferences.autoplayEnabled,
        loadBaselineA, loadBaselineB
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
            autoplayEnabled = userPreferences.autoplayEnabled,
            isWorkoutSetupDialogVisible = false,
            showConnectionCard = false,
            showWorkoutSetupCard = false,
            loadBaselineA = loadBaselineA,
            loadBaselineB = loadBaselineB
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
            onProceedFromSummary = { viewModel.proceedFromSummary() },
            onRpeLogged = { rpe -> viewModel.logRpeForCurrentSet(rpe) },
            onResetForNewWorkout = { viewModel.resetForNewWorkout() },
            onStartNextExercise = { viewModel.advanceToNextExercise() },
            onJumpToExercise = { /* Not used in ActiveWorkoutScreen */ },
            onUpdateParameters = { viewModel.updateWorkoutParameters(it) },
            onShowWorkoutSetupDialog = { /* Not used in ActiveWorkoutScreen */ },
            onHideWorkoutSetupDialog = { /* Not used in ActiveWorkoutScreen */ },
            kgToDisplay = viewModel::kgToDisplay,
            displayToKg = viewModel::displayToKg,
            formatWeight = viewModel::formatWeight
        )
    }

    WorkoutTab(
        state = workoutUiState,
        actions = workoutActions,
        exerciseRepository = exerciseRepository,
        hapticEvents = hapticEvents
    )

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
                        viewModel.stopWorkout()
                        showExitConfirmation = false
                        navController.navigateUp()
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
            onDismiss = { prCelebrationEvent = null },
            onSoundTrigger = { viewModel.emitPRSound() }
        )
    }

    // Badge Celebration Dialog Queue
    if (earnedBadges.isNotEmpty()) {
        BadgeCelebrationQueue(
            badges = earnedBadges,
            onAllCelebrated = { earnedBadges = emptyList() },
            onMarkCelebrated = { badgeId ->
                kotlinx.coroutines.MainScope().launch {
                    gamificationRepository.markBadgeCelebrated(badgeId)
                }
            },
            onSoundTrigger = { viewModel.emitBadgeSound() }
        )
    }
}
