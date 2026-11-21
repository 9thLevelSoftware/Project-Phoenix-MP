package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import com.devil.phoenixproject.presentation.util.rememberPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ApplyStatusBarAppearance
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Slate800
import com.devil.phoenixproject.ui.theme.Slate900
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*

// Phoenix fire colors
private val FireOrange = Color(0xFFFF6B35)
private val FireYellow = Color(0xFFFFB347)
private val FireRed = Color(0xFFE63946)
private val EmberGold = Color(0xFFFFD700)

/**
 * Animated splash screen with the Project Phoenix logo.
 * Features:
 * - Dramatic logo entrance with scale and bounce
 * - Animated fire glow behind the logo
 * - Rising ember particles
 * - Pulsing phoenix glow
 * - "Project Phoenix" text fade-in
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // Splash uses the dark Phoenix Flame gradient regardless of theme mode, so keep
    // status bar icons light for contrast while SplashScreen is visible.
    ApplyStatusBarAppearance(isDark = true)

    // home-shell-18: SplashScreen renders outside EnhancedMainScreen's CompositionLocalProvider,
    // so LocalPlatformAccessibilitySettings would return the default (reduceMotion=false).
    // Use rememberPlatformAccessibilitySettings() directly to query the platform setting.
    val reduceMotion = rememberPlatformAccessibilitySettings().reduceMotion

    // Animation states
    var showLogo by remember(reduceMotion) { mutableStateOf(reduceMotion) }
    var showText by remember(reduceMotion) { mutableStateOf(reduceMotion) }
    var showEmbers by remember(reduceMotion) { mutableStateOf(false) }

    // Entrance sequence. Callers drop the splash by stopping composition.
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            showLogo = true
            showText = true
            showEmbers = false
        } else {
            delay(100)
            showLogo = true
            delay(400)
            showEmbers = true
            delay(300)
            showText = true
        }
    }

    val logoBreath: Float
    val glowIntensity: Float
    val fireFlicker: Float
    if (reduceMotion) {
        logoBreath = 1f
        glowIntensity = 0.8f
        fireFlicker = 1f
    } else {
        // Infinite transition for continuous animations
        val infiniteTransition = rememberInfiniteTransition(label = "splash")

        val animatedLogoBreath by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        val animatedGlowIntensity by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EaseInOutQuad),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow",
        )
        val animatedFireFlicker by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flicker",
        )
        logoBreath = animatedLogoBreath
        glowIntensity = animatedGlowIntensity
        fireFlicker = animatedFireFlicker
    }

    // Logo entrance animation
    // home-shell-18: replace inline MediumBouncy with charter SpringBouncy (HighBouncy+StiffnessLow)
    val logoScale by animateFloatAsState(
        targetValue = if (showLogo || reduceMotion) 1f else 0.3f,
        animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncy,
        label = "logoScale",
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (showLogo || reduceMotion) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(600, easing = EaseOutCubic),
        label = "logoAlpha",
    )

    // Text entrance animation
    val textAlpha by animateFloatAsState(
        targetValue = if (showText || reduceMotion) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(800, easing = EaseOutCubic),
        label = "textAlpha",
    )

    val textOffset by animateFloatAsState(
        targetValue = if (showText || reduceMotion) 0f else 20f,
        animationSpec = if (reduceMotion) snap() else tween(800, easing = EaseOutCubic),
        label = "textOffset",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Slate900,
                        Slate800,
                        Slate900,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val isNarrow = maxWidth < 360.dp
        val isShort = maxHeight < 520.dp
        val fontScale = LocalDensity.current.fontScale
        val logoMaxDim = minOf(maxWidth * 0.55f, maxHeight * 0.38f, 220.dp)
        val glowOuterSize = (logoMaxDim * 1.35f).coerceAtMost(maxWidth * 0.85f)
        val glowInnerSize = (logoMaxDim * 1.05f).coerceAtMost(maxWidth * 0.65f)

        // Ember particles layer (behind logo)
        if (!reduceMotion && showEmbers) {
            EmberParticles(
                modifier = Modifier.fillMaxSize(),
                particleCount = 25,
            )
        }

        // Fire glow behind logo
        Box(
            modifier = Modifier
                .size(glowOuterSize)
                .scale(logoScale * logoBreath * 1.2f)
                .alpha(glowIntensity * 0.5f * logoAlpha)
                .blur(40.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            FireOrange.copy(alpha = (fireFlicker * 0.8f).coerceIn(0f, 1f)),
                            FireYellow.copy(alpha = 0.4f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Secondary inner glow
        Box(
            modifier = Modifier
                .size(glowInnerSize)
                .scale(logoScale * logoBreath * 1.05f)
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
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Phoenix logo with animations
            Image(
                painter = painterResource(Res.drawable.phoenix_logo),
                contentDescription = stringResource(Res.string.cd_phoenix_logo),
                modifier = Modifier
                    .sizeIn(maxWidth = 220.dp, maxHeight = 220.dp)
                    .size(logoMaxDim)
                    .scale(logoScale * logoBreath)
                    .alpha(logoAlpha),
                contentScale = ContentScale.Fit,
            )

            Spacer(modifier = Modifier.height(if (isShort) 16.dp else 32.dp))

            // "Project Phoenix" text
            val titleStyle = if (isNarrow || fontScale >= 1.2f) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineSmall
            }
            val titleTracking = when {
                isNarrow || fontScale >= 1.2f -> 1.5.sp
                else -> 4.sp
            }
            Text(
                text = "PROJECT PHOENIX",
                style = titleStyle.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = titleTracking,
                    color = FireOrange,
                    shadow = Shadow(
                        color = FireYellow.copy(alpha = 0.6f),
                        offset = Offset(0f, 0f),
                        blurRadius = 12f,
                    ),
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(textAlpha)
                    .offset(y = textOffset.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tagline
            Text(
                text = "Rise from the ashes",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = if (isNarrow) 1.sp else 2.sp,
                    color = Color.White.copy(alpha = 0.7f),
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(textAlpha * 0.8f)
                    .offset(y = textOffset.dp),
            )
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
