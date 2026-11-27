package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@Composable
fun ProgressionLineChart(
    data: List<Pair<Long, Float>>, // Timestamp to Weight
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier.height(200.dp),
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val innerDotColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    // Sort data by timestamp and sanitize non-finite weights
    val sortedData = remember(data) {
        data.filter { it.second.isFinite() }.sortedBy { it.first }
    }

    if (sortedData.size < 2) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sortedData.size == 1) {
                    val onlyPoint = sortedData.first()
                    Text(
                        text = formatWeight(onlyPoint.second, weightUnit),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = lineColor,
                    )
                    Text(
                        text = stringResource(
                            Res.string.chart_one_point_hint,
                            KmpUtils.formatTimestamp(onlyPoint.first, "MMM d"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.chart_min_two_sessions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = labelColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val revealProgress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(sortedData, reduceMotion) {
        if (reduceMotion) {
            revealProgress.snapTo(1f)
        } else {
            revealProgress.snapTo(0f)
            revealProgress.animateTo(1f, tween(durationMillis = 800))
        }
    }

    val minWeight = sortedData.minOf { it.second }
    val maxWeight = sortedData.maxOf { it.second }
    val weightRange = (maxWeight - minWeight).coerceAtLeast(1f) // Avoid divide by zero

    // Y-axis padding (10%)
    val yPadding = weightRange * 0.1f
    val yMin = (minWeight - yPadding).coerceAtLeast(0f)
    val yMax = maxWeight + yPadding
    val yRange = (yMax - yMin).let { if (it.isFinite() && it > 0f) it else 1f }

    val maxLabel = formatWeight(maxWeight, weightUnit)
    val minLabel = formatWeight(minWeight, weightUnit)
    val firstDate = KmpUtils.formatTimestamp(sortedData.first().first, "MMM d")
    val lastDate = KmpUtils.formatTimestamp(sortedData.last().first, "MMM d")

    Box(modifier = modifier.padding(8.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val maxLabelLayout = textMeasurer.measure(maxLabel, labelStyle)
            val minLabelLayout = textMeasurer.measure(minLabel, labelStyle)
            val firstDateLayout = textMeasurer.measure(firstDate, labelStyle)
            val lastDateLayout = textMeasurer.measure(lastDate, labelStyle)

            val padPx = 8.dp.toPx()
            val yAxisWidth = maxOf(maxLabelLayout.size.width, minLabelLayout.size.width).toFloat() + padPx
            val xAxisHeight = maxOf(firstDateLayout.size.height, lastDateLayout.size.height).toFloat() + 6.dp.toPx()

            val plotLeft = yAxisWidth
            val plotRight = (width - padPx).coerceAtLeast(plotLeft + 1f)
            val plotTop = padPx
            val plotBottom = (height - xAxisHeight).coerceAtLeast(plotTop + 1f)
            val plotWidth = plotRight - plotLeft
            val plotHeight = plotBottom - plotTop

            // Subtle top & bottom baseline guides
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, plotTop),
                end = Offset(plotRight, plotTop),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            val path = Path()
            val fillPath = Path()
            val xStep = plotWidth / (sortedData.size - 1)
            val progress = revealProgress.value

            val points = sortedData.mapIndexed { index, point ->
                val x = plotLeft + index * xStep
                val normalizedY = ((point.second - yMin) / yRange).coerceIn(0f, 1f)
                val targetY = plotBottom - (normalizedY * plotHeight)
                val animatedY = plotBottom - (plotBottom - targetY) * progress
                Offset(x, animatedY)
            }

            points.forEachIndexed { index, pt ->
                if (index == 0) {
                    path.moveTo(pt.x, pt.y)
                    fillPath.moveTo(pt.x, plotBottom)
                    fillPath.lineTo(pt.x, pt.y)
                } else {
                    path.lineTo(pt.x, pt.y)
                    fillPath.lineTo(pt.x, pt.y)
                }
            }

            // Close fill path
            fillPath.lineTo(points.last().x, plotBottom)
            fillPath.close()

            // Draw fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor, fillColor.copy(alpha = 0f)),
                    startY = plotTop,
                    endY = plotBottom,
                ),
            )

            // Draw line
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )

            // Draw data points
            points.forEach { pt ->
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = pt,
                )
                drawCircle(
                    color = innerDotColor,
                    radius = 2.dp.toPx(),
                    center = pt,
                )
            }

            // Draw Y-axis labels in reserved left gutter with overlap guard
            val maxLabelY = plotTop.coerceIn(0f, (plotBottom - maxLabelLayout.size.height).coerceAtLeast(0f))
            drawText(
                textMeasurer = textMeasurer,
                text = maxLabel,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(0f, maxLabelY),
            )

            val minLabelY = (plotBottom - minLabelLayout.size.height).coerceAtLeast(0f)
            val minGapPx = 4.dp.toPx()
            if (minLabel != maxLabel && minLabelY - (maxLabelY + maxLabelLayout.size.height) >= minGapPx) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = minLabel,
                    style = labelStyle.copy(color = labelColor),
                    topLeft = Offset(0f, minLabelY),
                )
            }

            // Draw X-axis labels (First and Last date) with overlap guard
            val xLabelY = plotBottom + 4.dp.toPx()
            drawText(
                textMeasurer = textMeasurer,
                text = firstDate,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(plotLeft, xLabelY),
            )

            val lastLabelX = (plotRight - lastDateLayout.size.width).coerceAtLeast(plotLeft)
            if (lastDate != firstDate && lastLabelX - (plotLeft + firstDateLayout.size.width) >= minGapPx) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = lastDate,
                    style = labelStyle.copy(color = labelColor),
                    topLeft = Offset(lastLabelX, xLabelY),
                )
            }
        }
    }
}
