package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.effectiveTotalVolumeKg
import com.devil.phoenixproject.ui.theme.DataColors
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.datetime.*

/**
 * Time period for volume chart grouping
 */
enum class VolumePeriod(val label: String) {
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year")
}

/**
 * Time period filter for history/insights tabs
 */
enum class HistoryTimePeriod(val label: String) {
    DAYS_7("7 days"),
    DAYS_14("14 days"),
    DAYS_30("30 days"),
    ALL("All time")
}

/**
 * Volume trend chart showing total volume lifted over time.
 * Supports Week/Month/Year period selection with bar tap interactivity.
 * Uses Compose Canvas for cross-platform rendering.
 */
@Composable
fun VolumeTrendChart(
    workoutSessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier
) {
    if (workoutSessions.isEmpty()) {
        EmptyVolumeTrendState(modifier = modifier)
        return
    }

    var selectedPeriod by remember { mutableStateOf(VolumePeriod.WEEK) }
    var selectedBarIndex by remember { mutableStateOf<Int?>(null) }

    // Reset selection when period changes
    LaunchedEffect(selectedPeriod) {
        selectedBarIndex = null
    }

    // Process data based on selected period
    val volumeData = remember(workoutSessions, weightUnit, selectedPeriod) {
        processVolumeData(workoutSessions, weightUnit, selectedPeriod)
    }

    if (volumeData.isEmpty()) {
        EmptyVolumeTrendState(modifier = modifier)
        return
    }

    val maxVolume = volumeData.maxOfOrNull { it.volume } ?: 1f
    val barColor = DataColors.Volume
    val selectedBarColor = DataColors.Intensity
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Animation for bars
    var animationProgress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = animationProgress,
        animationSpec = tween(durationMillis = 800),
        label = "VolumeChartAnimation"
    )

    LaunchedEffect(volumeData) {
        animationProgress = 0f
        animationProgress = 1f
    }

    // Responsive column width for Y-axis labels
    val windowSizeClass = LocalWindowSizeClass.current
    val columnWidth = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 80.dp
        WindowWidthSizeClass.Medium -> 65.dp
        WindowWidthSizeClass.Compact -> 50.dp
    }

    // Responsive chart height
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 250.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        // Period selector chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VolumePeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = {
                        Text(
                            period.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // Selected bar detail tooltip
        selectedBarIndex?.let { index ->
            if (index in volumeData.indices) {
                val data = volumeData[index]
                val unit = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                Text(
                    text = "${data.dateLabel}: ${formatVolumeLabel(data.volume, weightUnit)} $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            // Chart area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Y-axis labels
                Column(
                    modifier = Modifier
                        .width(columnWidth)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatVolumeLabel(maxVolume, weightUnit),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                    Text(
                        text = formatVolumeLabel(maxVolume / 2, weightUnit),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                }

                // Scrollable chart area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                ) {
                    val barWidth = 40.dp
                    val barSpacing = 8.dp
                    val chartWidth = (barWidth + barSpacing) * volumeData.size + barSpacing

                    Canvas(
                        modifier = Modifier
                            .width(chartWidth.coerceAtLeast(200.dp))
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)
                            .pointerInput(volumeData) {
                                detectTapGestures { offset ->
                                    val barWidthPx = barWidth.toPx()
                                    val barSpacingPx = barSpacing.toPx()
                                    // Determine which bar was tapped
                                    val tappedIndex = volumeData.indices.firstOrNull { index ->
                                        val barX = barSpacingPx + index * (barWidthPx + barSpacingPx)
                                        offset.x >= barX && offset.x <= barX + barWidthPx
                                    }
                                    selectedBarIndex = if (tappedIndex == selectedBarIndex) null else tappedIndex
                                }
                            }
                    ) {
                        val usableHeight = size.height
                        val barWidthPx = barWidth.toPx()
                        val barSpacingPx = barSpacing.toPx()

                        // Draw horizontal grid lines
                        val gridLineCount = 4
                        for (i in 0..gridLineCount) {
                            val y = usableHeight * i / gridLineCount
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f
                            )
                        }

                        // Draw bars
                        volumeData.forEachIndexed { index, data ->
                            val x = barSpacingPx + index * (barWidthPx + barSpacingPx)
                            val normalizedHeight = (data.volume / maxVolume) * animatedProgress
                            val barHeight = normalizedHeight * usableHeight
                            val y = usableHeight - barHeight

                            val isSelected = index == selectedBarIndex
                            val color = if (isSelected) selectedBarColor else barColor

                            // Draw bar with rounded top corners
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(x, y),
                                size = Size(barWidthPx, barHeight),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                        }
                    }
                }
            }

            // X-axis labels (dates)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = columnWidth + 4.dp, top = 4.dp)
            ) {
                volumeData.forEach { data ->
                    Text(
                        text = data.dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}

/**
 * Data class for volume chart entries
 */
private data class VolumeDataPoint(
    val dateLabel: String,
    val volume: Float,
    val timestamp: Long
)

/**
 * Process workout sessions into volume data points based on the selected time period.
 */
private fun processVolumeData(
    sessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    period: VolumePeriod
): List<VolumeDataPoint> {
    val sorted = sessions.sortedBy { it.timestamp }
    if (sorted.isEmpty()) return emptyList()

    val grouped = when (period) {
        VolumePeriod.WEEK -> groupByDay(sorted)
        VolumePeriod.MONTH -> groupByWeek(sorted)
        VolumePeriod.YEAR -> groupByMonth(sorted)
    }

    val maxItems = when (period) {
        VolumePeriod.WEEK -> 7
        VolumePeriod.MONTH -> 5  // ~4-5 weeks in a month
        VolumePeriod.YEAR -> 12
    }

    return grouped.map { (label, daySessions) ->
        val totalVolume = daySessions.sumOf { session ->
            session.effectiveTotalVolumeKg().toDouble()
        }.toFloat()

        val displayVolume = if (weightUnit == WeightUnit.LB) {
            totalVolume * 2.20462f
        } else {
            totalVolume
        }

        VolumeDataPoint(
            dateLabel = label,
            volume = displayVolume,
            timestamp = daySessions.first().timestamp
        )
    }.takeLast(maxItems)
}

/**
 * Group sessions by calendar day (for Week view).
 * Returns map of dateLabel -> sessions, showing last 7 days.
 */
private fun groupByDay(sessions: List<WorkoutSession>): List<Pair<String, List<WorkoutSession>>> {
    val now = Instant.fromEpochMilliseconds(currentTimeMillis())
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val startDate = today.minus(6, DateTimeUnit.DAY)

    val sessionsByDay = sessions
        .groupBy { session ->
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
        }
        .filterKeys { it >= startDate }

    // Fill all 7 days (including empty ones)
    return (0..6).map { offset ->
        val date = startDate.plus(offset, DateTimeUnit.DAY)
        val monthName = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val label = "$monthName ${date.dayOfMonth}"
        val daySessions = sessionsByDay[date] ?: emptyList()
        label to daySessions
    }.filter { it.second.isNotEmpty() }
}

/**
 * Group sessions by week (for Month view).
 * Groups by Monday-start weeks.
 */
private fun groupByWeek(sessions: List<WorkoutSession>): List<Pair<String, List<WorkoutSession>>> {
    val now = Instant.fromEpochMilliseconds(currentTimeMillis())
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val startDate = today.minus(29, DateTimeUnit.DAY)

    return sessions
        .filter { session ->
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            date >= startDate
        }
        .groupBy { session ->
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            // Find Monday of this week (kotlinx.datetime DayOfWeek: MONDAY=0 via isoDayNumber=1)
            val daysSinceMonday = date.dayOfWeek.isoDayNumber - 1
            val monday = date.minus(daysSinceMonday, DateTimeUnit.DAY)
            monday
        }
        .entries.sortedBy { it.key }
        .map { (monday, weekSessions) ->
            val monthName = monday.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val label = "$monthName ${monday.dayOfMonth}"
            label to weekSessions
        }
}

/**
 * Group sessions by month (for Year view).
 */
private fun groupByMonth(sessions: List<WorkoutSession>): List<Pair<String, List<WorkoutSession>>> {
    val now = Instant.fromEpochMilliseconds(currentTimeMillis())
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val startMonth = today.minus(11, DateTimeUnit.MONTH)

    return sessions
        .filter { session ->
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            date >= startMonth
        }
        .groupBy { session ->
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"
        }
        .entries.sortedBy { it.key }
        .map { (_, monthSessions) ->
            val firstDate = Instant.fromEpochMilliseconds(monthSessions.first().timestamp)
                .toLocalDateTime(TimeZone.currentSystemDefault()).date
            val monthName = firstDate.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            monthName to monthSessions
        }
}

/**
 * Format volume label for Y-axis
 */
private fun formatVolumeLabel(volume: Float, weightUnit: WeightUnit): String {
    return when {
        volume >= 1000 -> "${(volume / 1000).toInt()}k"
        volume >= 100 -> "${volume.toInt()}"
        else -> "${volume.toInt()}"
    }
}

@Composable
private fun EmptyVolumeTrendState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Complete workouts to see your volume trend",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
