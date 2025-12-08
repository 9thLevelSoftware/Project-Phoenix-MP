package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Movement phase for the cable - determines indicator coloring
 */
enum class MovementPhase {
    CONCENTRIC,  // Pulling/lifting against resistance (positive velocity)
    ECCENTRIC,   // Lowering/resisting (negative velocity)
    STATIC       // Holding position (near-zero velocity)
}

/**
 * Enhanced vertical cable position indicator with pro-level workout visualization.
 *
 * Features:
 * - **ROM Zone**: Highlighted zone showing the target range of motion
 * - **Phase-Reactive Coloring**: Green for concentric (lifting), Orange for eccentric (lowering)
 * - **Ghost Indicators**: Faint marks showing the peak/bottom of the last rep
 * - **Glow Effect**: Radial gradient around the current position indicator
 * - **Smooth Animations**: Fluid position and color transitions
 *
 * @param label Label for the bar (e.g., "L" or "R")
 * @param currentPosition Current cable position in mm (0-1000 range) - Issue #197
 * @param velocity Current velocity (positive = concentric/extending, negative = eccentric/retracting)
 * @param minPosition ROM bottom position (from calibration)
 * @param maxPosition ROM top position (from calibration)
 * @param ghostMin Ghost marker at last rep's minimum extent
 * @param ghostMax Ghost marker at last rep's maximum extent
 * @param isActive Whether the cable is actively being used
 */
