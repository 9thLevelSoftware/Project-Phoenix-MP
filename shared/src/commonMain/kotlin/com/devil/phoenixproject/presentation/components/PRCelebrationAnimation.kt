package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * PR Celebration Dialog - Shows animated celebration when user achieves a new Personal Record
 *
 * Features:
 * - Confetti explosion animation
 * - Pulsing "NEW PR!" text
 * - Star icons with scale animation
 * - Auto-dismisses after celebration
 *
 * @param show Whether to show the celebration
 * @param exerciseName Name of the exercise for the PR
 * @param weight Weight achieved (formatted string)
 * @param onDismiss Callback when celebration is complete
 */
@Composable
fun PRCelebrationDialog(
    show: Boolean,
    exerciseName: String,
    weight: String,
    onDismiss: () -> Unit,
    onSoundTrigger: () -> Unit = {}
) {
    if (!show) return

    // Trigger sound when dialog is shown
    LaunchedEffect(show) {
        onSoundTrigger()
    }

    // Auto-dismiss after 3 seconds
    LaunchedEffect(show) {
        kotlinx.coroutines.delay(3000)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        PRCelebrationContent(
            exerciseName = exerciseName,
            weight = weight
        )
    }
}

@Composable
private fun PRCelebrationContent(
    exerciseName: String,
    weight: String
) {
    // Animation states
    val infiniteTransition = rememberInfiniteTransition(label = "celebration")

    // Pulsing scale for "NEW PR!" text
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large
            )
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        // Lottie confetti animation layer (background)
        LottieAnimation(
            animationJson = CelebrationAnimations.confetti,
            size = 300.dp,
            contentDescription = "Celebration confetti",
            modifier = Modifier.alpha(0.8f)
        )

        // Content overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Lottie trophy/star animation
            LottieAnimation(
                animationJson = CelebrationAnimations.trophy,
                size = 100.dp,
                contentDescription = "Trophy celebration"
            )

            // "NEW PR!" text
            Text(
                "NEW PR!",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.scale(pulseScale)
            )

            // Exercise name
            Text(
                exerciseName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Weight achieved
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    weight,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tap to dismiss hint
            Text(
                "Tap to dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.6f)
            )
        }
    }
}
