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
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.ui.theme.Spacing

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
    enableVideoPlayback: Boolean,
    onStopWorkout: () -> Unit,
    formatWeight: (Float, WeightUnit) -> String,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onStartNextExercise: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    0 -> ExecutionPage(
                        metric = metric,
                        repCount = repCount,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        workoutParameters = workoutParameters
                    )
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
                // Left Bar
                EnhancedCablePositionBar(
                    label = "L",
                    currentPosition = metric.positionA,
                    velocity = metric.velocityA,
                    minPosition = repRanges?.minPosA,
                    maxPosition = repRanges?.maxPosA,
                    isActive = metric.positionA > 0,
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
                    isActive = metric.positionB > 0,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
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
                    "${workoutParameters.weightPerCableKg} ${weightUnit.name}", // Simplified display
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
    workoutParameters: WorkoutParameters
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Giant Rep Counter
        Text(
            if (repCount.isWarmupComplete) "REP" else "WARMUP",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp
        )
        Text(
            text = "${repCount.workingReps}",
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
            val totalLoad = metric.loadA + metric.loadB // Metric load is usually per cable
            // Max force for gauge scaling - this should ideally be dynamic or based on user's max
            // For now, let's assume 50kg per cable is a decent range, so 100kg total
            // Or use the weight setting + 20%
            val targetWeight = workoutParameters.weightPerCableKg * 2 // Total target
            val gaugeMax = (targetWeight * 1.5f).coerceAtLeast(40f) // Dynamic scale

            CircularForceGauge(
                currentForce = totalLoad,
                maxForce = gaugeMax,
                velocity = (metric.velocityA + metric.velocityB) / 2.0, // Avg velocity
                label = formatWeight(totalLoad, weightUnit),
                subLabel = "TOTAL LOAD",
                modifier = Modifier.size(200.dp)
            )
        } else {
            // Placeholder if no metric
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
