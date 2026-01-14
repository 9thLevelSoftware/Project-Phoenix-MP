package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.charts.VolumeTrendChart
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Detail screen for a single exercise.
 * Shows 1RM progression, trend chart, and workout history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    exerciseId: String,
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode
) {
    val allWorkoutSessions by viewModel.allWorkoutSessions.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()

    // Filter sessions for this exercise
    val exerciseSessions = remember(allWorkoutSessions, exerciseId) {
        allWorkoutSessions
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
    }

    // Get exercise name
    var exerciseName by remember { mutableStateOf("Loading...") }
    LaunchedEffect(exerciseId) {
        val exercise = viewModel.exerciseRepository.getExerciseById(exerciseId)
        exerciseName = exercise?.name ?: "Unknown Exercise"
        // Clear topbar title to allow dynamic title from EnhancedMainScreen
        viewModel.updateTopBarTitle("")
    }

    // Calculate 1RM progression
    val oneRepMaxData = remember(exerciseSessions) {
        exerciseSessions.mapNotNull { session ->
            if (session.workingReps > 0) {
                val oneRm = calculateOneRepMax(session.weightPerCableKg, session.workingReps)
                session.timestamp to oneRm
            } else null
        }.reversed() // Chronological order for chart
    }

    val currentOneRepMax = oneRepMaxData.lastOrNull()?.second
    val previousOneRepMax = if (oneRepMaxData.size >= 2) oneRepMaxData[oneRepMaxData.size - 2].second else null

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                // 1RM Hero Card
                item {
                    OneRepMaxCard(
                        currentOneRepMax = currentOneRepMax,
                        previousOneRepMax = previousOneRepMax,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight
                    )
                }

                // Progression Chart
                if (oneRepMaxData.size >= 2) {
                    item {
                        ProgressionChartCard(
                            data = oneRepMaxData,
                            weightUnit = weightUnit,
                            formatWeight = viewModel::formatWeight
                        )
                    }
                }

                // History Header
                item {
                    Text(
                        "HISTORY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.medium)
                    )
                }

                // History List
                if (exerciseSessions.isEmpty()) {
                    item {
                        Text(
                            "No workout history for this exercise.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(exerciseSessions, key = { it.id }) { session ->
                        SessionHistoryRow(
                            session = session,
                            weightUnit = weightUnit,
                            formatWeight = viewModel::formatWeight
                        )
                    }
                }

                // Bottom padding
                item {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun OneRepMaxCard(
    currentOneRepMax: Float?,
    previousOneRepMax: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    val delta = if (currentOneRepMax != null && previousOneRepMax != null) {
        currentOneRepMax - previousOneRepMax
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "ESTIMATED 1RM",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(8.dp))

            if (currentOneRepMax != null) {
                Text(
                    formatWeight(currentOneRepMax, weightUnit),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (delta != null && delta != 0f) {
                    Spacer(Modifier.height(8.dp))
                    val isPositive = delta > 0
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPositive) Icons.AutoMirrored.Filled.TrendingUp
                            else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${if (isPositive) "+" else ""}${formatWeight(delta, weightUnit)} from last",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            } else {
                Text(
                    "No data",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ProgressionChartCard(
    data: List<Pair<Long, Float>>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    var selectedTimeRange by remember { mutableStateOf(TimeRange.DAYS_90) }

    // Filter data by time range
    val filteredData = remember(data, selectedTimeRange) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val cutoff = when (selectedTimeRange) {
            TimeRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
            TimeRange.DAYS_90 -> now - 90L * 24 * 60 * 60 * 1000
            TimeRange.YEAR_1 -> now - 365L * 24 * 60 * 60 * 1000
            TimeRange.ALL -> 0L
        }
        data.filter { it.first >= cutoff }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "PROGRESSION",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            // Simple line representation (placeholder for actual chart)
            if (filteredData.size >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Chart placeholder - would use a proper charting library
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val minVal = filteredData.minOf { it.second }
                        val maxVal = filteredData.maxOf { it.second }
                        Text(
                            "${formatWeight(minVal, weightUnit)} → ${formatWeight(maxVal, weightUnit)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${filteredData.size} data points",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Time range selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedTimeRange == range,
                        onClick = { selectedTimeRange = range },
                        label = { Text(range.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryRow(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp),
        onClick = { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        KmpUtils.formatTimestamp(session.timestamp, "MMM dd, yyyy"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${formatWeight(session.weightPerCableKg, weightUnit)} × ${session.workingReps} reps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded details
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DetailItem("Mode", session.mode)
                    DetailItem("Total Reps", session.workingReps.toString())
                    DetailItem("Duration", formatDuration(session.duration))
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
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
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// Helpers

private enum class TimeRange(val label: String) {
    DAYS_30("30d"),
    DAYS_90("90d"),
    YEAR_1("1y"),
    ALL("All")
}

private fun calculateOneRepMax(weight: Float, reps: Int): Float {
    if (reps <= 0) return weight
    if (reps == 1) return weight
    return weight * (1 + 0.0333f * reps)
}

private fun formatDuration(durationMs: Long): String {
    val minutes = durationMs / 60000
    return if (minutes > 0) "${minutes}min" else "<1min"
}
