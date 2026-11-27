package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Countdown Card Component
 *
 * Displays countdown before workout begins in autoplay mode.
 * Shows animated countdown with exercise preparation info.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountdownCard(
    countdownSecondsRemaining: Int,
    nextExerciseName: String,
    nextExerciseWeight: Float? = null,
    nextExerciseReps: Int? = null,
    nextExerciseMode: String? = null,
    currentExerciseIndex: Int? = null,
    totalExercises: Int? = null,
    formatWeight: ((Float) -> String)? = null,
    isEchoMode: Boolean = false,
    onSkipCountdown: () -> Unit,
    onEndWorkout: () -> Unit,
    modifier: Modifier = Modifier,
    // Issue #237: Motion-triggered set start progress (null = disabled, 0..1 = hold progress)
    motionStartHoldProgress: Float? = null,
) {
    // Responsive sizing based on window size class
    val windowSizeClass = LocalWindowSizeClass.current
    val countdownSize = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 280.dp
        WindowWidthSizeClass.Medium -> 240.dp
        WindowWidthSizeClass.Compact -> 200.dp
    }
    val countdownFontSize = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 100.sp
        WindowWidthSizeClass.Medium -> 88.sp
        WindowWidthSizeClass.Compact -> 76.sp
    }
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Pulsing animation (disabled when reduceMotion is active)
    val pulse = if (reduceMotion) {
        1f
    } else {
        val infinite = rememberInfiniteTransition(label = "countdown-pulse")
        val animatedPulse by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        animatedPulse
    }

    // Host insets are consumed once by ActiveWorkoutScreen/WorkoutTab; BoxWithConstraints
    // guards verticalScroll so CountdownCard never crashes under WorkoutTab's unbounded scroll.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(screenBackgroundBrush()),
    ) {
        val scrollModifier = if (constraints.hasBoundedHeight) {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
        } else {
            Modifier.fillMaxWidth()
        }

        Column(
            modifier = scrollModifier.padding(horizontal = 4.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            // Top section: Exercise info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // Progress indicator (if in routine)
                if (currentExerciseIndex != null && totalExercises != null && totalExercises > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.resume_exercise_of_total,
                                currentExerciseIndex + 1,
                                totalExercises,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Exercise name - prominent display
                Text(
                    text = nextExerciseName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            // Center section: Countdown circle
            // Issue #237: Animate motion-start hold progress for smooth ring-fill
            val animatedHoldProgress by animateFloatAsState(
                targetValue = motionStartHoldProgress ?: 0f,
                animationSpec = tween(durationMillis = if (reduceMotion) 0 else 100),
                label = "motion-start-hold",
            )
            val motionStartArcColor = MaterialTheme.colorScheme.tertiary

            Box(
                modifier = Modifier.size(countdownSize),
                contentAlignment = Alignment.Center,
            ) {
                // Outer pulsing ring
                Box(
                    modifier = Modifier
                        .size(countdownSize)
                        .scale(pulse)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                ),
                            ),
                        ),
                )

                // Issue #237: Motion-start ring-fill arc overlay
                if (motionStartHoldProgress != null && animatedHoldProgress > 0f) {
                    Canvas(modifier = Modifier.size(countdownSize - 8.dp)) {
                        drawArc(
                            color = motionStartArcColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedHoldProgress,
                            useCenter = false,
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }

                // Inner circle
                Box(
                    modifier = Modifier
                        .size(countdownSize - 24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    val countdownText = if (countdownSecondsRemaining > 0) {
                        countdownSecondsRemaining.toString()
                    } else {
                        stringResource(Res.string.countdown_go)
                    }

                    Text(
                        text = countdownText,
                        fontSize = countdownFontSize,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        softWrap = false,
                        color = if (countdownSecondsRemaining == 0) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            // Parameters section
            if (nextExerciseWeight != null || nextExerciseReps != null ||
                nextExerciseMode != null
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Section header
                        Text(
                            text = stringResource(Res.string.set_ready_set_configuration),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                        )

                        // Parameters wrap gracefully on narrow or scaled viewports
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Echo mode shows "Adaptive" instead of weight
                            if (isEchoMode) {
                                CountdownParamChip(
                                    icon = Icons.Default.FitnessCenter,
                                    label = stringResource(Res.string.label_weight),
                                    value = stringResource(Res.string.hud_adaptive),
                                    modifier = Modifier.widthIn(min = 84.dp),
                                )
                            } else if (nextExerciseWeight != null && formatWeight != null) {
                                CountdownParamChip(
                                    icon = Icons.Default.FitnessCenter,
                                    label = stringResource(Res.string.label_weight),
                                    value = formatWeight(nextExerciseWeight),
                                    modifier = Modifier.widthIn(min = 84.dp),
                                )
                            }
                            if (nextExerciseReps != null) {
                                CountdownParamChip(
                                    icon = Icons.Default.Repeat,
                                    label = stringResource(Res.string.label_reps),
                                    value = nextExerciseReps.toString(),
                                    modifier = Modifier.widthIn(min = 84.dp),
                                )
                            }
                            if (nextExerciseMode != null) {
                                CountdownParamChip(
                                    icon = Icons.Default.Speed,
                                    label = stringResource(Res.string.summary_stat_mode),
                                    value = nextExerciseMode,
                                    modifier = Modifier.widthIn(min = 84.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom section: Action buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Skip Countdown - Primary action
                Button(
                    onClick = onSkipCountdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.countdown_start_now),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // End Workout - Secondary action
                TextButton(
                    onClick = onEndWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.rest_end_workout),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Individual parameter chip for the countdown card
 */
@Composable
private fun CountdownParamChip(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Icon in a subtle circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        // Value - prominent
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Label - subtle
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
