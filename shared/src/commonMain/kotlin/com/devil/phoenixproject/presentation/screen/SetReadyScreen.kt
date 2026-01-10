package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel

/**
 * Set Ready Screen - Focused view for a single exercise/set.
 * Allows parameter adjustments before starting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetReadyScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val workoutState by viewModel.workoutState.collectAsState()
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Get current state
    val setReadyState = routineFlowState as? RoutineFlowState.SetReady
    val routine = loadedRoutine

    if (setReadyState == null || routine == null) {
        LaunchedEffect(Unit) {
            navController.navigateUp()
        }
        return
    }

    val currentExercise = routine.exercises.getOrNull(setReadyState.exerciseIndex)
    if (currentExercise == null) {
        LaunchedEffect(Unit) {
            navController.navigateUp()
        }
        return
    }

    val isEchoMode = currentExercise.programMode is ProgramMode.Echo

    // Navigation state
    val canGoPrev = setReadyState.exerciseIndex > 0 || setReadyState.setIndex > 0
    val isLastSet = setReadyState.setIndex >= currentExercise.setReps.size - 1
    val isLastExercise = setReadyState.exerciseIndex >= routine.exercises.size - 1
    val canSkip = !(isLastSet && isLastExercise)

    // Stop confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Watch for workout state changes to navigate to ActiveWorkout
    LaunchedEffect(workoutState) {
        when (workoutState) {
            is WorkoutState.Countdown, is WorkoutState.Active -> {
                navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                    popUpTo(NavigationRoutes.SetReady.route) { inclusive = true }
                }
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentExercise.exercise.displayName) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.returnToOverview(); navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showStopConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop Routine")
                    }
                }
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header - Set X of Y
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Set ${setReadyState.setIndex + 1} of ${currentExercise.setReps.size}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        currentExercise.programMode.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Configuration card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isEchoMode) {
                        // Echo mode controls
                        Text(
                            "ECHO SETTINGS",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Echo Level selector
                        Text("Echo Level", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EchoLevel.entries.forEach { level ->
                                FilterChip(
                                    selected = setReadyState.echoLevel == level,
                                    onClick = {
                                        viewModel.updateSetReadyEchoLevel(level)
                                    },
                                    label = { Text(level.displayName) }
                                )
                            }
                        }

                        // Eccentric Load slider
                        Text("Eccentric Load: ${setReadyState.eccentricLoadPercent}%",
                            style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = (setReadyState.eccentricLoadPercent ?: 100).toFloat(),
                            onValueChange = {
                                viewModel.updateSetReadyEccentricLoad(it.toInt())
                            },
                            valueRange = 0f..150f,
                            steps = 5
                        )
                    } else {
                        // Standard mode controls
                        Text(
                            "SET CONFIGURATION",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Weight adjuster
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Weight", style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    viewModel.updateSetReadyWeight(setReadyState.adjustedWeight - 0.5f)
                                }) {
                                    Icon(Icons.Default.Remove, "Decrease")
                                }
                                Text(
                                    viewModel.formatWeight(setReadyState.adjustedWeight, weightUnit),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = {
                                    viewModel.updateSetReadyWeight(setReadyState.adjustedWeight + 0.5f)
                                }) {
                                    Icon(Icons.Default.Add, "Increase")
                                }
                            }
                        }

                        HorizontalDivider()

                        // Reps adjuster
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Reps", style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    viewModel.updateSetReadyReps(setReadyState.adjustedReps - 1)
                                }) {
                                    Icon(Icons.Default.Remove, "Decrease")
                                }
                                Text(
                                    "${setReadyState.adjustedReps}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = {
                                    viewModel.updateSetReadyReps(setReadyState.adjustedReps + 1)
                                }) {
                                    Icon(Icons.Default.Add, "Increase")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Navigation buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // PREV button
                OutlinedButton(
                    onClick = { viewModel.setReadyPrev() },
                    enabled = canGoPrev,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                    Text("PREV")
                }

                // START SET button (primary)
                Button(
                    onClick = {
                        viewModel.ensureConnection(
                            onConnected = { viewModel.startSetFromReady() },
                            onFailed = {}
                        )
                    },
                    modifier = Modifier.weight(2f),
                    enabled = connectionState is ConnectionState.Connected
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("START SET", fontWeight = FontWeight.Bold)
                }

                // SKIP button
                OutlinedButton(
                    onClick = { viewModel.setReadySkip() },
                    enabled = canSkip,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SKIP")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
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
                        navController.popBackStack(NavigationRoutes.DailyRoutines.route, false)
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
