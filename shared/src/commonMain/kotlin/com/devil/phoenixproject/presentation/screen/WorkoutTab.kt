package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepRanges
import com.devil.phoenixproject.presentation.components.AutoStartOverlay
import com.devil.phoenixproject.presentation.components.AutoStopOverlay
import com.devil.phoenixproject.presentation.components.EnhancedCablePositionBar
import com.devil.phoenixproject.presentation.components.ExerciseNavigator
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.RpeIndicator
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.roundToInt
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * WorkoutTab with State Holder Pattern (2025 Material Expressive).
 * This overload accepts consolidated state and actions for cleaner API.
 *
 * @param state Consolidated UI state
 * @param actions Callback interface for UI events
 * @param exerciseRepository Repository for loading exercise details/videos
 * @param hapticEvents Optional flow for triggering haptic feedback
 */
@Composable
fun WorkoutTab(
    state: WorkoutUiState,
    actions: WorkoutActions,
    exerciseRepository: ExerciseRepository,
    hapticEvents: SharedFlow<HapticEvent>? = null,
    modifier: Modifier = Modifier
) {
    // Delegate to the original implementation
    WorkoutTab(
        connectionState = state.connectionState,
        workoutState = state.workoutState,
        currentMetric = state.currentMetric,
        currentHeuristicKgMax = state.currentHeuristicKgMax,
        workoutParameters = state.workoutParameters,
        repCount = state.repCount,
        repRanges = state.repRanges,
        autoStopState = state.autoStopState,
        autoStartCountdown = state.autoStartCountdown,
        weightUnit = state.weightUnit,
        enableVideoPlayback = state.enableVideoPlayback,
        exerciseRepository = exerciseRepository,
        isWorkoutSetupDialogVisible = state.isWorkoutSetupDialogVisible,
        hapticEvents = hapticEvents,
        loadedRoutine = state.loadedRoutine,
        currentExerciseIndex = state.currentExerciseIndex,
        currentSetIndex = state.currentSetIndex,
        skippedExercises = state.skippedExercises,
        completedExercises = state.completedExercises,
        autoplayEnabled = state.autoplayEnabled,
        summaryCountdownSeconds = state.summaryCountdownSeconds,
        onJumpToExercise = actions::onJumpToExercise,
        canGoBack = state.canGoBack,
        canSkipForward = state.canSkipForward,
        kgToDisplay = actions::kgToDisplay,
        displayToKg = actions::displayToKg,
        formatWeight = actions::formatWeight,
        onScan = actions::onScan,
        onCancelScan = actions::onCancelScan,
        onDisconnect = actions::onDisconnect,
        onStartWorkout = actions::onStartWorkout,
        onStopWorkout = actions::onStopWorkout,
        onSkipRest = actions::onSkipRest,
        onSkipCountdown = actions::onSkipCountdown,
        onProceedFromSummary = actions::onProceedFromSummary,
        onRpeLogged = actions::onRpeLogged,
        onResetForNewWorkout = actions::onResetForNewWorkout,
        onStartNextExercise = actions::onStartNextExercise,
        onUpdateParameters = actions::onUpdateParameters,
        onShowWorkoutSetupDialog = actions::onShowWorkoutSetupDialog,
        onHideWorkoutSetupDialog = actions::onHideWorkoutSetupDialog,
        modifier = modifier,
        showConnectionCard = state.showConnectionCard,
        showWorkoutSetupCard = state.showWorkoutSetupCard,
        loadBaselineA = state.loadBaselineA,
        loadBaselineB = state.loadBaselineB,
        timedExerciseRemainingSeconds = state.timedExerciseRemainingSeconds
    )
}

/**
 * Workout Tab - displays workout controls during active workout
 * Full implementation matching parent project
 */
