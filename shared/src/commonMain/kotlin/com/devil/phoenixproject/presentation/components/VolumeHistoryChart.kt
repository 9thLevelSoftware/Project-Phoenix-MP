package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.DataColors

// Placeholder for TrendPoint if not resolved
data class VolumePoint(
    val label: String, // e.g., "Mon", "Tue" or Date
    val volume: Float,
)

/**
 * Volume history bar chart with labels.
 * Uses BoxWithConstraints for KMP-compatible text label positioning.
 */
@Composable
fun VolumeHistoryChart(data: List<VolumePoint>, modifier: Modifier = Modifier.height(200.dp), barColor: Color = DataColors.Volume) {
    if (data.isEmpty()) return

    val maxVolume = data.maxOfOrNull { it.volume } ?: 1f
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val boxWidth = with(density) { maxWidth.toPx() }
        val boxHeight = with(density) { maxHeight.toPx() }
        val barWidth = boxWidth / (data.size * 2f)
        val spaceWidth = boxWidth / (data.size * 2f)
        val labelHeight = 20.dp
        val chartHeight = boxHeight - with(density) { labelHeight.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            data.forEachIndexed { index, point ->
                val volumeHeight = (point.volume / maxVolume) * chartHeight
                val x = index * (barWidth + spaceWidth) + (spaceWidth / 2)
                val y = chartHeight - volumeHeight

                drawRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, volumeHeight),
                )
            }
        }

        // Draw text labels below bars using Compose Text
        data.forEachIndexed { index, point ->
            val x = index * (barWidth + spaceWidth) + (spaceWidth / 2)
            val labelWidth = barWidth + spaceWidth

            Text(
                text = point.label,
                style = labelStyle,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(
                        x = with(density) { x.toDp() },
                        y = maxHeight - labelHeight,
                    )
                    .width(with(density) { labelWidth.toDp() }),
            )
        }
    }
}
