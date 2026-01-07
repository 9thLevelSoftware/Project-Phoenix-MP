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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions

/**
 * Material 3 Expressive Combo Chart
 * Pure Canvas implementation combining column (bars) and line charts.
 * Perfect for comparing volume (columns) with weight progression (line).
 */
@Composable
fun ComboChart(
    columnData: List<Pair<String, Float>>, // Label to value pairs for columns
    lineData: List<Pair<String, Float>>, // Label to value pairs for line
    modifier: Modifier = Modifier,
    columnLabel: String = "Volume",
    lineLabel: String = "Weight",
    columnColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
    animationDuration: Int = 1500
) {
    // Data validation
    if (columnData.isEmpty() && lineData.isEmpty()) {
        ComboEmptyChartState(
            message = "No data available",
            modifier = modifier
        )
        return
    }

    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(columnData, lineData) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = animationDuration,
                easing = EaseInOutCubic
            )
        )
    }

    // Calculate ranges
    val columnValues = columnData.map { it.second }
    val lineValues = lineData.map { it.second }
    val maxColumnValue = columnValues.maxOrNull() ?: 1f
    val maxLineValue = lineValues.maxOrNull() ?: 1f
    val minLineValue = lineValues.minOrNull() ?: 0f
    val lineValueRange = (maxLineValue - minLineValue).coerceAtLeast(1f)

    val labels = (columnData.map { it.first } + lineData.map { it.first }).distinct()
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val density = LocalDensity.current

    Column(modifier = modifier.padding(16.dp)) {
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Column legend
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .padding(end = 4.dp)
            )
            Canvas(modifier = Modifier.size(12.dp)) {
                drawRoundRect(
                    color = columnColor,
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = columnLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Line legend
            Canvas(modifier = Modifier.size(12.dp)) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = lineLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val chartWidthPx = with(density) { maxWidth.toPx() }
            val chartHeightPx = with(density) { maxHeight.toPx() }
            val paddingBottom = 30.dp
            val paddingBottomPx = with(density) { paddingBottom.toPx() }
            val effectiveHeight = chartHeightPx - paddingBottomPx

            Canvas(modifier = Modifier.fillMaxSize()) {
                val progress = animationProgress.value
                val barCount = labels.size.coerceAtLeast(1)
                val barWidth = (chartWidthPx / barCount) * 0.6f
                val barSpacing = (chartWidthPx / barCount) * 0.4f / 2f

                // Draw horizontal grid
                for (i in 0..4) {
                    val y = effectiveHeight * (1 - i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(chartWidthPx, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Draw columns
                labels.forEachIndexed { index, label ->
                    val columnValue = columnData.find { it.first == label }?.second ?: 0f
                    val normalizedHeight = (columnValue / maxColumnValue) * effectiveHeight * progress

                    val x = barSpacing + index * (barWidth + barSpacing * 2)
                    val y = effectiveHeight - normalizedHeight

                    drawRoundRect(
                        color = columnColor.copy(alpha = 0.8f),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, normalizedHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }

                // Draw line
                if (lineData.size >= 2) {
                    val linePath = Path()
                    var firstPoint = true

                    labels.forEachIndexed { index, label ->
                        val lineValue = lineData.find { it.first == label }?.second
                        if (lineValue != null) {
                            val normalizedY = ((lineValue - minLineValue) / lineValueRange)
                            val x = barSpacing + index * (barWidth + barSpacing * 2) + barWidth / 2
                            val y = effectiveHeight - (normalizedY * effectiveHeight * 0.9f * progress) - effectiveHeight * 0.05f

                            if (firstPoint) {
                                linePath.moveTo(x, y)
                                firstPoint = false
                            } else {
                                linePath.lineTo(x, y)
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

                    // Draw line points
                    labels.forEachIndexed { index, label ->
                        val lineValue = lineData.find { it.first == label }?.second
                        if (lineValue != null) {
                            val normalizedY = ((lineValue - minLineValue) / lineValueRange)
                            val x = barSpacing + index * (barWidth + barSpacing * 2) + barWidth / 2
                            val y = effectiveHeight - (normalizedY * effectiveHeight * 0.9f * progress) - effectiveHeight * 0.05f

                            drawCircle(
                                color = lineColor,
                                radius = 5.dp.toPx(),
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }
                }
            }

            // X-axis labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                labels.forEach { label ->
                    Text(
                        text = label,
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

@Composable
private fun ComboEmptyChartState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
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