@Composable
fun WorkoutTab(
    connectionState: ConnectionState,
    workoutState: WorkoutState,
    currentMetric: WorkoutMetric?,
    currentHeuristicKgMax: Float = 0f, // Echo mode: actual measured force per cable (kg)
    workoutParameters: WorkoutParameters,
    repCount: RepCount,
    repRanges: RepRanges?,
    autoStopState: AutoStopUiState,
    autoStartCountdown: Int? = null,
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    exerciseRepository: ExerciseRepository,
    isWorkoutSetupDialogVisible: Boolean = false,
    hapticEvents: SharedFlow<HapticEvent>? = null,
    loadedRoutine: Routine? = null,
    currentExerciseIndex: Int = 0,
    currentSetIndex: Int = 0,
    skippedExercises: Set<Int> = emptySet(),
    completedExercises: Set<Int> = emptySet(),
    autoplayEnabled: Boolean = false,
    summaryCountdownSeconds: Int = 10,  // Countdown duration for SetSummary auto-continue (0 = Off)
    onJumpToExercise: (Int) -> Unit = {},
    canGoBack: Boolean = false,
    canSkipForward: Boolean = false,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onDisconnect: () -> Unit,
    onStartWorkout: () -> Unit,
    onStopWorkout: () -> Unit,
    onSkipRest: () -> Unit,
    onSkipCountdown: () -> Unit,
    onProceedFromSummary: () -> Unit = {},
    onRpeLogged: ((Int) -> Unit)? = null,  // Optional RPE callback for set summary
    onResetForNewWorkout: () -> Unit,
    onStartNextExercise: () -> Unit = {},
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onShowWorkoutSetupDialog: () -> Unit = {},
    onHideWorkoutSetupDialog: () -> Unit = {},
    modifier: Modifier = Modifier,
    showConnectionCard: Boolean = true,
    showWorkoutSetupCard: Boolean = true,
    loadBaselineA: Float = 0f,
    loadBaselineB: Float = 0f,
    timedExerciseRemainingSeconds: Int? = null  // Issue #192: Countdown for timed exercises
) {
    // Note: HapticFeedbackEffect is now global in EnhancedMainScreen
    // No need for local haptic effect here

    // Gradient backgrounds
    val backgroundGradient = screenBackgroundBrush()

    // HUD LAYOUT FOR ACTIVE WORKOUT
    if (workoutState is WorkoutState.Active && connectionState is ConnectionState.Connected) {
        WorkoutHud(
            activeState = workoutState,
            metric = currentMetric,
            workoutParameters = workoutParameters,
            repCount = repCount,
            repRanges = repRanges,
            weightUnit = weightUnit,
            connectionState = connectionState,
            exerciseRepository = exerciseRepository,
            loadedRoutine = loadedRoutine,
            currentExerciseIndex = currentExerciseIndex,
            currentSetIndex = currentSetIndex,
            enableVideoPlayback = enableVideoPlayback,
            onStopWorkout = onStopWorkout,
            formatWeight = formatWeight,
            onUpdateParameters = onUpdateParameters,
            onStartNextExercise = onStartNextExercise,
            currentHeuristicKgMax = currentHeuristicKgMax,
            loadBaselineA = loadBaselineA,
            loadBaselineB = loadBaselineB,
            timedExerciseRemainingSeconds = timedExerciseRemainingSeconds,
            modifier = modifier
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // Show position bars at edges only when workout is active and metric is available
        val showPositionBars = connectionState is ConnectionState.Connected &&
            workoutState is WorkoutState.Active &&
            currentMetric != null

        // Left edge bar (Cable A / Left hand) - Enhanced with phase-reactive coloring
        // Uses safeGestures inset to avoid overlap with system back gesture areas
        if (showPositionBars && currentMetric != null) {
            // Calculate danger zone status
            val isDanger = repRanges?.isInDangerZone(currentMetric.positionA, currentMetric.positionB) ?: false

            EnhancedCablePositionBar(
                label = "L",
                currentPosition = currentMetric.positionA,
                velocity = currentMetric.velocityA,
                minPosition = repRanges?.minPosA,
                maxPosition = repRanges?.maxPosA,
                // Ghost indicators: use last rep's positions
                ghostMin = repRanges?.lastRepBottomA,
                ghostMax = repRanges?.lastRepTopA,
                // isActive defaults to true - bars only shown during Active state anyway
                isDanger = isDanger,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Start))
                    .width(40.dp)
                    .fillMaxHeight(0.8f) // Don't stretch full height for better visual balance
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }

        // Right edge bar (Cable B / Right hand) - Enhanced with phase-reactive coloring
        // Uses safeGestures inset to avoid overlap with system back gesture areas
        if (showPositionBars && currentMetric != null) {
            // Calculate danger zone status
            val isDanger = repRanges?.isInDangerZone(currentMetric.positionA, currentMetric.positionB) ?: false

            EnhancedCablePositionBar(
                label = "R",
                currentPosition = currentMetric.positionB,
                velocity = currentMetric.velocityB,
                minPosition = repRanges?.minPosB,
                maxPosition = repRanges?.maxPosB,
                // Ghost indicators: use last rep's positions
                ghostMin = repRanges?.lastRepBottomB,
                ghostMax = repRanges?.lastRepTopB,
                // isActive defaults to true - bars only shown during Active state anyway
                isDanger = isDanger,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.End))
                    .width(40.dp)
                    .fillMaxHeight(0.8f) // Don't stretch full height for better visual balance
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }

        // Center content column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (showPositionBars) 56.dp else 20.dp,
                    end = if (showPositionBars) 56.dp else 20.dp,
                    top = 0.dp,
                    bottom = 0.dp
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Card (conditionally shown)
            if (showConnectionCard) {
                ConnectionCard(
                    connectionState = connectionState,
                    onScan = onScan,
                    onCancelScan = onCancelScan,
                    onDisconnect = onDisconnect
                )
            }

            if (connectionState is ConnectionState.Connected) {
                // Show setup button when in Idle state, otherwise show workout controls
                when (workoutState) {
                    is WorkoutState.Idle -> {
                        if (showWorkoutSetupCard) {
                            WorkoutSetupCard(
                                onShowWorkoutSetupDialog = onShowWorkoutSetupDialog
                            )
                        }
                    }
                    is WorkoutState.Error -> {
                        ErrorCard(message = workoutState.message)
                    }
                    is WorkoutState.Completed -> {
                        CompletedCard(
                            loadedRoutine = loadedRoutine,
                            currentExerciseIndex = currentExerciseIndex,
                            onStartNextExercise = onStartNextExercise,
                            onResetForNewWorkout = onResetForNewWorkout
                        )
                    }
                    is WorkoutState.Active -> {
                        // NEW HUD LAYOUT
                        // We intercept the Active state here and delegate everything to WorkoutHud
                        // NOTE: WorkoutHud includes its own Scaffold, so it might conflict if nested deeply.
                        // Ideally WorkoutTab should switch completely.
                        // For now, we render it inside this column? No, that's bad (scaffold inside column).
                        // Refactoring: We should lift WorkoutHud to be the root content of WorkoutTab when active.
                    }
                    else -> {}
                }

                // Display state-specific cards (only non-overlay cards)
//                when (workoutState) {
//                    is WorkoutState.Active -> {
//                         // Legacy cards removed in favor of HUD
//                    }
//                    else -> {}
//                }
//
//                // Only show live metrics after warmup is complete
//                if (workoutState is WorkoutState.Active
//                    && currentMetric != null
//                    && repCount.isWarmupComplete) {
//                    // Legacy LiveMetricsCard removed
//                }
            }

            // Show "Workout Paused" card when connection is lost during an active workout (Issue #42)
            // Note: SetSummary is excluded because the summary screen doesn't need connection
            // and should remain fully visible to show workout results and save to history
            val isWorkoutInProgress = workoutState is WorkoutState.Active ||
                workoutState is WorkoutState.Countdown ||
                workoutState is WorkoutState.Resting
            val isDisconnected = connectionState is ConnectionState.Disconnected ||
                connectionState is ConnectionState.Error

            if (isWorkoutInProgress && isDisconnected) {
                WorkoutPausedCard(
                    onScan = onScan,
                    workoutState = workoutState,
                    repCount = repCount
                )
            }

            // OVERLAYS - These float on top of all content
            when (workoutState) {
                is WorkoutState.Countdown -> {
                    if (!workoutParameters.isJustLift) {
                        CountdownCard(
                            countdownSecondsRemaining = workoutState.secondsRemaining,
                            nextExerciseName = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)?.exercise?.name ?: "Exercise",
                            nextExerciseWeight = workoutParameters.weightPerCableKg,
                            nextExerciseReps = workoutParameters.reps,
                            nextExerciseMode = workoutParameters.programMode.displayName,
                            currentExerciseIndex = if (loadedRoutine != null) currentExerciseIndex else null,
                            totalExercises = loadedRoutine?.exercises?.size,
                            formatWeight = { weight -> formatWeight(weight, weightUnit) },
                            isEchoMode = workoutParameters.isEchoMode,
                            onSkipCountdown = onSkipCountdown,
                            onEndWorkout = onStopWorkout
                        )
                    }
                }
                is WorkoutState.SetSummary -> {
                    // Compute contextual button label
                    val buttonLabel = run {
                        val routine = loadedRoutine
                        if (routine == null) {
                            "Done" // Just Lift / Single Exercise
                        } else {
                            val currentExercise = routine.exercises.getOrNull(currentExerciseIndex)
                            val isLastSetOfExercise = currentExercise != null &&
                                currentSetIndex >= currentExercise.setReps.size - 1
                            val isLastExercise = currentExerciseIndex >= routine.exercises.size - 1

                            when {
                                isLastSetOfExercise && isLastExercise -> "Complete Routine"
                                isLastSetOfExercise -> "Next Exercise"
                                else -> "Next Set"
                            }
                        }
                    }

                    // Full-screen wrapper with proper system bar padding
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(screenBackgroundBrush())
                            .systemBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SetSummaryCard(
                            summary = workoutState,
                            workoutMode = workoutParameters.programMode.displayName,
                            weightUnit = weightUnit,
                            kgToDisplay = kgToDisplay,
                            formatWeight = formatWeight,
                            onContinue = onProceedFromSummary,
                            autoplayEnabled = autoplayEnabled,
                            summaryCountdownSeconds = summaryCountdownSeconds,
                            onRpeLogged = onRpeLogged,
                            buttonLabel = buttonLabel
                        )
                    }
                }
                is WorkoutState.Resting -> {
                    RestTimerCard(
                        restSecondsRemaining = workoutState.restSecondsRemaining,
                        nextExerciseName = workoutState.nextExerciseName,
                        isLastExercise = workoutState.isLastExercise,
                        currentSet = workoutState.currentSet,
                        totalSets = workoutState.totalSets,
                        nextExerciseWeight = workoutParameters.weightPerCableKg,
                        nextExerciseReps = workoutParameters.reps,
                        nextExerciseMode = workoutParameters.programMode.displayName,
                        currentExerciseIndex = if (loadedRoutine != null) currentExerciseIndex else null,
                        totalExercises = loadedRoutine?.exercises?.size,
                        weightUnit = weightUnit,
                        lastUsedWeight = workoutParameters.lastUsedWeightKg,
                        prWeight = workoutParameters.prWeightKg,
                        formatWeight = { weight -> formatWeight(weight, weightUnit) },
                        formatWeightWithUnit = formatWeight,
                        isSupersetTransition = workoutState.isSupersetTransition,
                        supersetLabel = workoutState.supersetLabel,
                        onSkipRest = onSkipRest,
                        onEndWorkout = onStopWorkout,
                        onUpdateReps = { newReps ->
                            onUpdateParameters(workoutParameters.copy(reps = newReps))
                        },
                        onUpdateWeight = { newWeight ->
                            onUpdateParameters(workoutParameters.copy(weightPerCableKg = newWeight))
                        },
                        // Echo mode specific
                        programMode = workoutParameters.programMode,
                        echoLevel = workoutParameters.echoLevel,
                        eccentricLoadPercent = workoutParameters.eccentricLoad.percentage,
                        onUpdateEchoLevel = { newLevel ->
                            onUpdateParameters(workoutParameters.copy(echoLevel = newLevel))
                        },
                        onUpdateEccentricLoad = { newPercent ->
                            // Snap to nearest EccentricLoad enum value (0-150 range)
                            val newLoad = com.devil.phoenixproject.domain.model.EccentricLoad.entries
                                .minByOrNull { kotlin.math.abs(it.percentage - newPercent) }
                                ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
                            onUpdateParameters(workoutParameters.copy(eccentricLoad = newLoad))
                        }
                    )
                }
                else -> {}
            }

            // Exercise Navigator - shows when routine is loaded with multiple exercises
            // Only show during states where navigation makes sense (not during active workout)
            if (loadedRoutine != null &&
                loadedRoutine.exercises.size > 1 &&
                workoutState !is WorkoutState.Active
            ) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                ExerciseNavigator(
                    currentIndex = currentExerciseIndex,
                    exerciseNames = loadedRoutine.exercises.map { it.exercise.name },
                    skippedIndices = skippedExercises,
                    completedIndices = completedExercises,
                    onNavigateToExercise = onJumpToExercise,
                    canGoBack = canGoBack,
                    canSkipForward = canSkipForward
                )
            }
        }

        // --- FLOATING OVERLAYS ---
        // Auto-stop overlay - floats at bottom when active (Just Lift / AMRAP)
        if (workoutState is WorkoutState.Active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                AutoStopOverlay(
                    autoStopState = autoStopState,
                    isJustLift = workoutParameters.isJustLift
                )
            }
        }

        // Auto-start overlay - shows when user grabs handles in Idle state (Just Lift)
        if (workoutState is WorkoutState.Idle && autoStartCountdown != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AutoStartOverlay(
                    isActive = true,
                    secondsRemaining = autoStartCountdown
                )
            }
        }
    }

    // Show the workout setup dialog
    if (isWorkoutSetupDialogVisible) {
        WorkoutSetupDialog(
            workoutParameters = workoutParameters,
            weightUnit = weightUnit,
            exerciseRepository = exerciseRepository,
            kgToDisplay = kgToDisplay,
            displayToKg = displayToKg,
            onUpdateParameters = onUpdateParameters,
            onStartWorkout = {
                onStartWorkout()
                onHideWorkoutSetupDialog()
            },
            onDismiss = onHideWorkoutSetupDialog
        )
    }
}

