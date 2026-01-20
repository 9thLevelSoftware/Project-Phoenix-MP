package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.components.AnimatedRepCounter
import com.devil.phoenixproject.presentation.components.CircularForceGauge
import com.devil.phoenixproject.presentation.components.EnhancedCablePositionBar
import com.devil.phoenixproject.presentation.components.StableRepProgress
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import kotlinx.coroutines.delay

/**
 * Workout Heads-Up Display (HUD)
 * Replaces the scrolling vertical list with a pinned, paged interface.
 *
 * Slots:
 * - Top Bar: Connection Status (Left), Phase/Mode (Center), Stop Button (Right)
 * - Center: Horizontal Pager (Metrics | Video | Stats)
 * - Bottom Bar: Weight/Reps controls & Navigation
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutHud(
    activeState: WorkoutState.Active,
    metric: WorkoutMetric?,
    workoutParameters: WorkoutParameters,
    repCount: RepCount,
    repRanges: com.devil.phoenixproject.domain.usecase.RepRanges?, // Full qualified to avoid conflict if any
    weightUnit: WeightUnit,
    connectionState: ConnectionState,
    exerciseRepository: ExerciseRepository,
    loadedRoutine: Routine?,
    currentExerciseIndex: Int,
    currentSetIndex: Int,
    enableVideoPlayback: Boolean,
    onStopWorkout: () -> Unit,
    formatWeight: (Float, WeightUnit) -> String,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onStartNextExercise: () -> Unit,
    currentHeuristicKgMax: Float = 0f, // Echo mode: actual measured force per cable (kg)
    loadBaselineA: Float = 0f, // Load baseline for cable A (base tension to subtract)
    loadBaselineB: Float = 0f, // Load baseline for cable B (base tension to subtract)
    timedExerciseRemainingSeconds: Int? = null, // Issue #192: Countdown for timed exercises
    modifier: Modifier = Modifier
) {
    // Determine if we're in Echo mode
    val isEchoMode = workoutParameters.isEchoMode
    val pagerState = rememberPagerState(pageCount = { 3 })

    // Determine gradient for background based on phase?
    // For now, keep it simple dark/light surface
    Scaffold(
        modifier = modifier,
        topBar = {
            HudTopBar(
                connectionState = connectionState,
                workoutMode = workoutParameters.programMode.displayName,
                onStopWorkout = onStopWorkout
            )
        },
        bottomBar = {
            HudBottomBar(
                workoutParameters = workoutParameters,
                formatWeight = formatWeight,
                weightUnit = weightUnit,
                onUpdateParameters = onUpdateParameters,
                onNextExercise = onStartNextExercise,
                // Issue #125: Never show Next button during Active state - exercise navigation
                // should only be allowed when the machine is not engaged. Official app behavior.
                showNextButton = false
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Background Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        // Derive exercise info for display
                        val currentExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)
                        val exerciseName = currentExercise?.exercise?.name
                        val totalSets = currentExercise?.setReps?.size ?: 0

                        ExecutionPage(
                            metric = metric,
                            repCount = repCount,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            workoutParameters = workoutParameters,
                            isEchoMode = isEchoMode,
                            echoForceKgMax = currentHeuristicKgMax,
                            loadBaselineA = loadBaselineA,
                            loadBaselineB = loadBaselineB,
                            exerciseName = exerciseName,
                            currentSetIndex = currentSetIndex,
                            totalSets = totalSets,
                            timedExerciseRemainingSeconds = timedExerciseRemainingSeconds
                        )
                    }
                    1 -> InstructionPage(
                        loadedRoutine = loadedRoutine,
                        currentExerciseIndex = currentExerciseIndex,
                        exerciseRepository = exerciseRepository,
                        enableVideoPlayback = enableVideoPlayback
                    )
                    2 -> StatsPage(
                        metric = metric,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight
                    )
                }
            }
            
            // Pager Indicator
            Row(
                Modifier
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color = if (pagerState.currentPage == iteration)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }

            // PERIPHERAL VISION BARS (Pinned to edges, overlaying the pager)
            // Only show bars for cables that have built meaningful range of motion
            // Issue #194: Add 6-second delay before hiding inactive cables to prevent flickering
            if (metric != null) {
                // Calculate danger zone status for both cables
                val isDangerA = repRanges?.isInDangerZone(metric.positionA, metric.positionB) ?: false
                val isDangerB = isDangerA  // Same check applies to both (symmetric)

                // Determine which cables are currently active (have meaningful ROM)
                val isCableACurrentlyActive = repRanges?.isCableAActive() ?: false
                val isCableBCurrentlyActive = repRanges?.isCableBActive() ?: false

                // Issue #194: Delayed visibility - cables stay visible for 6 seconds after becoming inactive
                // This prevents flickering when users hit stopping points during reps
                val hideDelayMs = 6000L

                // Cable A visibility with delay
                var showCableA by remember { mutableStateOf(false) }
                LaunchedEffect(isCableACurrentlyActive) {
                    if (isCableACurrentlyActive) {
                        // Immediately show when active
                        showCableA = true
                    } else if (showCableA) {
                        // Delay hiding when becoming inactive
                        delay(hideDelayMs)
                        showCableA = false
                    }
                }

                // Cable B visibility with delay
                var showCableB by remember { mutableStateOf(false) }
                LaunchedEffect(isCableBCurrentlyActive) {
                    if (isCableBCurrentlyActive) {
                        // Immediately show when active
                        showCableB = true
                    } else if (showCableB) {
                        // Delay hiding when becoming inactive
                        delay(hideDelayMs)
                        showCableB = false
                    }
                }

                // Left Bar - only show if cable A is visible (with delay)
                if (showCableA) {
                    EnhancedCablePositionBar(
                        label = "L",
                        currentPosition = metric.positionA,
                        velocity = metric.velocityA,
                        minPosition = repRanges?.minPosA,
                        maxPosition = repRanges?.maxPosA,
                        // Ghost indicators: use last rep's rolling average positions
                        ghostMin = repRanges?.lastRepBottomA,
                        ghostMax = repRanges?.lastRepTopA,
                        isDanger = isDangerA,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(24.dp) // Thinner for HUD
                            .fillMaxHeight(0.6f)
                            .padding(start = 4.dp)
                    )
                }

                // Right Bar - only show if cable B is visible (with delay)
                if (showCableB) {
                    EnhancedCablePositionBar(
                        label = "R",
                        currentPosition = metric.positionB,
                        velocity = metric.velocityB,
                        minPosition = repRanges?.minPosB,
                        maxPosition = repRanges?.maxPosB,
                        // Ghost indicators: use last rep's rolling average positions
                        ghostMin = repRanges?.lastRepBottomB,
                        ghostMax = repRanges?.lastRepTopB,
                        isDanger = isDangerB,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(24.dp) // Thinner for HUD
                            .fillMaxHeight(0.6f)
                            .padding(end = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HudTopBar(
    connectionState: ConnectionState,
    workoutMode: String,
    onStopWorkout: () -> Unit
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val buttonHeight = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 80.dp
        WindowWidthSizeClass.Medium -> 72.dp
        WindowWidthSizeClass.Compact -> 64.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(buttonHeight)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Connection Status (Small Dot)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (connectionState is ConnectionState.Connected) Color.Green else Color.Red
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                workoutMode,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Right: STOP Button (Prominent)
        Button(
            onClick = onStopWorkout,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("STOP", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HudBottomBar(
    workoutParameters: WorkoutParameters,
    formatWeight: (Float, WeightUnit) -> String,
    weightUnit: WeightUnit,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onNextExercise: () -> Unit,
    showNextButton: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Weight Controls - Echo mode shows "Adaptive" since weight is dynamic
            Column {
                Text(
                    "Weight / Cable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (workoutParameters.isEchoMode) "Adaptive" else formatWeight(workoutParameters.weightPerCableKg, weightUnit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Next Exercise Button (if applicable)
            if (showNextButton) {
                FloatingActionButton(
                    onClick = onNextExercise,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Exercise")
                }
            }
        }
    }
}

@Composable
private fun ExecutionPage(
    metric: WorkoutMetric?,
    repCount: RepCount,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    workoutParameters: WorkoutParameters,
    isEchoMode: Boolean = false,
    echoForceKgMax: Float = 0f, // Echo mode: actual measured force per cable (kg)
    loadBaselineA: Float = 0f, // Load baseline for cable A (base tension to subtract)
    loadBaselineB: Float = 0f, // Load baseline for cable B (base tension to subtract)
    exerciseName: String? = null, // Current exercise name (null for Just Lift)
    currentSetIndex: Int = 0, // Current set (0-based)
    totalSets: Int = 0, // Total number of sets for current exercise
    timedExerciseRemainingSeconds: Int? = null // Issue #192: Countdown for timed exercises
) {
    // Issue #192: Check if this is a timed exercise
    val isTimedExercise = timedExerciseRemainingSeconds != null

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Exercise Name and Set Counter (only shown for routines/single exercise, NOT Just Lift)
        // Display above the rep counter when exerciseName is available
        // Sized larger to fill gap between top bar and rep counter
        if (!workoutParameters.isJustLift && exerciseName != null) {
            // Exercise Name - large and prominent
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Set Counter: "Set X / Y" - prominent secondary text
            if (totalSets > 0) {
                Text(
                    text = "Set ${currentSetIndex + 1} / $totalSets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Issue #192: Show countdown timer for timed exercises, rep counter for normal exercises
        if (isTimedExercise && timedExerciseRemainingSeconds != null) {
            // Timed exercise - show countdown timer
            val remainingSeconds = timedExerciseRemainingSeconds // Smart cast to non-null
            Text(
                "TIME",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp
            )

            // Large countdown display
            Text(
                text = "${remainingSeconds}s",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                fontWeight = FontWeight.Black,
                color = if (remainingSeconds <= 5)
                    MaterialTheme.colorScheme.error // Highlight last 5 seconds
                else
                    MaterialTheme.colorScheme.primary
            )
        } else {
            // Normal exercise - show rep counter
            // Issue #163: Animated Rep Counter with stable progress display
            // Shows phase label and animated counter during working reps
            // Shows warmup counter during warmup phase
            Text(
                if (repCount.isWarmupComplete) "REP" else "WARMUP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp
            )

            if (repCount.isWarmupComplete) {
                // Issue #163: Animated working rep counter
                // Shows the current rep being performed with animated visual feedback:
                // - IDLE: Solid confirmed count
                // - CONCENTRIC: Outline reveals bottom-to-top
                // - ECCENTRIC: Fill reveals top-to-bottom
                AnimatedRepCounter(
                    nextRepNumber = repCount.workingReps + 1,
                    phase = repCount.activeRepPhase,
                    phaseProgress = repCount.phaseProgress,
                    confirmedReps = repCount.workingReps,
                    targetReps = workoutParameters.reps,
                    showStableCounter = false,  // We show it separately below
                    size = 120.dp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Stable "X / Y" progress display - always visible and stable
                if (!workoutParameters.isJustLift && !workoutParameters.isAMRAP && workoutParameters.reps > 0) {
                    StableRepProgress(
                        confirmedReps = repCount.workingReps,
                        targetReps = workoutParameters.reps
                    )
                }
            } else {
                // Warmup counter (non-animated)
                Text(
                    text = "${repCount.warmupReps} / ${workoutParameters.warmupReps}",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Circular Force Gauge
        if (metric != null) {
            // Current Load - show per-cable resistance
            // Always use max(loadA, loadB) to show peak force (matches official app)
            // For Echo mode: use heuristic kgMax (actual measured force)
            //
            // The heuristic data provides actual measured force via the machine's
            // force telemetry (c7b73007-b245-4503-a1ed-9e4e97eb9802), polled at 4Hz.
            // For Echo mode this is essential as the machine dynamically adjusts resistance.
            val perCableKg = if (isEchoMode && echoForceKgMax > 0f) {
                echoForceKgMax
            } else {
                // Use max of both loads - works for single and double cable exercises
                maxOf(metric.loadA, metric.loadB)
            }
            val targetWeight = workoutParameters.weightPerCableKg
            val gaugeMax = (targetWeight * 1.5f).coerceAtLeast(20f)

            // For Echo mode: show "—" when force data isn't available yet (Issue #52)
            // This prevents showing "0 kg" during initial reps before heuristic data populates
            val forceLabel = if (isEchoMode && perCableKg <= 0f) {
                "—"
            } else {
                formatWeight(perCableKg, weightUnit)
            }

            val hudSize = ResponsiveDimensions.componentSize(baseSize = 200.dp)
            CircularForceGauge(
                currentForce = perCableKg,
                maxForce = gaugeMax,
                velocity = (metric.velocityA + metric.velocityB) / 2.0,
                label = forceLabel,
                subLabel = "PER CABLE",
                modifier = Modifier.size(hudSize)
            )
        } else {
            Text("Waiting for data...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InstructionPage(
    loadedRoutine: Routine?,
    currentExerciseIndex: Int,
    exerciseRepository: ExerciseRepository,
    enableVideoPlayback: Boolean
) {
    val currentExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)
    val exerciseId = currentExercise?.exercise?.id

    // Load video for exercise - key on exerciseIndex to reset when exercise changes
    var videoEntity by remember(currentExerciseIndex) { mutableStateOf<ExerciseVideoEntity?>(null) }
    var isLoading by remember(currentExerciseIndex) { mutableStateOf(true) }

    LaunchedEffect(currentExerciseIndex, exerciseId) {
        isLoading = true
        videoEntity = null
        if (exerciseId != null) {
            try {
                val videos = exerciseRepository.getVideos(exerciseId)
                videoEntity = videos.firstOrNull()
            } catch (_: Exception) {
                // Video loading failed - videoEntity stays null
            }
        }
        isLoading = false
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            !enableVideoPlayback -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "Video Playback Disabled",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Enable in Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            isLoading -> {
                CircularProgressIndicator()
            }
            videoEntity != null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Exercise name header
                    currentExercise?.exercise?.name?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // Video player - takes most of the space
                    VideoPlayer(
                        videoUrl = videoEntity?.videoUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "No Video Available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    currentExercise?.exercise?.name?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPage(
    metric: WorkoutMetric?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    if (metric == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Analytics,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    "Waiting for Metrics...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            "Live Stats",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Load Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "LOAD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn(
                        label = "Left",
                        value = formatWeight(metric.loadA, weightUnit),
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatColumn(
                        label = "Right",
                        value = formatWeight(metric.loadB, weightUnit),
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatColumn(
                        label = "Total",
                        value = formatWeight(metric.totalLoad, weightUnit),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // Velocity Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "VELOCITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 1.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn(
                        label = "Left",
                        value = "${metric.velocityA.toInt()} mm/s",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    StatColumn(
                        label = "Right",
                        value = "${metric.velocityB.toInt()} mm/s",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Position Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "POSITION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 1.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatColumn(
                        label = "Left",
                        value = "${metric.positionA.toInt()} mm",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    StatColumn(
                        label = "Right",
                        value = "${metric.positionB.toInt()} mm",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
