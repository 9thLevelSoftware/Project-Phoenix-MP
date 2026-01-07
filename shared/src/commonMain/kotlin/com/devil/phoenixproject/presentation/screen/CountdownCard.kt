package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.*

/**
 * Countdown Card Component
 *
 * Displays 3-2-1-GO countdown before workout begins in autoplay mode.
 * Shows animated countdown with exercise preparation info.
 */
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
    onSkipCountdown: () -> Unit,
    onEndWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Responsive sizing based on window size class
    val windowSizeClass = LocalWindowSizeClass.current
    val countdownSize = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 320.dp
        WindowWidthSizeClass.Medium -> 270.dp
        WindowWidthSizeClass.Compact -> 220.dp
    }
    val countdownFontSize = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 120.sp
        WindowWidthSizeClass.Medium -> 105.sp
        WindowWidthSizeClass.Compact -> 90.sp
    }

    // Background gradient - respects theme mode
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            )
            .padding(20.dp)
    ) {
        // Subtle pulsing overlay to create an immersive feel
        val infinite = rememberInfiniteTransition(label = "countdown-pulse")
        val pulse by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // GET READY Header - Material 3 Expressive
            Text(
                text = "GET READY",
                style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger (was labelLarge)
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp
            )

            // Countdown number - large centered text with pulsing animation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(countdownSize),
                contentAlignment = Alignment.Center
            ) {
                // Circular background with pulse effect
                Box(
                    modifier = Modifier
                        .size(countdownSize)
                        .scale(pulse)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(countdownSize)
                        )
                )

                // Countdown text or "GO!"
                val countdownText = if (countdownSecondsRemaining > 0) {
                    countdownSecondsRemaining.toString()
                } else {
                    "GO!"
                }

                Text(
                    text = countdownText,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = countdownFontSize),
                    fontWeight = FontWeight.ExtraBold,
                    color = if (countdownSecondsRemaining == 0)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.primary
                )
            }

            // UP NEXT section - Material 3 Expressive
            Text(
                text = "UP NEXT",
                style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger (was labelMedium)
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp
            )

            // Next exercise name - Material 3 Expressive
            Text(
                text = nextExerciseName,
                style = MaterialTheme.typography.headlineSmall, // Material 3 Expressive: Larger (was titleLarge)
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Workout parameters preview (if available)
            if (nextExerciseWeight != null || nextExerciseReps != null) {
                Spacer(modifier = Modifier.height(Spacing.small))

                // Parameters card - Material 3 Expressive
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 12dp)
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Material 3 Expressive: Higher elevation
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Text(
                            "WORKOUT PARAMETERS",
                            style = MaterialTheme.typography.labelLarge, // Material 3 Expressive: Larger (was labelSmall)
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (nextExerciseWeight != null && formatWeight != null) {
                                WorkoutParamItemCountdown(
                                    icon = Icons.Default.Settings,
                                    label = "Weight",
                                    value = formatWeight(nextExerciseWeight)
                                )
                            }
                            if (nextExerciseReps != null) {
                                WorkoutParamItemCountdown(
                                    icon = Icons.Default.Refresh,
                                    label = "Target Reps",
                                    value = nextExerciseReps.toString()
                                )
                            }
                            if (nextExerciseMode != null) {
                                WorkoutParamItemCountdown(
                                    icon = Icons.Default.Settings,
                                    label = "Mode",
                                    value = nextExerciseMode.take(8)
                                )
                            }
                        }
                    }
                }
            }

            // Progress through routine (if multi-exercise)
            if (currentExerciseIndex != null && totalExercises != null && totalExercises > 1) {
                Spacer(modifier = Modifier.height(Spacing.small))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Exercise ${currentExerciseIndex + 1} of $totalExercises",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (currentExerciseIndex + 1).toFloat() / totalExercises },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Action buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Skip Countdown button (primary action) - Material 3 Expressive
                Button(
                    onClick = onSkipCountdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp), // Material 3 Expressive: Taller button
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp, // Material 3 Expressive: Higher elevation
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Skip countdown",
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon (was 20dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        text = "Skip Countdown",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was labelLarge)
                        fontWeight = FontWeight.Bold
                    )
                }

                // End Workout button (secondary/destructive action) - Material 3 Expressive
                TextButton(
                    onClick = onEndWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded (was 16dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "End workout",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        text = "End Workout",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger (was labelMedium)
                        fontWeight = FontWeight.Bold, // Material 3 Expressive: Bolder
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun WorkoutParamItemCountdown(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
