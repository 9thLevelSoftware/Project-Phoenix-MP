package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WorkoutMode

@Composable
fun HomeScreen(
    onWorkoutModeSelected: (WorkoutMode) -> Unit
) {
    val workoutModes = listOf(
        WorkoutMode.OldSchool,
        WorkoutMode.Pump,
        WorkoutMode.TUT,
        WorkoutMode.TUTBeast,
        WorkoutMode.EccentricOnly,
        WorkoutMode.Echo(com.devil.phoenixproject.domain.model.EchoLevel.HARD) // Default Echo
    )

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier.padding(paddingValues).fillMaxSize()
        ) {
            // Orientation-aware grid strategy:
            // Use Adaptive grid which automatically fits columns based on available width.
            // In Portrait (<600dp), likely 2 columns.
            // In Landscape (>600dp), likely 3 or 4 columns.
            val minColumnSize = 160.dp
            
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minColumnSize),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(workoutModes) { mode ->
                    WorkoutModeCard(
                        mode = mode,
                        onClick = { onWorkoutModeSelected(mode) }
                    )
                }
            }
        }
    }
}

@Composable
fun WorkoutModeCard(
    mode: WorkoutMode,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize() // Will fill the grid cell
    ) {
        Text(
            text = mode.displayName,
            modifier = Modifier.padding(16.dp)
        )
        // TODO: Add icon and description
    }
}