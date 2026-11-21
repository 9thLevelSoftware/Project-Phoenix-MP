package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.effectiveHeaviestKgPerCable
import com.devil.phoenixproject.domain.model.effectiveTotalVolumeKg
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.presentation.components.charts.ComboChart
import com.devil.phoenixproject.presentation.components.charts.GaugeChart
import com.devil.phoenixproject.presentation.components.charts.MuscleGroupCircleChart
import com.devil.phoenixproject.presentation.components.charts.RadarChart
import com.devil.phoenixproject.presentation.components.charts.VolumeTrendChart
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.badges_workouts_label
import projectphoenix.shared.generated.resources.exercise_unknown
import projectphoenix.shared.generated.resources.insights_all_time_achievements
import projectphoenix.shared.generated.resources.insights_based_on_sessions
import projectphoenix.shared.generated.resources.insights_compared_last_7_days
import projectphoenix.shared.generated.resources.insights_days_of_gains
import projectphoenix.shared.generated.resources.insights_days_since_first
import projectphoenix.shared.generated.resources.insights_favorite_exercise
import projectphoenix.shared.generated.resources.insights_favorite_mode
import projectphoenix.shared.generated.resources.insights_fun_blue_whales
import projectphoenix.shared.generated.resources.insights_fun_cars_crushed
import projectphoenix.shared.generated.resources.insights_fun_elephants
import projectphoenix.shared.generated.resources.insights_fun_jumbo_jets
import projectphoenix.shared.generated.resources.insights_fun_kg_lifted
import projectphoenix.shared.generated.resources.insights_fun_titanics
import projectphoenix.shared.generated.resources.insights_last_7_sessions
import projectphoenix.shared.generated.resources.insights_lifetime_empty
import projectphoenix.shared.generated.resources.insights_lifetime_stats
import projectphoenix.shared.generated.resources.insights_max_weight_unit_label
import projectphoenix.shared.generated.resources.insights_mode_distribution
import projectphoenix.shared.generated.resources.insights_monthly_consistency
import projectphoenix.shared.generated.resources.insights_monthly_consistency_cd
import projectphoenix.shared.generated.resources.insights_muscle_arms
import projectphoenix.shared.generated.resources.insights_muscle_back
import projectphoenix.shared.generated.resources.insights_muscle_balance
import projectphoenix.shared.generated.resources.insights_muscle_balance_empty
import projectphoenix.shared.generated.resources.insights_muscle_balance_subtitle
import projectphoenix.shared.generated.resources.insights_muscle_chest
import projectphoenix.shared.generated.resources.insights_muscle_core
import projectphoenix.shared.generated.resources.insights_muscle_legs
import projectphoenix.shared.generated.resources.insights_muscle_shoulders
import projectphoenix.shared.generated.resources.insights_no_mode_data
import projectphoenix.shared.generated.resources.insights_no_workout_data
import projectphoenix.shared.generated.resources.insights_prs_hit
import projectphoenix.shared.generated.resources.insights_same
import projectphoenix.shared.generated.resources.insights_this_week
import projectphoenix.shared.generated.resources.insights_total_reps
import projectphoenix.shared.generated.resources.insights_total_volume
import projectphoenix.shared.generated.resources.insights_total_volume_history
import projectphoenix.shared.generated.resources.insights_total_workouts
import projectphoenix.shared.generated.resources.insights_volume_per_session
import projectphoenix.shared.generated.resources.insights_volume_unit_label
import projectphoenix.shared.generated.resources.insights_volume_vs_intensity
import projectphoenix.shared.generated.resources.insights_week_summary
import projectphoenix.shared.generated.resources.insights_workouts_count
import projectphoenix.shared.generated.resources.insights_workouts_last_30_days
import projectphoenix.shared.generated.resources.label_kg
import projectphoenix.shared.generated.resources.label_lbs
import kotlin.math.roundToInt

/**
 * Insight card components for workout analytics
 * These components display various analytics and insights about workout data
 */

/**
 * Week-over-week summary data
 */
