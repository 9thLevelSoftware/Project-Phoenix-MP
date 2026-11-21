package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Material 3 Expressive Gauge Chart
 * Custom gauge chart for goal progress visualization
 * Shows progress from 0% to 100% with Material 3 colors
 *
 * Uses Compose Text stacked in a Column so font scaling never overlaps the arc.
 */
@Composable
fun GaugeChart(
    currentValue: Float,
    targetValue: Float,
    modifier: Modifier = Modifier,
    label: String = "Progress",
    showPercentage: Boolean = true,
) {
    // Data validation
    if (targetValue <= 0f) {
        EmptyGaugeState(
            message = stringResource(Res.string.chart_invalid_target),
            modifier = modifier,
        )
        return
    }

    val progress = (currentValue / targetValue).coerceIn(0f, 1f)
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val animatedProgressState = remember { Animatable(if (reduceMotion) progress else 0f) }
    LaunchedEffect(progress, reduceMotion) {
        if (reduceMotion) {
            animatedProgressState.snapTo(progress)
        } else {
            animatedProgressState.animateTo(
                targetValue = progress,
                animationSpec = tween(durationMillis = 1500),
            )
        }
    }
    val animatedProgress = animatedProgressState.value

    val percentage = (animatedProgress * 100).toInt()
    // Heat-ramp semantics: low progress = error (red), mid = warning (amber), high = success/primary
    val accessColors = AccessibilityTheme.colors
    val gaugeColor = when {
        animatedProgress >= 0.8f -> accessColors.success
        animatedProgress >= 0.5f -> accessColors.warning
        else -> accessColors.error
    }

    // Contrasting track color against both surface and surfaceContainerHighest cards
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 160.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 24.dp.toPx()
                val maxRx = (size.width - strokeWidth) / 2f
                val maxRy = size.height - strokeWidth
                val radius = minOf(maxRx, maxRy).coerceAtLeast(1f)
                val centerX = size.width / 2f
                val centerY = (size.height + radius) / 2f

                // Background arc
                drawArc(
                    color = trackColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                    ),
                )

                // Progress arc
                if (animatedProgress > 0f) {
                    drawArc(
                        color = gaugeColor,
                        startAngle = 180f,
                        sweepAngle = 180f * animatedProgress,
                        useCenter = false,
                        topLeft = Offset(centerX - radius, centerY - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                        ),
                    )

                    // Gradient highlight overlay
                    drawArc(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                gaugeColor.copy(alpha = 0.8f),
                                gaugeColor.copy(alpha = 0.4f),
                            ),
                            start = Offset(centerX - radius, centerY),
                            end = Offset(centerX + radius, centerY),
                        ),
                        startAngle = 180f,
                        sweepAngle = 180f * animatedProgress,
                        useCenter = false,
                        topLeft = Offset(centerX - radius, centerY - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(
                            width = 8.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = if (showPercentage) "$percentage%" else "${currentValue.toInt()}/${targetValue.toInt()}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyGaugeState(message: String, modifier: Modifier = Modifier) {
    val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = stringResource(Res.string.chart_invalid_gauge),
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
}
