package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.ui.theme.DataColors
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Material 3 Expressive Workout Metrics Detail Chart
 * Pure Canvas implementation for Kotlin Multiplatform compatibility.
 * Visualizes time-series workout data: Load A & B, Position A & B
 */
@Composable
fun WorkoutMetricsDetailChart(
    metrics: List<WorkoutMetric>,
    modifier: Modifier = Modifier,
    showLoad: Boolean = true,
    showPosition: Boolean = true,
    showPower: Boolean = true,
    animationDuration: Int = 1500,
) {
    // Data validation
    if (metrics.isEmpty()) {
        MetricsEmptyChartState(
            message = stringResource(Res.string.chart_no_metrics),
            modifier = modifier,
        )
        return
    }

    var showLoadA by remember { mutableStateOf(showLoad) }
    var showLoadB by remember { mutableStateOf(showLoad) }
    var showPosA by remember { mutableStateOf(showPosition) }
    var showPosB by remember { mutableStateOf(showPosition) }

    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(metrics) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = animationDuration,
                easing = EaseInOutCubic,
            ),
        )
    }

    // Calculate ranges
    val loadValues = metrics.flatMap { listOf(it.loadA, it.loadB) }
    val positionValues = metrics.flatMap { listOf(it.positionA, it.positionB) }
    val maxLoad = loadValues.maxOrNull() ?: 1f
    val maxPosition = positionValues.maxOrNull() ?: 1f

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val density = LocalDensity.current

    // Colors for each metric
    val loadAColor = DataColors.LoadA
    val loadBColor = DataColors.LoadB
    val posAColor = DataColors.PositionA
    val posBColor = DataColors.PositionB

    Column(modifier = modifier.padding(16.dp)) {
        // Legend / Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showLoadA,
                onClick = { showLoadA = !showLoadA },
                label = { Text(stringResource(Res.string.chart_load_a)) },
                leadingIcon = {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = loadAColor)
                    }
                },
            )
            FilterChip(
                selected = showLoadB,
                onClick = { showLoadB = !showLoadB },
                label = { Text(stringResource(Res.string.chart_load_b)) },
                leadingIcon = {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = loadBColor)
                    }
                },
            )
            FilterChip(
                selected = showPosA,
                onClick = { showPosA = !showPosA },
                label = { Text(stringResource(Res.string.chart_pos_a)) },
                leadingIcon = {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = posAColor)
                    }
                },
            )
            FilterChip(
                selected = showPosB,
                onClick = { showPosB = !showPosB },
                label = { Text(stringResource(Res.string.chart_pos_b)) },
                leadingIcon = {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = posBColor)
                    }
                },
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            val chartWidth = with(density) { maxWidth.toPx() }
            val chartHeight = with(density) { maxHeight.toPx() }
            val paddingLeft = 40.dp
            val paddingBottom = 20.dp
            val paddingLeftPx = with(density) { paddingLeft.toPx() }
            val paddingBottomPx = with(density) { paddingBottom.toPx() }

            val effectiveWidth = chartWidth - paddingLeftPx
            val effectiveHeight = chartHeight - paddingBottomPx

            Canvas(modifier = Modifier.fillMaxSize()) {
                val progress = animationProgress.value

                // Draw horizontal grid
                for (i in 0..4) {
                    val y = effectiveHeight * (1 - i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(paddingLeftPx, y),
                        end = Offset(chartWidth, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // Helper function to draw metric line
                fun drawMetricLine(values: List<Float>, maxValue: Float, color: Color, show: Boolean) {
                    if (!show || values.isEmpty() || maxValue <= 0) return

                    val path = Path()
                    val step = effectiveWidth / (values.size - 1).coerceAtLeast(1)

                    values.forEachIndexed { index, value ->
                        val x = paddingLeftPx + step * index
                        val normalizedY = (value / maxValue).coerceIn(0f, 1f)
                        val y = effectiveHeight - (normalizedY * effectiveHeight * progress)

                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                // Draw each metric line
                drawMetricLine(
                    values = metrics.map { it.loadA },
                    maxValue = maxLoad,
                    color = loadAColor,
                    show = showLoadA,
                )
                drawMetricLine(
                    values = metrics.map { it.loadB },
                    maxValue = maxLoad,
                    color = loadBColor,
                    show = showLoadB,
                )
                drawMetricLine(
                    values = metrics.map { it.positionA },
                    maxValue = maxPosition,
                    color = posAColor,
                    show = showPosA,
                )
                drawMetricLine(
                    values = metrics.map { it.positionB },
                    maxValue = maxPosition,
                    color = posBColor,
                    show = showPosB,
                )
            }

            // Y-axis labels
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(paddingLeft - 4.dp)
                    .height(with(density) { effectiveHeight.toDp() }),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Show load scale if load is visible
                if (showLoadA || showLoadB) {
                    Text(
                        text = "${maxLoad.toInt()}kg",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Time indicator
            Text(
                text = "Time →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp),
            )
        }

        // Summary stats
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (showLoadA || showLoadB) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Max Load",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${maxLoad.toInt()} kg",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Samples",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${metrics.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            if (showPosA || showPosB) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Max ROM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${maxPosition.toInt()} mm",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsEmptyChartState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ShowChart,
                contentDescription = stringResource(Res.string.chart_no_metrics),
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
