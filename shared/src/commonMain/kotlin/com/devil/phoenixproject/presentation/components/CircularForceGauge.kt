package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
    // Determine phase and color
    // Velocity > 0.1 => Concentric (Lifting) -> Primary/Green
    // Velocity < -0.1 => Eccentric (Lowering) -> Secondary/Purple
    // Else => Static/Holding -> SurfaceVariant
    val isConcentric = velocity > 0.05
    val isEccentric = velocity < -0.05
    
    val phaseColor = when {
        isConcentric -> MaterialTheme.colorScheme.primary
        isEccentric -> MaterialTheme.colorScheme.tertiary // Commonly used for Eccentric in Vitruvian context
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    // Animate the progress
    val progress = (currentForce / maxForce).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100)
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.aspectRatio(1f)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val strokeWidth = size.width * 0.08f
            val diameter = min(size.width, size.height) - strokeWidth
            val radius = diameter / 2f
            
            // Background Track
            drawArc(
                color = Color.Gray.copy(alpha = 0.2f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Arc
            drawArc(
                color = phaseColor,
                startAngle = 135f,
                sweepAngle = 270f * animatedProgress,
                useCenter = false,
                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Center Text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = phaseColor, // Color the label with phase color
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
