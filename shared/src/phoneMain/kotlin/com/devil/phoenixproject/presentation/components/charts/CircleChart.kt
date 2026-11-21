package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Material 3 Expressive Circle Chart (Donut Chart)
 * Custom Compose Canvas implementation for beautiful donut/pie visualizations
 * Perfect for muscle group distribution and progress tracking
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuscleGroupCircleChart(
    data: List<Pair<String, Float>>, // Label to value pairs
    modifier: Modifier = Modifier,
) {
    // Data validation - Material 3 Expressive: Handle empty/invalid data gracefully
    val validData = remember(data) { data.filter { it.second > 0f && it.second.isFinite() } }
    if (validData.isEmpty()) {
        EmptyChartState(
            modifier = modifier,
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
            colorScheme.errorContainer,
        )
    }

    // Calculate total and normalized values
    val total = validData.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)
    val normalizedData = remember(validData, total) {
        validData.map { (label, value) -> label to (value / total) }
    }

    // Animation state gated on reduceMotion
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val animationProgress = remember { Animatable(if (reduceMotion) 1f else 0f) }

    LaunchedEffect(normalizedData, reduceMotion) {
        if (reduceMotion) {
            animationProgress.snapTo(1f)
        } else {
            animationProgress.snapTo(0f)
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1500),
            )
        }
    }

    // Use ResponsiveDimensions for consistent tablet-responsive sizing
    val chartSize = ResponsiveDimensions.chartHeight(baseHeight = 220.dp)
    val gapDegrees = if (normalizedData.size > 1) 3f else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                val strokeWidth = 24.dp.toPx()
                val radius = (size.minDimension / 2f - strokeWidth / 2f).coerceAtLeast(1f)
                val center = Offset(size.width / 2f, size.height / 2f)
                val progress = animationProgress.value

                var startAngle = -90f // Start from top

                // Draw all donut segments
                normalizedData.forEachIndexed { index, (_, percentage) ->
                    val fullSweep = percentage * 360f
                    val animatedSweep = fullSweep * progress
                    val sweepAngle = if (gapDegrees > 0f) {
                        (animatedSweep - gapDegrees).coerceAtLeast(0f)
                    } else {
                        animatedSweep
                    }
                    val color = colors[index % colors.size]

                    if (sweepAngle > 0f) {
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - radius,
                                center.y - radius,
                            ),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(
                                width = strokeWidth,
                                cap = if (normalizedData.size == 1) StrokeCap.Round else StrokeCap.Butt,
                            ),
                        )
                    }

                    startAngle += fullSweep
                }
            }
        }

        // Color + label + percentage legend
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            normalizedData.forEachIndexed { index, (label, fraction) ->
                val color = colors[index % colors.size]
                val pct = (fraction * 100f).roundToInt()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Text(
                        text = "$label ($pct%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
private fun EmptyChartState(modifier: Modifier = Modifier) {
    // Use ResponsiveDimensions for consistent tablet-responsive sizing
    val chartSize = ResponsiveDimensions.chartHeight(baseHeight = 280.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = stringResource(Res.string.chart_no_data),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(Res.string.chart_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