private data class WeekSummary(val workouts: Int, val totalVolume: Float, val totalReps: Int, val prsHit: Int)

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
    modifier: Modifier = Modifier,
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
                it.effectiveTotalVolumeKg().toDouble()
            }.toFloat(),
            totalReps = thisWeekSessions.sumOf { it.totalReps },
            prsHit = thisWeekPRs.size,
        )

        val lastWeekSummary = WeekSummary(
            workouts = lastWeekSessions.size,
            totalVolume = lastWeekSessions.sumOf {
                it.effectiveTotalVolumeKg().toDouble()
            }.toFloat(),
            totalReps = lastWeekSessions.sumOf { it.totalReps },
            prsHit = lastWeekPRs.size,
        )

        Pair(thisWeekSummary, lastWeekSummary)
    }

    // Helper function to calculate comparison
    fun calculateComparison(current: Int, previous: Int): Comparison = when {
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

    fun calculatePercentageComparison(current: Float, previous: Float): Comparison = when {
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

    // Calculate comparisons
    val workoutsComparison = calculateComparison(thisWeek.workouts, lastWeek.workouts)
    val volumeComparison = calculatePercentageComparison(thisWeek.totalVolume, lastWeek.totalVolume)
    val repsComparison = calculateComparison(thisWeek.totalReps, lastWeek.totalReps)
    val prsComparison = calculateComparison(thisWeek.prsHit, lastWeek.prsHit)

    // Format volume for display
    val displayVolume = if (weightUnit == WeightUnit.LB) {
        UnitConverter.kgToLb(thisWeek.totalVolume)
    } else {
        thisWeek.totalVolume
    }
    val volumeText = when {
        displayVolume >= 1000 -> "${(displayVolume / 1000).roundToInt()}k ${weightUnit.name.lowercase()}"
        else -> "${displayVolume.roundToInt()} ${weightUnit.name.lowercase()}"
    }

    val weekSummaryDescription = stringResource(
        Res.string.insights_week_summary,
        thisWeek.workouts,
        volumeText,
        thisWeek.totalReps,
        thisWeek.prsHit,
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = weekSummaryDescription
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_this_week),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_compared_last_7_days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stat rows
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WeekStatRow(
                    label = stringResource(Res.string.badges_workouts_label),
                    value = "${thisWeek.workouts}",
                    comparison = workoutsComparison,
                )

                WeekStatRow(
                    label = stringResource(Res.string.insights_total_volume),
                    value = volumeText,
                    comparison = volumeComparison,
                )

                WeekStatRow(
                    label = stringResource(Res.string.insights_total_reps),
                    value = "${thisWeek.totalReps}",
                    comparison = repsComparison,
                )

                WeekStatRow(
                    label = stringResource(Res.string.insights_prs_hit),
                    value = "${thisWeek.prsHit}",
                    comparison = prsComparison,
                )
            }
        }
    }
}

/**
 * Individual stat row with comparison indicator
 */