@Composable
fun EnhancedCablePositionBar(
    label: String,
    currentPosition: Float,  // Position in mm (Issue #197)
    velocity: Double = 0.0,
    minPosition: Float? = null,  // Position in mm (Float for Nordic parity)
    maxPosition: Float? = null,  // Position in mm (Float for Nordic parity)
    ghostMin: Float? = null,
    ghostMax: Float? = null,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Determine movement phase from velocity
    val phase = remember(velocity) {
        when {
            velocity > 50 -> MovementPhase.CONCENTRIC   // Pulling out (extending cable)
            velocity < -50 -> MovementPhase.ECCENTRIC   // Letting back in (retracting cable)
            else -> MovementPhase.STATIC
        }
    }

    // Phase-reactive colors
    val concentricColor = Color(0xFF00E676)  // Bright green
    val eccentricColor = Color(0xFFFF7043)   // Orange-red
    val staticColor = Color(0xFF90CAF9)      // Light blue
    val inactiveColor = Color(0xFF616161)    // Grey

    // Animate color based on phase
    val activeColor by animateColorAsState(
        targetValue = when {
            !isActive -> inactiveColor
            phase == MovementPhase.CONCENTRIC -> concentricColor
            phase == MovementPhase.ECCENTRIC -> eccentricColor
            else -> staticColor
        },
        animationSpec = tween(durationMillis = 150),
        label = "Phase Color"
    )

    // Smooth position animation (50ms for responsive feel without jitter)
    val maxPos = 1000f
    val animatedPosition by animateFloatAsState(
        targetValue = (currentPosition / maxPos).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 50),
        label = "Position"
    )

    // Calculate normalized positions
    val minProgress = minPosition?.let { (it / maxPos).coerceIn(0f, 1f) }
    val maxProgress = maxPosition?.let { (it / maxPos).coerceIn(0f, 1f) }
    val ghostMinProgress = ghostMin?.let { (it / maxPos).coerceIn(0f, 1f) }
    val ghostMaxProgress = ghostMax?.let { (it / maxPos).coerceIn(0f, 1f) }

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Label at top with phase indicator
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = activeColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Main canvas for the bar
        Canvas(
            modifier = Modifier
                .weight(1f)
                .width(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            val barWidth = size.width
            val barHeight = size.height

            // 1. Draw ROM Zone (target range)
            if (minProgress != null && maxProgress != null && maxProgress > minProgress) {
                drawRomZone(
                    barWidth = barWidth,
                    barHeight = barHeight,
                    minProgress = minProgress,
                    maxProgress = maxProgress,
                    color = activeColor
                )
            }

            // 2. Draw Ghost Indicators (previous rep extent)
            if (ghostMinProgress != null) {
                drawGhostMarker(
                    barWidth = barWidth,
                    barHeight = barHeight,
                    progress = ghostMinProgress,
                    color = activeColor.copy(alpha = 0.3f)
                )
            }
            if (ghostMaxProgress != null) {
                drawGhostMarker(
                    barWidth = barWidth,
                    barHeight = barHeight,
                    progress = ghostMaxProgress,
                    color = activeColor.copy(alpha = 0.3f)
                )
            }

            // 3. Draw ROM boundary markers
            if (minProgress != null && maxProgress != null) {
                // Min marker (bottom of ROM)
                drawRomMarker(
                    barWidth = barWidth,
                    barHeight = barHeight,
                    progress = minProgress,
                    color = activeColor.copy(alpha = 0.7f)
                )
                // Max marker (top of ROM)
                drawRomMarker(
                    barWidth = barWidth,
                    barHeight = barHeight,
                    progress = maxProgress,
                    color = activeColor.copy(alpha = 0.7f)
                )
            }

            // 4. Draw glow effect around current position
            val indicatorY = barHeight * (1f - animatedPosition)
            drawGlowEffect(
                centerX = barWidth / 2,
                centerY = indicatorY,
                radius = barWidth * 1.2f,
                color = activeColor
            )

            // 5. Draw current position indicator (pill shape)
            drawPositionIndicator(
                barWidth = barWidth,
                barHeight = barHeight,
                position = animatedPosition,
                color = activeColor
            )
        }

        // Position percentage at bottom
        Text(
            text = "${(animatedPosition * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = activeColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Draw the ROM (Range of Motion) zone - a highlighted area showing target range
 */
private fun DrawScope.drawRomZone(
    barWidth: Float,
    barHeight: Float,
    minProgress: Float,
    maxProgress: Float,
    color: Color
) {
    val topY = barHeight * (1f - maxProgress)
    val bottomY = barHeight * (1f - minProgress)
    val zoneHeight = bottomY - topY

    drawRoundRect(
        color = color.copy(alpha = 0.15f),
        topLeft = Offset(0f, topY),
        size = Size(barWidth, zoneHeight),
        cornerRadius = CornerRadius(8f, 8f)
    )
}

/**
 * Draw a ghost marker - faint indicator showing previous rep extent
 */
private fun DrawScope.drawGhostMarker(
    barWidth: Float,
    barHeight: Float,
    progress: Float,
    color: Color
) {
    val y = barHeight * (1f - progress)
    val markerWidth = barWidth * 0.6f
    val startX = (barWidth - markerWidth) / 2

    // Dashed line effect using multiple small rectangles
    val dashWidth = 4f
    val gapWidth = 4f
    var currentX = startX

    while (currentX < startX + markerWidth) {
        drawRect(
            color = color,
            topLeft = Offset(currentX, y - 1f),
            size = Size(dashWidth.coerceAtMost(startX + markerWidth - currentX), 2f)
        )
        currentX += dashWidth + gapWidth
    }
}

/**
 * Draw ROM boundary marker - solid line at min/max positions
 */
private fun DrawScope.drawRomMarker(
    barWidth: Float,
    barHeight: Float,
    progress: Float,
    color: Color
) {
    val y = barHeight * (1f - progress)

    drawRoundRect(
        color = color,
        topLeft = Offset(2f, y - 1.5f),
        size = Size(barWidth - 4f, 3f),
        cornerRadius = CornerRadius(1.5f, 1.5f)
    )
}

/**
 * Draw glow effect around the position indicator
 */
private fun DrawScope.drawGlowEffect(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.4f),
                color.copy(alpha = 0.1f),
                Color.Transparent
            ),
            center = Offset(centerX, centerY),
            radius = radius
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )
}

/**
 * Draw the main position indicator - a pill/capsule shape
 */
private fun DrawScope.drawPositionIndicator(
    barWidth: Float,
    barHeight: Float,
    position: Float,
    color: Color
) {
    val indicatorHeight = 20f
    val indicatorWidth = barWidth - 8f
    val y = barHeight * (1f - position) - (indicatorHeight / 2)
    val x = 4f

    // White inner fill
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(x, y),
        size = Size(indicatorWidth, indicatorHeight),
        cornerRadius = CornerRadius(indicatorHeight / 2, indicatorHeight / 2)
    )

    // Colored border
    drawRoundRect(
        color = color,
        topLeft = Offset(x + 2f, y + 2f),
        size = Size(indicatorWidth - 4f, indicatorHeight - 4f),
        cornerRadius = CornerRadius((indicatorHeight - 4f) / 2, (indicatorHeight - 4f) / 2)
    )
}
