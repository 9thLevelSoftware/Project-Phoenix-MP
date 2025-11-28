package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.vitphoe_logo

/**
 * Splash screen with the Vitruvian Phoenix logo and app branding.
 * Uses Compose Multiplatform resources for cross-platform compatibility.
 */
@Composable
fun SplashScreen(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // Animation for logo scale
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(250))
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    // Match the ic_launcher_background color from parent project
                    Color(0xFF0F172A) // slate-900
                ),
            contentAlignment = Alignment.Center
        ) {
            // Logo with subtle pulse animation
            Image(
                painter = painterResource(Res.drawable.vitphoe_logo),
                contentDescription = "Vitruvian Phoenix Logo",
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .aspectRatio(1f)
                    .scale(pulseScale),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Simple splash screen variant without animations.
 * Useful for instant display before main content loads.
 */
@Composable
fun SimpleSplashScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)), // slate-900
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(Res.drawable.vitphoe_logo),
            contentDescription = "Vitruvian Phoenix Logo",
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f),
            contentScale = ContentScale.Fit
        )
    }
}