@Composable
private fun WeekStatRow(label: String, value: String, comparison: Comparison, modifier: Modifier = Modifier) {
    val (icon, color, comparisonText) = when (comparison) {
        is Comparison.Increase -> Triple(
            Icons.Default.ArrowUpward,
            AccessibilityTheme.colors.success,
            comparison.value,
        )

        is Comparison.Decrease -> Triple(
            Icons.Default.ArrowDownward,
            AccessibilityTheme.colors.error,
            comparison.value,
        )

        is Comparison.NoChange -> Triple(
            Icons.Default.Remove,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(Res.string.insights_same),
        )

        is Comparison.NoData -> Triple(
            Icons.Default.Remove,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "-",
        )
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Comparison indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = comparisonText,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MuscleBalanceRadarCard(personalRecords: List<PersonalRecord>, exerciseRepository: ExerciseRepository, modifier: Modifier = Modifier) {
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
        val standardGroups = listOf(
            "Chest" to Res.string.insights_muscle_chest,
            "Back" to Res.string.insights_muscle_back,
            "Legs" to Res.string.insights_muscle_legs,
            "Shoulders" to Res.string.insights_muscle_shoulders,
            "Arms" to Res.string.insights_muscle_arms,
            "Core" to Res.string.insights_muscle_core,
        )

        radarData = standardGroups.map { (group, labelRes) ->
            val count = counts[group] ?: 0
            getString(labelRes) to (if (maxCount > 0) count / maxCount else 0f)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_muscle_balance),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_muscle_balance_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (radarData.isNotEmpty() && radarData.any { it.second > 0 }) {
                RadarChart(
                    data = radarData,
                    maxValue = 1.0f,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(Res.string.insights_muscle_balance_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
    }
}

@Composable
fun ConsistencyGaugeCard(workoutSessions: List<WorkoutSession>, modifier: Modifier = Modifier) {
    val stats = remember(workoutSessions) {
        val thirtyDaysAgo = KmpUtils.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val count = workoutSessions.count { it.timestamp >= thirtyDaysAgo }
        count
    }

    // Dynamic target based on history, defaulting to 12 (3/week)
    val target = 12f

    val consistencyCd = stringResource(
        Res.string.insights_monthly_consistency_cd,
        stats,
        target.toInt(),
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = consistencyCd
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_monthly_consistency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_workouts_last_30_days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            GaugeChart(
                currentValue = stats.toFloat(),
                targetValue = target,
                label = stringResource(Res.string.badges_workouts_label),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun VolumeVsIntensityCard(workoutSessions: List<WorkoutSession>, weightUnit: WeightUnit, modifier: Modifier = Modifier) {
    // Prepare data for the last 7 sessions
    val (columnData, lineData) = remember(workoutSessions, weightUnit) {
        val sortedSessions = workoutSessions.sortedBy { it.timestamp }.takeLast(7)

        if (sortedSessions.isEmpty()) {
            Pair(emptyList<Pair<String, Float>>(), emptyList<Pair<String, Float>>())
        } else {
            // Issue #1182: an Echo session with no accepted working telemetry has no load or
            // volume to chart - never plot the configured seed or a zero as a lifted load.
            val chartSessions = sortedSessions.filter { session ->
                !EchoAchievedLoadResolver.isEcho(session) || EchoAchievedLoadResolver.fromSession(session) != null
            }
            val columns = chartSessions.mapIndexed { index, session ->
                val label = "S${index + 1}"
                val volume = session.effectiveTotalVolumeKg()
                // Convert to lbs if needed
                val adjustedVolume = if (weightUnit == WeightUnit.LB) UnitConverter.kgToLb(volume) else volume
                label to adjustedVolume
            }

            val lines = chartSessions.mapIndexed { index, session ->
                val label = "S${index + 1}"
                val maxWeight = session.effectiveHeaviestKgPerCable()
                // Convert to lbs if needed
                val adjustedWeight = if (weightUnit == WeightUnit.LB) UnitConverter.kgToLb(maxWeight) else maxWeight
                label to adjustedWeight
            }

            Pair(columns, lines)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_volume_vs_intensity),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_last_7_sessions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (columnData.isNotEmpty()) {
                val weightSuffix = stringResource(
                    if (weightUnit == WeightUnit.KG) Res.string.label_kg else Res.string.label_lbs,
                )
                ComboChart(
                    columnData = columnData,
                    lineData = lineData,
                    columnLabel = stringResource(Res.string.insights_volume_unit_label, weightSuffix),
                    lineLabel = stringResource(Res.string.insights_max_weight_unit_label, weightSuffix),
                    columnUnit = weightSuffix,
                    lineUnit = weightSuffix,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(Res.string.insights_no_workout_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
    }
}

@Composable
fun WorkoutModeDistributionCard(workoutSessions: List<WorkoutSession>, modifier: Modifier = Modifier) {
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
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_mode_distribution),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_based_on_sessions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (modeData.isNotEmpty()) {
                // Using MuscleGroupCircleChart as a donut chart
                // Chart is now responsive and self-sizing for tablet support
                MuscleGroupCircleChart(
                    data = modeData,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(Res.string.insights_no_mode_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
    }
}

@Composable
fun TotalVolumeCard(
    workoutSessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_total_volume_history),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_volume_per_session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (workoutSessions.isNotEmpty()) {
                VolumeTrendChart(
                    workoutSessions = workoutSessions,
                    weightUnit = weightUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(Res.string.insights_no_workout_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
    }
}

/**
 * Volume comparison data class for fun volume representations
 */
private data class VolumeComparison(val funValue: String, val funLabelRes: StringResource, val actualKg: Float)

/**
 * Format a float to one decimal place (KMP-compatible)
 */
internal fun formatOneDecimal(value: Float): String {
    // round(Float) returns Int; divide by 10f so the fractional tenth survives.
    val rounded = kotlin.math.round(value * 10) / 10f
    return if (rounded == rounded.toLong().toFloat()) {
        "${rounded.toLong()}.0"
    } else {
        "$rounded"
    }
}

/**
 * Get fun volume comparison based on total volume in kg
 */
private fun getVolumeComparison(totalVolumeKg: Float): VolumeComparison = when {
    totalVolumeKg >= 1_000_000 -> VolumeComparison(
        funValue = formatOneDecimal(totalVolumeKg / 52_000_000f),
        funLabelRes = Res.string.insights_fun_titanics,
        actualKg = totalVolumeKg,
    )

    totalVolumeKg >= 200_000 -> VolumeComparison(
        funValue = formatOneDecimal(totalVolumeKg / 150_000f),
        funLabelRes = Res.string.insights_fun_blue_whales,
        actualKg = totalVolumeKg,
    )

    totalVolumeKg >= 100_000 -> VolumeComparison(
        funValue = formatOneDecimal(totalVolumeKg / 100_000f),
        funLabelRes = Res.string.insights_fun_jumbo_jets,
        actualKg = totalVolumeKg,
    )

    totalVolumeKg >= 50_000 -> VolumeComparison(
        funValue = formatOneDecimal(totalVolumeKg / 5_000f),
        funLabelRes = Res.string.insights_fun_elephants,
        actualKg = totalVolumeKg,
    )

    totalVolumeKg >= 10_000 -> VolumeComparison(
        funValue = formatOneDecimal(totalVolumeKg / 1_500f),
        funLabelRes = Res.string.insights_fun_cars_crushed,
        actualKg = totalVolumeKg,
    )

    else -> VolumeComparison(
        funValue = "${totalVolumeKg.toInt()}",
        funLabelRes = Res.string.insights_fun_kg_lifted,
        actualKg = totalVolumeKg,
    )
}

/**
 * Lifetime stats data class
 */
private data class LifetimeStats(
    val totalWorkouts: Int,
    val totalVolumeKg: Float,
    val totalReps: Int,
    val daysSinceFirst: Long,
    val favoriteExercise: String?,
    val favoriteExerciseCount: Int,
    val favoriteMode: String?,
    val favoriteModeCount: Int,
)

/**
 * Calculate lifetime stats from workout sessions
 */
private fun calculateLifetimeStats(workoutSessions: List<WorkoutSession>, exerciseNames: Map<String, String>): LifetimeStats {
    if (workoutSessions.isEmpty()) {
        return LifetimeStats(
            totalWorkouts = 0,
            totalVolumeKg = 0f,
            totalReps = 0,
            daysSinceFirst = 0,
            favoriteExercise = null,
            favoriteExerciseCount = 0,
            favoriteMode = null,
            favoriteModeCount = 0,
        )
    }

    val totalWorkouts = workoutSessions.size

    val totalVolumeKg = workoutSessions.sumOf {
        it.effectiveTotalVolumeKg().toDouble()
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
        favoriteModeCount = favoriteModeCount,
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
    modifier: Modifier = Modifier,
) {
    // Build exercise names map
    var exerciseNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(workoutSessions) {
        val exerciseIds = workoutSessions.mapNotNull { it.exerciseId }.distinct()
        val names = mutableMapOf<String, String>()
        exerciseIds.forEach { id ->
            val exercise = exerciseRepository.getExerciseById(id)
            names[id] = exercise?.name ?: getString(Res.string.exercise_unknown)
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
            UnitConverter.kgToLb(stats.totalVolumeKg)
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
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.insights_lifetime_stats),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.insights_all_time_achievements),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (workoutSessions.isEmpty()) {
                Text(
                    stringResource(Res.string.insights_lifetime_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Total Workouts
                    LifetimeStatRow(
                        label = stringResource(Res.string.insights_total_workouts),
                        value = "${stats.totalWorkouts}",
                        subtext = null,
                    )

                    // Total Volume with fun comparison
                    if (stats.totalVolumeKg >= 10_000) {
                        // Show fun comparison prominently
                        LifetimeStatRow(
                            label = stringResource(Res.string.insights_total_volume),
                            value = "${volumeComparison.funValue} ${stringResource(volumeComparison.funLabelRes)}",
                            subtext = actualVolumeDisplay,
                            isFunStat = true,
                        )
                    } else {
                        LifetimeStatRow(
                            label = stringResource(Res.string.insights_total_volume),
                            value = actualVolumeDisplay,
                            subtext = null,
                        )
                    }

                    // Total Reps
                    LifetimeStatRow(
                        label = stringResource(Res.string.insights_total_reps),
                        value = when {
                            stats.totalReps >= 1000 -> "${formatOneDecimal(stats.totalReps / 1000f)}k"
                            else -> "${stats.totalReps}"
                        },
                        subtext = null,
                    )

                    // Days Since First Workout
                    if (stats.daysSinceFirst > 0) {
                        LifetimeStatRow(
                            label = stringResource(Res.string.insights_days_since_first),
                            value = "${stats.daysSinceFirst}",
                            subtext = stringResource(Res.string.insights_days_of_gains),
                        )
                    }

                    // Favorite Exercise
                    stats.favoriteExercise?.let { exercise ->
                        LifetimeStatRow(
                            label = stringResource(Res.string.insights_favorite_exercise),
                            value = exercise,
                            subtext = stringResource(Res.string.insights_workouts_count, stats.favoriteExerciseCount),
                        )
                    }

                    // Favorite Mode
                    stats.favoriteMode?.let { mode ->
                        LifetimeStatRow(
                            label = stringResource(Res.string.insights_favorite_mode),
                            value = mode,
                            subtext = stringResource(Res.string.insights_workouts_count, stats.favoriteModeCount),
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
private fun LifetimeStatRow(label: String, value: String, subtext: String?, isFunStat: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isFunStat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            subtext?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
