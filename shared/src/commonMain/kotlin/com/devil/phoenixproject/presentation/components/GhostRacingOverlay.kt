package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.GhostRepComparison
import com.devil.phoenixproject.domain.model.GhostVerdict
import com.devil.phoenixproject.ui.theme.AccessibilityTheme

/**
 * Ghost Racing Overlay -- dual vertical progress bars comparing current vs ghost velocities.
 *
 * Positioned in the WorkoutHud overlay zone during active sets when a ghost session is loaded.
 * Shows real-time velocity comparison with a verdict badge after each rep.
 *
 * @param currentRepVelocity Current rep MCV in mm/s (0 if no rep yet)
 * @param ghostRepVelocity Ghost rep MCV for matching rep index in mm/s
 * @param maxVelocity Max velocity for progress bar scaling (used to normalize 0..1)
 * @param verdict Latest rep comparison verdict (null before first rep completes)
 * @param modifier Modifier for positioning within the overlay zone
 */
@Composable
fun GhostRacingOverlay(
    currentRepVelocity: Float,
    ghostRepVelocity: Float,
    maxVelocity: Float,
    verdict: GhostRepComparison?,
    modifier: Modifier = Modifier
) {
    // Pre-compute AccessibilityTheme colors before any draw blocks (Phase 17 mandate)
    val successColor = AccessibilityTheme.colors.success
    val errorColor = AccessibilityTheme.colors.error
    val warningColor = AccessibilityTheme.colors.warning
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Dual progress bars side by side
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            VerticalProgressBar(
                progress = if (maxVelocity > 0f) (currentRepVelocity / maxVelocity).coerceIn(0f, 1f) else 0f,
                color = primaryColor,
                label = "YOU",
                backgroundColor = surfaceVariantColor,
                labelColor = onSurfaceVariantColor
            )
            VerticalProgressBar(
                progress = if (maxVelocity > 0f) (ghostRepVelocity / maxVelocity).coerceIn(0f, 1f) else 0f,
                color = onSurfaceVariantColor.copy(alpha = 0.5f),
                label = "BEST",
                backgroundColor = surfaceVariantColor,
                labelColor = onSurfaceVariantColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Verdict badge
        if (verdict != null) {
            val (verdictText, verdictColor) = when (verdict.verdict) {
                GhostVerdict.AHEAD -> "AHEAD" to successColor
                GhostVerdict.BEHIND -> "BEHIND" to errorColor
                GhostVerdict.TIED -> "TIED" to warningColor
                GhostVerdict.BEYOND -> "NEW BEST" to successColor
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(verdictColor.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = verdictText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = verdictColor
                )
            }
        } else {
            // No rep completed yet -- muted placeholder
            Text(
                text = "VS GHOST",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariantColor.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Single thin vertical progress bar with bottom-up fill and top label.
 *
 * @param progress Fill fraction in 0..1 range
 * @param color Fill color for the active portion
 * @param label Text label above the bar (e.g., "YOU", "BEST")
 * @param backgroundColor Background color for the empty portion
 * @param labelColor Color for the label text
 */
@Composable
private fun VerticalProgressBar(
    progress: Float,
    color: Color,
    label: String,
    backgroundColor: Color,
    labelColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor.copy(alpha = 0.3f))
        ) {
            // Bottom-up fill: align to bottom, fill height proportional to progress
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight(progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}