/**
 * Workout Setup Card - shown when connected and idle
 */
@Composable
private fun WorkoutSetupCard(
    onShowWorkoutSetupDialog: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Text(
                "Workout Setup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Button(
                onClick = onShowWorkoutSetupDialog,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Configure workout settings")
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    "Setup Workout",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Error Card - shown when workout fails
 */
@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Workout error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Workout Failed to Start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Text(
                "Returning to previous screen...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Workout Paused Card - shown when connection is lost during an active workout (Issue #42)
 * Displays workout progress and prompts user to reconnect
 */
@Composable
private fun WorkoutPausedCard(
    onScan: () -> Unit,
    workoutState: WorkoutState,
    repCount: RepCount
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = "Connection lost",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Workout Paused",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Connection to trainer lost",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            // Show workout progress info
            val progressText = when {
                repCount.workingReps > 0 -> "Progress: ${repCount.workingReps} reps completed"
                repCount.warmupReps > 0 -> "Progress: ${repCount.warmupReps} warmup reps"
                else -> "Workout was in progress"
            }
            Text(
                progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Text(
                "Reconnect to continue your session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.medium))

            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text("Reconnect", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Completed Card - shown when workout/exercise is complete
 */
@Composable
private fun CompletedCard(
    loadedRoutine: Routine?,
    currentExerciseIndex: Int,
    onStartNextExercise: () -> Unit,
    onResetForNewWorkout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Workout completed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Workout Completed!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            // Check if this is a routine with more exercises
            val hasMoreExercises = loadedRoutine != null &&
                currentExerciseIndex < (loadedRoutine.exercises.size - 1)

            if (hasMoreExercises && loadedRoutine != null) {
                // Show next exercise preview
                val nextExercise = loadedRoutine.exercises[currentExerciseIndex + 1]

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(Spacing.medium)) {
                        Text(
                            "Next Exercise",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(Modifier.height(Spacing.small))

                        Text(
                            nextExercise.exercise.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            formatReps(nextExercise.setReps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(Modifier.height(Spacing.medium))

                        Button(
                            onClick = onStartNextExercise,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(20.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 2.dp
                            )
                        ) {
                            Text(
                                "Start Next Exercise",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Last exercise or not a routine - show "Start New Workout"
                Button(
                    onClick = onResetForNewWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Start new workout")
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Start New Workout",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Active Workout Card - shown during active workout
 */
@Composable
private fun ActiveWorkoutCard(
    workoutParameters: WorkoutParameters,
    onStopWorkout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Text(
                "Workout Active",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            Button(
                onClick = onStopWorkout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Stop workout")
                Spacer(modifier = Modifier.width(Spacing.small))
                Text("Stop Workout")
            }
        }
    }
}

/**
 * Connection Card - shows connection status and controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionCard(
    connectionState: ConnectionState,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Disconnect confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            icon = { Icon(Icons.Default.BluetoothDisabled, contentDescription = null) },
            title = { Text("Disconnect?") },
            text = {
                Text("Are you sure you want to disconnect from the Vitruvian machine?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectDialog = false
                        onDisconnect()
                    }
                ) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Text(
                "Connection",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            when (connectionState) {
                is ConnectionState.Disconnected -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onScan) {
                            Icon(Icons.Default.Search, contentDescription = "Scan for devices")
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text("Scan")
                        }
                    }
                }
                is ConnectionState.Scanning -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text("Scanning for devices...")
                        }
                        TextButton(onClick = onCancelScan) {
                            Text("Cancel")
                        }
                    }
                }
                is ConnectionState.Connecting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text("Connecting...")
                        }
                        TextButton(onClick = onCancelScan) {
                            Text("Cancel")
                        }
                    }
                }
                is ConnectionState.Connected -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                            ) {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        connectionState.deviceName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        connectionState.deviceAddress,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = { showDisconnectDialog = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.BluetoothDisabled,
                                    contentDescription = "Disconnect",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
                is ConnectionState.Error -> {
                    Text(
                        "Error: ${connectionState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Rep Counter Card - displays current rep count
 *
 * Visual feedback flow (matches parent repo):
 * - hasPendingRep: At TOP (concentric peak) - show next rep number in grey
 * - !hasPendingRep: At BOTTOM (confirmed) - show current rep in full color
 */
@Composable
fun RepCounterCard(repCount: RepCount, workoutParameters: WorkoutParameters) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Determine display values for working reps:
            // - hasPendingRep: At TOP (concentric peak) - show next rep number in grey
            // - !hasPendingRep: At BOTTOM (confirmed) - show current rep in full color
            val (countText, isPending) = if (repCount.isWarmupComplete) {
                if (repCount.hasPendingRep) {
                    // At TOP - show PENDING rep (next number, will be confirmed at bottom)
                    Pair((repCount.workingReps + 1).toString(), true)
                } else {
                    // At BOTTOM or idle - show CONFIRMED rep count
                    Pair(repCount.workingReps.toString(), false)
                }
            } else {
                Pair("${repCount.warmupReps} / ${workoutParameters.warmupReps}", false)
            }

            // Show AMRAP indicator when in AMRAP mode and warmup is complete
            if (workoutParameters.isAMRAP && repCount.isWarmupComplete) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(bottom = Spacing.small)
                ) {
                    Text(
                        text = "AMRAP",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            val labelText = when {
                !repCount.isWarmupComplete -> "WARMUP"
                workoutParameters.isAMRAP -> "REPS (As Many As Possible)"
                else -> "REPS"
            }

            Text(
                text = labelText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(Spacing.medium))

            // Rep count display with pending state (grey when at TOP, colored when confirmed)
            Text(
                text = countText,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = if (isPending) {
                    // Grey color for pending rep (at TOP, waiting for eccentric)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                } else {
                    // Full color for confirmed rep (at BOTTOM, completed)
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
        }
    }
}

/**
 * Live Metrics Card - displays real-time workout metrics
 */
@Composable
fun LiveMetricsCard(
    metric: WorkoutMetric,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val labelWidth = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 80.dp
        WindowWidthSizeClass.Medium -> 65.dp
        WindowWidthSizeClass.Compact -> 50.dp
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Text(
                "Live Metrics",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            // Current Load - show per-cable resistance
            Text(
                formatWeight(metric.totalLoad / 2f, weightUnit),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text("Per Cable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Cable Position Bars
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Cable Positions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.extraSmall)
                )

                // Cable A Position Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "A",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    LinearProgressIndicator(
                        progress = { (metric.positionA / 1000f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Text(
                        "${metric.positionA.toInt()}mm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(labelWidth).padding(start = Spacing.extraSmall),
                        textAlign = TextAlign.End
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.extraSmall))

                // Cable B Position Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "B",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    LinearProgressIndicator(
                        progress = { (metric.positionB / 1000f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Text(
                        "${metric.positionB.toInt()}mm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(labelWidth).padding(start = Spacing.extraSmall),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

/**
 * Vertical cable position bar for left/right side display
 */
@Composable
fun VerticalCablePositionBar(
    label: String,
    currentPosition: Int,
    minPosition: Int?,
    maxPosition: Int?,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Label at top
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Vertical bar container
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val barHeight = maxHeight

            // Calculate positions as fractions
            val maxPos = 1000
            val currentProgress = (currentPosition / maxPos.toFloat()).coerceIn(0f, 1f)
            val minProgress = minPosition?.let { (it / maxPos.toFloat()).coerceIn(0f, 1f) }
            val maxProgress = maxPosition?.let { (it / maxPos.toFloat()).coerceIn(0f, 1f) }

            // Range zone visualization
            if (minProgress != null && maxProgress != null && maxProgress > minProgress) {
                val rangeHeight = maxProgress - minProgress
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight * rangeHeight)
                        .align(Alignment.BottomCenter)
                        .offset(y = -barHeight * minProgress)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                )
            }

            // Current position fill (from bottom up)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight * currentProgress)
                    .align(Alignment.BottomCenter)
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        }
                    )
            )

            // Range markers
            if (minProgress != null && maxProgress != null && maxProgress > minProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter)
                        .offset(y = -barHeight * minProgress)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter)
                        .offset(y = -barHeight * maxProgress)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                )
            }
        }

        // Position value at bottom
        Text(
            text = "${currentPosition / 10}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Current Exercise Card - Shows exercise details during active workout
 */
@Composable
fun CurrentExerciseCard(
    loadedRoutine: Routine?,
    currentExerciseIndex: Int,
    workoutParameters: WorkoutParameters,
    exerciseRepository: ExerciseRepository,
    enableVideoPlayback: Boolean,
    formatWeight: (Float) -> String,
    kgToDisplay: (Float) -> Float,
    weightUnit: WeightUnit
) {
    // Get current exercise from routine if available
    val currentExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)

    // Get exercise entity and video for display
    // Issue #142: Key the remember on currentExerciseIndex so state resets when exercise changes.
    var exerciseEntity by remember(currentExerciseIndex) { mutableStateOf<Exercise?>(null) }
    var videoEntity by remember(currentExerciseIndex) { mutableStateOf<ExerciseVideoEntity?>(null) }

    // Load exercise and video data
    // Issue #142: Include currentExerciseIndex in the key to ensure video reloads when
    // navigating to a different exercise position. This handles cases where the same
    // exercise appears multiple times in a routine (same exercise.id but different index).
    LaunchedEffect(currentExerciseIndex, currentExercise?.exercise?.id, workoutParameters.selectedExerciseId) {
        // Clear stale data first
        exerciseEntity = null
        videoEntity = null
        // Load new exercise and video data
        val exerciseId = currentExercise?.exercise?.id ?: workoutParameters.selectedExerciseId
        if (exerciseId != null) {
            exerciseEntity = exerciseRepository.getExerciseById(exerciseId)
            val videos = exerciseRepository.getVideos(exerciseId)
            videoEntity = videos.firstOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Exercise name
            Text(
                text = currentExercise?.exercise?.name ?: exerciseEntity?.name ?: "Exercise",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Exercise details
            if (currentExercise != null) {
                val repsText = if (currentExercise.setReps.isEmpty()) {
                    "No sets configured"
                } else if (currentExercise.setReps.all { it == currentExercise.setReps.first() }) {
                    "${currentExercise.setReps.size}x${currentExercise.setReps.first()}"
                } else {
                    currentExercise.setReps.joinToString(", ")
                }

                val isExerciseEcho = currentExercise.programMode == ProgramMode.Echo
                val descriptionText = if (isExerciseEcho) {
                    "$repsText reps - ${currentExercise.programMode.displayName} - Adaptive"
                } else {
                    val weightText = if (currentExercise.setWeightsPerCableKg.isNotEmpty()) {
                        val displayWeights = currentExercise.setWeightsPerCableKg.map { kgToDisplay(it) }
                        val minWeight = displayWeights.minOrNull() ?: 0f
                        val maxWeight = displayWeights.maxOrNull() ?: 0f
                        val weightSuffix = if (weightUnit == WeightUnit.LB) "lbs" else "kg"

                        if (minWeight == maxWeight) {
                            "${formatFloat(minWeight, 1)} $weightSuffix/cable"
                        } else {
                            "${formatFloat(minWeight, 1)}-${formatFloat(maxWeight, 1)} $weightSuffix/cable"
                        }
                    } else {
                        "${formatWeight(currentExercise.weightPerCableKg)}/cable"
                    }

                    "$repsText @ $weightText - ${currentExercise.programMode.displayName}"
                }

                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                val descriptionText = if (workoutParameters.isEchoMode) {
                    "${workoutParameters.reps} reps - ${workoutParameters.programMode.displayName} - Adaptive"
                } else {
                    "${workoutParameters.reps} reps @ ${formatWeight(workoutParameters.weightPerCableKg)}/cable - ${workoutParameters.programMode.displayName}"
                }

                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Video player - shows exercise demonstration video or placeholder
            if (enableVideoPlayback) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                VideoPlayer(
                    videoUrl = videoEntity?.videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
    }
}

/**
 * Enhanced Set Summary Card - matches official Vitruvian app design
 * Shows detailed metrics: reps, volume, mode, peak/avg forces, duration, energy
 */
@Composable
fun SetSummaryCard(
    summary: WorkoutState.SetSummary,
    workoutMode: String,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
    onContinue: () -> Unit,
    autoplayEnabled: Boolean,
    summaryCountdownSeconds: Int,  // Configurable countdown duration (0 = Off, no auto-continue)
    onRpeLogged: ((Int) -> Unit)? = null,  // Optional RPE callback
    isHistoryView: Boolean = false,  // Hide interactive elements when viewing from history
    savedRpe: Int? = null,  // Show saved RPE value in history view
    buttonLabel: String = "Done"  // Contextual label: "Next Set", "Next Exercise", "Complete Routine"
) {
    // State for RPE tracking
    var loggedRpe by remember { mutableStateOf<Int?>(null) }

    // Issue #142: Use a unique key derived from the summary to ensure countdown resets for each new set.
    // Using durationMs and repCount as a composite identifier since these are unique per set completion.
    val summaryKey = remember(summary) { "${summary.durationMs}_${summary.repCount}_${summary.totalVolumeKg}" }

    // Auto-continue countdown - reset when summary changes
    var autoCountdown by remember(summaryKey) {
        mutableStateOf(if (autoplayEnabled && summaryCountdownSeconds > 0) summaryCountdownSeconds else -1)
    }

    // Issue #142: Auto-advance countdown for routine progression.
    // The summaryKey ensures this effect restarts for each unique set completion.
    // Note: LaunchedEffect is automatically cancelled when composable leaves composition,
    // so we don't need explicit isActive checks - delay() will throw CancellationException.
    LaunchedEffect(summaryKey, autoplayEnabled, summaryCountdownSeconds) {
        if (autoplayEnabled && summaryCountdownSeconds > 0 && !isHistoryView) {
            autoCountdown = summaryCountdownSeconds
            while (autoCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                autoCountdown--
            }
            // Countdown completed - advance to next set/exercise
            if (autoCountdown == 0) {
                onContinue()
            }
        }
    }

    // Calculate display values
    val displayReps = summary.repCount
    val totalVolumeDisplay = kgToDisplay(summary.totalVolumeKg, weightUnit)
    val heaviestLiftDisplay = kgToDisplay(summary.heaviestLiftKgPerCable, weightUnit)
    val durationSeconds = (summary.durationMs / 1000).toInt()
    val durationFormatted = "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}"

    // Peak/Avg forces - take max of both cables for display
    val peakConcentric = kgToDisplay(maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB), weightUnit)
    val peakEccentric = kgToDisplay(maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB), weightUnit)
    val avgConcentric = kgToDisplay(maxOf(summary.avgForceConcentricA, summary.avgForceConcentricB), weightUnit)
    val avgEccentric = kgToDisplay(maxOf(summary.avgForceEccentricA, summary.avgForceEccentricB), weightUnit)

    val unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Gradient header with Total Reps and Total Volume
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Total reps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        )
                        Text(
                            "$displayReps",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Total volume ($unitLabel)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        )
                        Text(
                            "${totalVolumeDisplay.roundToInt()}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Stats Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Mode and Heaviest Lift
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    label = "Mode",
                    value = workoutMode,
                    icon = Icons.Default.GridView,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    label = "Heaviest Lift",
                    value = "${heaviestLiftDisplay.roundToInt()}",
                    unit = "($unitLabel)",
                    icon = Icons.Default.FitnessCenter,
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2: Peak Force (concentric/eccentric)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryForceCard(
                    label = "Peak force ($unitLabel/cable)",
                    concentricValue = peakConcentric,
                    eccentricValue = peakEccentric,
                    modifier = Modifier.weight(1f)
                )
                SummaryForceCard(
                    label = "Avg force ($unitLabel/cable)",
                    concentricValue = avgConcentric,
                    eccentricValue = avgEccentric,
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Duration and Energy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    label = "Duration",
                    value = durationFormatted,
                    unit = "sec",
                    icon = Icons.Default.Timer,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    label = "Energy",
                    value = "${summary.estimatedCalories.roundToInt()}",
                    unit = "(kCal)",
                    icon = Icons.Default.LocalFireDepartment,
                    modifier = Modifier.weight(1f)
                )
            }

            // Echo Mode Phase Breakdown
            if (summary.isEchoMode && (summary.warmupAvgWeightKg > 0 || summary.workingAvgWeightKg > 0)) {
                EchoPhaseBreakdownCard(
                    warmupReps = summary.warmupReps,
                    workingReps = summary.workingReps,
                    burnoutReps = summary.burnoutReps,
                    warmupAvgWeight = kgToDisplay(summary.warmupAvgWeightKg, weightUnit),
                    workingAvgWeight = kgToDisplay(summary.workingAvgWeightKg, weightUnit),
                    burnoutAvgWeight = kgToDisplay(summary.burnoutAvgWeightKg, weightUnit),
                    peakWeight = kgToDisplay(summary.peakWeightKg, weightUnit),
                    unitLabel = unitLabel
                )
            }

            // RPE section - show read-only in history view, interactive in live view
            if (isHistoryView && savedRpe != null) {
                // Show saved RPE as read-only
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "RPE",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "$savedRpe/10",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (!isHistoryView && onRpeLogged != null) {
                // RPE Capture (optional) - shown if callback is provided in live view
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "How hard was that?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Log your perceived exertion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RpeIndicator(
                            currentRpe = loggedRpe,
                            onRpeChanged = { rpe ->
                                loggedRpe = rpe
                                onRpeLogged(rpe)
                            }
                        )
                    }
                }
            }
        }

        // Done/Continue button - only show in live view
        if (!isHistoryView) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (autoplayEnabled && summaryCountdownSeconds > 0 && autoCountdown > 0) {
                        "$buttonLabel ($autoCountdown)"
                    } else {
                        buttonLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * Individual stat card for the summary grid
 */
@Composable
private fun SummaryStatCard(
    label: String,
    value: String,
    unit: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                unit?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Force card showing concentric (up arrow) and eccentric (down arrow) values
 */
@Composable
private fun SummaryForceCard(
    label: String,
    concentricValue: Float,
    eccentricValue: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Concentric (lifting) with up arrow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${concentricValue.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " \u2191", // Up arrow
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Eccentric (lowering) with down arrow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${eccentricValue.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " \u2193", // Down arrow
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * Echo Mode Phase Breakdown Card
 * Shows average weight per phase (warmup, working, burnout) with rep counts
 */
@Composable
private fun EchoPhaseBreakdownCard(
    warmupReps: Int,
    workingReps: Int,
    burnoutReps: Int,
    warmupAvgWeight: Float,
    workingAvgWeight: Float,
    burnoutAvgWeight: Float,
    peakWeight: Float,
    unitLabel: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Echo Phase Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Peak: ${peakWeight.roundToInt()} $unitLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Phase breakdown row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Warmup Phase
                if (warmupReps > 0 || warmupAvgWeight > 0) {
                    PhaseStatColumn(
                        phaseName = "Warmup",
                        reps = warmupReps,
                        avgWeight = warmupAvgWeight,
                        unitLabel = unitLabel,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // Working Phase
                PhaseStatColumn(
                    phaseName = "Working",
                    reps = workingReps,
                    avgWeight = workingAvgWeight,
                    unitLabel = unitLabel,
                    color = MaterialTheme.colorScheme.primary,
                    isPrimary = true
                )

                // Burnout Phase
                if (burnoutReps > 0 || burnoutAvgWeight > 0) {
                    PhaseStatColumn(
                        phaseName = "Burnout",
                        reps = burnoutReps,
                        avgWeight = burnoutAvgWeight,
                        unitLabel = unitLabel,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * Individual phase stat column for Echo breakdown
 */
@Composable
private fun PhaseStatColumn(
    phaseName: String,
    reps: Int,
    avgWeight: Float,
    unitLabel: String,
    color: Color,
    isPrimary: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            phaseName,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            "${avgWeight.roundToInt()}",
            style = if (isPrimary) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "$unitLabel/cable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (reps > 0) {
            Text(
                "$reps reps",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

/**
 * Workout Setup Dialog - Full configuration dialog for workout parameters
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSetupDialog(
    workoutParameters: WorkoutParameters,
    weightUnit: WeightUnit,
    exerciseRepository: ExerciseRepository,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onStartWorkout: () -> Unit,
    onDismiss: () -> Unit
) {
    // State for exercise selection
    var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
    var showExercisePicker by remember { mutableStateOf(false) }

    // State for mode selection
    var showModeMenu by remember { mutableStateOf(false) }
    var showModeSubSelector by remember { mutableStateOf(false) }
    var modeSubSelectorType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workoutParameters.selectedExerciseId) {
        workoutParameters.selectedExerciseId?.let { id ->
            exerciseRepository.getExerciseById(id).also { selectedExercise = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                "Workout Setup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Exercise Selection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Exercise",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showExercisePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedExercise?.name ?: "Select Exercise")
                        }
                    }
                }

                // Mode Selection
                val modeLabel = if (workoutParameters.isJustLift) "Base Mode (resistance profile)" else "Workout Mode"
                ExposedDropdownMenuBox(
                    expanded = showModeMenu,
                    onExpandedChange = { showModeMenu = !showModeMenu }
                ) {
                    OutlinedTextField(
                        value = workoutParameters.programMode.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(modeLabel) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModeMenu)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    ExposedDropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Old School") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.OldSchool))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pump") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.Pump))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Eccentric Only") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.EccentricOnly))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Echo Mode")
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Navigate")
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "Echo"
                                showModeSubSelector = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("TUT")
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Navigate")
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "TUT"
                                showModeSubSelector = true
                            }
                        )
                    }
                }

                // Weight Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (workoutParameters.isEchoMode) {
                            Text(
                                "Weight per cable",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Adaptive",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Echo mode adapts weight to your output",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val weightRange = if (weightUnit == WeightUnit.LB) 1..220 else 1..100
                            Text(
                                "Weight per cable (${weightUnit.name.lowercase()})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val currentWeightDisplay = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit).toInt()
                            Text(
                                "$currentWeightDisplay ${weightUnit.name.lowercase()}",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Slider(
                                value = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit),
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(weightPerCableKg = kg))
                                },
                                valueRange = weightRange.first.toFloat()..weightRange.last.toFloat(),
                                steps = weightRange.last - weightRange.first - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Reps Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (!workoutParameters.isJustLift) {
                            Text(
                                "Target reps: ${workoutParameters.reps}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Slider(
                                value = workoutParameters.reps.toFloat(),
                                onValueChange = { reps ->
                                    onUpdateParameters(workoutParameters.copy(reps = reps.toInt()))
                                },
                                valueRange = 1f..50f,
                                steps = 49,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "Target reps",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "N/A",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Just Lift mode doesn't use target reps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Progression/Regression UI (only for certain modes - not Echo)
                val currentProgramMode = workoutParameters.programMode
                val showProgressionUI = currentProgramMode == ProgramMode.Pump ||
                    currentProgramMode == ProgramMode.OldSchool ||
                    currentProgramMode == ProgramMode.EccentricOnly ||
                    currentProgramMode == ProgramMode.TUT ||
                    currentProgramMode == ProgramMode.TUTBeast
                if (showProgressionUI) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                "Progression/Regression",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val maxProgression = if (weightUnit == WeightUnit.LB) 6f else 3f
                            val currentProgression = kgToDisplay(workoutParameters.progressionRegressionKg, weightUnit)

                            Text(
                                "${formatFloat(currentProgression, 1)} ${weightUnit.name.lowercase()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = when {
                                    currentProgression > 0 -> MaterialTheme.colorScheme.primary
                                    currentProgression < 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Slider(
                                value = currentProgression,
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(progressionRegressionKg = kg))
                                },
                                valueRange = -maxProgression..maxProgression,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                "Negative = Regression, Positive = Progression",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }

                // Just Lift Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Just Lift")
                    Switch(
                        checked = workoutParameters.isJustLift,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isJustLift = checked))
                        }
                    )
                }

                // Finish At Top Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Finish At Top")
                    Switch(
                        checked = workoutParameters.stopAtTop,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(stopAtTop = checked))
                        },
                        enabled = !workoutParameters.isJustLift
                    )
                }

                // AMRAP Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AMRAP Mode")
                        Text(
                            "As Many Reps As Possible",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = workoutParameters.isAMRAP,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isAMRAP = checked))
                        },
                        enabled = !workoutParameters.isJustLift
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStartWorkout,
                enabled = selectedExercise != null
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start workout")
                Spacer(modifier = Modifier.width(Spacing.small))
                Text("Start Workout")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Exercise Picker Dialog
    if (showExercisePicker) {
        ExercisePickerDialog(
            exerciseRepository = exerciseRepository,
            onDismiss = { showExercisePicker = false },
            onExerciseSelected = { exercise ->
                onUpdateParameters(workoutParameters.copy(selectedExerciseId = exercise.id))
                showExercisePicker = false
            }
        )
    }

    // Mode Sub-Selector Dialog
    if (showModeSubSelector && modeSubSelectorType != null) {
        ModeSubSelectorDialog(
            type = modeSubSelectorType!!,
            workoutParameters = workoutParameters,
            onDismiss = { showModeSubSelector = false },
            onSelect = { mode, eccentricLoad ->
                val newProgramMode = mode.toProgramMode()
                val newEchoLevel = if (mode is WorkoutMode.Echo) mode.level else workoutParameters.echoLevel
                val newEccentricLoad = eccentricLoad ?: workoutParameters.eccentricLoad
                onUpdateParameters(workoutParameters.copy(
                    programMode = newProgramMode,
                    echoLevel = newEchoLevel,
                    eccentricLoad = newEccentricLoad
                ))
                showModeSubSelector = false
            }
        )
    }
}

/**
 * Mode Sub-Selector Dialog for hierarchical workout modes (TUT and Echo)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSubSelectorDialog(
    type: String,
    workoutParameters: WorkoutParameters,
    onDismiss: () -> Unit,
    onSelect: (WorkoutMode, EccentricLoad?) -> Unit
) {
    when (type) {
        "TUT" -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Select TUT Variant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(28.dp),
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        OutlinedButton(
                            onClick = { onSelect(WorkoutMode.TUT, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("TUT")
                        }
                        OutlinedButton(
                            onClick = { onSelect(WorkoutMode.TUTBeast, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("TUT Beast")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }
        "Echo" -> {
            var selectedEchoLevel by remember {
                mutableStateOf(
                    if (workoutParameters.isEchoMode) {
                        workoutParameters.echoLevel
                    } else {
                        EchoLevel.HARD
                    }
                )
            }
            var selectedEccentricLoad by remember {
                mutableStateOf(
                    if (workoutParameters.isEchoMode) {
                        workoutParameters.eccentricLoad
                    } else {
                        EccentricLoad.LOAD_100
                    }
                )
            }
            var showEchoLevelMenu by remember { mutableStateOf(false) }
            var showEccentricMenu by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Echo Mode Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(28.dp),
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Text(
                            "Echo adapts resistance to your output",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Echo Level Dropdown
                        ExposedDropdownMenuBox(
                            expanded = showEchoLevelMenu,
                            onExpandedChange = { showEchoLevelMenu = !showEchoLevelMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedEchoLevel.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Echo Level") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEchoLevelMenu)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = showEchoLevelMenu,
                                onDismissRequest = { showEchoLevelMenu = false }
                            ) {
                                listOf(EchoLevel.HARD, EchoLevel.HARDER, EchoLevel.HARDEST, EchoLevel.EPIC).forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(level.displayName) },
                                        onClick = {
                                            selectedEchoLevel = level
                                            showEchoLevelMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Eccentric Load Dropdown
                        ExposedDropdownMenuBox(
                            expanded = showEccentricMenu,
                            onExpandedChange = { showEccentricMenu = !showEccentricMenu }
                        ) {
                            OutlinedTextField(
                                value = selectedEccentricLoad.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Eccentric Load") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEccentricMenu)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = showEccentricMenu,
                                onDismissRequest = { showEccentricMenu = false }
                            ) {
                                listOf(
                                    EccentricLoad.LOAD_0,
                                    EccentricLoad.LOAD_50,
                                    EccentricLoad.LOAD_75,
                                    EccentricLoad.LOAD_100,
                                    EccentricLoad.LOAD_120,
                                    EccentricLoad.LOAD_150
                                ).forEach { load ->
                                    DropdownMenuItem(
                                        text = { Text(load.displayName) },
                                        onClick = {
                                            selectedEccentricLoad = load
                                            showEccentricMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onSelect(WorkoutMode.Echo(selectedEchoLevel), selectedEccentricLoad)
                        }
                    ) {
                        Text("Select")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * Exercise Picker Dialog - Allows selecting an exercise from the library
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerDialog(
    exerciseRepository: ExerciseRepository,
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit
) {
    val exercises by exerciseRepository.getAllExercises().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ExerciseCategory?>(null) }

    // Filter exercises
    val filteredExercises = exercises.filter { exercise ->
        val matchesSearch = searchQuery.isEmpty() ||
            exercise.name.contains(searchQuery, ignoreCase = true)
        val matchesCategory = selectedCategory == null ||
            exercise.muscleGroup.equals(selectedCategory?.displayName, ignoreCase = true)
        matchesSearch && matchesCategory
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                "Select Exercise",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            BoxWithConstraints {
                val maxSheetHeight = (maxHeight * 0.8f).coerceIn(300.dp, 600.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                ) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search exercises") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Muscle group filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") }
                    )
                    ExerciseCategory.entries.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Exercise list
                if (filteredExercises.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No exercises found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                    ) {
                        filteredExercises.forEach { exercise ->
                            Card(
                                onClick = { onExerciseSelected(exercise) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.medium),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            exercise.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            exercise.muscleGroup,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Select",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * KMP-compatible float formatting helper
 * @param value The float value to format
 * @param decimals Number of decimal places
 * @return Formatted string
 */
private fun formatFloat(value: Float, decimals: Int): String {
    val factor = 10f.pow(decimals)
    val rounded = (value * factor).roundToInt() / factor
    return if (decimals == 0) {
        rounded.roundToInt().toString()
    } else {
        val intPart = rounded.toInt()
        val decPart = ((rounded - intPart) * factor).roundToInt()
        "$intPart.${"$decPart".padStart(decimals, '0')}"
    }
}

private fun Float.pow(n: Int): Float {
    var result = 1f
    repeat(n) { result *= this }
    return result
}

/**
 * Format reps for display in workout completion card.
 * Handles AMRAP (null reps), uniform reps, and varied reps.
 */
private fun formatReps(setReps: List<Int?>): String {
    if (setReps.isEmpty()) return "AMRAP - As Many Reps As Possible"

    val nonNullReps = setReps.filterNotNull()
    return when {
        nonNullReps.isEmpty() -> "${setReps.size} sets AMRAP"
        nonNullReps.size == setReps.size && nonNullReps.distinct().size == 1 ->
            "${setReps.size} sets x ${nonNullReps.first()} reps"
        else -> {
            val min = nonNullReps.minOrNull() ?: 0
            val max = nonNullReps.maxOrNull() ?: 0
            if (min != max) {
                "${setReps.size} sets x $min-$max reps"
            } else {
                "${setReps.size} sets x $min reps"
            }
        }
    }
}
