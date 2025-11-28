package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Confetti particle for celebration animation
 */
data class ConfettiParticle(
    val startX: Float,
    val startY: Float,
    val color: Color,
    val size: Float,
    val rotationSpeed: Float,
    val velocityX: Float,
    val velocityY: Float
)

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
    onDismiss: () -> Unit
) {
    if (!show) return

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

    // Star rotation
    val starRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ),
        label = "star_rotation"
    )

    // Confetti animation
    val confettiProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confetti"
    )

    // Generate confetti particles
    val confettiParticles = remember {
        List(30) {
            ConfettiParticle(
                startX = Random.nextFloat(),
                startY = 0f,
                color = listOf(
                    Color(0xFFFFD700), // Gold
                    Color(0xFFFFA500), // Orange
                    Color(0xFFFF69B4), // Pink
                    Color(0xFF9333EA), // Purple
                    Color(0xFF3B82F6), // Blue
                    Color(0xFF10B981)  // Green
                ).random(),
                size = Random.nextFloat() * 8f + 4f,
                rotationSpeed = Random.nextFloat() * 10f - 5f,
                velocityX = Random.nextFloat() * 400f - 200f,
                velocityY = Random.nextFloat() * -800f - 400f
            )
        }
    }

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
        // Confetti layer
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            confettiParticles.forEach { particle ->
                val x = size.width * particle.startX + particle.velocityX * confettiProgress
                val y = particle.startY + particle.velocityY * confettiProgress + 0.5f * 980f * confettiProgress * confettiProgress
                val rotation = particle.rotationSpeed * confettiProgress * 360f

                if (y < size.height && x >= 0 && x <= size.width) {
                    // Draw confetti as small rectangles
                    val path = Path().apply {
                        val halfSize = particle.size / 2
                        moveTo(x - halfSize, y - halfSize)
                        lineTo(x + halfSize, y - halfSize)
                        lineTo(x + halfSize, y + halfSize)
                        lineTo(x - halfSize, y + halfSize)
                        close()
                    }

                    // Simple rotation transform
                    val radians = rotation.toDouble() * PI / 180.0
                    val cosR = cos(radians).toFloat()
                    val sinR = sin(radians).toFloat()

                    drawPath(
                        path = path,
                        color = particle.color.copy(alpha = 1f - confettiProgress * 0.5f)
                    )
                }
            }
        }

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Spinning stars
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Personal record achievement",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier
                            .size(32.dp)
                            .scale(pulseScale)
                    )
                }
            }

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
