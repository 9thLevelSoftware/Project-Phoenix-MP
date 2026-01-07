package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.HomeButtonColors
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.random.Random

/**
 * Icon animation types for AnimatedActionButton
 */
enum class IconAnimation {
    NONE,
    PULSE,      // Scale pulse (for Play icon)
    ROTATE,     // Continuous rotation (for Loop icon)
    TILT,       // Oscillating tilt (for Dumbbell icon)
    FIRE        // Flickering flame effect
}

/**
 * Data class for a single flame particle
 */
private data class FlameParticle(
    var x: Float,
    var y: Float,
    var radius: Float,
    var speedY: Float,
    var life: Float = 1.0f,
    var driftOffset: Float = Random.nextFloat() * 100f
)

/**
 * Modifier that adds a flame particle effect behind content.
 * Uses frame-synced animation for smooth 60fps performance.
 */
private fun Modifier.onFire(): Modifier = composed {
    val particles = remember { mutableStateListOf<FlameParticle>() }
    var time by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val startTime = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                time = now - startTime

                // Update existing particles
                val iterator = particles.listIterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.y -= p.speedY
                    p.life -= 0.02f
                    p.radius *= 0.97f

                    if (p.life <= 0f || p.radius < 1f) {
                        iterator.remove()
                    }
                }

                // Spawn new particles (2 per frame for dense fire)
                if (particles.size < 80) {
                    repeat(2) {
                        particles.add(
                            FlameParticle(
                                x = -1f,
                                y = -1f,
                                radius = Random.nextFloat() * 8f + 4f,
                                speedY = Random.nextFloat() * 2f + 1f
                            )
                        )
                    }
                }
            }
        }
    }

    drawWithCache {
        onDrawBehind {
            // Dark base
            drawRect(Color(0xFF1A0800))

            particles.forEach { p ->
                // Initialize position if new
                if (p.y < 0) {
                    p.x = Random.nextFloat() * size.width
                    p.y = size.height + Random.nextFloat() * 10f
                }

                // Horizontal sine wave drift
                val drift = sin((time / 800_000_000f) + p.driftOffset) * 4f

                // Color based on life: Yellow → Orange → Red → Fade
                val color = when {
                    p.life > 0.7f -> Color(0xFFFFD700) // Gold/Yellow (hot center)
                    p.life > 0.4f -> Color(0xFFFF6B00) // Orange
                    else -> Color(0xFFFF4500).copy(alpha = (p.life * 2f).coerceIn(0f, 1f)) // Red, fading
                }

                // Draw with additive blending for glow effect
                drawCircle(
                    color = color,
                    radius = p.radius,
                    center = Offset(p.x + drift, p.y),
                    blendMode = BlendMode.Plus
                )

                // Inner brighter core
                if (p.life > 0.5f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (p.life - 0.5f) * 0.6f),
                        radius = p.radius * 0.4f,
                        center = Offset(p.x + drift, p.y),
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }
}

/**
 * Animated FAB with press feedback and idle animations.
 *
 * @param label Button text
 * @param icon Button icon
 * @param onClick Click handler
 * @param isPrimary If true, uses solid Royal Blue. If false, uses solid Muted Purple.
 * @param isFireButton If true, uses fire particle animation (for Just Lift)
 * @param iconAnimation Type of icon animation to apply
 * @param modifier Modifier for the button
 */
@Composable
fun AnimatedActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    isPrimary: Boolean,
    isFireButton: Boolean = false,
    iconAnimation: IconAnimation = IconAnimation.NONE,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Responsive button height based on device size
    val windowSizeClass = LocalWindowSizeClass.current
    val buttonHeight = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 80.dp
        WindowWidthSizeClass.Medium -> 72.dp
        WindowWidthSizeClass.Compact -> 64.dp
    }

    // Press feedback animation
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pressScale"
    )

    // Idle animations
    val infiniteTransition = rememberInfiniteTransition(label = "idleTransition")

    // Primary button pulse
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPrimary || isFireButton) 1.02f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Icon animations
    val iconRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (iconAnimation == IconAnimation.ROTATE) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "iconRotation"
    )

    val iconTilt by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconTilt"
    )

    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconPulse"
    )

    // Fire icon flicker
    val fireIconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fireIconScale"
    )

    // Determine icon transform
    val iconModifier = when {
        iconAnimation == IconAnimation.FIRE || isFireButton -> Modifier.scale(fireIconScale)
        iconAnimation == IconAnimation.PULSE -> Modifier.scale(iconPulse)
        iconAnimation == IconAnimation.ROTATE -> Modifier.graphicsLayer { rotationZ = iconRotation }
        iconAnimation == IconAnimation.TILT -> Modifier.graphicsLayer { rotationZ = iconTilt }
        else -> Modifier
    }

    // Colors based on button type
    val containerColor = when {
        isFireButton -> Color.Transparent
        isPrimary -> HomeButtonColors.PrimaryBlue
        else -> HomeButtonColors.AccentPurple
    }

    val contentColor = Color.White

    // Fire button with particle system
    if (isFireButton) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(buttonHeight)
                .scale(scale * pulseScale)
                .clip(RoundedCornerShape(28.dp))
                .onFire()
        ) {
            // Button content
            Surface(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = contentColor,
                interactionSource = interactionSource
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = iconModifier.then(Modifier.size(32.dp)),
                                tint = Color.White
                            )
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        Text(
                            text = label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    } else {
        // Standard button
        if (icon != null) {
            ExtendedFloatingActionButton(
                onClick = onClick,
                containerColor = containerColor,
                contentColor = contentColor,
                interactionSource = interactionSource,
                modifier = modifier
                    .fillMaxWidth()
                    .height(buttonHeight)
                    .scale(scale * pulseScale),
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = iconModifier.then(Modifier.size(28.dp))
                    )
                },
                text = { Text(label, style = MaterialTheme.typography.titleMedium) }
            )
        } else {
            ExtendedFloatingActionButton(
                onClick = onClick,
                containerColor = containerColor,
                contentColor = contentColor,
                interactionSource = interactionSource,
                modifier = modifier
                    .fillMaxWidth()
                    .height(buttonHeight)
                    .scale(scale * pulseScale),
                content = { Text(label, style = MaterialTheme.typography.titleMedium) }
            )
        }
    }
}
