package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.ContentAwareFit
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.scaledForWeightValue
import kotlin.math.min

/**
 * Circular Force Gauge
 * Visualizes current force load and concentric/eccentric phase.
 *
 * @param currentForce The current force value (e.g., in kg or lbs).
 * @param maxForce The maximum expected force for the scale (default: 100f).
 * @param velocity The current velocity to determine phase (positive = concentric, negative = eccentric).
 * @param label Main text to display in center (e.g. "25 kg").
 * @param subLabel Secondary text (e.g. "LOAD").
 */
@Composable
fun CircularForceGauge(
    currentForce: Float,
    maxForce: Float = 100f,
    velocity: Double,
    label: String,
    subLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    // Determine phase and color
    // Velocity > 0.1 => Concentric (Lifting) -> Primary/Green
    // Velocity < -0.1 => Eccentric (Lowering) -> Secondary/Purple
    // Else => Static/Holding -> SurfaceVariant
    val isConcentric = velocity > 0.05
    val isEccentric = velocity < -0.05

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val phaseColor = when {
        isConcentric -> MaterialTheme.colorScheme.primary
        isEccentric -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    // Animate the progress
    val progress = (currentForce / maxForce).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 100),
    )

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.aspectRatio(1f),
    ) {
        val diameterDp = min(maxWidth.value, maxHeight.value)
        // Center text sits inside the inner ring chord so multi-digit lbs values stay clear of the arc
        val innerAvailableDp = ((diameterDp - 32f) * 0.68f).coerceAtLeast(48f)
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val weightTextScale = LocalWeightTextScale.current

        val baseValueStyle = MaterialTheme.typography.displayMedium
            .scaledForWeightValue(weightTextScale)
            .copy(fontWeight = FontWeight.Bold)
        val valueFitScale = ContentAwareFit.singleLineFitFontScale(
            text = label,
            style = baseValueStyle,
            availableDp = innerAvailableDp,
            measurer = textMeasurer,
            density = density,
        )
        val valueStyle = if (valueFitScale < 1f) {
            baseValueStyle.copy(fontSize = baseValueStyle.fontSize * valueFitScale)
        } else {
            baseValueStyle
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val strokeWidth = size.width * 0.08f
            val diameter = min(size.width, size.height) - strokeWidth

            // Background Track
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Active Arc
            drawArc(
                color = phaseColor,
                startAngle = 135f,
                sweepAngle = 270f * animatedProgress,
                useCenter = false,
                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        // Center Text
        Column(
            modifier = Modifier.widthIn(max = innerAvailableDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (subLabel != null) {
                val baseSubStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                val subFitScale = ContentAwareFit.singleLineFitFontScale(
                    text = subLabel,
                    style = baseSubStyle,
                    availableDp = innerAvailableDp,
                    measurer = textMeasurer,
                    density = density,
                )
                val subStyle = if (subFitScale < 1f) {
                    baseSubStyle.copy(fontSize = baseSubStyle.fontSize * subFitScale)
                } else {
                    baseSubStyle
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subLabel,
                    style = subStyle,
                    color = phaseColor, // Color the label with phase color
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
