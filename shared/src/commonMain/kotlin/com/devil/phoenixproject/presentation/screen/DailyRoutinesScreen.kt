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
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel

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

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Daily Routines")
    }

    // Determine actual theme (matching Theme.kt logic)
    val useDarkColors = when (themeMode) {
        com.devil.phoenixproject.ui.theme.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        com.devil.phoenixproject.ui.theme.ThemeMode.LIGHT -> false
        com.devil.phoenixproject.ui.theme.ThemeMode.DARK -> true
    }

    val backgroundGradient = if (useDarkColors) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A), // slate-900
                Color(0xFF1E1B4B), // indigo-950
                Color(0xFF172554)  // blue-950
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0E7FF), // indigo-200 - soft lavender
                Color(0xFFFCE7F3), // pink-100 - soft pink
                Color(0xFFDDD6FE)  // violet-200 - soft violet
            )
        )
    }

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
                viewModel.ensureConnection(
                    onConnected = {
                        viewModel.loadRoutine(routine)
                        viewModel.startWorkout()
                        navController.navigate(NavigationRoutes.ActiveWorkout.route)
                    },
                    onFailed = { /* Error shown via StateFlow */ }
                )
            },
            onDeleteRoutine = { routineId -> viewModel.deleteRoutine(routineId) },
            onSaveRoutine = { routine -> viewModel.saveRoutine(routine) },
            onEditRoutine = { routineId -> 
                navController.navigate(NavigationRoutes.RoutineEditor.createRoute(routineId)) 
            },
            onCreateRoutine = {
                navController.navigate(NavigationRoutes.RoutineEditor.createRoute("new")) 
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
    }
}
