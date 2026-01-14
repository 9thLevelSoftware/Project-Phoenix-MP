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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.ui.theme.*

/**
 * Rest Timer Card Component
 *
 * Displays during rest periods between sets/exercises in autoplay mode.
 * Shows countdown timer, next exercise info, and editable workout parameters.
 */
@Composable
fun RestTimerCard(
    restSecondsRemaining: Int,
    nextExerciseName: String,
    isLastExercise: Boolean,
    currentSet: Int,
    totalSets: Int,
    nextExerciseWeight: Float? = null,
    nextExerciseReps: Int? = null,
    nextExerciseMode: String? = null,
    currentExerciseIndex: Int? = null,
    totalExercises: Int? = null,
    weightUnit: WeightUnit = WeightUnit.KG,
    lastUsedWeight: Float? = null,
    prWeight: Float? = null,
    formatWeight: ((Float) -> String)? = null,
    formatWeightWithUnit: ((Float, WeightUnit) -> String)? = null,
    isSupersetTransition: Boolean = false,
    supersetLabel: String? = null,
    onSkipRest: () -> Unit,
    onEndWorkout: () -> Unit,
    onUpdateReps: ((Int) -> Unit)? = null,
    onUpdateWeight: ((Float) -> Unit)? = null,
    // Echo mode specific
    programMode: ProgramMode? = null,
    echoLevel: EchoLevel? = null,
    eccentricLoadPercent: Int? = null,
    onUpdateEchoLevel: ((EchoLevel) -> Unit)? = null,
    onUpdateEccentricLoad: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Local state for editing parameters
    var editedReps by remember(nextExerciseReps) { mutableStateOf(nextExerciseReps ?: 10) }
    var editedWeight by remember(nextExerciseWeight) { mutableStateOf(nextExerciseWeight ?: 20f) }
    var editedEchoLevel by remember(echoLevel) { mutableStateOf(echoLevel ?: EchoLevel.HARD) }
    var editedEccentricPercent by remember(eccentricLoadPercent) { mutableStateOf(eccentricLoadPercent ?: 100) }

    // Determine if this is Echo mode
    val isEchoMode = programMode == ProgramMode.Echo

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
            .systemBarsPadding()
            .padding(20.dp)
    ) {
        // Subtle pulsing overlay to create an immersive feel
        val infinite = rememberInfiniteTransition(label = "rest-pulse")
        val pulse by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
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
            // REST TIME Header - shows superset info if applicable
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isSupersetTransition && supersetLabel != null) {
                    // Show superset badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = supersetLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = if (isSupersetTransition) "QUICK REST" else "REST TIME",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSupersetTransition)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp
                )
            }

            // Countdown timer - large centered text with pulsing animation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                // Circular background with pulse effect
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulse)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(200.dp)
                        )
                )

                // Timer text
                Text(
                    text = formatRestTime(restSecondsRemaining),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // UP NEXT section with exercise info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "UP NEXT",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )

                // Next exercise name or completion message
                Text(
                    text = if (isLastExercise) "Workout Complete" else nextExerciseName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isLastExercise)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Mode display (moved from parameters card)
                if (!isLastExercise && nextExerciseMode != null) {
                    Text(
                        text = "$nextExerciseMode Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Set progress indicator - FIXED: Show upcoming set (currentSet + 1)
                if (!isLastExercise) {
                    Text(
                        text = "Set ${currentSet + 1} of $totalSets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Editable workout parameters (if available and not last exercise)
            // Show for: non-Echo modes with weight/reps, OR Echo mode with echo settings
            val showConfigCard = !isLastExercise && (
                (isEchoMode && (echoLevel != null || nextExerciseReps != null)) ||
                (!isEchoMode && (nextExerciseWeight != null || nextExerciseReps != null))
            )

            if (showConfigCard) {
                Spacer(modifier = Modifier.height(Spacing.small))

                // Parameters config card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
                    ) {
                        Text(
                            "NEXT SET CONFIGURATION",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )

                        // Reps adjuster with hybrid slider (shown for all modes)
                        if (nextExerciseReps != null) {
                            SliderWithButtons(
                                value = editedReps.toFloat(),
                                onValueChange = { newValue ->
                                    editedReps = newValue.toInt().coerceIn(1, 50)
                                    onUpdateReps?.invoke(editedReps)
                                },
                                valueRange = 1f..50f,
                                step = 1f,
                                label = "Target Reps",
                                formatValue = { it.toInt().toString() }
                            )
                        }

                        if (isEchoMode) {
                            // Echo mode: Show Echo Level selector + Eccentric Load slider
                            RestTimerEchoLevelSelector(
                                selectedLevel = editedEchoLevel,
                                onLevelChange = { newLevel ->
                                    editedEchoLevel = newLevel
                                    onUpdateEchoLevel?.invoke(newLevel)
                                }
                            )

                            RestTimerEccentricLoadSlider(
                                percent = editedEccentricPercent,
                                onPercentChange = { newPercent ->
                                    editedEccentricPercent = newPercent
                                    onUpdateEccentricLoad?.invoke(newPercent)
                                }
                            )
                        } else {
                            // Non-Echo modes: Show weight adjuster
                            if (nextExerciseWeight != null && formatWeightWithUnit != null) {
                                val maxWeight = if (weightUnit == WeightUnit.LB) 242f else 110f  // 110kg per cable max
                                val weightStep = if (weightUnit == WeightUnit.LB) 0.5f else 0.25f  // Match other weight selectors

                                SliderWithButtons(
                                    value = editedWeight,
                                    onValueChange = { newWeight ->
                                        editedWeight = newWeight.coerceIn(0f, maxWeight)
                                        onUpdateWeight?.invoke(editedWeight)
                                    },
                                    valueRange = 0f..maxWeight,
                                    step = weightStep,
                                    label = "Weight per cable",
                                    formatValue = { formatWeightWithUnit(it, weightUnit) }
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
            Spacer(modifier = Modifier.height(Spacing.small))

            // Action buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Skip Rest button (primary action)
                Button(
                    onClick = onSkipRest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Skip rest",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        text = if (isLastExercise) "Continue" else "Skip Rest",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // End Workout button (secondary/destructive action)
                TextButton(
                    onClick = onEndWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(20.dp)
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Formats rest time in seconds to MM:SS format
 */
private fun formatRestTime(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
}

@Composable
fun WorkoutParamItem(
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
            contentDescription = "Rest timer status",
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

/**
 * Echo Level selector for Rest Timer - Row of 4 buttons (Hard/Harder/Hardest/Epic)
 */
@Composable
private fun RestTimerEchoLevelSelector(
    selectedLevel: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit
) {
    Column {
        Text(
            text = "ECHO LEVEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
        ) {
            EchoLevel.entries.forEach { level ->
                val isSelected = level == selectedLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onLevelChange(level) }
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Eccentric Load slider for Rest Timer (100-150%)
 */
@Composable
private fun RestTimerEccentricLoadSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ECCENTRIC LOAD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        // Fine-grained slider (5% increments) - callback snaps to nearest valid EccentricLoad enum
        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth()
        )
    }
}
