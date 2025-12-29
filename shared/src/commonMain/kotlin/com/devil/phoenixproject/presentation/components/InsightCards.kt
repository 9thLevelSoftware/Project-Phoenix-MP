@file:Suppress("unused")

package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.charts.*
import com.devil.phoenixproject.util.KmpUtils
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Insight card components for workout analytics
 * These components display various analytics and insights about workout data
 */

/**
 * Week-over-week summary data
 */
private data class WeekSummary(
    val workouts: Int,
    val totalVolume: Float,
    val totalReps: Int,
    val prsHit: Int
)

/**
 * Comparison result for week-over-week metrics
 */
private sealed class Comparison {
    data class Increase(val value: String) : Comparison()
    data class Decrease(val value: String) : Comparison()
    object NoChange : Comparison()
    object NoData : Comparison()
}

/**
 * This Week Summary Card - shows week-over-week comparison metrics
 */
@Composable
fun ThisWeekSummaryCard(
    workoutSessions: List<WorkoutSession>,
    personalRecords: List<PersonalRecord>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    val now = remember { KmpUtils.currentTimeMillis() }
    val oneDayMs = 24L * 60 * 60 * 1000
    val sevenDaysMs = 7 * oneDayMs

    // Calculate week boundaries
    val thisWeekStart = now - sevenDaysMs
    val lastWeekStart = now - (2 * sevenDaysMs)
    val lastWeekEnd = thisWeekStart

    // Calculate summaries for each week
    val (thisWeek, lastWeek) = remember(workoutSessions, personalRecords, now) {
        val thisWeekSessions = workoutSessions.filter { it.timestamp >= thisWeekStart }
        val lastWeekSessions = workoutSessions.filter {
            it.timestamp >= lastWeekStart && it.timestamp < lastWeekEnd
        }

        val thisWeekPRs = personalRecords.filter { it.timestamp >= thisWeekStart }
        val lastWeekPRs = personalRecords.filter {
            it.timestamp >= lastWeekStart && it.timestamp < lastWeekEnd
        }

        val thisWeekSummary = WeekSummary(
            workouts = thisWeekSessions.size,
            totalVolume = thisWeekSessions.sumOf {
                (it.weightPerCableKg * 2 * it.totalReps).toDouble()
            }.toFloat(),
            totalReps = thisWeekSessions.sumOf { it.totalReps },
            prsHit = thisWeekPRs.size
        )

        val lastWeekSummary = WeekSummary(
            workouts = lastWeekSessions.size,
            totalVolume = lastWeekSessions.sumOf {
                (it.weightPerCableKg * 2 * it.totalReps).toDouble()
            }.toFloat(),
            totalReps = lastWeekSessions.sumOf { it.totalReps },
            prsHit = lastWeekPRs.size
        )

        Pair(thisWeekSummary, lastWeekSummary)
    }

    // Helper function to calculate comparison
    fun calculateComparison(current: Int, previous: Int): Comparison {
        return when {
            previous == 0 && current == 0 -> Comparison.NoChange
            previous == 0 -> Comparison.Increase("+$current")
            else -> {
                val diff = current - previous
                when {
                    diff > 0 -> Comparison.Increase("+$diff")
                    diff < 0 -> Comparison.Decrease("$diff")
                    else -> Comparison.NoChange
                }
            }
        }
    }

    fun calculatePercentageComparison(current: Float, previous: Float): Comparison {
        return when {
            previous == 0f && current == 0f -> Comparison.NoChange
            previous == 0f && current > 0f -> Comparison.Increase("+100%")
            previous == 0f -> Comparison.NoData
            else -> {
                val percentChange = ((current - previous) / previous * 100).roundToInt()
                when {
                    percentChange > 0 -> Comparison.Increase("+$percentChange%")
                    percentChange < 0 -> Comparison.Decrease("$percentChange%")
                    else -> Comparison.NoChange
                }
            }
        }
    }

    // Calculate comparisons
    val workoutsComparison = calculateComparison(thisWeek.workouts, lastWeek.workouts)
    val volumeComparison = calculatePercentageComparison(thisWeek.totalVolume, lastWeek.totalVolume)
    val repsComparison = calculateComparison(thisWeek.totalReps, lastWeek.totalReps)
    val prsComparison = calculateComparison(thisWeek.prsHit, lastWeek.prsHit)

    // Format volume for display
    val displayVolume = if (weightUnit == WeightUnit.LB) {
        thisWeek.totalVolume * 2.20462f
    } else {
        thisWeek.totalVolume
    }
    val volumeText = when {
        displayVolume >= 1000 -> "${(displayVolume / 1000).roundToInt()}k ${weightUnit.name.lowercase()}"
        else -> "${displayVolume.roundToInt()} ${weightUnit.name.lowercase()}"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "This Week",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Compared to last 7 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stat rows
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WeekStatRow(
                    label = "Workouts",
                    value = "${thisWeek.workouts}",
                    comparison = workoutsComparison
                )

                WeekStatRow(
                    label = "Total Volume",
                    value = volumeText,
                    comparison = volumeComparison
                )

                WeekStatRow(
                    label = "Total Reps",
                    value = "${thisWeek.totalReps}",
                    comparison = repsComparison
                )

                WeekStatRow(
                    label = "PRs Hit",
                    value = "${thisWeek.prsHit}",
                    comparison = prsComparison
                )
            }
        }
    }
}

