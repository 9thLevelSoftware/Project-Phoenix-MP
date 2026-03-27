package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*

// Phoenix fire colors
private val FireOrange = Color(0xFFFF6B35)
private val FireYellow = Color(0xFFFFB347)
private val FireRed = Color(0xFFE63946)
private val EmberGold = Color(0xFFFFD700)
private val DarkSlate = Color(0xFF0F172A)
private val DeepNavy = Color(0xFF1E293B)

/**
 * Animated splash screen with the Vitruvian Phoenix logo.
 * Features:
 * - Dramatic logo entrance with scale and bounce
 * - Animated fire glow behind the logo
 * - Rising ember particles
 * - Pulsing phoenix glow
 * - "Project Phoenix" text fade-in
 */
@Composable
fun SplashScreen(visible: Boolean, modifier: Modifier = Modifier) {
    // Animation states
    var showLogo by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showEmbers by remember { mutableStateOf(false) }

    // Trigger animations when visible
    LaunchedEffect(visible) {
        if (visible) {
            delay(100)
            showLogo = true
            delay(400)
            showEmbers = true
            delay(300)
            showText = true
        } else {
            showLogo = false
            showText = false
            showEmbers = false
        }
    }

    // Infinite transition for continuous animations
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    // Logo breathing/pulse animation
    val logoBreath by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    // Fire glow intensity animation
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    // Fire flicker (faster, more erratic)
    val fireFlicker by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker",
    )

    // Logo entrance animation
    val logoScale by animateFloatAsState(
        targetValue = if (showLogo) 1f else 0.3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "logoScale",
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (showLogo) 1f else 0f,
        animationSpec = tween(600, easing = EaseOutCubic),
        label = "logoAlpha",
    )

    // Text entrance animation
    val textAlpha by animateFloatAsState(
        targetValue = if (showText) 1f else 0f,
        animationSpec = tween(800, easing = EaseOutCubic),
        label = "textAlpha",
    )

    val textOffset by animateFloatAsState(
        targetValue = if (showText) 0f else 20f,
        animationSpec = tween(800, easing = EaseOutCubic),
        label = "textOffset",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(400)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DarkSlate,
                            DeepNavy,
                            DarkSlate,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Ember particles layer (behind logo)
            if (showEmbers) {
                EmberParticles(
                    modifier = Modifier.fillMaxSize(),
                    particleCount = 25,
                )
            }

            // Fire glow behind logo
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .scale(logoScale * logoBreath * 1.3f)
                    .alpha(glowIntensity * 0.5f * logoAlpha)
                    .blur(40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                FireOrange.copy(alpha = fireFlicker * 0.8f),
                                FireYellow.copy(alpha = 0.4f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // Secondary inner glow
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .aspectRatio(1f)
                    .scale(logoScale * logoBreath * 1.1f)
                    .alpha(glowIntensity * 0.7f * logoAlpha)
                    .blur(25.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                FireYellow.copy(alpha = 0.9f),
                                FireOrange.copy(alpha = 0.5f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // Main content column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Phoenix logo with animations
                Image(
                    painter = painterResource(Res.drawable.vitphoe_logo),
                    contentDescription = stringResource(Res.string.cd_phoenix_logo),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .aspectRatio(1f)
                        .scale(logoScale * logoBreath)
                        .alpha(logoAlpha),
                    contentScale = ContentScale.Fit,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // "Project Phoenix" text
                Text(
                    text = "PROJECT PHOENIX",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = FireOrange,
                        shadow = Shadow(
                            color = FireYellow.copy(alpha = 0.6f),
                            offset = Offset(0f, 0f),
                            blurRadius = 12f,
                        ),
                    ),
                    modifier = Modifier
                        .alpha(textAlpha)
                        .offset(y = textOffset.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tagline
                Text(
                    text = "Rise from the ashes",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier
                        .alpha(textAlpha * 0.8f)
                        .offset(y = textOffset.dp),
                )
            }
        }
    }
}

/**
 * Rising ember particles effect
 */
@Composable
private fun EmberParticles(modifier: Modifier = Modifier, particleCount: Int = 20) {
    // Create stable particle data
    val particles = remember {
        List(particleCount) { EmberParticle() }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "embers")

    // Animation progress for particle movement
    val animationProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "emberProgress",
    )

    Canvas(modifier = modifier) {
        particles.forEach { particle ->
            drawEmber(particle, animationProgress, size.width, size.height)
        }
    }
}

/**
 * Data class for ember particle properties
 */
private class EmberParticle {
    val xOffset: Float = Random.nextFloat() // 0-1 horizontal position
    val speed: Float = 0.3f + Random.nextFloat() * 0.7f // Speed variation
    val size: Float = 2f + Random.nextFloat() * 4f // Size variation
    val flickerSpeed: Float = 2f + Random.nextFloat() * 3f // Flicker rate
    val startDelay: Float = Random.nextFloat() // Stagger start times
    val wobbleAmount: Float = 10f + Random.nextFloat() * 20f // Horizontal wobble
    val wobbleSpeed: Float = 1f + Random.nextFloat() * 2f // Wobble frequency
    val color: Color = listOf(
        FireOrange,
        FireYellow,
        EmberGold,
        FireRed,
    ).random()
}

/**
 * Draw a single ember particle
 */
private fun DrawScope.drawEmber(particle: EmberParticle, progress: Float, canvasWidth: Float, canvasHeight: Float) {
    // Calculate particle position with staggered start
    val adjustedProgress = ((progress + particle.startDelay) * particle.speed) % 1f

    // Start from bottom center area, rise upward
    val startY = canvasHeight * 0.7f // Start from lower portion
    val endY = -50f // End above screen

    val y = startY + (endY - startY) * adjustedProgress

    // Horizontal wobble using sine wave
    val baseX = canvasWidth * particle.xOffset
    val wobble = sin(adjustedProgress * particle.wobbleSpeed * 6.28f) * particle.wobbleAmount
    val x = baseX + wobble

    // Fade in at start, fade out at end
    val fadeIn = (adjustedProgress * 5f).coerceIn(0f, 1f)
    val fadeOut = ((1f - adjustedProgress) * 3f).coerceIn(0f, 1f)
    val alpha = fadeIn * fadeOut

    // Flicker effect
    val flicker = 0.5f + 0.5f * sin(adjustedProgress * particle.flickerSpeed * 6.28f)

    // Size decreases as particle rises
    val currentSize = particle.size * (1f - adjustedProgress * 0.5f)

    // Draw the ember
    drawCircle(
        color = particle.color.copy(alpha = alpha * flicker * 0.8f),
        radius = currentSize,
        center = Offset(x, y),
    )

    // Draw glow around ember
    drawCircle(
        color = particle.color.copy(alpha = alpha * flicker * 0.3f),
        radius = currentSize * 2.5f,
        center = Offset(x, y),
    )
}

/**
 * Simple splash screen variant without animations.
 * Useful for instant display before main content loads.
 */
@Composable
fun SimpleSplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkSlate),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.vitphoe_logo),
                contentDescription = stringResource(Res.string.cd_phoenix_logo),
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .aspectRatio(1f),
                contentScale = ContentScale.Fit,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "PROJECT PHOENIX",
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = FireOrange,
                ),
            )
        }
    }
}
