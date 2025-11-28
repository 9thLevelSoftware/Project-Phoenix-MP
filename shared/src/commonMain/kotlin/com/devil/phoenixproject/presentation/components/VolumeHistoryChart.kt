package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Placeholder for TrendPoint if not resolved
data class VolumePoint(
    val label: String, // e.g., "Mon", "Tue" or Date
    val volume: Float
)

@Composable
fun VolumeHistoryChart(
    data: List<VolumePoint>,
    modifier: Modifier = Modifier.height(200.dp),
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    if (data.isEmpty()) return

    val maxVolume = data.maxOfOrNull { it.volume } ?: 1f

    Canvas(modifier = modifier.fillMaxSize()) {
        val barWidth = size.width / (data.size * 2f) // Bars take up half the space
        val spaceWidth = size.width / (data.size * 2f)
        val maxHeight = size.height

        data.forEachIndexed { index, point ->
            val volumeHeight = (point.volume / maxVolume) * maxHeight
            val x = index * (barWidth + spaceWidth) + (spaceWidth / 2)
            val y = maxHeight - volumeHeight

            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, volumeHeight)
            )

            // TODO: Draw text labels (requires expect/actual or library in pure KMP canvas)
            // drawIntoCanvas { canvas -> ... } relies on nativeCanvas which is platform specific
        }
    }
}
