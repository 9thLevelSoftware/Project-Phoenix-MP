package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Material 3 Expressive Radar/Spider Chart
 * Visualizes muscle group balance and distribution
 * Shows relative strength/volume across different muscle groups
 *
 * Uses Compose Text with dynamically measured bounds so labels never clip.
 */
@Composable
fun RadarChart(
    data: List<Pair<String, Float>>, // Label to normalized value (0.0 to 1.0)
    modifier: Modifier = Modifier,
    maxValue: Float = 1f,
    showLabels: Boolean = true,
) {
    // Data validation
    if (data.isEmpty() || maxValue <= 0f) {
        RadarEmptyChartState(
            message = stringResource(Res.string.chart_no_data),
            modifier = modifier,
        )
        return
    }

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val revealProgress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(data, reduceMotion) {
        if (reduceMotion) {
            revealProgress.snapTo(1f)
        } else {
            revealProgress.snapTo(0f)
            revealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1500),
            )
        }
    }

    val animatedData = data.map { (label, value) ->
        label to (value.coerceIn(0f, maxValue) * revealProgress.value)
    }

    val colorScheme = MaterialTheme.colorScheme
    val outlineColor = colorScheme.outline
    val primaryColor = colorScheme.primary
    val primaryContainerColor = colorScheme.primaryContainer
    val labelColor = colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val numPoints = animatedData.size
    val angleStep = (2 * PI) / numPoints
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 300.dp)

    val labelMeasurements = remember(data, labelStyle, showLabels) {
        if (showLabels) {
            data.map { (label, _) -> textMeasurer.measure(label, labelStyle) }
        } else {
            emptyList()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .padding(8.dp),
    ) {
        val boxWidth = with(density) { maxWidth.toPx() }
        val boxHeight = with(density) { maxHeight.toPx() }
        val centerX = boxWidth / 2f
        val centerY = boxHeight / 2f
        val labelGapPx = with(density) { 8.dp.toPx() }
        val maxLabelWidthPx = if (showLabels) {
            (labelMeasurements.maxOfOrNull { it.size.width.toFloat() } ?: 0f) + labelGapPx + with(density) { 4.dp.toPx() }
        } else {
            with(density) { 8.dp.toPx() }
        }
        val maxLabelHeightPx = if (showLabels) {
            (labelMeasurements.maxOfOrNull { it.size.height.toFloat() } ?: 0f) + labelGapPx + with(density) { 4.dp.toPx() }
        } else {
            with(density) { 8.dp.toPx() }
        }
        val radius = minOf(
            (boxWidth / 2f - maxLabelWidthPx),
            (boxHeight / 2f - maxLabelHeightPx),
        ).coerceAtLeast(with(density) { 24.dp.toPx() })
        val labelRadius = radius + labelGapPx

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw grid circles
            for (i in 1..5) {
                val gridRadius = radius * (i / 5f)
                drawCircle(
                    color = outlineColor.copy(alpha = 0.2f),
                    radius = gridRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.dp.toPx()),
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
                    strokeWidth = 1.dp.toPx(),
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
                style = Fill,
            )

            // Draw outline
            drawPath(
                path = dataPath,
                color = primaryColor,
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
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
                    center = Offset(x, y),
                )
                drawCircle(
                    color = primaryContainerColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }

        // Draw dynamically measured labels positioned around the radar without clipping
        if (showLabels) {
            animatedData.forEachIndexed { index, (label, _) ->
                val measured = labelMeasurements.getOrNull(index)
                val labelW = measured?.size?.width?.toFloat() ?: 0f
                val labelH = measured?.size?.height?.toFloat() ?: 0f
                val angle = index * angleStep - PI / 2
                val cosA = cos(angle)
                val sinA = sin(angle)
                val labelX = centerX + labelRadius * cosA.toFloat()
                val labelY = centerY + labelRadius * sinA.toFloat()

                val textAlign = when {
                    cosA > 0.3 -> TextAlign.Start
                    cosA < -0.3 -> TextAlign.End
                    else -> TextAlign.Center
                }

                val rawX = when {
                    cosA > 0.3 -> labelX
                    cosA < -0.3 -> labelX - labelW
                    else -> labelX - labelW / 2f
                }
                val rawY = when {
                    sinA > 0.3 -> labelY
                    sinA < -0.3 -> labelY - labelH
                    else -> labelY - labelH / 2f
                }
                val maxOffsetX = (boxWidth - labelW).coerceAtLeast(0f)
                val maxOffsetY = (boxHeight - labelH).coerceAtLeast(0f)

                Text(
                    text = label,
                    style = labelStyle,
                    color = labelColor,
                    textAlign = textAlign,
                    maxLines = 2,
                    modifier = Modifier.offset {
                        IntOffset(
                            x = rawX.coerceIn(0f, maxOffsetX).roundToInt(),
                            y = rawY.coerceIn(0f, maxOffsetY).roundToInt(),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RadarEmptyChartState(message: String, modifier: Modifier = Modifier) {
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 320.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
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
