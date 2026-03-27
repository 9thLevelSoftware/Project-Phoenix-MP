package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.RepPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Issue #163: Animated Rep Counter
 *
 * Displays the current rep being performed with animated visual feedback:
 * - IDLE: Shows nothing (blank) - the stable X/Y counter shows confirmed reps
 * - CONCENTRIC (lifting): Number outline reveals from bottom to top
 * - ECCENTRIC (lowering): Number fills with color from top to bottom
 * - When rep completes: Celebration animation (scale down + fade out)
 *
 * The animation provides visual feedback during each phase of the rep:
 * 1. User starts pulling → wireframe "1" draws from bottom to top
 * 2. User lowers → "1" fills from top to bottom
 * 3. Rep confirmed → "1" scales down and fades out (celebration)
 * 4. Back to blank, ready for next rep
 *
 * @param nextRepNumber The rep number being performed (workingReps + 1)
 * @param phase Current movement phase (IDLE, CONCENTRIC, ECCENTRIC)
 * @param phaseProgress Progress through current phase (0.0 to 1.0)
 * @param confirmedReps The confirmed completed rep count
 * @param targetReps Target rep count (for stable X/Y display)
 * @param showStableCounter Whether to show the stable "X / Y" counter below
 * @param size Size of the component
 */
@Composable
fun AnimatedRepCounter(
    nextRepNumber: Int,
    phase: RepPhase,
    phaseProgress: Float,
    confirmedReps: Int,
    targetReps: Int,
    showStableCounter: Boolean = true,
    size: Dp = 120.dp,
    modifier: Modifier = Modifier,
) {
    // Primary color for both outline stroke and fill
    val fillColor = MaterialTheme.colorScheme.primary

    // Track the previous confirmedReps to detect when a rep is completed
    var lastConfirmedReps by remember { mutableIntStateOf(confirmedReps) }

    // Celebration animation state
    val celebrationScale = remember { Animatable(1f) }
    val celebrationAlpha = remember { Animatable(1f) }
    var showingCelebration by remember { mutableIntStateOf(0) } // 0 = no celebration, >0 = celebrating that rep number

    // Detect when a rep is completed and trigger celebration
    LaunchedEffect(confirmedReps) {
        if (confirmedReps > lastConfirmedReps && confirmedReps > 0) {
            // A new rep was just confirmed - show celebration for this number
            showingCelebration = confirmedReps
            celebrationScale.snapTo(1f)
            celebrationAlpha.snapTo(1f)

            // Animate scale down and fade out (explosion/burn away effect)
            // Launch both animations in parallel within this coroutine scope
            launch {
                celebrationScale.animateTo(
                    targetValue = 1.5f, // Scale up slightly first
                    animationSpec = tween(durationMillis = 100),
                )
                celebrationScale.animateTo(
                    targetValue = 0f, // Then scale down to nothing
                    animationSpec = tween(durationMillis = 200),
                )
            }
            launch {
                delay(50) // Slight delay before fade
                celebrationAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 250),
                )
                showingCelebration = 0 // Clear celebration state
            }
        }
        lastConfirmedReps = confirmedReps
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size),
    ) {
        // Show celebration animation if a rep was just completed
        // During celebration, ONLY the celebration is shown (no phase animation)
        if (showingCelebration > 0) {
            Text(
                text = showingCelebration.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = (size.value * 0.7f).sp,
                    fontWeight = FontWeight.Black,
                ),
                color = fillColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    scaleX = celebrationScale.value
                    scaleY = celebrationScale.value
                    alpha = celebrationAlpha.value
                },
            )
        } else {
            // Only show phase animation when NOT celebrating
            // Use key(phase) to reset animation state when phase changes
            // This prevents the smooth 1→0 transition between phases that causes
            // the "inverted animation on alternating reps" bug
            key(phase) {
                // Animate progress only WITHIN a phase, not across phase transitions
                val animatedProgress by animateFloatAsState(
                    targetValue = phaseProgress,
                    animationSpec = tween(durationMillis = 100),
                    label = "phase_progress",
                )

                when (phase) {
                    RepPhase.IDLE -> {
                        // Show NOTHING during idle - the stable X/Y counter shows confirmed reps
                    }

                    RepPhase.CONCENTRIC -> {
                        // Stroke-only outline reveals from bottom to top
                        // Progress 0.0 = nothing visible, 1.0 = full outline visible
                        val strokeWidth = size.value * 0.04f // Stroke width proportional to size
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Stroke-only text (just the outline edges, no fill)
                            Text(
                                text = nextRepNumber.toString(),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = (size.value * 0.7f).sp,
                                    fontWeight = FontWeight.Black,
                                    drawStyle = Stroke(
                                        width = strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                ),
                                color = fillColor, // Use primary color for the outline
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.Offscreen
                                        // Clip from bottom up based on progress
                                        // At progress 0: clip = 0 (show nothing from bottom)
                                        // At progress 1: clip = 1 (show everything)
                                        clip = true
                                    }
                                    .drawWithContent {
                                        // Draw into canvas with clip rect
                                        drawContext.canvas.save()
                                        val visibleFromBottom = this.size.height * animatedProgress
                                        val clipTop = this.size.height - visibleFromBottom
                                        drawContext.canvas.clipRect(
                                            Rect(0f, clipTop, this.size.width, this.size.height),
                                        )
                                        drawContent()
                                        drawContext.canvas.restore()
                                    },
                            )
                        }
                    }

                    RepPhase.ECCENTRIC -> {
                        // Fill reveals from top to bottom over the stroke outline
                        // Progress 0.0 = outline only, 1.0 = fully filled
                        val strokeWidth = size.value * 0.04f
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Stroke outline as background (always visible)
                            Text(
                                text = nextRepNumber.toString(),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = (size.value * 0.7f).sp,
                                    fontWeight = FontWeight.Black,
                                    drawStyle = Stroke(
                                        width = strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                ),
                                color = fillColor,
                                textAlign = TextAlign.Center,
                            )

                            // Filled text (revealed top-to-bottom based on progress)
                            Text(
                                text = nextRepNumber.toString(),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = (size.value * 0.7f).sp,
                                    fontWeight = FontWeight.Black,
                                ),
                                color = fillColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        // Mask: hide from bottom up based on inverse progress
                                        // At progress 0: hide everything (clip entire area)
                                        // At progress 1: show everything (no clip)
                                        val visibleHeight = this.size.height * animatedProgress
                                        val clipTop = visibleHeight
                                        drawRect(
                                            color = Color.Transparent,
                                            topLeft = Offset(0f, clipTop),
                                            size = this.size.copy(height = this.size.height - clipTop),
                                            blendMode = BlendMode.Clear,
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stable rep progress display showing confirmed reps vs target.
 * Example: "3 / 10"
 */
@Composable
fun StableRepProgress(confirmedReps: Int, targetReps: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$confirmedReps / $targetReps",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
