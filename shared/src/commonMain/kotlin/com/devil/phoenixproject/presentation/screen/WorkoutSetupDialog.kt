package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ConfirmEditTextField
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

internal const val WorkoutSetupTargetRepsRemoteStep = 1f

/**
 * Workout Setup Dialog - Full configuration dialog for workout parameters
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSetupDialog(
    workoutParameters: WorkoutParameters,
    weightUnit: WeightUnit,
    exerciseRepository: ExerciseRepository,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onUpdateParameters: (WorkoutParameters) -> Unit,
    onStartWorkout: () -> Unit,
    onDismiss: () -> Unit,
) {
    // State for exercise selection
    var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
    var showExercisePicker by remember { mutableStateOf(false) }

    // State for mode selection
    var showModeMenu by remember { mutableStateOf(false) }
    var showModeSubSelector by remember { mutableStateOf(false) }
    var modeSubSelectorType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workoutParameters.selectedExerciseId) {
        val id = workoutParameters.selectedExerciseId
        selectedExercise = if (id != null) exerciseRepository.getExerciseById(id) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                "Workout Setup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                // Exercise Selection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Exercise",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showExercisePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedExercise?.name ?: "Select Exercise")
                        }
                    }
                }

                // Mode Selection
                val modeLabel = if (workoutParameters.isJustLift) "Base Mode (resistance profile)" else "Workout Mode"
                ExposedDropdownMenuBox(
                    expanded = showModeMenu,
                    onExpandedChange = { showModeMenu = !showModeMenu },
                ) {
                    OutlinedTextField(
                        value = workoutParameters.programMode.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(modeLabel) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModeMenu)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        colors = OutlinedTextFieldDefaults.colors(),
                    )
                    ExposedDropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.mode_old_school)) },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.OldSchool))
                                showModeMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.mode_pump)) },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.Pump))
                                showModeMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.mode_eccentric_only)) },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.EccentricOnly))
                                showModeMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.echo_mode))
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(Res.string.cd_navigate),
                                    )
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "Echo"
                                showModeSubSelector = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.mode_tut))
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(Res.string.cd_navigate),
                                    )
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "TUT"
                                showModeSubSelector = true
                            },
                        )
                    }
                }

                // Weight Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        if (workoutParameters.isEchoMode) {
                            Text(
                                "Weight per cable",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Adaptive",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Echo mode adapts weight to your output",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val weightRange = if (weightUnit == WeightUnit.LB) 1..220 else 1..100
                            Text(
                                "Weight per cable (${weightUnit.name.lowercase()})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val currentWeightDisplay = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit).toInt()
                            Text(
                                "$currentWeightDisplay ${weightUnit.name.lowercase()}",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            ExpressiveSlider(
                                // F043: weightPerCableKg defaults to 0, but the slider
                                // range starts at 1; feeding 0 violates Material's
                                // value-range requirement and can crash/render invalid.
                                // Clamp the displayed value into the range.
                                value = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit)
                                    .coerceIn(weightRange.first.toFloat(), weightRange.last.toFloat()),
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(weightPerCableKg = kg))
                                },
                                valueRange = weightRange.first.toFloat()..weightRange.last.toFloat(),
                                steps = weightRange.last - weightRange.first - 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Reps Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        if (!workoutParameters.isJustLift) {
                            Text(
                                "Target reps: ${workoutParameters.reps}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            ExpressiveSlider(
                                value = workoutParameters.reps.toFloat(),
                                onValueChange = { reps ->
                                    onUpdateParameters(workoutParameters.copy(reps = reps.toInt()))
                                },
                                valueRange = 1f..50f,
                                steps = 49,
                                remoteStep = WorkoutSetupTargetRepsRemoteStep,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                "Target reps",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "N/A",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Just Lift mode doesn't use target reps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Progression/Regression UI (only for certain modes - not Echo)
                val currentProgramMode = workoutParameters.programMode
                val showProgressionUI = currentProgramMode == ProgramMode.Pump ||
                    currentProgramMode == ProgramMode.OldSchool ||
                    currentProgramMode == ProgramMode.EccentricOnly ||
                    currentProgramMode == ProgramMode.TUT ||
                    currentProgramMode == ProgramMode.TUTBeast
                if (showProgressionUI) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                "Progression/Regression",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val maxProgression = if (weightUnit == WeightUnit.LB) 6f else 3f
                            val currentProgression = kgToDisplay(workoutParameters.progressionRegressionKg, weightUnit)

                            Text(
                                "${formatFloat(currentProgression, 1)} ${weightUnit.name.lowercase()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = when {
                                    currentProgression > 0 -> MaterialTheme.colorScheme.primary
                                    currentProgression < 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )

                            ExpressiveSlider(
                                value = currentProgression,
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(progressionRegressionKg = kg))
                                },
                                valueRange = -maxProgression..maxProgression,
                                remoteStep = 0.1f,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                "Negative = Regression, Positive = Progression",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }

                // Just Lift Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.just_lift))
                    Switch(
                        checked = workoutParameters.isJustLift,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isJustLift = checked))
                        },
                    )
                }

                // Finish At Top Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.finish_at_top))
                    Switch(
                        checked = workoutParameters.stopAtTop,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(stopAtTop = checked))
                        },
                        enabled = !workoutParameters.isJustLift,
                    )
                }

                // AMRAP Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(Res.string.amrap_mode))
                        Text(
                            "As Many Reps As Possible",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = workoutParameters.isAMRAP,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isAMRAP = checked))
                        },
                        enabled = !workoutParameters.isJustLift,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStartWorkout,
                enabled = selectedExercise != null,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(Res.string.cd_start_workout))
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(stringResource(Res.string.start_workout))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )

    // Exercise Picker Dialog — uses canonical ExercisePickerContent via components version
    ExercisePickerDialog(
        showDialog = showExercisePicker,
        exerciseRepository = exerciseRepository,
        onDismiss = { showExercisePicker = false },
        onExerciseSelected = { exercise ->
            onUpdateParameters(workoutParameters.copy(selectedExerciseId = exercise.id))
            showExercisePicker = false
        },
        enableVideoPlayback = false,
    )

    // Mode Sub-Selector Dialog
    if (showModeSubSelector && modeSubSelectorType != null) {
        ModeSubSelectorDialog(
            type = modeSubSelectorType!!,
            workoutParameters = workoutParameters,
            onDismiss = { showModeSubSelector = false },
            onSelect = { mode, eccentricLoad ->
                val newProgramMode = mode.toProgramMode()
                val newEchoLevel = if (mode is WorkoutMode.Echo) mode.level else workoutParameters.echoLevel
                val newEccentricLoad = eccentricLoad ?: workoutParameters.eccentricLoad
                onUpdateParameters(
                    workoutParameters.copy(
                        programMode = newProgramMode,
                        echoLevel = newEchoLevel,
                        eccentricLoad = newEccentricLoad,
                    ),
                )
                showModeSubSelector = false
            },
        )
    }
}
