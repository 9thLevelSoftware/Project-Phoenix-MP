package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Circle Chart (Donut Chart)
 * Custom Compose Canvas implementation for beautiful donut/pie visualizations
 * Perfect for muscle group distribution and progress tracking
 */
@Composable
fun MuscleGroupCircleChart(
    data: List<Pair<String, Float>>, // Label to value pairs
    modifier: Modifier = Modifier,
    onSegmentClick: ((String, Float) -> Unit)? = null // Reserved for future click handling
) {
    // Data validation - Material 3 Expressive: Handle empty/invalid data gracefully
    if (data.isEmpty() || data.all { it.second <= 0f }) {
        EmptyChartState(
            modifier = modifier
        )
        return
    }

    // Material 3 Expressive color palette
    val colorScheme = MaterialTheme.colorScheme
    val colors = remember(colorScheme) {
        listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.primaryContainer,
            colorScheme.secondaryContainer,
            colorScheme.tertiaryContainer,
            colorScheme.error,
            colorScheme.errorContainer
        )
    }

    // Calculate total and normalized values
    val total = data.sumOf { it.second.toDouble() }.toFloat()
    val normalizedData = remember(data, total) {
        data.map { (label, value) -> label to (value / total) }
    }

    // Animation state
    val animationProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 1500),
        label = "chart_animation"
    )

    // Use ResponsiveDimensions for consistent tablet-responsive sizing
    val chartSize = ResponsiveDimensions.chartHeight(baseHeight = 280.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(
                        if (onSegmentClick != null) {
                            Modifier.pointerInput(data, normalizedData) {
                                detectTapGestures { tapOffset ->
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val radius = min(size.width, size.height).toFloat() / 2f
                                    val innerRadius = radius * 0.4f
                                    val strokeWidth = 24.dp.toPx()

                                    // Calculate distance from center
                                    val dx = tapOffset.x - center.x
                                    val dy = tapOffset.y - center.y
                                    val distance = sqrt(dx * dx + dy * dy)

                                    // Check if tap is within the donut ring
                                    val outerEdge = radius
                                    val innerEdge = innerRadius
                                    if (distance >= innerEdge && distance <= outerEdge + strokeWidth / 2) {
                                        // Calculate angle (convert to degrees, adjust for starting at top)
                                        var angle = atan2(dy, dx) * (180.0 / PI).toFloat()
                                        angle = (angle + 90f + 360f) % 360f // Adjust to start from top

                                        // Find which segment was tapped
                                        var cumulativeAngle = 0f
                                        for ((label, percentage) in normalizedData) {
                                            val sweepAngle = percentage * 360f
                                            if (angle >= cumulativeAngle && angle < cumulativeAngle + sweepAngle) {
                                                // Found the segment - get original value from data
                                                val originalValue = data.find { it.first == label }?.second ?: percentage * total
                                                onSegmentClick(label, originalValue)
                                                break
                                            }
                                            cumulativeAngle += sweepAngle
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                val surfaceColor = colorScheme.surface
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f
                val innerRadius = radius * 0.4f // Donut chart style (40% inner radius)
                val strokeWidth = 24.dp.toPx() // Material 3 Expressive: Thicker strokes
                val spacing = 8.dp.toPx() // Material 3 Expressive: More spacing

                var startAngle = -90f // Start from top

                // Draw all donut segments
                normalizedData.forEachIndexed { index, (_, percentage) ->
                    val sweepAngle = percentage * 360f * animationProgress
                    val color = colors[index % colors.size]

                    // Draw donut segment
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = maxOf(0f, sweepAngle - spacing),
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius,
                            center.y - radius
                        ),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    )

                    startAngle += sweepAngle
                }

                // Draw inner circle once to create donut effect
                drawCircle(
                    color = surfaceColor,
                    radius = innerRadius,
                    center = center
                )
            }
        }
    }
}

/**
 * Empty state for charts when no data is available
 */
@Composable
private fun EmptyChartState(
    modifier: Modifier = Modifier
) {
    // Use ResponsiveDimensions for consistent tablet-responsive sizing
    val chartSize = ResponsiveDimensions.chartHeight(baseHeight = 280.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = "No data available",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