/**
 * Individual stat row with comparison indicator
 */
@Composable
private fun WeekStatRow(
    label: String,
    value: String,
    comparison: Comparison,
    modifier: Modifier = Modifier
) {
    val (icon, color, comparisonText) = when (comparison) {
        is Comparison.Increase -> Triple(
            Icons.Default.ArrowUpward,
            Color(0xFF4CAF50), // Green
            comparison.value
        )
        is Comparison.Decrease -> Triple(
            Icons.Default.ArrowDownward,
            Color(0xFFF44336), // Red
            comparison.value
        )
        is Comparison.NoChange -> Triple(
            Icons.Default.Remove,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "same"
        )
        is Comparison.NoData -> Triple(
            Icons.Default.Remove,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "-"
        )
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Comparison indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = comparisonText,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun MuscleBalanceRadarCard(
    personalRecords: List<PersonalRecord>,
    exerciseRepository: ExerciseRepository,
    modifier: Modifier = Modifier
) {
    // Calculate muscle group frequency
    // Map of Muscle Group -> Frequency (0.0 - 1.0)
    var radarData by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }

    LaunchedEffect(personalRecords) {
        val counts = mutableMapOf<String, Int>()
        var total = 0

        personalRecords.forEach { pr ->
            try {
                val exercise = exerciseRepository.getExerciseById(pr.exerciseId)
                val groups = exercise?.muscleGroups?.split(",")?.map { it.trim() } ?: listOf("Other")

                groups.forEach { group ->
                    // Normalize group names
                    val normalizedGroup = when {
                        group.contains("Chest", ignoreCase = true) -> "Chest"
                        group.contains("Back", ignoreCase = true) -> "Back"
                        group.contains("Leg", ignoreCase = true) ||
                                group.contains("Quadriceps", ignoreCase = true) ||
                                group.contains("Hamstrings", ignoreCase = true) -> "Legs"
                        group.contains("Shoulder", ignoreCase = true) -> "Shoulders"
                        group.contains("Arm", ignoreCase = true) ||
                                group.contains("Bicep", ignoreCase = true) ||
                                group.contains("Tricep", ignoreCase = true) -> "Arms"
                        group.contains("Core", ignoreCase = true) ||
                                group.contains("Abs", ignoreCase = true) -> "Core"
                        else -> "Other"
                    }

                    if (normalizedGroup != "Other") {
                        counts[normalizedGroup] = (counts[normalizedGroup] ?: 0) + 1
                        total++
                    }
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }

        // Convert to relative frequency (0.0 - 1.0) relative to the max category
        // This makes the chart look full even if absolute counts are low
        val maxCount = counts.values.maxOrNull()?.toFloat() ?: 1f

        // Ensure all standard groups are represented
        val standardGroups = listOf("Chest", "Back", "Legs", "Shoulders", "Arms", "Core")

        radarData = standardGroups.map { group ->
            val count = counts[group] ?: 0
            group to (if (maxCount > 0) count / maxCount else 0f)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Muscle Balance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Relative training focus by body part",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (radarData.isNotEmpty() && radarData.any { it.second > 0 }) {
                RadarChart(
                    data = radarData,
                    maxValue = 1.0f,
                    modifier = Modifier.height(300.dp)
                )
            } else {
                Text(
                    "Complete workouts to see your muscle balance analysis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}

@Composable
fun ConsistencyGaugeCard(
    workoutSessions: List<WorkoutSession>,
    modifier: Modifier = Modifier
) {
    val stats = remember(workoutSessions) {
        val thirtyDaysAgo = KmpUtils.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val count = workoutSessions.count { it.timestamp >= thirtyDaysAgo }
        count
    }

    // Dynamic target based on history, defaulting to 12 (3/week)
    val target = 12f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Monthly Consistency",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Workouts in the last 30 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            GaugeChart(
                currentValue = stats.toFloat(),
                targetValue = target,
                label = "Workouts",
                modifier = Modifier.height(250.dp)
            )
        }
    }
}

@Composable
fun VolumeVsIntensityCard(
    workoutSessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier
) {
    // Prepare data for the last 7 sessions
    val (columnData, lineData) = remember(workoutSessions, weightUnit) {
        val sortedSessions = workoutSessions.sortedBy { it.timestamp }.takeLast(7)

        if (sortedSessions.isEmpty()) {
            Pair(emptyList<Pair<String, Float>>(), emptyList<Pair<String, Float>>())
        } else {
            val columns = sortedSessions.mapIndexed { index, session ->
                val label = "S${index + 1}"
                // Volume = weight * reps (approximate)
                val volume = (session.weightPerCableKg * 2 * session.totalReps).toFloat()
                // Convert to lbs if needed
                val adjustedVolume = if (weightUnit == WeightUnit.LB) volume * 2.20462f else volume
                label to adjustedVolume
            }

            val lines = sortedSessions.mapIndexed { index, session ->
                val label = "S${index + 1}"
                val maxWeight = session.weightPerCableKg * 2 // Total weight
                // Convert to lbs if needed
                val adjustedWeight = if (weightUnit == WeightUnit.LB) maxWeight * 2.20462f else maxWeight
                label to adjustedWeight
            }

            Pair(columns, lines)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Volume vs Intensity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Last 7 sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (columnData.isNotEmpty()) {
                ComboChart(
                    columnData = columnData,
                    lineData = lineData,
                    columnLabel = "Volume (${if (weightUnit == WeightUnit.KG) "kg" else "lb"})",
                    lineLabel = "Max Weight",
                    modifier = Modifier.height(300.dp)
                )
            } else {
                Text(
                    "No workout data available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}

@Composable
fun WorkoutModeDistributionCard(
    personalRecords: List<PersonalRecord>,
    modifier: Modifier = Modifier
) {
    val modeData = remember(personalRecords) {
        personalRecords
            .groupingBy { it.workoutMode }
            .eachCount()
            .map { it.key to it.value.toFloat() }
            .sortedByDescending { it.second }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Mode Distribution",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Based on Personal Records",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (modeData.isNotEmpty()) {
                // Using MuscleGroupCircleChart as a donut chart
                MuscleGroupCircleChart(
                    data = modeData,
                    modifier = Modifier.height(300.dp)
                )
            } else {
                Text(
                    "No mode data available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}

@Composable
fun TotalVolumeCard(
    workoutSessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Total Volume History",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Volume lifted per workout session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (workoutSessions.isNotEmpty()) {
                VolumeTrendChart(
                    workoutSessions = workoutSessions,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                    modifier = Modifier.height(280.dp)
                )
            } else {
                Text(
                    "No workout data available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}
