@file:Suppress("unused")

package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.charts.*
import com.devil.phoenixproject.util.KmpLocalDate
import com.devil.phoenixproject.util.KmpUtils
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
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "This week summary: ${thisWeek.workouts} workouts, $volumeText volume, ${thisWeek.totalReps} reps, ${thisWeek.prsHit} PRs"
            },
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
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Monthly consistency: $stats workouts in the last 30 days, target is ${target.toInt()}"
            },
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
                    columnLabel = "Volume (${if (weightUnit == WeightUnit.KG) "kg" else "lbs"})",
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
    workoutSessions: List<WorkoutSession>,
    modifier: Modifier = Modifier
) {
    val modeData = remember(workoutSessions) {
        workoutSessions
            .groupingBy { it.mode }
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
                "Based on Workout Sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (modeData.isNotEmpty()) {
                // Using MuscleGroupCircleChart as a donut chart
                // Chart is now responsive and self-sizing for tablet support
                MuscleGroupCircleChart(
                    data = modeData,
                    modifier = Modifier.fillMaxWidth()
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

/**
 * Volume comparison data class for fun volume representations
 */
private data class VolumeComparison(
    val funValue: String,
    val funLabel: String,
    val actualKg: Float
)

/**
 * Format a float to one decimal place (KMP-compatible)
 */
private fun formatOneDecimal(value: Float): String {
    val rounded = kotlin.math.round(value * 10) / 10
    return if (rounded == rounded.toLong().toFloat()) {
        "${rounded.toLong()}.0"
    } else {
        "$rounded"
    }
}

/**
 * Get fun volume comparison based on total volume in kg
 */
private fun getVolumeComparison(totalVolumeKg: Float): VolumeComparison {
    return when {
        totalVolumeKg >= 1_000_000 -> VolumeComparison(
            funValue = formatOneDecimal(totalVolumeKg / 52_000_000f),
            funLabel = "Titanics",
            actualKg = totalVolumeKg
        )
        totalVolumeKg >= 200_000 -> VolumeComparison(
            funValue = formatOneDecimal(totalVolumeKg / 150_000f),
            funLabel = "Blue Whales",
            actualKg = totalVolumeKg
        )
        totalVolumeKg >= 100_000 -> VolumeComparison(
            funValue = formatOneDecimal(totalVolumeKg / 100_000f),
            funLabel = "Jumbo Jets",
            actualKg = totalVolumeKg
        )
        totalVolumeKg >= 50_000 -> VolumeComparison(
            funValue = formatOneDecimal(totalVolumeKg / 5_000f),
            funLabel = "Elephants Moved",
            actualKg = totalVolumeKg
        )
        totalVolumeKg >= 10_000 -> VolumeComparison(
            funValue = formatOneDecimal(totalVolumeKg / 1_500f),
            funLabel = "Cars Crushed",
            actualKg = totalVolumeKg
        )
        else -> VolumeComparison(
            funValue = "${totalVolumeKg.toInt()}",
            funLabel = "kg lifted",
            actualKg = totalVolumeKg
        )
    }
}

/**
 * Lifetime stats data class
 */
data class LifetimeStats(
    val totalWorkouts: Int,
    val totalVolumeKg: Float,
    val totalReps: Int,
    val daysSinceFirst: Long,
    val favoriteExercise: String?,
    val favoriteExerciseCount: Int,
    val favoriteMode: String?,
    val favoriteModeCount: Int
)

/**
 * Calculate lifetime stats from workout sessions
 */
private fun calculateLifetimeStats(
    workoutSessions: List<WorkoutSession>,
    exerciseNames: Map<String, String>
): LifetimeStats {
    if (workoutSessions.isEmpty()) {
        return LifetimeStats(
            totalWorkouts = 0,
            totalVolumeKg = 0f,
            totalReps = 0,
            daysSinceFirst = 0,
            favoriteExercise = null,
            favoriteExerciseCount = 0,
            favoriteMode = null,
            favoriteModeCount = 0
        )
    }

    val totalWorkouts = workoutSessions.size

    // Calculate total volume: weight per cable * 2 (total weight) * reps
    val totalVolumeKg = workoutSessions.sumOf {
        (it.weightPerCableKg * 2 * it.totalReps).toDouble()
    }.toFloat()

    val totalReps = workoutSessions.sumOf { it.totalReps }

    // Days since first workout
    val firstWorkoutTimestamp = workoutSessions.minOf { it.timestamp }
    val now = KmpUtils.currentTimeMillis()
    val daysSinceFirst = (now - firstWorkoutTimestamp) / (24L * 60 * 60 * 1000)

    // Favorite exercise (by workout count)
    val exerciseCounts = workoutSessions
        .mapNotNull { it.exerciseId }
        .groupingBy { it }
        .eachCount()
    val favoriteExerciseId = exerciseCounts.maxByOrNull { it.value }?.key
    val favoriteExercise = favoriteExerciseId?.let { exerciseNames[it] }
    val favoriteExerciseCount = favoriteExerciseId?.let { exerciseCounts[it] } ?: 0

    // Favorite mode (by workout count)
    val modeCounts = workoutSessions
        .groupingBy { it.mode }
        .eachCount()
    val favoriteMode = modeCounts.maxByOrNull { it.value }?.key
    val favoriteModeCount = favoriteMode?.let { modeCounts[it] } ?: 0

    return LifetimeStats(
        totalWorkouts = totalWorkouts,
        totalVolumeKg = totalVolumeKg,
        totalReps = totalReps,
        daysSinceFirst = daysSinceFirst,
        favoriteExercise = favoriteExercise,
        favoriteExerciseCount = favoriteExerciseCount,
        favoriteMode = favoriteMode,
        favoriteModeCount = favoriteModeCount
    )
}

/**
 * Lifetime Stats Card - shows all-time statistics with fun volume comparisons
 */
@Composable
fun LifetimeStatsCard(
    workoutSessions: List<WorkoutSession>,
    exerciseRepository: ExerciseRepository,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier
) {
    // Build exercise names map
    var exerciseNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(workoutSessions) {
        val exerciseIds = workoutSessions.mapNotNull { it.exerciseId }.distinct()
        val names = mutableMapOf<String, String>()
        exerciseIds.forEach { id ->
            val exercise = exerciseRepository.getExerciseById(id)
            names[id] = exercise?.name ?: "Unknown Exercise"
        }
        exerciseNames = names
    }

    val stats = remember(workoutSessions, exerciseNames) {
        calculateLifetimeStats(workoutSessions, exerciseNames)
    }

    val volumeComparison = remember(stats.totalVolumeKg) {
        getVolumeComparison(stats.totalVolumeKg)
    }

    // Format actual volume for display
    val actualVolumeDisplay = remember(stats.totalVolumeKg, weightUnit) {
        val displayVolume = if (weightUnit == WeightUnit.LB) {
            stats.totalVolumeKg * 2.20462f
        } else {
            stats.totalVolumeKg
        }
        val unitLabel = weightUnit.name.lowercase()
        when {
            displayVolume >= 1_000_000 -> "${formatOneDecimal(displayVolume / 1_000_000f)}M $unitLabel"
            displayVolume >= 1_000 -> "${formatOneDecimal(displayVolume / 1_000f)}k $unitLabel"
            else -> "${displayVolume.toInt()} $unitLabel"
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Lifetime Stats",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Your all-time achievements",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (workoutSessions.isEmpty()) {
                Text(
                    "Complete workouts to see your lifetime stats!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Total Workouts
                    LifetimeStatRow(
                        label = "Total Workouts",
                        value = "${stats.totalWorkouts}",
                        subtext = null
                    )

                    // Total Volume with fun comparison
                    if (stats.totalVolumeKg >= 10_000) {
                        // Show fun comparison prominently
                        LifetimeStatRow(
                            label = "Total Volume",
                            value = "${volumeComparison.funValue} ${volumeComparison.funLabel}",
                            subtext = actualVolumeDisplay,
                            isFunStat = true
                        )
                    } else {
                        LifetimeStatRow(
                            label = "Total Volume",
                            value = actualVolumeDisplay,
                            subtext = null
                        )
                    }

                    // Total Reps
                    LifetimeStatRow(
                        label = "Total Reps",
                        value = when {
                            stats.totalReps >= 1000 -> "${formatOneDecimal(stats.totalReps / 1000f)}k"
                            else -> "${stats.totalReps}"
                        },
                        subtext = null
                    )

                    // Days Since First Workout
                    if (stats.daysSinceFirst > 0) {
                        LifetimeStatRow(
                            label = "Days Since First",
                            value = "${stats.daysSinceFirst}",
                            subtext = "days of gains"
                        )
                    }

                    // Favorite Exercise
                    stats.favoriteExercise?.let { exercise ->
                        LifetimeStatRow(
                            label = "Favorite Exercise",
                            value = exercise,
                            subtext = "${stats.favoriteExerciseCount} workouts"
                        )
                    }

                    // Favorite Mode
                    stats.favoriteMode?.let { mode ->
                        LifetimeStatRow(
                            label = "Favorite Mode",
                            value = mode,
                            subtext = "${stats.favoriteModeCount} workouts"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual stat row for Lifetime Stats Card
 */
@Composable
private fun LifetimeStatRow(
    label: String,
    value: String,
    subtext: String?,
    isFunStat: Boolean = false,
    modifier: Modifier = Modifier
) {
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

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isFunStat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            subtext?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Next Badge Progress Card - shows the user's closest badges to being earned
 *
 * @param badgesWithProgress List of all badges with their progress
 * @param onBadgeClick Callback when a badge is tapped (navigates to Badges screen)
 * @param modifier Optional modifier
 */
@Composable
fun NextBadgeProgressCard(
    badgesWithProgress: List<BadgeWithProgress>,
    onBadgeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Filter and sort badges:
    // 1. Exclude earned badges
    // 2. Exclude secret badges (isSecret = true)
    // 3. Sort by progress percentage descending
    // 4. Take top 3
    val nextBadges = remember(badgesWithProgress) {
        badgesWithProgress
            .filter { !it.isEarned && !it.badge.isSecret }
            .sortedByDescending { it.progressPercent }
            .take(3)
    }

    // Don't show if no badges to display
    if (nextBadges.isEmpty()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onBadgeClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Next Badges",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Your closest achievements",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View all badges",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                nextBadges.forEach { badgeWithProgress ->
                    NextBadgeProgressItem(badgeWithProgress = badgeWithProgress)
                }
            }
        }
    }
}

/**
 * Individual badge progress item within the NextBadgeProgressCard
 */
@Composable
private fun NextBadgeProgressItem(
    badgeWithProgress: BadgeWithProgress,
    modifier: Modifier = Modifier
) {
    val badge = badgeWithProgress.badge
    val tierColor = Color(badge.tier.colorHex)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Badge icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(tierColor.copy(alpha = 0.8f), tierColor.copy(alpha = 0.3f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getBadgeIconForProgress(badge.iconResource),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Badge info and progress
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = badge.getProgressDescription(badgeWithProgress.currentProgress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress bar with tier color
            LinearProgressIndicator(
                progress = { badgeWithProgress.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = tierColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/**
 * Helper function to map badge icon resource to ImageVector
 */
private fun getBadgeIconForProgress(iconResource: String): ImageVector {
    return when (iconResource) {
        "fire" -> Icons.Default.LocalFireDepartment
        "trophy" -> Icons.Default.EmojiEvents
        "dumbbell" -> Icons.Default.FitnessCenter
        "repeat" -> Icons.Default.Repeat
        "compass" -> Icons.Default.Explore
        "calendar" -> Icons.Default.CalendarMonth
        "sun" -> Icons.Default.WbSunny
        "moon" -> Icons.Default.NightsStay
        "weight" -> Icons.Default.FitnessCenter
        "lightning" -> Icons.Default.Bolt
        "body" -> Icons.Default.Accessibility
        "phoenix" -> Icons.Default.LocalFireDepartment
        "shield" -> Icons.Default.Shield
        "list" -> Icons.Default.Checklist
        else -> Icons.Default.Star
    }
}

/**
 * Data class representing a single day's workout activity
 */
private data class DayActivity(
    val date: KmpLocalDate,
    val volume: Float,
    val workoutCount: Int
)

/**
 * Calendar Heatmap Card - GitHub-style contribution graph showing workout activity
 *
 * Displays last 13 weeks of workout activity with color intensity based on volume lifted.
 * Rows represent days of week (Mon-Sun), columns represent weeks.
 *
 * @param workoutSessions List of all workout sessions
 * @param weightUnit Weight unit for display
 * @param modifier Optional modifier
 */
@Composable
fun CalendarHeatmapCard(
    workoutSessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier
) {
    // Calculate daily volumes for last 13 weeks (91 days)
    val (dailyActivities, maxVolume, monthLabels) = remember(workoutSessions) {
        val today = KmpLocalDate.today()
        val daysToShow = 91 // 13 weeks
        val startDate = today.minusDays(daysToShow - 1)

        // Group sessions by date and calculate volume
        val activityMap = mutableMapOf<String, DayActivity>()

        workoutSessions.forEach { session ->
            val sessionDate = KmpLocalDate.fromTimestamp(session.timestamp)
            // Only include sessions within our date range
            if (!sessionDate.isBefore(startDate) && !sessionDate.isAfter(today)) {
                val key = sessionDate.toKey()
                val volume = (session.weightPerCableKg * 2 * session.totalReps)
                val existing = activityMap[key]
                if (existing != null) {
                    activityMap[key] = existing.copy(
                        volume = existing.volume + volume,
                        workoutCount = existing.workoutCount + 1
                    )
                } else {
                    activityMap[key] = DayActivity(
                        date = sessionDate,
                        volume = volume,
                        workoutCount = 1
                    )
                }
            }
        }

        // Build list of all days
        val dailyList = mutableListOf<DayActivity>()
        var currentDate = startDate
        while (!currentDate.isAfter(today)) {
            val key = currentDate.toKey()
            dailyList.add(
                activityMap[key] ?: DayActivity(
                    date = currentDate,
                    volume = 0f,
                    workoutCount = 0
                )
            )
            currentDate = currentDate.plusDays(1)
        }

        // Find max volume for intensity calculation
        val maxVol = dailyList.maxOfOrNull { it.volume } ?: 0f

        // Build month labels (find first day of each month in range)
        val months = mutableListOf<Pair<String, Int>>() // Month name, column index
        var prevMonth = -1
        dailyList.forEachIndexed { index, activity ->
            if (activity.date.month != prevMonth) {
                val monthName = getMonthShortName(activity.date.month)
                // Calculate which column this falls into (index / 7 for week column)
                val weekIndex = index / 7
                months.add(monthName to weekIndex)
                prevMonth = activity.date.month
            }
        }

        Triple(dailyList, maxVol, months)
    }

    // Organize into grid: 7 rows (days) x N columns (weeks)
    // Row 0 = Monday, Row 6 = Sunday (ISO week)
    val gridData = remember(dailyActivities) {
        // Find the starting day of week (1=Monday, 7=Sunday)
        val firstDayOfWeek = if (dailyActivities.isNotEmpty()) {
            val firstDate = dailyActivities.first().date
            getDayOfWeekIso(firstDate)
        } else 1

        // Calculate total weeks needed
        val totalDays = dailyActivities.size + (firstDayOfWeek - 1)
        val numWeeks = (totalDays + 6) / 7

        // Create grid: Array of 7 rows, each containing week columns
        val grid = Array(7) { arrayOfNulls<DayActivity?>(numWeeks) }

        // Fill in the grid
        dailyActivities.forEachIndexed { index, activity ->
            val adjustedIndex = index + (firstDayOfWeek - 1)
            val weekCol = adjustedIndex / 7
            val dayRow = adjustedIndex % 7
            if (weekCol < numWeeks && dayRow < 7) {
                grid[dayRow][weekCol] = activity
            }
        }

        grid
    }

    val cellSize = 14.dp
    val cellGap = 2.dp
    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

    // Count workout days for accessibility
    val workoutDays = remember(dailyActivities) {
        dailyActivities.count { it.workoutCount > 0 }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Activity heatmap: $workoutDays workout days in the last 13 weeks"
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Activity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Last 13 weeks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (gridData.isNotEmpty() && gridData[0].isNotEmpty()) {
                // Month labels row - simplified positioning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp), // Align with grid (day labels width)
                    horizontalArrangement = Arrangement.Start
                ) {
                    // Filter labels to avoid overlapping (keep labels at least 3 weeks apart)
                    val filteredLabels = monthLabels.filterIndexed { idx, (_, weekIndex) ->
                        if (idx == 0) true
                        else {
                            val prevWeek = monthLabels.getOrNull(idx - 1)?.second ?: -10
                            weekIndex - prevWeek >= 3
                        }
                    }

                    var currentWeek = 0
                    filteredLabels.forEach { (monthName, weekIndex) ->
                        if (weekIndex > currentWeek) {
                            // Add spacer for weeks between labels
                            val spacerWeeks = weekIndex - currentWeek
                            Spacer(modifier = Modifier.width((spacerWeeks * 16).dp))
                        }
                        Text(
                            text = monthName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        currentWeek = weekIndex + 2 // Account for label width
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Heatmap grid with day labels
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Day of week labels column
                    Column(
                        modifier = Modifier.width(20.dp),
                        verticalArrangement = Arrangement.spacedBy(cellGap)
                    ) {
                        dayLabels.forEachIndexed { index, label ->
                            // Only show labels for Mon, Wed, Fri (indices 0, 2, 4)
                            Box(
                                modifier = Modifier.size(cellSize),
                                contentAlignment = Alignment.Center
                            ) {
                                if (index % 2 == 0) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Grid of cells
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(cellGap)
                    ) {
                        // Each column is a week
                        for (weekIndex in gridData[0].indices) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(cellGap)
                            ) {
                                // Each row is a day of week
                                for (dayIndex in 0 until 7) {
                                    val activity = gridData[dayIndex][weekIndex]
                                    HeatmapCell(
                                        activity = activity,
                                        maxVolume = maxVolume,
                                        cellSize = cellSize
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Legend
                HeatmapLegend()
            } else {
                Text(
                    "Complete workouts to see your activity heatmap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            }
        }
    }
}

/**
 * Individual heatmap cell with color based on volume intensity
 */
@Composable
private fun HeatmapCell(
    activity: DayActivity?,
    maxVolume: Float,
    cellSize: Dp,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    val cellColor = remember(activity, maxVolume, primaryColor, surfaceVariant) {
        when {
            activity == null -> Color.Transparent
            activity.volume <= 0f -> surfaceVariant.copy(alpha = 0.5f)
            maxVolume <= 0f -> surfaceVariant.copy(alpha = 0.5f)
            else -> {
                // Calculate intensity level (0-4)
                val ratio = activity.volume / maxVolume
                val level = when {
                    ratio >= 0.75f -> 4
                    ratio >= 0.50f -> 3
                    ratio >= 0.25f -> 2
                    ratio > 0f -> 1
                    else -> 0
                }
                // Apply intensity to primary color
                when (level) {
                    0 -> surfaceVariant.copy(alpha = 0.5f)
                    1 -> primaryColor.copy(alpha = 0.3f)
                    2 -> primaryColor.copy(alpha = 0.5f)
                    3 -> primaryColor.copy(alpha = 0.75f)
                    else -> primaryColor
                }
            }
        }
    }

    Box(
        modifier = modifier
            .size(cellSize)
            .clip(RoundedCornerShape(2.dp))
            .background(cellColor)
    )
}

/**
 * Legend showing intensity levels
 */
@Composable
private fun HeatmapLegend(
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Less",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))

        // Level 0 - no workout
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(surfaceVariant.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(2.dp))

        // Level 1
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(primaryColor.copy(alpha = 0.3f))
        )
        Spacer(modifier = Modifier.width(2.dp))

        // Level 2
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(primaryColor.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(2.dp))

        // Level 3
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(primaryColor.copy(alpha = 0.75f))
        )
        Spacer(modifier = Modifier.width(2.dp))

        // Level 4
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(primaryColor)
        )

        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "More",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Get short month name from month number (1-12)
 */
private fun getMonthShortName(month: Int): String {
    return when (month) {
        1 -> "Jan"
        2 -> "Feb"
        3 -> "Mar"
        4 -> "Apr"
        5 -> "May"
        6 -> "Jun"
        7 -> "Jul"
        8 -> "Aug"
        9 -> "Sep"
        10 -> "Oct"
        11 -> "Nov"
        12 -> "Dec"
        else -> ""
    }
}

/**
 * Get ISO day of week (1=Monday, 7=Sunday) from KmpLocalDate
 */
private fun getDayOfWeekIso(date: KmpLocalDate): Int {
    val localDate = kotlinx.datetime.LocalDate(date.year, date.month, date.dayOfMonth)
    return localDate.dayOfWeek.ordinal + 1 // DayOfWeek.MONDAY.ordinal is 0
}
