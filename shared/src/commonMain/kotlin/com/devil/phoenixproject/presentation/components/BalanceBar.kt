package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.AccessibilityTheme

/**
 * Horizontal balance bar visualizing left/right (A/B cable) force asymmetry.
 *
 * Displays a horizontal bar with a center line representing 50/50 balance.
 * An indicator extends from center toward the dominant side, proportional to asymmetry.
 * Color-coded by severity:
 * - Green: <10% asymmetry (acceptable)
 * - Yellow/Amber: 10-15% asymmetry (caution)
 * - Red: >15% asymmetry (concerning)
 *
 * When [showAlert] is true (3+ consecutive high-asymmetry reps), the border
 * pulses red to draw attention without being overly distracting.
 *
 * @param asymmetryPercent Imbalance percentage (0-100)
 * @param dominantSide "A" (left), "B" (right), or "BALANCED"
 * @param showAlert True when consecutive high-asymmetry threshold exceeded
 * @param modifier Modifier for layout customization
 */
@Composable
fun BalanceBar(
    asymmetryPercent: Float,
    dominantSide: String,
    showAlert: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Severity color from AccessibilityTheme (ASYM-04)
    val colors = AccessibilityTheme.colors
    val severityColor = when {
        asymmetryPercent < 10f -> colors.asymmetryGood
        asymmetryPercent < 15f -> colors.asymmetryCaution
        else -> colors.asymmetryBad
    }

    // Alert border color from theme (resolve in @Composable scope for Canvas use)
    val alertBorderColor = colors.asymmetryBad

    // Alert animation (ASYM-05): pulsing border when showAlert is true
    // Always create the transition to satisfy Compose's call-site stability requirement.
    // When showAlert is false the animated value is simply unused.
    val alertTransition = rememberInfiniteTransition(label = "AsymmetryAlert")
    val alertAlpha by alertTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlertPulse"
    )

    val borderModifier = if (showAlert) {
        Modifier.border(
            width = 2.dp,
            color = alertBorderColor.copy(alpha = alertAlpha),
            shape = RoundedCornerShape(8.dp)
        )
    } else {
        Modifier
    }

    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // Row layout: bar + percentage beside it (WCAG: numeric value beside bar, not inside)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .then(borderModifier)
        ) {
            // Canvas for the bar drawing
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val barWidth = size.width
                val barHeight = size.height
                val centerX = barWidth / 2f

                // 1. Background rounded rectangle
                drawRoundRect(
                    color = backgroundColor,
                    topLeft = Offset.Zero,
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )

                // 2. Center tick mark (50/50 balance point)
                drawLine(
                    color = Color.White.copy(alpha = 0.5f),
                    start = Offset(centerX, 2f),
                    end = Offset(centerX, barHeight - 2f),
                    strokeWidth = 1.5f
                )

                // 3. Asymmetry indicator from center toward dominant side
                val maxIndicatorWidth = barWidth / 2f - 4f // Max extent from center
                val indicatorWidth = (asymmetryPercent / 100f).coerceIn(0f, 1f) * maxIndicatorWidth
                val indicatorY = 2f
                val indicatorHeight = barHeight - 4f

                if (indicatorWidth > 0.5f) {
                    val indicatorX = when (dominantSide) {
                        "A" -> centerX - indicatorWidth // Extends left (cable A = left)
                        "B" -> centerX                   // Extends right (cable B = right)
                        else -> centerX - indicatorWidth / 2f // Balanced: tiny centered
                    }

                    drawRoundRect(
                        color = severityColor.copy(alpha = 0.8f),
                        topLeft = Offset(indicatorX, indicatorY),
                        size = Size(indicatorWidth, indicatorHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }

            // Side labels "L" and "R" at edges
            Text(
                text = "L",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
            )

            Text(
                text = "R",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )
        }

        // Asymmetry percentage text BESIDE the bar (not inside)
        Text(
            text = "${asymmetryPercent.toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            ),
            color = if (asymmetryPercent < 10f) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            } else {
                severityColor
            },
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp).padding(start = 4.dp)
        )
    }
}
