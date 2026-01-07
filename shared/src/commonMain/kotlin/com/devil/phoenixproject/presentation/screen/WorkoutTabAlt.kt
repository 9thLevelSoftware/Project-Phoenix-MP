package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.AutoStartOverlay
import com.devil.phoenixproject.presentation.components.AutoStopOverlay
import com.devil.phoenixproject.presentation.components.EnhancedCablePositionBar
import com.devil.phoenixproject.presentation.components.HapticFeedbackEffect
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Alternative WorkoutTab design - Compact, no-scroll layout
 * Based on parent repo design with:
 * - Compact header with connection pill and exercise info
 * - Video takes top portion when available
 * - Large centered rep counter (120sp)
 * - Thicker edge position bars (32dp)
 * - Reuses existing RestTimerCard and SetSummaryCard
 * - Fixed bottom stop button
 */
@Composable
fun WorkoutTabAlt(
    connectionState: ConnectionState,
    workoutState: WorkoutState,
    currentMetric: WorkoutMetric?,
    currentHeuristicKgMax: Float = 0f,
    workoutParameters: WorkoutParameters,
    repCount: RepCount,
    repRanges: com.devil.phoenixproject.domain.usecase.RepRanges?,
    autoStopState: AutoStopUiState,
    autoStartCountdown: Int? = null,
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    exerciseRepository: ExerciseRepository,
    isWorkoutSetupDialogVisible: Boolean = false,
    hapticEvents: kotlinx.coroutines.flow.SharedFlow<HapticEvent>? = null,
    loadedRoutine: Routine? = null,
    currentExerciseIndex: Int = 0,
    autoplayEnabled: Boolean = false,
    summaryCountdownSeconds: Int = 10,  // Countdown duration for SetSummary auto-continue (0 = Off)
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onDisconnect: () -> Unit,
    onStartWorkout: () -> Unit,
    onStopWorkout: () -> Unit,
    onSkipRest: () -> Unit,
    onProceedFromSummary: () -> Unit = {},
    onResetForNewWorkout: () -> Unit,
    onStartNextExercise: () -> Unit = {},
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onShowWorkoutSetupDialog: () -> Unit = {},
    onHideWorkoutSetupDialog: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Unused params kept for signature compatibility
    showConnectionCard: Boolean = true,
    showWorkoutSetupCard: Boolean = true
) {
    // Note: HapticFeedbackEffect is now global in EnhancedMainScreen
    // No need for local haptic effect here

    // Responsive button height based on window size
    val windowSizeClass = LocalWindowSizeClass.current
    val buttonHeight = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 80.dp
        WindowWidthSizeClass.Medium -> 72.dp
        WindowWidthSizeClass.Compact -> 64.dp
    }

    // Gradient backgrounds
    val backgroundGradient = screenBackgroundBrush()

    // Load current exercise data
    val currentExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)
    var exerciseEntity by remember { mutableStateOf<Exercise?>(null) }
    var videoEntity by remember { mutableStateOf<ExerciseVideoEntity?>(null) }

    LaunchedEffect(currentExercise?.exercise?.id, workoutParameters.selectedExerciseId) {
        val exerciseId = currentExercise?.exercise?.id ?: workoutParameters.selectedExerciseId
        if (exerciseId != null) {
            exerciseEntity = exerciseRepository.getExerciseById(exerciseId)
            val videos = exerciseRepository.getVideos(exerciseId)
            videoEntity = videos.firstOrNull()
        } else {
            exerciseEntity = null
            videoEntity = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // --- LAYER 1: CONTENT (No Scroll) ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. COMPACT HEADER (Connection & Title)
            AltCompactHeader(
                connectionState = connectionState,
                onDisconnect = onDisconnect,
                onScan = onScan,
                onCancelScan = onCancelScan,
                exerciseName = currentExercise?.exercise?.name ?: exerciseEntity?.name ?: "Quick Workout",
                workoutType = workoutParameters.programMode.displayName
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. MAIN WORKOUT AREA (Weighted to fill space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // If Idle, show Setup. If Active/Rest/Summary, show Dashboard.
                when (workoutState) {
                    is WorkoutState.Idle -> {
                        AltIdleStateView(
                            videoUrl = if (enableVideoPlayback) videoEntity?.videoUrl else null,
                            showVideoPlaceholder = enableVideoPlayback,
                            workoutParameters = workoutParameters,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            onShowSetup = onShowWorkoutSetupDialog,
                            onStart = onStartWorkout
                        )
                    }
                    is WorkoutState.Active, is WorkoutState.Countdown -> {
                        AltActiveStateDashboard(
                            workoutState = workoutState,
                            workoutParameters = workoutParameters,
                            currentMetric = currentMetric,
                            repCount = repCount,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            currentHeuristicKgMax = currentHeuristicKgMax,
                            videoUrl = if (enableVideoPlayback) videoEntity?.videoUrl else null,
                            showVideoPlaceholder = enableVideoPlayback,
                            exerciseName = currentExercise?.exercise?.name ?: exerciseEntity?.name ?: "Exercise",
                            autoStopState = autoStopState
                        )
                    }
                    is WorkoutState.SetSummary -> {
                        // Re-use existing summary component
                        SetSummaryCard(
                            summary = workoutState,
                            workoutMode = workoutParameters.programMode.displayName,
                            weightUnit = weightUnit,
                            kgToDisplay = kgToDisplay,
                            formatWeight = formatWeight,
                            onContinue = onProceedFromSummary,
                            autoplayEnabled = autoplayEnabled,
                            summaryCountdownSeconds = summaryCountdownSeconds
                        )
                    }
                    is WorkoutState.Resting -> {
                        // Pass through to existing rest card
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
                            formatWeight = { weight -> formatWeight(weight, weightUnit) },
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
                    is WorkoutState.Completed -> {
                        AltCompletedStateView(
                            onReset = onResetForNewWorkout,
                            onNext = onStartNextExercise,
                            hasMoreExercises = loadedRoutine != null && currentExerciseIndex < (loadedRoutine.exercises.size - 1)
                        )
                    }
                    is WorkoutState.Error -> {
                        Text("Error: ${workoutState.message}", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. BOTTOM CONTROLS (Fixed Height)
            // Only show stop button if active and not in a state that has its own buttons (like Summary/Rest)
            if (workoutState is WorkoutState.Active || workoutState is WorkoutState.Countdown) {
                Button(
                    onClick = onStopWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(buttonHeight), // Responsive hit target (64/72/80dp)
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(32.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "STOP WORKOUT",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- LAYER 2: EDGE OVERLAYS (Position Bars) ---
        // These sit on top of the column at the edges
        if (connectionState is ConnectionState.Connected &&
            workoutState is WorkoutState.Active &&
            currentMetric != null) {

            // Calculate danger zone status for both cables
            val isDangerA = repRanges?.isInDangerZone(currentMetric.positionA, currentMetric.positionB) ?: false
            val isDangerB = isDangerA  // Same check applies to both (symmetric)

            EnhancedCablePositionBar(
                label = "L",
                currentPosition = currentMetric.positionA,
                velocity = currentMetric.velocityA,
                minPosition = repRanges?.minPosA,
                maxPosition = repRanges?.maxPosA,
                // Ghost indicators: use last rep's rolling average positions
                ghostMin = repRanges?.lastRepBottomA,
                ghostMax = repRanges?.lastRepTopA,
                // isActive defaults to true - bars only shown during Active state anyway
                isDanger = isDangerA,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(32.dp) // Thicker bars
                    .fillMaxHeight(0.7f) // Don't cover header/footer
                    .padding(start = 4.dp)
            )

            EnhancedCablePositionBar(
                label = "R",
                currentPosition = currentMetric.positionB,
                velocity = currentMetric.velocityB,
                minPosition = repRanges?.minPosB,
                maxPosition = repRanges?.maxPosB,
                // Ghost indicators: use last rep's rolling average positions
                ghostMin = repRanges?.lastRepBottomB,
                ghostMax = repRanges?.lastRepTopB,
                // isActive defaults to true - bars only shown during Active state anyway
                isDanger = isDangerB,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(32.dp) // Thicker bars
                    .fillMaxHeight(0.7f)
                    .padding(end = 4.dp)
            )
        }

        // --- OVERLAYS ---
        // Countdown overlay for non-Just Lift workouts
        if (workoutState is WorkoutState.Countdown && !workoutParameters.isJustLift) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SimpleCountdownOverlay(secondsRemaining = workoutState.secondsRemaining)
            }
        }

        // Auto-stop overlay - floats at bottom when active (Just Lift / AMRAP)
        if (workoutState is WorkoutState.Active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp), // Above the stop button
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

    // Workout Setup Dialog
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

// ----------------------------------------------------------------------------
// COMPACT COMPONENTS (No Scroll Design)
// ----------------------------------------------------------------------------

@Composable
private fun AltCompactHeader(
    connectionState: ConnectionState,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    exerciseName: String,
    workoutType: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Connection Pill
        Surface(
            onClick = {
                when (connectionState) {
                    is ConnectionState.Disconnected -> onScan()
                    is ConnectionState.Scanning, is ConnectionState.Connecting -> onCancelScan()
                    else -> onDisconnect()
                }
            },
            shape = RoundedCornerShape(50),
            color = if (connectionState is ConnectionState.Connected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Icon(
                    imageVector = if (connectionState is ConnectionState.Connected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (connectionState is ConnectionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (connectionState is ConnectionState.Connected) "Connected" else "Connect",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (connectionState is ConnectionState.Connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Exercise Name & Mode
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = workoutType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun AltActiveStateDashboard(
    workoutState: WorkoutState,
    workoutParameters: WorkoutParameters,
    currentMetric: WorkoutMetric?,
    repCount: RepCount,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    currentHeuristicKgMax: Float,
    videoUrl: String?,
    showVideoPlaceholder: Boolean,
    exerciseName: String,
    autoStopState: AutoStopUiState
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Video Section (Takes top portion)
        // Add horizontal padding so video fits between the edge position bars (32dp + 4dp each side)
        if (videoUrl != null || showVideoPlaceholder) {
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp) // Extra padding for position bars
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (videoUrl != null) {
                    VideoPlayer(videoUrl = videoUrl, modifier = Modifier.fillMaxSize())
                } else {
                    // Placeholder when video playback enabled but no video available
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Video Preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // Metrics Section (Takes remaining space)
        // Add horizontal padding so card fits between the edge position bars
        Card(
            modifier = Modifier
                .weight(if (videoUrl != null || showVideoPlaceholder) 0.6f else 1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // --- CURRENT SET INFO ---
                    // Shows exercise name and target reps/weight
                    if (!workoutParameters.isJustLift) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Current Set: $exerciseName",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val currentSetWeightText = if (workoutParameters.isEchoMode) {
                                    "Adaptive"
                                } else {
                                    formatWeight(workoutParameters.weightPerCableKg, weightUnit)
                                }
                                Text(
                                    text = "${workoutParameters.reps} Reps @ $currentSetWeightText",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 1. REP COUNTER (Hero Element)
                    val (countText, isPending) = if (repCount.isWarmupComplete) {
                        if (repCount.hasPendingRep) Pair((repCount.workingReps + 1).toString(), true)
                        else Pair(repCount.workingReps.toString(), false)
                    } else {
                        Pair("${repCount.warmupReps}/${workoutParameters.warmupReps}", false)
                    }

                    Text(
                        text = if (!repCount.isWarmupComplete) "WARMUP" else "REPS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = countText,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isPending) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary
                    )

                    // 2. LIVE LOAD DISPLAY (Directly below reps, integrated)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Echo uses heuristic, normal uses config or live total/2
                    val isEchoMode = workoutParameters.isEchoMode
                    val perCableKg = if (isEchoMode && currentHeuristicKgMax > 0) {
                        currentHeuristicKgMax
                    } else {
                        (currentMetric?.totalLoad ?: 0f) / 2f
                    }
                    // For Echo mode: show "—" when force data isn't available yet (Issue #52)
                    val loadLabel = if (isEchoMode && perCableKg <= 0f) "—" else formatWeight(perCableKg, weightUnit)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = loadLabel,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Active Load",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AltIdleStateView(
    videoUrl: String?,
    showVideoPlaceholder: Boolean,
    workoutParameters: WorkoutParameters,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onShowSetup: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Preview Video section
        if (videoUrl != null || showVideoPlaceholder) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (videoUrl != null) {
                    VideoPlayer(videoUrl = videoUrl, modifier = Modifier.fillMaxSize())
                } else {
                    // Placeholder when video playback enabled but no video available
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Video Preview",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // Setup Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Ready to Start", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        val readyWeightText = if (workoutParameters.isEchoMode) {
                            "Adaptive"
                        } else {
                            formatWeight(workoutParameters.weightPerCableKg, weightUnit)
                        }
                        Text(
                            "${workoutParameters.reps} Reps • $readyWeightText",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onShowSetup,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Edit")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START WORKOUT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AltCompletedStateView(
    onReset: () -> Unit,
    onNext: () -> Unit,
    hasMoreExercises: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Set Complete",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (hasMoreExercises) {
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Next Exercise")
            }
        } else {
            Button(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start New Workout")
            }
        }
    }
}

/**
 * Simple countdown overlay for workout start
 */
@Composable
private fun SimpleCountdownOverlay(secondsRemaining: Int) {
    val countdownSize = ResponsiveDimensions.componentSize(baseSize = 200.dp)
    Card(
        modifier = Modifier.size(countdownSize),
        shape = RoundedCornerShape(countdownSize / 2), // Keep circular (radius = size/2)
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GET READY",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = secondsRemaining.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
