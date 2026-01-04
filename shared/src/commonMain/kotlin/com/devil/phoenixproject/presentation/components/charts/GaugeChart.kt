@file:Suppress("unused")

package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions

/**
 * Material 3 Expressive Gauge Chart
 * Custom gauge chart for goal progress visualization
 * Shows progress from 0% to 100% with Material 3 colors
 *
 * Uses Compose Text overlaid on Canvas for KMP-compatible text rendering.
 */
@Composable
fun GaugeChart(
    currentValue: Float,
    targetValue: Float,
    modifier: Modifier = Modifier,
    label: String = "Progress",
    showPercentage: Boolean = true
) {
    // Data validation
    if (targetValue <= 0f) {
        EmptyGaugeState(
            message = "Invalid target value",
            modifier = modifier
        )
        return
    }

    val progress = (currentValue / targetValue).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1500),
        label = "GaugeProgress"
    )

    val percentage = (animatedProgress * 100).toInt()
    val gaugeColor = when {
        animatedProgress >= 0.8f -> MaterialTheme.colorScheme.primary
        animatedProgress >= 0.5f -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    val surfaceContainerHighestColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height * 0.8f
                val radius = size.width.coerceAtMost(size.height * 1.2f) / 2.5f

                // Background arc
                drawArc(
                    color = surfaceContainerHighestColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(
                        width = 24.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // Progress arc
                drawArc(
                    color = gaugeColor,
                    startAngle = 180f,
                    sweepAngle = 180f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(
                        width = 24.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // Gradient overlay
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            gaugeColor.copy(alpha = 0.8f),
                            gaugeColor.copy(alpha = 0.4f)
                        ),
                        start = Offset(centerX - radius, centerY),
                        end = Offset(centerX + radius, centerY)
                    ),
                    startAngle = 180f,
                    sweepAngle = 180f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(
                        width = 8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            // Center text overlaid on canvas (positioned in lower portion where arc center is)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = 24.dp)
            ) {
                Text(
                    text = if (showPercentage) "$percentage%" else "${currentValue.toInt()}/${targetValue.toInt()}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Label text below gauge
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

@Composable
private fun EmptyGaugeState(
    message: String,
    modifier: Modifier = Modifier
) {
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = "Invalid gauge data",
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
}
