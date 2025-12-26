package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Mode Confirmation Screen
 *
 * Shows users the exercises in a template with their suggested modes.
 * Users can adjust modes before the cycle is created.
 *
 * @param template The cycle template being configured
 * @param onConfirm Callback with exercise name to mode mapping when user confirms
 * @param onCancel Callback when user cancels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeConfirmationScreen(
    template: CycleTemplate,
    onConfirm: (Map<String, ProgramMode>) -> Unit,
    onCancel: () -> Unit
) {
    // State: Map of exercise name to selected ProgramMode
    // Note: Bodyweight exercises (null suggestedMode) are excluded - they don't use cables
    val modeSelections = remember {
        mutableStateMapOf<String, ProgramMode>().apply {
            // Initialize with suggested modes from template (skip bodyweight exercises)
            template.days.forEach { day ->
                day.routine?.exercises?.forEach { exercise ->
                    exercise.suggestedMode?.let { mode ->
                        put(exercise.exerciseName, mode)
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
                        onClick = { onConfirm(modeSelections.toMap()) },
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
                        ExerciseModeCard(
                            exercise = exercise,
                            // suggestedMode is guaranteed non-null due to filter above
                            selectedMode = modeSelections[exercise.exerciseName] ?: exercise.suggestedMode!!,
                            onModeChanged = { newMode ->
                                modeSelections[exercise.exerciseName] = newMode
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
 * Card showing a single exercise with mode selection dropdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseModeCard(
    exercise: TemplateExercise,
    selectedMode: ProgramMode,
    onModeChanged: (ProgramMode) -> Unit
) {
    var showModeDropdown by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            // Exercise name
            Text(
                text = exercise.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Sets x Reps info
            val setsRepsText = if (exercise.isPercentageBased) {
                "${exercise.percentageSets?.size ?: exercise.sets} sets (5/3/1 progression)"
            } else {
                "${exercise.sets} x ${exercise.reps ?: "AMRAP"}"
            }

            Text(
                text = setsRepsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.extraSmall))

            // Mode dropdown
            Text(
                text = "Workout Mode",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Box {
                OutlinedTextField(
                    value = getProgramModeDisplayName(selectedMode),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            "Select mode",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                // Transparent clickable overlay
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showModeDropdown = true }
                )

                DropdownMenu(
                    expanded = showModeDropdown,
                    onDismissRequest = { showModeDropdown = false }
                ) {
                    // Available program modes
                    listOf(
                        ProgramMode.OldSchool,
                        ProgramMode.Pump,
                        ProgramMode.TUT,
                        ProgramMode.TUTBeast,
                        ProgramMode.EccentricOnly
                    ).forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        getProgramModeDisplayName(mode),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        getProgramModeDescription(mode),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onModeChanged(mode)
                                showModeDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Get display name for ProgramMode
 */
private fun getProgramModeDisplayName(mode: ProgramMode): String {
    return mode.displayName
}

/**
 * Get description for ProgramMode
 */
private fun getProgramModeDescription(mode: ProgramMode): String {
    return when (mode) {
        is ProgramMode.OldSchool -> "Classic resistance training"
        is ProgramMode.Pump -> "High volume, shorter ROM"
        is ProgramMode.TUT -> "Time under tension focused"
        is ProgramMode.TUTBeast -> "Extended time under tension"
        is ProgramMode.EccentricOnly -> "Eccentric-only training"
    }
}
