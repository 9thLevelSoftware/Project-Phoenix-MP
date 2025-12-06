package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils

/**
 * Summary of an exercise's performance history.
 */
data class ExerciseSummary(
    val exerciseId: String,
    val exerciseName: String,
    val bestOneRepMax: Float?,
    val bestWeight: Float,
    val lastPerformed: Long,
    val totalSessions: Int,
    val totalSets: Int
)

/**
 * Exercises tab showing A-Z list of performed exercises.
 * Tapping an exercise navigates to ExerciseDetailScreen.
 */
@Composable
fun ExercisesTab(
    workoutSessions: List<WorkoutSession>,
    exerciseNames: Map<String, String>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onExerciseClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    // Build exercise summaries from workout sessions
    val exerciseSummaries = remember(workoutSessions, exerciseNames) {
        workoutSessions
            .filter { it.exerciseId != null }
            .groupBy { it.exerciseId!! }
            .map { (exerciseId, sessions) ->
                ExerciseSummary(
                    exerciseId = exerciseId,
                    exerciseName = exerciseNames[exerciseId] ?: "Unknown Exercise",
                    bestOneRepMax = calculateBestOneRepMax(sessions),
                    bestWeight = sessions.maxOf { it.weightPerCableKg },
                    lastPerformed = sessions.maxOf { it.timestamp },
                    totalSessions = sessions.size,
                    totalSets = sessions.sumOf { estimateSets(it) }
                )
            }
    }

    // Filter by search query
    val filtered = remember(exerciseSummaries, searchQuery) {
        if (searchQuery.isBlank()) exerciseSummaries
        else exerciseSummaries.filter {
            it.exerciseName.contains(searchQuery, ignoreCase = true)
        }
    }

    // Recent (top 5 by last performed)
    val recent = filtered.sortedByDescending { it.lastPerformed }.take(5)

    // Alphabetical with section headers
    val alphabetical = filtered.sortedBy { it.exerciseName.lowercase() }
    val grouped = alphabetical.groupBy { it.exerciseName.first().uppercaseChar() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        // Search bar
        item {
            Spacer(Modifier.height(Spacing.medium))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search exercises...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        // Empty state
        if (exerciseSummaries.isEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.extraLarge))
                EmptyExercisesState()
            }
            return@LazyColumn
        }

        // Recent section (only when not searching)
        if (searchQuery.isBlank() && recent.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.medium))
                Text(
                    "RECENT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(recent, key = { "recent_${it.exerciseId}" }) { summary ->
                ExerciseSummaryRow(
                    summary = summary,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                    onClick = { onExerciseClick(summary.exerciseId) }
                )
            }
        }

        // All Exercises section header
        item {
            Spacer(Modifier.height(Spacing.medium))
            Text(
                "ALL EXERCISES",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Alphabetical groups
        grouped.forEach { (letter, exercises) ->
            item(key = "header_$letter") {
                AlphaHeader(letter.toString())
            }

            items(exercises, key = { "all_${it.exerciseId}" }) { summary ->
                ExerciseSummaryRow(
                    summary = summary,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                    onClick = { onExerciseClick(summary.exerciseId) }
                )
            }
        }

        // Bottom padding
        item {
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ExerciseSummaryRow(
    summary: ExerciseSummary,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatRelativeTime(summary.lastPerformed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${summary.totalSessions} sessions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Best 1RM or best weight
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatWeight(
                            summary.bestOneRepMax ?: summary.bestWeight,
                            weightUnit
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (summary.bestOneRepMax != null) {
                        Text(
                            text = "Est. 1RM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "View details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AlphaHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyExercisesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.FitnessCenter,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No Exercises Yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Complete workouts to see your exercise history here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Helper functions

private fun formatRelativeTime(timestamp: Long): String {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val diff = now - timestamp
    val days = diff / (24 * 60 * 60 * 1000)

    return when {
        days < 1 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} weeks ago"
        else -> KmpUtils.formatTimestamp(timestamp, "MMM dd")
    }
}

/**
 * Epley formula for estimated one-rep max
 */
private fun calculateOneRepMax(weight: Float, reps: Int): Float {
    if (reps <= 0) return weight
    if (reps == 1) return weight
    return weight * (1 + 0.0333f * reps)
}

/**
 * Get best 1RM from a list of sessions
 */
private fun calculateBestOneRepMax(sessions: List<WorkoutSession>): Float? {
    return sessions.mapNotNull { session ->
        if (session.workingReps > 0) {
            calculateOneRepMax(session.weightPerCableKg, session.workingReps)
        } else null
    }.maxOrNull()
}

/**
 * Estimate number of sets from a session
 */
private fun estimateSets(session: WorkoutSession): Int {
    return if (session.reps > 0 && session.workingReps > 0) {
        (session.workingReps / session.reps).coerceAtLeast(1)
    } else 1
}
