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
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * PR Celebration Dialog - Shows animated celebration when user achieves a new Personal Record
 *
 * Features:
 * - Confetti explosion animation
 * - Pulsing "NEW PR!" text with workout mode context
 * - Star icons with scale animation
 * - Auto-dismisses after celebration
 *
 * @param show Whether to show the celebration
 * @param exerciseName Name of the exercise for the PR
 * @param weight Weight achieved (formatted string)
 * @param workoutMode Optional workout mode to display (e.g., "Old School", "Echo")
 * @param onDismiss Callback when celebration is complete
 * @param onSoundTrigger Callback to trigger celebration sound
 */
@Composable
fun PRCelebrationDialog(
    show: Boolean,
    exerciseName: String,
    weight: String,
    workoutMode: String? = null,
    phaseLabel: String? = null,
    onDismiss: () -> Unit,
    onSoundTrigger: () -> Unit = {},
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
            dismissOnClickOutside = true,
        ),
    ) {
        PRCelebrationContent(
            exerciseName = exerciseName,
            weight = weight,
            workoutMode = workoutMode,
            phaseLabel = phaseLabel,
        )
    }
}

@Composable
private fun PRCelebrationContent(
    exerciseName: String,
    weight: String,
    workoutMode: String? = null,
    phaseLabel: String? = null,
) {
    // workout-widgets-18: spring-based ping-pong replaces flat infiniteRepeatable tween.
    // Springs can't go inside infiniteRepeatable; state-driven flip via finishedListener achieves
    // the same organic bounce feel with a real spring.
    // reduceMotion: pulseTarget stays false so the channel never animates (scale = 1.0f, settled).
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    var pulseTarget by remember { mutableStateOf(false) }
    val pulseScale by animateFloatAsState(
        targetValue = if (pulseTarget && !reduceMotion) 1.2f else 1.0f,
        animationSpec = ExpressiveMotion.SpringBouncy,
        finishedListener = { _ -> if (!reduceMotion) pulseTarget = !pulseTarget },
        label = "pulse",
    )
    LaunchedEffect(Unit) {
        if (!reduceMotion) pulseTarget = true
    }

    // Format the celebration title with workout mode context
    val celebrationTitle = if (workoutMode != null) {
        "NEW ${workoutMode.uppercase()} PR!"
    } else {
        "NEW PR!"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
            )
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Lottie confetti animation layer (background)
        LottieAnimation(
            animationJson = CelebrationAnimations.confetti,
            size = 300.dp,
            contentDescription = stringResource(Res.string.cd_confetti),
            modifier = Modifier.alpha(0.8f),
        )

        // Content overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Lottie trophy/star animation
            LottieAnimation(
                animationJson = CelebrationAnimations.trophy,
                size = 100.dp,
                contentDescription = stringResource(Res.string.cd_trophy),
            )

            // "NEW [MODE] PR!" text with mode context
            // workout-widgets-15: secondary (EmberYellow gold) instead of tertiary (AshBlue)
            // — gold is the universal trophy colour, and aligns with the charter's fire palette.
            Text(
                celebrationTitle,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.scale(pulseScale),
            )

            // Exercise name
            Text(
                exerciseName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            phaseLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    "$label phase",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Weight achieved
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    weight,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tap to dismiss hint
            Text(
                "Tap to dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.6f),
            )
        }
    }
}
