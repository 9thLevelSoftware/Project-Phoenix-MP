package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.components.BatchedBadgeCelebrationDialog
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.PRCelebrationDialog
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.navigation.safePopOrNavigate
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_continue_set
import vitruvianprojectphoenix.shared.generated.resources.action_exit
import vitruvianprojectphoenix.shared.generated.resources.end_workout
import vitruvianprojectphoenix.shared.generated.resources.exit_workout_message
import vitruvianprojectphoenix.shared.generated.resources.exit_workout_title
import vitruvianprojectphoenix.shared.generated.resources.skip_exercise
import vitruvianprojectphoenix.shared.generated.resources.stop_current_set_message
import vitruvianprojectphoenix.shared.generated.resources.stop_current_set_title
import vitruvianprojectphoenix.shared.generated.resources.stop_set

/**
 * Active Workout screen - displays workout controls and metrics during an active workout.
 * This screen is shown when a workout is in progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(navController: NavController, viewModel: MainViewModel, exerciseRepository: ExerciseRepository) {
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
    // Issue #152: Collect skipped/completed sets for ExerciseNavigator dot state
    val skippedExercises by viewModel.skippedExercises.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()
    val hapticEvents = viewModel.hapticEvents
    val connectionState by viewModel.connectionState.collectAsState()
    // Load baseline for base tension subtraction (~4kg per cable)
    val loadBaselineA by viewModel.loadBaselineA.collectAsState()
    val loadBaselineB by viewModel.loadBaselineB.collectAsState()
    // Issue #192: Timed exercise countdown for duration-based exercises
    val timedExerciseRemainingSeconds by viewModel.timedExerciseRemainingSeconds.collectAsState()
    val isCurrentExerciseBodyweight by viewModel.isCurrentExerciseBodyweight.collectAsState()
    val latestRepQuality by viewModel.latestRepQuality.collectAsState()
    val latestBiomechanicsResult by viewModel.latestBiomechanicsResult.collectAsState()
    // Issue #237: Motion-triggered set start
    val motionStartHoldProgress by viewModel.motionStartHoldProgress.collectAsState()
    // Issue #297, #228: Rest timer pause state
    val isRestPaused by viewModel.isRestPaused.collectAsState()
    // Phase 35C: Variable warm-up set state
    val currentWarmupSetIndex by viewModel.currentWarmupSetIndex.collectAsState()
    val totalWarmupSets by viewModel.totalWarmupSets.collectAsState()
    // Issue #113: Just Lift visual rest countdown
    val justLiftRestCountdown by viewModel.justLiftRestCountdown.collectAsState()
    // Issue #190: Exercise timer pause state
    val isExerciseTimerPaused by viewModel.isExerciseTimerPaused.collectAsState()
    val currentRackLoadAdjustment by viewModel.currentRackLoadAdjustment.collectAsState()

    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()
    val routineFlowState by viewModel.routineFlowState.collectAsState()

    // State for confirmation dialog
    var showExitConfirmation by remember { mutableStateOf(false) }
    val isRoutineFlow = routineFlowState != RoutineFlowState.NotInRoutine

    // PR Celebration state
    var prCelebrationEvent by remember { mutableStateOf<PRCelebrationEvent?>(null) }
    LaunchedEffect(Unit) {
        viewModel.prCelebrationEvent.collect { event ->
            prCelebrationEvent = event
        }
    }

    // Badge Celebration state
    val gamificationRepository: GamificationRepository = koinInject()
    val userProfileRepository: UserProfileRepository = koinInject()
    var earnedBadges by remember { mutableStateOf<List<Badge>>(emptyList()) }
    LaunchedEffect(Unit) {
        viewModel.badgeEarnedEvents.collect { badges ->
            earnedBadges = badges
        }
    }

    // Issue #141: Voice-activated emergency stop via safe word detection
    val safeWordManager: com.devil.phoenixproject.domain.voice.SafeWordDetectionManager =
        koinInject()
    LaunchedEffect(Unit) {
        safeWordManager.startForWorkout()
        safeWordManager.detectedWord.collect {
            Logger.i { "Safe word detected — stopping current set" }
            viewModel.stopAndReturnToSetReady()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            safeWordManager.stop()
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
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Issue #348: Wake lock moved to EnhancedMainScreen (session-scoped) so it
    // stays active across SetReady ↔ ActiveWorkout navigation during routines.

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
    // Hoisted out of LaunchedEffect so BackHandler and setTopBarBackAction share one code path.
    // Guard change (lens-navigation-ux-1): ALL active states show the confirmation dialog.
    // UNSAFE states (Active/Countdown/Initializing): no machine command before user confirms.
    // SAFE states (Resting/SetSummary/BodyweightRepEntry): machine is already stopped but dialog
    // gives user access to Skip Exercise and End Workout — per lens-navigation-ux-1 recommendation
    // to set showExitConfirmation = true unconditionally when isWorkoutActive is true.
    val onBack: () -> Unit = remember(viewModel, navController) {
        fun() {
            val workoutStateValue = viewModel.workoutState.value
            val isWorkoutActive = workoutStateValue is WorkoutState.Active ||
                workoutStateValue is WorkoutState.Resting ||
                workoutStateValue is WorkoutState.Countdown ||
                workoutStateValue is WorkoutState.Initializing ||
                workoutStateValue is WorkoutState.SetSummary ||
                workoutStateValue is WorkoutState.BodyweightRepEntry
            if (isWorkoutActive) {
                showExitConfirmation = true
            } else {
                navController.navigateUp()
            }
        }
    }

    // Wire to system back (BackHandler) AND top-bar back — single code path for both sources.
    // Keyed on onBack so a re-remembered lambda (viewModel/navController change) re-registers
    // instead of leaving the top bar holding a stale callback (review finding 4B.1/IMPORTANT-1).
    BackHandler { onBack() }
    LaunchedEffect(onBack) {
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

        Logger.d {
            "ActiveWorkoutScreen: workoutState=$workoutState, isJustLift=${workoutParameters.isJustLift}"
        }
        when {
            workoutState is WorkoutState.Completed -> {
                Logger.d { "ActiveWorkoutScreen: Workout completed, navigating back in 2s" }
                delay(2000)
                hasNavigatedAway = true
                navController.navigateUp()
            }

            workoutState is WorkoutState.Idle && workoutParameters.isJustLift -> {
                // Just Lift completed and reset to Idle - navigate back to Just Lift screen
                Logger.d {
                    "ActiveWorkoutScreen: Just Lift idle, navigating back to JustLiftScreen"
                }
                hasNavigatedAway = true
                navController.navigateUp()
            }

            workoutState is WorkoutState.Idle &&
                (
                    loadedRoutine == null ||
                        loadedRoutine?.id?.startsWith(
                            DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX,
                        ) ==
                        true
                    ) -> {
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
            workoutState is WorkoutState.BodyweightRepEntry ||
            workoutState is WorkoutState.Resting

        when (routineFlowState) {
            is RoutineFlowState.SetReady -> {
                if (!isWorkoutActive) {
                    Logger.d {
                        "ActiveWorkoutScreen: RoutineFlowState.SetReady + Idle - navigating to SetReady"
                    }
                    hasNavigatedAway = true
                    navController.navigate(NavigationRoutes.SetReady.route) {
                        // Issue #541: see comment at the first SetReady nav site in this file.
                        popUpTo(NavigationRoutes.ActiveWorkout.route) { inclusive = true }
                    }
                }
            }

            is RoutineFlowState.Complete -> {
                // Issue #393: Guard against navigating while workoutState is still active.
                // Without this, the Complete navigation fires while workoutState is SetSummary,
                // allowing EnhancedMainScreen's shouldResumeActiveWorkout guard to bounce back,
                // creating duplicate RoutineComplete screens (visible as overlapping garbled UI).
                if (!isWorkoutActive) {
                    Logger.d {
                        "ActiveWorkoutScreen: RoutineFlowState.Complete + Idle - navigating to RoutineComplete"
                    }
                    hasNavigatedAway = true
                    navController.navigate(NavigationRoutes.RoutineComplete.route) {
                        popUpTo(NavigationRoutes.RoutineOverview.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }

            else -> {}
        }
    }

    // Use the new state holder pattern for cleaner API
    // Issue #53: Compute canGoBack/canSkipForward based on routine and exercise index
    // Issue #152: Defensive gating — also disable during Active state (belt-and-suspenders
    // with the navigator visibility check in WorkoutTab)
    val isSetActive = workoutState is WorkoutState.Active || workoutState is WorkoutState.BodyweightRepEntry
    val canGoBack = !isSetActive && loadedRoutine != null && currentExerciseIndex > 0
    val canSkipForward =
        !isSetActive && loadedRoutine != null &&
            currentExerciseIndex < (loadedRoutine?.exercises?.size ?: 0) - 1

    // Issue #167: autoplayEnabled now derived from summaryCountdownSeconds
    // 0 (Unlimited) = autoplay OFF, != 0 (-1 or 5-30) = autoplay ON
    val autoplayEnabled = userPreferences.summaryCountdownSeconds != 0

    val workoutUiState = remember(
        connectionState, workoutState, currentMetric, currentHeuristicKgMax, workoutParameters,
        repCount, repRanges, autoStopState, weightUnit, enableVideoPlayback,
        loadedRoutine, currentExerciseIndex, currentSetIndex, skippedExercises, completedExercises,
        autoplayEnabled, userPreferences.summaryCountdownSeconds, loadBaselineA, loadBaselineB,
        canGoBack, canSkipForward,
        timedExerciseRemainingSeconds, isCurrentExerciseBodyweight, latestRepQuality,
        latestBiomechanicsResult,
        motionStartHoldProgress, isRestPaused,
        currentWarmupSetIndex, totalWarmupSets,
        justLiftRestCountdown, isExerciseTimerPaused,
        userPreferences.velocityLossThresholdPercent,
        userPreferences.effectiveWeightIncrementKg,
        currentRackLoadAdjustment,
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
            skippedExercises = skippedExercises,
            completedExercises = completedExercises,
            autoplayEnabled = autoplayEnabled,
            summaryCountdownSeconds = userPreferences.summaryCountdownSeconds,
            isWorkoutSetupDialogVisible = false,
            showConnectionCard = false,
            showWorkoutSetupCard = false,
            loadBaselineA = loadBaselineA,
            loadBaselineB = loadBaselineB,
            canGoBack = canGoBack,
            canSkipForward = canSkipForward,
            timedExerciseRemainingSeconds = timedExerciseRemainingSeconds,
            isCurrentExerciseBodyweight = isCurrentExerciseBodyweight,
            latestRepQualityScore = latestRepQuality?.composite,
            latestBiomechanicsResult = latestBiomechanicsResult,
            motionStartHoldProgress = motionStartHoldProgress,
            isRestPaused = isRestPaused,
            currentWarmupSetIndex = currentWarmupSetIndex,
            totalWarmupSets = totalWarmupSets,
            justLiftRestCountdown = justLiftRestCountdown,
            isExerciseTimerPaused = isExerciseTimerPaused,
            velocityLossThresholdPercent = userPreferences.velocityLossThresholdPercent,
            weightStepKg = userPreferences.effectiveWeightIncrementKg,
            rackLoadAdjustment = currentRackLoadAdjustment,
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
                    onFailed = { /* Error shown via StateFlow */ },
                )
            },
            onStopWorkout = { showExitConfirmation = true },
            onSkipRest = { viewModel.skipRest() },
            onExtendRest = { seconds -> viewModel.extendRestTime(seconds) },
            onToggleRestPause = { viewModel.toggleRestPause() },
            onResetRest = { viewModel.resetRestTimer() },
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
            formatWeight = viewModel::formatWeight,
            onTagJustLiftSessionExercise = { sessionId, exercise, isAmrap ->
                viewModel.tagJustLiftSessionExercise(sessionId, exercise, isAmrap)
            },
            onPauseExerciseTimer = { viewModel.pauseExerciseTimer() },
            onResumeExerciseTimer = { viewModel.resumeExerciseTimer() },
            onResetExerciseTimer = { viewModel.resetExerciseTimer() },
            onConfirmBodyweightSetResult = { reps, variant ->
                viewModel.confirmBodyweightSetResult(reps, variant)
            },
        )
    }

    // Issue #172: Scaffold wrapper for Snackbar support (user feedback messages)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            WorkoutTab(
                state = workoutUiState,
                actions = workoutActions,
                exerciseRepository = exerciseRepository,
                hapticEvents = hapticEvents,
                modifier = Modifier,
            )
        }
    }

    // Exit confirmation dialog
    if (showExitConfirmation) {
        if (isRoutineFlow) {
            // Redesigned per workout-execution-4 + lens-navigation-ux-15:
            // Primary safe action (Continue Set) in confirmButton; secondary actions
            // (Stop Set / Skip Exercise / End Workout) as full-width OutlinedButtons in
            // the text content area — matches ResumeRoutineDialog pattern, eliminates the
            // M3 dismissButton Column-of-three layout violation.
            AlertDialog(
                onDismissRequest = { showExitConfirmation = false },
                title = { Text(stringResource(Res.string.stop_current_set_title)) },
                text = {
                    Column {
                        Text(stringResource(Res.string.stop_current_set_message))
                        Spacer(Modifier.height(16.dp))
                        // Issue #320: stopAndReturnToSetReady routes through handleSetCompletion
                        // when reps > 0 (saves reps, auto-advances). Only nav to SetReady when
                        // no reps were completed (true "retry from scratch" scenario).
                        OutlinedButton(
                            onClick = {
                                val hasCompletedReps = viewModel.repCount.value.workingReps > 0
                                viewModel.stopAndReturnToSetReady()
                                showExitConfirmation = false
                                if (!hasCompletedReps) {
                                    navController.navigate(NavigationRoutes.SetReady.route) {
                                        // Issue #541: see comment at the first SetReady nav site.
                                        popUpTo(NavigationRoutes.ActiveWorkout.route) {
                                            inclusive = true
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.stop_set))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.stopAndSkipCurrentExercise()
                                showExitConfirmation = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.skip_exercise))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Error-styled via ButtonDefaults token for proper touch-target semantics
                        // (lens-navigation-ux-15: use ButtonDefaults.outlinedButtonColors rather
                        // than passing color directly to Text)
                        OutlinedButton(
                            onClick = {
                                // lens-navigation-ux-2: read destination BEFORE stopWorkout().
                                // stopWorkout(exitingWorkout=true) clears _routineFlowState to
                                // NotInRoutine inline (does NOT call exitRoutineFlow), but does
                                // NOT clear routineLaunchOrigin — origin is cleared only by
                                // exitRoutineFlow(). Read first so the correct destination is
                                // captured before state is torn down.
                                val dest = viewModel.routineExitDestination()
                                viewModel.stopWorkout(exitingWorkout = true)
                                showExitConfirmation = false
                                navController.safePopOrNavigate(dest)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Text(stringResource(Res.string.end_workout))
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                confirmButton = {
                    Button(onClick = { showExitConfirmation = false }) {
                        Text(stringResource(Res.string.action_continue_set))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showExitConfirmation = false },
                title = { Text(stringResource(Res.string.exit_workout_title)) },
                text = { Text(stringResource(Res.string.exit_workout_message)) },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                confirmButton = {
                    Button(
                        onClick = {
                            // Use exitingWorkout=true to reset state to Idle and clear routine context
                            // This prevents stale SetSummary state from blocking editing after exit
                            viewModel.stopWorkout(exitingWorkout = true)
                            showExitConfirmation = false
                            navController.navigateUp()
                        },
                    ) {
                        Text(stringResource(Res.string.action_exit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmation = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    }

    // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
    connectionError?.let { error ->
        ConnectionErrorDialog(
            message = error,
            onDismiss = { viewModel.clearConnectionError() },
        )
    }

    // PR Celebration Dialog - shows first if both PR and badges earned
    prCelebrationEvent?.let { event ->
        PRCelebrationDialog(
            show = true,
            exerciseName = event.exerciseName,
            weight = "${WeightDisplayFormatter.formatDisplayWeight(
                event.weightPerCableKg,
                cableCount = event.cableCount,
                weightUnit,
            )} × ${event.reps} reps",
            workoutMode = event.workoutMode,
            phaseLabel = event.phaseLabel,
            onDismiss = { prCelebrationEvent = null },
            onSoundTrigger = { viewModel.emitPRSound() },
        )
    }

    // Batched Badge Celebration Dialog - only shows when PR dialog is not showing (queued)
    // This prevents both dialogs from stacking and multiple sounds playing at once
    if (earnedBadges.isNotEmpty() && prCelebrationEvent == null) {
        val scope = rememberCoroutineScope()
        BatchedBadgeCelebrationDialog(
            badges = earnedBadges,
            onDismiss = { earnedBadges = emptyList() },
            onMarkAllCelebrated = { badgeIds ->
                scope.launch {
                    val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
                    gamificationRepository.markBadgesCelebrated(badgeIds, profileId)
                }
            },
            onSoundTrigger = {}, // Sound handled by ViewModel - skipped if PR already played
        )
    }
}
