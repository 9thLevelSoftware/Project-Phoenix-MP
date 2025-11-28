@file:Suppress("unused")

package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive Radar/Spider Chart
 * Visualizes muscle group balance and distribution
 * Shows relative strength/volume across different muscle groups
 *
 * TODO: Labels use Android nativeCanvas for text rendering.
 * For KMP: Replace with Compose Text positioned using Layout or Box
 */
@Composable
fun RadarChart(
    data: List<Pair<String, Float>>, // Label to normalized value (0.0 to 1.0)
    modifier: Modifier = Modifier,
    maxValue: Float = 1f,
    showLabels: Boolean = true
) {
    // Data validation
    if (data.isEmpty() || maxValue <= 0f) {
        EmptyChartState(
            message = "No data available",
            modifier = modifier
        )
        return
    }

    val animatedData = data.map { (label, value) ->
        val animatedValue by animateFloatAsState(
            targetValue = value.coerceIn(0f, maxValue),
            animationSpec = tween(durationMillis = 1500),
            label = "RadarValue_$label"
        )
        label to animatedValue
    }

    val colorScheme = MaterialTheme.colorScheme
    val outlineColor = colorScheme.outline
    val primaryColor = colorScheme.primary
    val primaryContainerColor = colorScheme.primaryContainer

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(24.dp)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.width.coerceAtMost(size.height) / 2.5f
        val numPoints = animatedData.size
        val angleStep = (2 * PI) / numPoints

        // Draw grid circles
        for (i in 1..5) {
            val gridRadius = radius * (i / 5f)
            drawCircle(
                color = outlineColor.copy(alpha = 0.2f),
                radius = gridRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Draw grid lines
        for (i in 0 until numPoints) {
            val angle = i * angleStep - PI / 2
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()

            drawLine(
                color = outlineColor.copy(alpha = 0.3f),
                start = Offset(centerX, centerY),
                end = Offset(x, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw data area
        val dataPath = Path().apply {
            animatedData.forEachIndexed { index, (_, value) ->
                val angle = index * angleStep - PI / 2
                val distance = radius * (value / maxValue)
                val x = centerX + distance * cos(angle).toFloat()
                val y = centerY + distance * sin(angle).toFloat()

                if (index == 0) {
                    moveTo(x, y)
                } else {
                    lineTo(x, y)
                }
            }
            close()
        }

        // Fill area
        drawPath(
            path = dataPath,
            color = primaryContainerColor.copy(alpha = 0.4f),
            style = Fill
        )

        // Draw outline
        drawPath(
            path = dataPath,
            color = primaryColor,
            style = Stroke(
                width = 4.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )

        // Draw data points
        animatedData.forEachIndexed { index, (_, value) ->
            val angle = index * angleStep - PI / 2
            val distance = radius * (value / maxValue)
            val x = centerX + distance * cos(angle).toFloat()
            val y = centerY + distance * sin(angle).toFloat()

            drawCircle(
                color = primaryColor,
                radius = 8.dp.toPx(),
                center = Offset(x, y)
            )
            drawCircle(
                color = primaryContainerColor,
                radius = 4.dp.toPx(),
                center = Offset(x, y)
            )
        }

        // TODO: Add labels using Compose Text instead of nativeCanvas
    }
}

@Composable
private fun EmptyChartState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = "No data available",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
