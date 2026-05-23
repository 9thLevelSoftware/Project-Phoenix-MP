package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.random.Random

/**
 * Icon animation types for AnimatedActionButton.
 *
 * Home actions intentionally use press-only feedback so labels remain readable.
 */
enum class IconAnimation {
    NONE,
    PULSE,
    ROTATE,
    TILT,
    FIRE,
}

private data class FlameParticle(
    var x: Float,
    var y: Float,
    var radius: Float,
    var speedY: Float,
    var life: Float = 1.0f,
    var driftOffset: Float = Random.nextFloat() * 100f,
)

private fun createFlameParticle(): FlameParticle = FlameParticle(
    x = Random.nextFloat(),
    y = 1f + Random.nextFloat() * 0.1f,
    radius = Random.nextFloat() * 8f + 4f,
    speedY = Random.nextFloat() * 0.015f + 0.006f,
)

private fun Modifier.onFire(): Modifier = composed {
    val particles = remember { mutableStateListOf<FlameParticle>() }
    var time by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val startTime = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                time = now - startTime

                val iterator = particles.listIterator()
                while (iterator.hasNext()) {
                    val particle = iterator.next()
                    particle.y -= particle.speedY
                    particle.life -= 0.02f
                    particle.radius *= 0.97f

                    if (particle.life <= 0f || particle.radius < 1f) {
                        iterator.remove()
                    }
                }

                if (particles.size < 80) {
                    repeat(2) {
                        particles.add(createFlameParticle())
                    }
                }
            }
        }
    }

    drawWithCache {
        onDrawBehind {
            drawRect(Color(0xFF1A0800))

            particles.forEach { particle ->
                val drift = sin((time / 800_000_000f) + particle.driftOffset) * 4f
                val color = when {
                    particle.life > 0.7f -> Color(0xFFFFD700)
                    particle.life > 0.4f -> Color(0xFFFF6B00)
                    else -> Color(0xFFFF4500).copy(alpha = (particle.life * 2f).coerceIn(0f, 1f))
                }

                drawCircle(
                    color = color,
                    radius = particle.radius,
                    center = Offset((particle.x * size.width) + drift, particle.y * size.height),
                    blendMode = BlendMode.Plus,
                )

                if (particle.life > 0.5f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (particle.life - 0.5f) * 0.6f),
                        radius = particle.radius * 0.4f,
                        center = Offset((particle.x * size.width) + drift, particle.y * size.height),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }
}

/**
 * Theme-aware home action button with merged accessibility semantics.
 */
@Composable
fun AnimatedActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    isPrimary: Boolean,
    isFireButton: Boolean = false,
    iconAnimation: IconAnimation = IconAnimation.NONE,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    supportingText: String? = null,
    heightOverride: Dp? = null,
    allowTwoLineLabel: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val indication = LocalIndication.current
    val windowSizeClass = LocalWindowSizeClass.current
    val useCompactAccessibility = isCompactAccessibilityLayout()

    val defaultHeight = remember(useCompactAccessibility, windowSizeClass.widthSizeClass, isPrimary) {
        if (useCompactAccessibility) {
            72.dp
        } else {
            when (windowSizeClass.widthSizeClass) {
                WindowWidthSizeClass.Expanded -> if (isPrimary) 80.dp else 64.dp
                WindowWidthSizeClass.Medium -> if (isPrimary) 76.dp else 60.dp
                WindowWidthSizeClass.Compact -> if (isPrimary) 72.dp else 56.dp
            }
        }
    }
    val buttonHeight = heightOverride ?: defaultHeight
    val shape = RoundedCornerShape(if (isPrimary || isFireButton) 24.dp else 18.dp)
    val semanticContentDescription = remember(contentDescription, supportingText) {
        listOfNotNull(
            contentDescription,
            supportingText?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "homeActionPressScale",
    )
    val iconRotation by animateFloatAsState(
        targetValue = when {
            !isPressed -> 0f
            iconAnimation == IconAnimation.ROTATE -> 18f
            iconAnimation == IconAnimation.TILT -> -8f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "homeActionIconRotation",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isPressed && (iconAnimation == IconAnimation.PULSE || iconAnimation == IconAnimation.FIRE || isFireButton)) {
            0.94f
        } else {
            1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "homeActionIconScale",
    )

    val containerColor = when {
        isFireButton -> Color.Transparent
        isPrimary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isFireButton -> Color.White
        isPrimary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = if (!isPrimary && !isFireButton) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    } else {
        null
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(buttonHeight)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                role = Role.Button,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                this.contentDescription = semanticContentDescription
                role = Role.Button
                this.onClick(label = semanticContentDescription) {
                    onClick()
                    true
                }
            },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (isPrimary || isFireButton) 0.dp else 2.dp,
        shadowElevation = if (isPrimary || isFireButton) 3.dp else 0.dp,
        border = border,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isFireButton) Modifier.clip(shape).onFire() else Modifier)
                .padding(horizontal = if (isPrimary) 18.dp else 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (isPrimary) 28.dp else 22.dp)
                            .graphicsLayer { rotationZ = iconRotation }
                            .scale(iconScale),
                        tint = contentColor,
                    )
                    Spacer(Modifier.width(if (isPrimary) 10.dp else 8.dp))
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        style = if (isPrimary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = if (allowTwoLineLabel) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (supportingText != null) {
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
