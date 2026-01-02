package com.devil.phoenixproject.presentation.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import org.koin.compose.koinInject

/**
 * "Up Next" dashboard widget that shows the current day in the active training cycle.
 * Provides a quick way to see and start today's workout.
 */
@Composable
fun UpNextWidget(
    routines: List<Routine>,
    onStartWorkout: (routineId: String, cycleId: String, dayNumber: Int) -> Unit,
    onViewAllCycles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cycleRepository: TrainingCycleRepository = koinInject()

    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    var progress by remember { mutableStateOf<CycleProgress?>(null) }

    // Load progress when active cycle changes
    LaunchedEffect(activeCycle) {
        activeCycle?.let { cycle ->
            progress = cycleRepository.getCycleProgress(cycle.id)
        }
    }

    if (activeCycle == null) {
        // No active cycle - show prompt to create one
        NoActiveCycleWidget(
            onCreateCycle = onViewAllCycles,
            modifier = modifier
        )
    } else {
        // Show the "Up Next" card
        ActiveCycleWidget(
            cycle = activeCycle!!,
            progress = progress,
            routines = routines,
            onStartWorkout = onStartWorkout,
            onViewDetails = onViewAllCycles,
            modifier = modifier
        )
    }
}

/**
 * Widget shown when there's no active training cycle.
 */
@Composable
private fun NoActiveCycleWidget(
    onCreateCycle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Loop,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "No Active Training Cycle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "Create a rolling schedule to track your workouts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onCreateCycle,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create Training Cycle")
            }
        }
    }
}

/**
 * Widget showing the active cycle's current day.
 */
@Composable
private fun ActiveCycleWidget(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    onStartWorkout: (routineId: String, cycleId: String, dayNumber: Int) -> Unit,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentDay = progress?.currentDayNumber ?: 1
    val currentCycleDay = cycle.days.find { it.dayNumber == currentDay }
    val routine = currentCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Gradient header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "UP NEXT",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    Surface(
                        onClick = onViewDetails,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                cycle.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Day info card
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    currentCycleDay?.name ?: "Day $currentDay",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                if (currentCycleDay?.isRestDay == true) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.SelfImprovement,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Rest Day",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (routine != null) {
                                    Text(
                                        routine.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${routine.exercises.size} exercises",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            // Day counter badge
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "$currentDay",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "of ${cycle.days.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Progress dots
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            cycle.days.take(10).forEach { day -> // Limit to 10 for UI
                                val isCurrentDay = day.dayNumber == currentDay
                                val isPastDay = day.dayNumber < currentDay
                                Box(
                                    modifier = Modifier
                                        .size(if (isCurrentDay) 10.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isCurrentDay -> MaterialTheme.colorScheme.primary
                                                isPastDay -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                            }
                                        )
                                )
                                if (day.dayNumber < cycle.days.size.coerceAtMost(10)) {
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                            if (cycle.days.size > 10) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "+${cycle.days.size - 10}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Start button
                        if (currentCycleDay?.isRestDay != true && currentCycleDay?.routineId != null) {
                            Button(
                                onClick = { currentCycleDay.routineId?.let { onStartWorkout(it, cycle.id, currentDay) } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Workout")
                            }
                        } else if (currentCycleDay?.isRestDay == true) {
                            OutlinedButton(
                                onClick = onViewDetails,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("View Cycle Details")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onViewDetails,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("No routine assigned")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact version of the Up Next widget for smaller spaces.
 */
@Composable
fun UpNextCompactWidget(
    routines: List<Routine>,
    onStartWorkout: (routineId: String, cycleId: String, dayNumber: Int) -> Unit,
    onViewCycles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cycleRepository: TrainingCycleRepository = koinInject()

    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    var progress by remember { mutableStateOf<CycleProgress?>(null) }

    LaunchedEffect(activeCycle) {
        activeCycle?.let { cycle ->
            progress = cycleRepository.getCycleProgress(cycle.id)
        }
    }

    val cycle = activeCycle ?: return

    val currentDay = progress?.currentDayNumber ?: 1
    val currentCycleDay = cycle.days.find { it.dayNumber == currentDay }
    val routine = currentCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp),
        onClick = {
            if (currentCycleDay?.routineId != null) {
                onStartWorkout(currentCycleDay.routineId!!, cycle.id, currentDay)
            } else {
                onViewCycles()
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$currentDay",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        currentCycleDay?.name ?: "Day $currentDay",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        when {
                            currentCycleDay?.isRestDay == true -> "Rest Day"
                            routine != null -> routine.name
                            else -> "No routine"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Icon(
                if (currentCycleDay?.isRestDay == true) Icons.Default.SelfImprovement
                else Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
