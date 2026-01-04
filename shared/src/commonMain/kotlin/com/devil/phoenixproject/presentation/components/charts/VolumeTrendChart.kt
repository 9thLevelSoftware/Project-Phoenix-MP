package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.ui.theme.DataColors
import com.devil.phoenixproject.util.KmpUtils
import kotlin.time.Instant
import kotlinx.datetime.*

/**
 * Volume trend chart showing total volume lifted over time.
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

    // Process data: group by date and calculate volume per day
    val volumeData = remember(workoutSessions, weightUnit) {
        processVolumeData(workoutSessions, weightUnit)
    }

    if (volumeData.isEmpty()) {
        EmptyVolumeTrendState(modifier = modifier)
        return
    }

    val maxVolume = volumeData.maxOfOrNull { it.volume } ?: 1f
    val barColor = DataColors.Volume
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

    Column(modifier = modifier.fillMaxWidth().height(chartHeight)) {
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

                        // Draw bar with rounded top corners
                        drawRoundRect(
                            color = barColor,
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

/**
 * Data class for volume chart entries
 */
private data class VolumeDataPoint(
    val dateLabel: String,
    val volume: Float,
    val timestamp: Long
)

/**
 * Process workout sessions into volume data points grouped by date
 */
private fun processVolumeData(
    sessions: List<WorkoutSession>,
    weightUnit: WeightUnit
): List<VolumeDataPoint> {
    // Group sessions by calendar day
    val sessionsByDay = sessions
        .sortedBy { it.timestamp }
        .groupBy { session ->
            // Convert timestamp to local date (normalize to midnight)
            val instant = Instant.fromEpochMilliseconds(session.timestamp)
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            localDate.toString()
        }

    return sessionsByDay.map { (dateStr, daySessions) ->
        // Calculate total volume for this day
        // Volume = sum of (weightPerCableKg * totalReps * 2) for each session
        val totalVolume = daySessions.sumOf { session ->
            (session.weightPerCableKg * session.totalReps * 2).toDouble()
        }.toFloat()

        // Convert to user's preferred unit
        val displayVolume = if (weightUnit == WeightUnit.LB) {
            totalVolume * 2.20462f
        } else {
            totalVolume
        }

        // Format date label (e.g., "Nov 26")
        val localDate = LocalDate.parse(dateStr)
        val monthName = localDate.month.name.take(3).lowercase()
            .replaceFirstChar { it.uppercase() }
        val dateLabel = "$monthName ${localDate.day}"

        VolumeDataPoint(
            dateLabel = dateLabel,
            volume = displayVolume,
            timestamp = daySessions.first().timestamp
        )
    }.takeLast(14) // Show last 14 days max
}

/**
 * Format volume label for Y-axis
 */
private fun formatVolumeLabel(volume: Float, weightUnit: WeightUnit): String {
    val unit = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
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
