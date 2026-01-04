package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass

/**
 * Pop-over overlay that appears when auto-stop is active (handles are down).
 * Used in Just Lift and AMRAP modes to show countdown before workout stops.
 *
 * This overlay floats on top of the workout screen and only appears when relevant.
 */
@Composable
fun AutoStopOverlay(
    autoStopState: AutoStopUiState,
    isJustLift: Boolean,
    modifier: Modifier = Modifier
) {
    // Only show when auto-stop is active
    AnimatedVisibility(
        visible = autoStopState.isActive,
        enter = fadeIn(animationSpec = tween(200)) +
                scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)) +
               scaleOut(targetScale = 0.8f, animationSpec = tween(150)),
        modifier = modifier
    ) {
        // Responsive dialog width based on screen size
        val windowSizeClass = LocalWindowSizeClass.current
        val dialogMaxWidth = when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 400.dp
            WindowWidthSizeClass.Medium -> 340.dp
            WindowWidthSizeClass.Compact -> 280.dp
        }

        // Pulsing animation for urgency
        val infiniteTransition = rememberInfiniteTransition(label = "autostop-pulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        Card(
            modifier = Modifier
                .scale(pulse)
                .widthIn(max = dialogMaxWidth),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Icon with animation
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = "Hands off handles",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                // Title
                Text(
                    text = "AUTO-STOP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    letterSpacing = 1.5.sp
                )

                // Countdown number - large and prominent
                Text(
                    text = "${autoStopState.secondsRemaining}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error
                )

                // Progress bar
                LinearProgressIndicator(
                    progress = { autoStopState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                )

                // Instruction text
                Text(
                    text = if (isJustLift) {
                        "Pick up handles to continue"
                    } else {
                        "Lift to continue set"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Compact inline auto-stop indicator for use within cards/dashboards.
 * Shows a subtle warning bar that doesn't obscure the main content.
 */
@Composable
fun AutoStopIndicatorBar(
    autoStopState: AutoStopUiState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = autoStopState.isActive,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.PanTool,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stopping in ${autoStopState.secondsRemaining}s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    LinearProgressIndicator(
                        progress = { autoStopState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

/**
 * Auto-start overlay for Just Lift mode - appears when user picks up handles
 * to indicate workout will start automatically.
 */
@Composable
fun AutoStartOverlay(
    isActive: Boolean,
    secondsRemaining: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(animationSpec = tween(200)) +
                scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)) +
               scaleOut(targetScale = 0.8f, animationSpec = tween(150)),
        modifier = modifier
    ) {
        // Responsive dialog width based on screen size
        val windowSizeClass = LocalWindowSizeClass.current
        val dialogMaxWidth = when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 400.dp
            WindowWidthSizeClass.Medium -> 340.dp
            WindowWidthSizeClass.Compact -> 280.dp
        }

        Card(
            modifier = Modifier.widthIn(max = dialogMaxWidth),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Starting workout",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "AUTO-START",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    letterSpacing = 1.5.sp
                )

                Text(
                    text = "$secondsRemaining",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Workout starting...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
