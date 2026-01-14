package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.components.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Routine Overview Screen - Entry point when starting a routine.
 * Shows a horizontal carousel of exercises with the ability to browse
 * and select where to begin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineOverviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()

    // Get the current routine from flow state
    val routine = when (val state = routineFlowState) {
        is RoutineFlowState.Overview -> state.routine
        else -> null
    }

    // If no routine is loaded, just return early
    // Don't auto-navigate here - the caller (dialog, back button) handles navigation
    // Auto-navigating causes double-back issues when exitRoutineFlow() clears the routine
    if (routine == null) {
        return
    }

    val pagerState = rememberPagerState(
        initialPage = (routineFlowState as? RoutineFlowState.Overview)?.selectedExerciseIndex ?: 0,
        pageCount = { routine.exercises.size }
    )

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    // Sync pager with viewmodel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.selectExerciseInOverview(pagerState.currentPage)
    }

    // Stop routine confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Handle system back press - show confirmation instead of silently exiting
    BackHandler {
        showStopConfirmation = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showStopConfirmation = true },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = { Icon(Icons.Default.Close, "Stop") },
                text = { Text("Stop") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
        ) {
            // Horizontal pager for exercises
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 16.dp
            ) { page ->
                val exercise = routine.exercises[page]
                val isCompleted = completedExercises.contains(page)

                // Track adjusted parameters for this exercise (first set as baseline)
                val initialWeight = exercise.setWeightsPerCableKg.firstOrNull() ?: exercise.weightPerCableKg
                val initialReps = exercise.setReps.firstOrNull() ?: 10
                var adjustedWeight by remember(exercise) { mutableStateOf(initialWeight) }
                var adjustedReps by remember(exercise) { mutableStateOf(initialReps) }

                // Echo mode state
                var echoLevel by remember(exercise) { mutableStateOf(exercise.echoLevel ?: EchoLevel.HARD) }
                var eccentricLoadPercent by remember(exercise) {
                    mutableStateOf(exercise.eccentricLoad?.percentage ?: 100)
                }

                // Load video for this exercise
                var videoEntity by remember { mutableStateOf<ExerciseVideoEntity?>(null) }
                LaunchedEffect(exercise.exercise.id) {
                    exercise.exercise.id?.let { exerciseId ->
                        try {
                            val videos = exerciseRepository.getVideos(exerciseId)
                            videoEntity = videos.firstOrNull()
                        } catch (_: Exception) {
                            // Video loading failed - will show placeholder
                        }
                    }
                }

                ExerciseOverviewCard(
                    exercise = exercise,
                    exerciseIndex = page,
                    isCompleted = isCompleted,
                    weightUnit = weightUnit,
                    formatWeight = viewModel::formatWeight,
                    videoUrl = if (enableVideoPlayback) videoEntity?.videoUrl else null,
                    adjustedWeight = adjustedWeight,
                    adjustedReps = adjustedReps,
                    isAMRAP = exercise.isAMRAP,
                    isEchoMode = exercise.programMode is ProgramMode.Echo,
                    echoLevel = echoLevel,
                    eccentricLoadPercent = eccentricLoadPercent,
                    onWeightChange = { newWeight ->
                        if (newWeight >= 0f) adjustedWeight = newWeight
                    },
                    onRepsChange = { newReps ->
                        if (newReps >= 1) adjustedReps = newReps
                    },
                    onEchoLevelChange = { echoLevel = it },
                    onEccentricLoadChange = { eccentricLoadPercent = it },
                    onStartExercise = {
                        // Use ensureConnection to auto-connect if needed (matches other start buttons)
                        viewModel.ensureConnection(
                            onConnected = {
                                // Pass adjusted values to SetReady
                                viewModel.enterSetReadyWithAdjustments(page, 0, adjustedWeight, adjustedReps)
                                navController.navigate(NavigationRoutes.SetReady.route)
                            },
                            onFailed = {} // Toast/error handled by ensureConnection
                        )
                    }
                )
            }

            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(routine.exercises.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val isCompleted = completedExercises.contains(index)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.tertiary
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }

        }
    }

    // Stop confirmation dialog
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Exit Routine?") },
            text = { Text("Progress will be saved.") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmation = false
                        viewModel.exitRoutineFlow()
                        navController.navigateUp()
                    }
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExerciseOverviewCard(
    exercise: RoutineExercise,
    exerciseIndex: Int,
    isCompleted: Boolean,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    videoUrl: String?,
    adjustedWeight: Float,
    adjustedReps: Int,
    isAMRAP: Boolean,
    isEchoMode: Boolean,
    echoLevel: EchoLevel,
    eccentricLoadPercent: Int,
    onWeightChange: (Float) -> Unit,
    onRepsChange: (Int) -> Unit,
    onEchoLevelChange: (EchoLevel) -> Unit,
    onEccentricLoadChange: (Int) -> Unit,
    onStartExercise: () -> Unit
) {
    // Weight parameters matching RestTimerCard exactly
    val maxWeight = if (weightUnit == WeightUnit.LB) 242f else 110f  // 110kg per cable max
    val weightStep = if (weightUnit == WeightUnit.LB) 0.5f else 0.25f  // Fine-grained like RestTimerCard

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Exercise header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Exercise ${exerciseIndex + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        exercise.exercise.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        exercise.exercise.muscleGroups,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Video thumbnail
                VideoPlayer(
                    videoUrl = videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                // Mode indicator (read-only)
                Text(
                    exercise.programMode.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Adjustment controls - matching RestTimerCard style
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
                    ) {
                        Text(
                            if (isEchoMode) "ECHO SETTINGS" else "SET CONFIGURATION",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )

                        Text(
                            "${exercise.setReps.size} sets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        if (isEchoMode) {
                            // Echo mode: Show Echo Level + Eccentric Load + Reps
                            OverviewEchoLevelSelector(
                                selectedLevel = echoLevel,
                                onLevelChange = onEchoLevelChange
                            )

                            OverviewEccentricLoadSlider(
                                percent = eccentricLoadPercent,
                                onPercentChange = onEccentricLoadChange
                            )

                            // Reps for Echo mode
                            if (!isAMRAP) {
                                SliderWithButtons(
                                    value = adjustedReps.toFloat(),
                                    onValueChange = { newValue ->
                                        onRepsChange(newValue.toInt().coerceIn(1, 50))
                                    },
                                    valueRange = 1f..50f,
                                    step = 1f,
                                    label = "Target Reps",
                                    formatValue = { it.toInt().toString() }
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Target Reps", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "AMRAP",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            // Standard modes: Weight + Reps
                            SliderWithButtons(
                                value = adjustedWeight,
                                onValueChange = { newWeight ->
                                    onWeightChange(newWeight.coerceIn(0f, maxWeight))
                                },
                                valueRange = 0f..maxWeight,
                                step = weightStep,
                                label = "Weight per cable",
                                formatValue = { formatWeight(it, weightUnit) }
                            )

                            // Reps adjuster (or AMRAP indicator)
                            if (isAMRAP) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Target Reps", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "AMRAP",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                SliderWithButtons(
                                    value = adjustedReps.toFloat(),
                                    onValueChange = { newValue ->
                                        onRepsChange(newValue.toInt().coerceIn(1, 50))
                                    },
                                    valueRange = 1f..50f,
                                    step = 1f,
                                    label = "Target Reps",
                                    formatValue = { it.toInt().toString() }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Start button
                Button(
                    onClick = onStartExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("START EXERCISE", fontWeight = FontWeight.Bold)
                }
            }

            // Completed overlay
            if (isCompleted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        "Completed",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/**
 * Echo Level selector for Overview - Row of 4 buttons matching RestTimerCard style
 */
@Composable
private fun OverviewEchoLevelSelector(
    selectedLevel: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit
) {
    Column {
        Text(
            text = "ECHO LEVEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
        ) {
            EchoLevel.entries.forEach { level ->
                val isSelected = level == selectedLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onLevelChange(level) }
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Eccentric Load slider for Overview matching RestTimerCard style (0-150%)
 */
@Composable
private fun OverviewEccentricLoadSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ECCENTRIC LOAD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth()
        )
    }
}
