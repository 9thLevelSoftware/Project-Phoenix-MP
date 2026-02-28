package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import kotlinx.coroutines.delay

/**
 * Returns the color for a rep quality score using a gradient scale from AccessibilityTheme.
 * Colors change with color-blind mode for WCAG 1.4.1 compliance.
 */
@Composable
private fun scoreColor(score: Int): Color {
    val colors = AccessibilityTheme.colors
    return when {
        score >= 95 -> colors.qualityExcellent
        score >= 80 -> colors.qualityGood
        score >= 60 -> colors.qualityFair
        score >= 40 -> colors.qualityBelowAverage
        else -> colors.qualityPoor
    }
}

/**
 * Overlay composable that displays the per-rep quality score on the workout HUD.
 *
 * Shows a prominent score number with color gradient after each rep,
 * auto-dismisses after 800ms. Excellent reps (95+) get a subtle pulse animation.
 *
 * @param latestRepQualityScore The score to display (null = hidden / not available)
 */
@Composable
fun RepQualityIndicator(
    latestRepQualityScore: Int?,
    modifier: Modifier = Modifier
) {
    var showScore by remember { mutableStateOf(false) }
    var displayedScore by remember { mutableIntStateOf(0) }
    var isExcellent by remember { mutableStateOf(false) }

    // Trigger display when score changes from null to non-null
    LaunchedEffect(latestRepQualityScore) {
        if (latestRepQualityScore != null) {
            displayedScore = latestRepQualityScore
            isExcellent = latestRepQualityScore >= 95
            showScore = true
            delay(800)
            showScore = false
        }
    }

    // Pulse animation for excellent reps (95+)
    val pulseScale by animateFloatAsState(
        targetValue = if (showScore && isExcellent) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 400),
        label = "qualityPulse"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = showScore,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.padding(top = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$displayedScore",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(displayedScore)
                )
            }
        }
    }
}
