package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions

/**
 * Material 3 Expressive Area Chart
 * Pure Canvas implementation for Kotlin Multiplatform compatibility.
 * Shows a line chart with gradient fill below the line.
 */
@Composable
fun AreaChart(
    data: List<Pair<String, Float>>, // Label to value pairs
    modifier: Modifier = Modifier,
    title: String? = null,
    label: String = "Value",
    showGrid: Boolean = true,
    showPopup: Boolean = true,
    animationDuration: Int = 2000,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    areaColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    // Data validation
    if (data.isEmpty()) {
        EmptyChartState(
            message = "No data available",
            modifier = modifier
        )
        return
    }

    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(data) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = animationDuration,
                easing = EaseInOutCubic
            )
        )
    }

    val values = data.map { it.second }
    val maxValue = values.maxOrNull() ?: 1f
    val minValue = values.minOrNull() ?: 0f
    val valueRange = (maxValue - minValue).coerceAtLeast(1f)

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val density = LocalDensity.current

    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

    Column(modifier = modifier.padding(16.dp)) {
        // Title
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val canvasWidth = with(density) { maxWidth.toPx() }
            val canvasHeight = with(density) { maxHeight.toPx() }
            val paddingLeft = 40.dp
            val paddingBottom = 30.dp
            val paddingLeftPx = with(density) { paddingLeft.toPx() }
            val paddingBottomPx = with(density) { paddingBottom.toPx() }

            val effectiveWidth = canvasWidth - paddingLeftPx
            val effectiveHeight = canvasHeight - paddingBottomPx

            Canvas(modifier = Modifier.fillMaxSize()) {
                val progress = animationProgress.value

                // Draw grid lines
                if (showGrid) {
                    // Horizontal grid lines (5 lines)
                    for (i in 0..4) {
                        val y = paddingBottomPx + (effectiveHeight * i / 4f)
                        drawLine(
                            color = gridColor,
                            start = Offset(paddingLeftPx, canvasHeight - y),
                            end = Offset(canvasWidth, canvasHeight - y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // Vertical grid lines
                    val stepX = effectiveWidth / (data.size - 1).coerceAtLeast(1)
                    for (i in data.indices) {
                        val x = paddingLeftPx + stepX * i
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, canvasHeight - paddingBottomPx),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }

                if (data.size < 2) {
                    // Just draw a single point
                    val x = paddingLeftPx + effectiveWidth / 2
                    val normalizedValue = (values[0] - minValue) / valueRange
                    val y = canvasHeight - paddingBottomPx - (normalizedValue * effectiveHeight * progress)
                    drawCircle(
                        color = lineColor,
                        radius = 6.dp.toPx(),
                        center = Offset(x, y)
                    )
                    return@Canvas
                }

                // Calculate points
                val stepX = effectiveWidth / (data.size - 1)
                val points = values.mapIndexed { index, value ->
                    val x = paddingLeftPx + stepX * index
                    val normalizedValue = (value - minValue) / valueRange
                    val y = canvasHeight - paddingBottomPx - (normalizedValue * effectiveHeight * progress)
                    Offset(x, y)
                }

                // Draw area fill with gradient
                val areaPath = Path().apply {
                    moveTo(points.first().x, canvasHeight - paddingBottomPx)
                    points.forEach { point ->
                        lineTo(point.x, point.y)
                    }
                    lineTo(points.last().x, canvasHeight - paddingBottomPx)
                    close()
                }

                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            areaColor.copy(alpha = 0.6f * progress),
                            areaColor.copy(alpha = 0.1f * progress)
                        ),
                        startY = 0f,
                        endY = canvasHeight - paddingBottomPx
                    )
                )

                // Draw line
                val linePath = Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) {
                            moveTo(point.x, point.y)
                        } else {
                            lineTo(point.x, point.y)
                        }
                    }
                }

                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw data points
                points.forEach { point ->
                    drawCircle(
                        color = lineColor,
                        radius = 5.dp.toPx(),
                        center = point
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = point
                    )
                }
            }

            // X-axis labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(start = paddingLeft),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                data.forEach { (labelText, _) ->
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Empty state for charts when no data is available
 */
@Composable
internal fun EmptyChartState(
    message: String,
    modifier: Modifier = Modifier
) {
    val emptyStateHeight = ResponsiveDimensions.chartHeight(baseHeight = 280.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(emptyStateHeight)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
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
