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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Material 3 Expressive Combo Chart
 * Pure Canvas implementation combining column (bars) and line charts.
 * Perfect for comparing volume (columns) with weight progression (line).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComboChart(
    columnData: List<Pair<String, Float>>, // Label to value pairs for columns
    lineData: List<Pair<String, Float>>, // Label to value pairs for line
    modifier: Modifier = Modifier,
    columnLabel: String = "Volume",
    lineLabel: String = "Weight",
    columnColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
    animationDuration: Int = 1500,
    columnUnit: String = "",
    lineUnit: String = "",
) {
    // Data validation
    if (columnData.isEmpty() && lineData.isEmpty()) {
        ComboEmptyChartState(
            message = stringResource(Res.string.chart_no_data),
            modifier = modifier,
        )
        return
    }

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val animationProgress = remember { Animatable(if (reduceMotion) 1f else 0f) }

    LaunchedEffect(columnData, lineData, reduceMotion) {
        if (reduceMotion) {
            animationProgress.snapTo(1f)
        } else {
            animationProgress.snapTo(0f)
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = animationDuration,
                    easing = EaseInOutCubic,
                ),
            )
        }
    }

    // Calculate ranges safely
    val columnValues = columnData.map { it.second }.filter { it.isFinite() }
    val lineValues = lineData.map { it.second }.filter { it.isFinite() }
    val maxColumnValue = (columnValues.maxOrNull() ?: 1f).let { if (it > 0f) it else 1f }
    val maxLineValue = lineValues.maxOrNull() ?: 1f
    val minLineValue = lineValues.minOrNull() ?: 0f
    val hasLineRange = (maxLineValue - minLineValue) > 0.001f
    val lineValueRange = if (hasLineRange) maxLineValue - minLineValue else 1f

    val labels = (columnData.map { it.first } + lineData.map { it.first }).distinct()
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val innerDotColor = MaterialTheme.colorScheme.surface // was Color.White — adapts to theme

    Column(modifier = modifier.padding(16.dp)) {
        // Legend (wraps cleanly at larger font sizes)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawRoundRect(
                        color = columnColor,
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                Text(
                    text = columnLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                Text(
                    text = lineLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Y-axis range/unit header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (columnValues.isNotEmpty()) {
                val colSuffix = if (columnUnit.isNotEmpty()) " $columnUnit" else ""
                Text(
                    text = "Max ${formatCompactValue(maxColumnValue)}$colSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = columnColor,
                )
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (lineValues.isNotEmpty()) {
                val lineSuffix = if (lineUnit.isNotEmpty()) " $lineUnit" else ""
                val rangeText = if (hasLineRange) {
                    "${formatCompactValue(minLineValue)}–${formatCompactValue(maxLineValue)}$lineSuffix"
                } else {
                    "${formatCompactValue(maxLineValue)}$lineSuffix"
                }
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = lineColor,
                )
            }
        }

        val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 180.dp)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight),
        ) {
            val chartWidthPx = size.width
            val effectiveHeight = size.height
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
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Draw columns
            labels.forEachIndexed { index, label ->
                val columnValue = columnData.find { it.first == label }?.second?.takeIf { it.isFinite() } ?: 0f
                val normalizedHeight = (columnValue / maxColumnValue) * effectiveHeight * progress

                val x = barSpacing + index * (barWidth + barSpacing * 2)
                val y = effectiveHeight - normalizedHeight

                drawRoundRect(
                    color = columnColor.copy(alpha = 0.8f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, normalizedHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }

            // Compute line points (supports 1 or more points)
            val linePoints = labels.mapIndexedNotNull { index, label ->
                val lineValue = lineData.find { it.first == label }?.second?.takeIf { it.isFinite() }
                    ?: return@mapIndexedNotNull null
                val normalizedY = if (hasLineRange) {
                    (lineValue - minLineValue) / lineValueRange
                } else {
                    0.5f
                }
                val x = barSpacing + index * (barWidth + barSpacing * 2) + barWidth / 2
                val y = effectiveHeight - (normalizedY * effectiveHeight * 0.9f * progress) - effectiveHeight * 0.05f
                Offset(x, y)
            }

            if (linePoints.size >= 2) {
                val linePath = Path().apply {
                    linePoints.forEachIndexed { i, pt ->
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // Draw line points even when only 1 point exists
            linePoints.forEach { pt ->
                drawCircle(
                    color = lineColor,
                    radius = 5.dp.toPx(),
                    center = pt,
                )
                drawCircle(
                    color = innerDotColor,
                    radius = 3.dp.toPx(),
                    center = pt,
                )
            }
        }

        // X-axis labels cleanly laid out below the Canvas so large fonts never collide
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun formatCompactValue(value: Float): String = when {
    !value.isFinite() -> "0"
    value >= 1000f -> "${(value / 1000f).roundToInt()}k"
    else -> value.roundToInt().toString()
}

@Composable
private fun ComboEmptyChartState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = stringResource(Res.string.chart_no_data),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
