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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.devil.phoenixproject.domain.voice.SafeWordState
import com.devil.phoenixproject.domain.voice.SafeWordUnavailableReason
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_continue_set
import projectphoenix.shared.generated.resources.action_exit
import projectphoenix.shared.generated.resources.action_retry
import projectphoenix.shared.generated.resources.end_workout
import projectphoenix.shared.generated.resources.exit_workout_message
import projectphoenix.shared.generated.resources.exit_workout_title
import projectphoenix.shared.generated.resources.skip_exercise
import projectphoenix.shared.generated.resources.stop_current_set_message
import projectphoenix.shared.generated.resources.stop_current_set_title
import projectphoenix.shared.generated.resources.stop_set
import projectphoenix.shared.generated.resources.voice_stop_reason_audio_focus_lost
import projectphoenix.shared.generated.resources.voice_stop_reason_not_calibrated
import projectphoenix.shared.generated.resources.voice_stop_reason_not_configured
import projectphoenix.shared.generated.resources.voice_stop_reason_permission
import projectphoenix.shared.generated.resources.voice_stop_reason_profile_switching
import projectphoenix.shared.generated.resources.voice_stop_reason_recognizer_unavailable
import projectphoenix.shared.generated.resources.voice_stop_reason_start_failed
import projectphoenix.shared.generated.resources.voice_stop_unavailable_chip
import projectphoenix.shared.generated.resources.voice_stop_unavailable_snackbar
import projectphoenix.shared.generated.resources.workout_save_failed
import projectphoenix.shared.generated.resources.workout_save_retry_failed

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
    val rackItems by viewModel.rackItems.collectAsState()
    val activeRackItemIds by viewModel.activeRackItemIds.collectAsState()
    val activeRackBehaviorOverrides by viewModel.activeRackBehaviorOverrides.collectAsState()
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
    val machineTeardownState by viewModel.machineTeardownState.collectAsState()
    val restTransitionPlan by viewModel.restTransitionPlan.collectAsState()

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

    // Issue #172: Snackbar for user feedback messages (e.g., navigation blocked)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // Issue #141: Voice-activated emergency stop via safe word detection
    val safeWordManager: com.devil.phoenixproject.domain.voice.SafeWordDetectionManager =
        koinInject()
    val safeWordState by safeWordManager.state.collectAsState()
    // F-039: warn once at the start of the set when the safe word cannot stop the
    // machine. Subscribed before startForWorkout() below so the emission is seen.
    LaunchedEffect(Unit) {
        safeWordManager.unavailableAtStart.collect { reason ->
            val message = getString(Res.string.voice_stop_unavailable_snackbar, getString(reason.messageRes()))
            snackbarScope.launch {
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            }
        }
    }
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

    // KD-9: the capped-command notice is state, not an event, because Just Lift sends the
    // command before this screen is composed. Drain it on arrival so it is shown exactly once.
    val commandLimitNotice by viewModel.commandLimitNotice.collectAsState()
    LaunchedEffect(commandLimitNotice) {
        val notice = commandLimitNotice ?: return@LaunchedEffect
        viewModel.consumeCommandLimitNotice()
        // Show it on snackbarScope, not in this effect: consuming the notice flips the state
        // back to null, which changes this LaunchedEffect's key and cancels its coroutine —
        // and showSnackbar dismisses the snackbar when cancelled. The snackbar has to outlive
        // the effect that triggered it, exactly like the feedback collector above.
        snackbarScope.launch {
            snackbarHostState.showSnackbar(message = notice, duration = SnackbarDuration.Long)
        }
    }

    // F-040: a failed save now offers the retry its message always promised.
    // The failure is a drainable StateFlow, not an event, because it can be
    // raised while this screen is not composed; draining it here keeps the
    // offer to exactly one.
    val saveFailureSessionId by viewModel.workoutSaveFailureSessionId.collectAsState()
    val saveFailedMessage = stringResource(Res.string.workout_save_failed)
    val saveRetryLabel = stringResource(Res.string.action_retry)
    val saveRetryUnavailable = stringResource(Res.string.workout_save_retry_failed)
    LaunchedEffect(saveFailureSessionId) {
        val failedSessionId = saveFailureSessionId ?: return@LaunchedEffect
        // Indefinite: losing a set is not a message to miss. It stays until the
        // user retries or dismisses it, and either answer drains the offer.
        val action = snackbarHostState.showSnackbar(
            message = saveFailedMessage,
            actionLabel = saveRetryLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (action == SnackbarResult.ActionPerformed) {
            if (!viewModel.retryWorkoutSave(failedSessionId)) {
                snackbarHostState.showSnackbar(
                    message = saveRetryUnavailable,
                    duration = SnackbarDuration.Short,
                )
            }
        } else {
            viewModel.dismissWorkoutSaveFailure(failedSessionId)
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
                        (
                            loadedRoutine?.id?.startsWith(
                                DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX,
                            ) ==
                                true &&
                                !viewModel.isStoppingWorkout()
                            )
                    ) -> {
                // Issue #660: a direct timed Stop Set returns a temp routine to SetReady.
                // Let the routine-flow observer navigate there rather than tearing down to Home.
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
        userPreferences.vbtEnabled,
        userPreferences.velocityLossThresholdPercent,
        userPreferences.effectiveWeightIncrementKg,
        currentRackLoadAdjustment,
        rackItems, activeRackItemIds, activeRackBehaviorOverrides,
        machineTeardownState,
        restTransitionPlan,
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
            vbtEnabled = userPreferences.vbtEnabled,
            velocityLossThresholdPercent = userPreferences.velocityLossThresholdPercent,
            weightStepKg = userPreferences.effectiveWeightIncrementKg,
            rackLoadAdjustment = currentRackLoadAdjustment,
            rackItems = rackItems,
            activeRackItemIds = activeRackItemIds,
            activeRackBehaviorOverrides = activeRackBehaviorOverrides,
            machineTeardownState = machineTeardownState,
            restTransitionPlan = restTransitionPlan,
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
            onRetryWorkoutTeardown = { viewModel.retryWorkoutTeardown() },
            onReconnectWorkoutTeardown = { viewModel.reconnectWorkoutTeardown() },
            onStopWorkout = { showExitConfirmation = true },
            onSkipRest = { viewModel.skipRest() },
            onSkipRestWithIdentity = { identity -> viewModel.skipRest(identity) },
            onAcceptDropSetAction = { identity, percentage -> viewModel.acceptDropSet(identity, percentage) },
            onDeclineDropSetAction = { identity -> viewModel.declineDropSet(identity) },
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
            onUpdateRackSelection = { viewModel.updateActiveRackSelection(it) },
            onUpdateRackBehaviorOverrides = { viewModel.updateActiveRackBehaviorOverrides(it) },
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
            // F-039: the safe word is a safety affordance — say so when it is not armed.
            (safeWordState as? SafeWordState.Unavailable)?.let { unavailable ->
                VoiceStopUnavailableChip(
                    reason = unavailable.reason,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            WorkoutTab(
                state = workoutUiState,
                actions = workoutActions,
                exerciseRepository = exerciseRepository,
                hapticEvents = hapticEvents,
                modifier = Modifier.weight(1f),
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
                                // NotInRoutine inline (does NOT call exitRoutineFlow) AND clears
                                // routineLaunchOrigin to null asynchronously. Read destination
                                // first so the correct route is captured before the async
                                // cleanup block runs and nulls out the origin.
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

    // Connection error dialog
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

/**
 * F-039: persistent HUD chip telling the user the voice safe-word emergency
 * stop is not armed, so they fall back to the on-screen Stop button.
 */
@Composable
private fun VoiceStopUnavailableChip(reason: SafeWordUnavailableReason, modifier: Modifier = Modifier) {
    Surface(
        // The chip can appear mid-set (permission revoked, microphone taken), when
        // the user's eyes are on the machine — announce it instead of waiting for
        // focus to land on it.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(Res.string.voice_stop_unavailable_chip, stringResource(reason.messageRes())),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Human-readable explanation for each way voice stop can be unavailable. */
private fun SafeWordUnavailableReason.messageRes(): StringResource = when (this) {
    SafeWordUnavailableReason.PROFILE_SWITCHING -> Res.string.voice_stop_reason_profile_switching
    SafeWordUnavailableReason.NOT_CONFIGURED -> Res.string.voice_stop_reason_not_configured
    SafeWordUnavailableReason.NOT_CALIBRATED -> Res.string.voice_stop_reason_not_calibrated
    SafeWordUnavailableReason.RECOGNIZER_UNAVAILABLE -> Res.string.voice_stop_reason_recognizer_unavailable
    SafeWordUnavailableReason.PERMISSION -> Res.string.voice_stop_reason_permission
    SafeWordUnavailableReason.AUDIO_FOCUS_LOST -> Res.string.voice_stop_reason_audio_focus_lost
    SafeWordUnavailableReason.START_FAILED -> Res.string.voice_stop_reason_start_failed
}
