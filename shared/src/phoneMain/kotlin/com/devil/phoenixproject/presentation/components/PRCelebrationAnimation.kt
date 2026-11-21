package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * PR Celebration Dialog - Shows animated celebration when user achieves a new Personal Record
 *
 * Features:
 * - Confetti explosion animation (gated on reduced-motion accessibility setting)
 * - Pulsing "NEW PR!" text with workout mode context
 * - Star icons with scale animation
 * - Auto-dismisses after celebration unless accessibility / reduced-motion requires tap dismissal
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

    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val accessibilityManager = LocalAccessibilityManager.current

    // Trigger sound when dialog is shown
    LaunchedEffect(show) {
        onSoundTrigger()
    }

    // Auto-dismiss after 3 seconds only when reduced-motion and accessibility timeouts allow it.
    LaunchedEffect(show, reduceMotion) {
        if (!reduceMotion) {
            val recommendedTimeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 3000L,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: 3000L
            if (recommendedTimeout <= 3000L) {
                kotlinx.coroutines.delay(recommendedTimeout)
                onDismiss()
            }
        }
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
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PRCelebrationContent(
    exerciseName: String,
    weight: String,
    workoutMode: String? = null,
    phaseLabel: String? = null,
    onDismiss: () -> Unit,
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
    LaunchedEffect(reduceMotion) {
        pulseTarget = !reduceMotion
    }

    // Format the celebration title with workout mode context
    val celebrationTitle = if (workoutMode != null) {
        "NEW ${workoutMode.uppercase()} PR!"
    } else {
        "NEW PR!"
    }

    val dismissLabel = stringResource(Res.string.action_dismiss)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
            )
            .clickable(
                role = Role.Button,
                onClickLabel = dismissLabel,
                onClick = onDismiss,
            )
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Lottie confetti animation layer (background) - gated on reduceMotion
        if (!reduceMotion) {
            LottieAnimation(
                animationJson = CelebrationAnimations.confetti,
                size = 300.dp,
                contentDescription = stringResource(Res.string.cd_confetti),
                modifier = Modifier.alpha(0.8f),
            )
        }

        // Content overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Trophy celebration icon/animation
            if (reduceMotion) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = stringResource(Res.string.cd_trophy),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(80.dp),
                )
            } else {
                LottieAnimation(
                    animationJson = CelebrationAnimations.trophy,
                    size = 100.dp,
                    contentDescription = stringResource(Res.string.cd_trophy),
                )
            }

            // "NEW [MODE] PR!" text with mode context
            // workout-widgets-15: secondary (EmberYellow gold) instead of tertiary (AshBlue)
            // — gold is the universal trophy colour, and aligns with the charter's fire palette.
            Text(
                text = celebrationTitle,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.scale(pulseScale),
            )

            // Exercise name
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            phaseLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = "$label phase",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Weight achieved
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = weight,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tap to dismiss hint
            Text(
                text = "Tap to dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.75f),
            )
        }
    }
}
