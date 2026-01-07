package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.presentation.components.ExerciseConfigModal
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Mode Confirmation Screen
 *
 * Shows users the exercises in a template with their suggested modes.
 * Users can tap exercise cards to open a modal and configure mode + settings.
 *
 * @param template The cycle template being configured
 * @param oneRepMaxValues Map of exercise name to 1RM value in kg (if available)
 * @param onConfirm Callback with exercise name to ExerciseConfig mapping when user confirms
 * @param onCancel Callback when user cancels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeConfirmationScreen(
    template: CycleTemplate,
    oneRepMaxValues: Map<String, Float> = emptyMap(),
    onConfirm: (Map<String, ExerciseConfig>) -> Unit,
    onCancel: () -> Unit
) {
    // State: Map of exercise name to ExerciseConfig
    // Note: Bodyweight exercises (null suggestedMode) are excluded - they don't use cables
    val exerciseConfigs = remember {
        mutableStateMapOf<String, ExerciseConfig>().apply {
            // Initialize with ExerciseConfig from template (skip bodyweight exercises)
            template.days.forEach { day ->
                day.routine?.exercises?.forEach { exercise ->
                    exercise.suggestedMode?.let { mode ->
                        put(exercise.exerciseName, ExerciseConfig.fromTemplate(
                            exerciseName = exercise.exerciseName,
                            suggestedMode = mode,
                            oneRepMaxKg = oneRepMaxValues[exercise.exerciseName]
                        ))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Confirm Modes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onConfirm(exerciseConfigs.toMap()) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Create Cycle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium)
        ) {
            // Template name and description header
            item(key = "header") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                ) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = "Review and adjust workout modes for each exercise:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Group exercises by day
            template.days.forEach { day ->
                // Day header
                item(key = "day_header_${day.dayNumber}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium)
                        ) {
                            Text(
                                text = day.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            if (day.isRestDay || day.routine == null) {
                                Text(
                                    text = "Rest Day",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            } else {
                                Text(
                                    text = day.routine.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // Exercise list for this day (excluding bodyweight exercises)
                if (!day.isRestDay && day.routine != null) {
                    // Filter to only cable exercises (those with a suggested mode)
                    val cableExercises = day.routine.exercises.filter { it.suggestedMode != null }
                    items(
                        items = cableExercises,
                        key = { exercise -> "${day.dayNumber}_${exercise.exerciseName}" }
                    ) { exercise ->
                        ConfigurableExerciseCard(
                            exercise = exercise,
                            config = exerciseConfigs[exercise.exerciseName] ?: ExerciseConfig.fromTemplate(
                                exerciseName = exercise.exerciseName,
                                suggestedMode = exercise.suggestedMode,
                                oneRepMaxKg = oneRepMaxValues[exercise.exerciseName]
                            ),
                            oneRepMaxKg = oneRepMaxValues[exercise.exerciseName],
                            onConfigUpdated = { newConfig ->
                                exerciseConfigs[exercise.exerciseName] = newConfig
                            }
                        )
                    }
                }
            }

            // Bottom spacer for better scroll experience
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(Spacing.medium))
            }
        }
    }
}

/**
 * Tappable card showing exercise info with mode and weight badges.
 * Tapping opens the ExerciseConfigModal for detailed configuration.
 */
@Composable
private fun ConfigurableExerciseCard(
    exercise: TemplateExercise,
    config: ExerciseConfig,
    oneRepMaxKg: Float?,
    onConfigUpdated: (ExerciseConfig) -> Unit
) {
    var showConfigModal by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showConfigModal = true },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Sets x Reps info
                val setsRepsText = if (exercise.isPercentageBased) {
                    "${exercise.percentageSets?.size ?: exercise.sets} sets (5/3/1)"
                } else {
                    "${exercise.sets} x ${exercise.reps ?: "AMRAP"}"
                }

                Text(
                    text = setsRepsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Mode + Weight badges
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                // Mode badge
                Surface(
                    shape = RoundedCornerShape(Spacing.small),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = getModeAbbreviation(config.mode),
                        modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Weight badge (if configured)
                if (config.weightPerCableKg > 0f) {
                    Surface(
                        shape = RoundedCornerShape(Spacing.small),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "${config.weightPerCableKg.toInt()}kg",
                            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }

    // Show config modal when tapped
    if (showConfigModal) {
        ExerciseConfigModal(
            exerciseName = exercise.exerciseName,
            templateSets = exercise.sets,
            templateReps = exercise.reps,
            oneRepMaxKg = oneRepMaxKg,
            initialConfig = config,
            onConfirm = { newConfig ->
                onConfigUpdated(newConfig)
                showConfigModal = false
            },
            onDismiss = { showConfigModal = false }
        )
    }
}

/**
 * Get short abbreviation for ProgramMode for badge display.
 */
private fun getModeAbbreviation(mode: ProgramMode): String = when (mode) {
    ProgramMode.OldSchool -> "OLD"
    ProgramMode.TUT -> "TUT"
    ProgramMode.Pump -> "PUMP"
    ProgramMode.EccentricOnly -> "ECC"
    ProgramMode.TUTBeast -> "BEAST"
    ProgramMode.Echo -> "ECHO"
}
