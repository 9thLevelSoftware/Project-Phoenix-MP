package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.components.ResumeRoutineDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Daily Routines screen - view and manage pre-built routines.
 * This screen wraps the existing RoutinesTab functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRoutinesScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository,
    themeMode: com.devil.phoenixproject.ui.theme.ThemeMode
) {
    val routines by viewModel.routines.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()
    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    // Resume/Restart dialog state (Issue #101)
    var showResumeDialog by remember { mutableStateOf(false) }
    var pendingRoutine by remember { mutableStateOf<Routine?>(null) }

    // Issue #130: Block routine editing during active workout
    var showWorkoutActiveDialog by remember { mutableStateOf(false) }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Daily Routines")
    }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // Reuse RoutinesTab content
        RoutinesTab(
            routines = routines,
            exerciseRepository = exerciseRepository,
            personalRecordRepository = viewModel.personalRecordRepository,
            formatWeight = viewModel::formatWeight,
            weightUnit = weightUnit,
            enableVideoPlayback = enableVideoPlayback,
            kgToDisplay = viewModel::kgToDisplay,
            displayToKg = viewModel::displayToKg,
            onStartWorkout = { routine ->
                // Issue #101: Check for resumable progress
                if (viewModel.hasResumableProgress(routine.id)) {
                    // Show resume dialog
                    pendingRoutine = routine
                    showResumeDialog = true
                } else {
                    // No progress - enter routine overview (new workflow)
                    viewModel.enterRoutineOverview(routine)
                    navController.navigate(NavigationRoutes.RoutineOverview.route)
                }
            },
            onDeleteRoutine = { routineId -> viewModel.deleteRoutine(routineId) },
            onDeleteRoutines = { routineIds -> viewModel.deleteRoutines(routineIds) },
            onSaveRoutine = { routine -> viewModel.saveRoutine(routine) },
            onEditRoutine = { routineId ->
                // Issue #130: Block editing during active workout
                if (viewModel.isWorkoutActive) {
                    showWorkoutActiveDialog = true
                } else {
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute(routineId))
                }
            },
            onCreateRoutine = {
                // Issue #130: Block creating during active workout
                if (viewModel.isWorkoutActive) {
                    showWorkoutActiveDialog = true
                } else {
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute("new"))
                }
            },
            themeMode = themeMode,
            modifier = Modifier.fillMaxSize()
        )

        // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
        connectionError?.let { error ->
            com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() }
            )
        }

        // Resume/Restart Dialog (Issue #101)
        if (showResumeDialog) {
            viewModel.getResumableProgressInfo()?.let { info ->
                ResumeRoutineDialog(
                    progressInfo = info,
                    onResume = {
                        showResumeDialog = false
                        // Resume: skip loadRoutine to keep existing progress, just navigate and start
                        viewModel.ensureConnection(
                            onConnected = {
                                viewModel.startWorkout()
                                navController.navigate(NavigationRoutes.ActiveWorkout.route)
                            },
                            onFailed = { /* Error shown via StateFlow */ }
                        )
                    },
                    onRestart = {
                        showResumeDialog = false
                        pendingRoutine?.let { routine ->
                            viewModel.enterRoutineOverview(routine)
                            navController.navigate(NavigationRoutes.RoutineOverview.route)
                        }
                    },
                    onDismiss = { showResumeDialog = false }
                )
            }
        }

        // Issue #130: Workout Active Dialog - blocks routine editing during workout
        if (showWorkoutActiveDialog) {
            AlertDialog(
                onDismissRequest = { showWorkoutActiveDialog = false },
                title = { Text("Workout in Progress") },
                text = { Text("Please stop the current workout before editing routines.") },
                confirmButton = {
                    TextButton(onClick = { showWorkoutActiveDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
