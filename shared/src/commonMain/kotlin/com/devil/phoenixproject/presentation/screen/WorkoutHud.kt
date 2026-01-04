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
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.CircularForceGauge
import com.devil.phoenixproject.presentation.components.EnhancedCablePositionBar
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass

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
    modifier: Modifier = Modifier
) {
    // Determine if we're in Echo mode
    val isEchoMode = workoutParameters.workoutType is WorkoutType.Echo
    val pagerState = rememberPagerState(pageCount = { 3 })
    
    // Determine gradient for background based on phase?
    // For now, keep it simple dark/light surface
    Scaffold(
        modifier = modifier,
        topBar = {
            HudTopBar(
                connectionState = connectionState,
                workoutMode = workoutParameters.workoutType.displayName,
                onStopWorkout = onStopWorkout
            )
        },
        bottomBar = {
            HudBottomBar(
                workoutParameters = workoutParameters,
                formatWeight = formatWeight,
                weightUnit = weightUnit,
                onUpdateParameters = onUpdateParameters,
                onNextExercise = onStartNextExercise, // Only if applicable, e.g. Just Lift doesn't really have next, but Routine does
                showNextButton = loadedRoutine != null
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
                            totalSets = totalSets
                        )
                    }
                    1 -> InstructionPage(
                        loadedRoutine = loadedRoutine,
                        currentExerciseIndex = currentExerciseIndex,
                        exerciseRepository = exerciseRepository,
                        enableVideoPlayback = enableVideoPlayback
                    )
                    2 -> StatsPage(
                        // Placeholder for detailed stats
                        metric = metric
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
            if (metric != null) {
                // Calculate danger zone status for both cables
                val isDangerA = repRanges?.isInDangerZone(metric.positionA, metric.positionB) ?: false
                val isDangerB = isDangerA  // Same check applies to both (symmetric)

                // Left Bar
                EnhancedCablePositionBar(
                    label = "L",
                    currentPosition = metric.positionA,
                    velocity = metric.velocityA,
                    minPosition = repRanges?.minPosA,
                    maxPosition = repRanges?.maxPosA,
                    // Ghost indicators: use last rep's rolling average positions
                    ghostMin = repRanges?.lastRepBottomA,
                    ghostMax = repRanges?.lastRepTopA,
                    // isActive defaults to true - bars only shown during Active state anyway
                    isDanger = isDangerA,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(24.dp) // Thinner for HUD
                        .fillMaxHeight(0.6f)
                        .padding(start = 4.dp)
                )

                // Right Bar
                EnhancedCablePositionBar(
                    label = "R",
                    currentPosition = metric.positionB,
                    velocity = metric.velocityB,
                    minPosition = repRanges?.minPosB,
                    maxPosition = repRanges?.maxPosB,
                    // Ghost indicators: use last rep's rolling average positions
                    ghostMin = repRanges?.lastRepBottomB,
                    ghostMax = repRanges?.lastRepTopB,
                    // isActive defaults to true - bars only shown during Active state anyway
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
            // Weight Controls (Simulated for now, could be +/- buttons)
            Column {
                Text(
                    "Weight / Cable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatWeight(workoutParameters.weightPerCableKg, weightUnit),
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
    totalSets: Int = 0 // Total number of sets for current exercise
) {
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

        // Giant Rep Counter (matches parent repo style)
        Text(
            if (repCount.isWarmupComplete) "REP" else "WARMUP",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp
        )

        // Rep count display with pending state (grey when at TOP, colored when confirmed)
        val countText = if (repCount.isWarmupComplete) {
            if (repCount.hasPendingRep) {
                (repCount.workingReps + 1).toString()
            } else {
                repCount.workingReps.toString()
            }
        } else {
            "${repCount.warmupReps} / ${workoutParameters.warmupReps}"
        }

        Text(
            text = countText,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            fontWeight = FontWeight.Black,
            color = if (repCount.hasPendingRep)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Circular Force Gauge
        if (metric != null) {
            // Current Load - show per-cable resistance (matching parent repo)
            // For Echo mode: use heuristic kgMax (actual measured force from device)
            // For other modes: use totalLoad / 2f (raw sensor average per cable)
            //
            // The heuristic data provides actual measured force via the machine's
            // force telemetry (c7b73007-b245-4503-a1ed-9e4e97eb9802), polled at 4Hz.
            // For Echo mode this is essential as the machine dynamically adjusts resistance.
            // For other modes, totalLoad from the monitor characteristic is reliable.
            val perCableKg = if (isEchoMode && echoForceKgMax > 0f) {
                echoForceKgMax
            } else {
                // Use totalLoad / 2f - matching parent repo exactly
                // No baseline subtraction needed - the machine reports actual tension
                metric.totalLoad / 2f
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
    // Logic to show video or instructions
    val exerciseId = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)?.exercise?.id
    
    // In a real implementation we'd fetch the video URL
    // For now, simple placeholder logic
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (enableVideoPlayback && exerciseId != null) {
             // We need to fetch the exercise video entity... simplified for HUD prototype
             // VideoPlayer(...) 
             Text("Video Player Placeholder\nExercise: $exerciseId")
        } else {
            Text("No Video Available")
        }
    }
}

@Composable
private fun StatsPage(
    metric: WorkoutMetric?
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Detailed Stats Graph Placeholder")
    }
}
