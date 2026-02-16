package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import com.devil.phoenixproject.ui.theme.Spacing

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
    onDismiss: () -> Unit
) {
    // State for exercise selection
    var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
    var showExercisePicker by remember { mutableStateOf(false) }

    // State for mode selection
    var showModeMenu by remember { mutableStateOf(false) }
    var showModeSubSelector by remember { mutableStateOf(false) }
    var modeSubSelectorType by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workoutParameters.selectedExerciseId) {
        workoutParameters.selectedExerciseId?.let { id ->
            exerciseRepository.getExerciseById(id).also { selectedExercise = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                "Workout Setup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Exercise Selection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Exercise",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showExercisePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedExercise?.name ?: "Select Exercise")
                        }
                    }
                }

                // Mode Selection
                val modeLabel = if (workoutParameters.isJustLift) "Base Mode (resistance profile)" else "Workout Mode"
                ExposedDropdownMenuBox(
                    expanded = showModeMenu,
                    onExpandedChange = { showModeMenu = !showModeMenu }
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
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    ExposedDropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Old School") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.OldSchool))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pump") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.Pump))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Eccentric Only") },
                            onClick = {
                                onUpdateParameters(workoutParameters.copy(programMode = ProgramMode.EccentricOnly))
                                showModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Echo Mode")
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Navigate")
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "Echo"
                                showModeSubSelector = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("TUT")
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Navigate")
                                }
                            },
                            onClick = {
                                showModeMenu = false
                                modeSubSelectorType = "TUT"
                                showModeSubSelector = true
                            }
                        )
                    }
                }

                // Weight Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (workoutParameters.isEchoMode) {
                            Text(
                                "Weight per cable",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Adaptive",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Echo mode adapts weight to your output",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val weightRange = if (weightUnit == WeightUnit.LB) 1..220 else 1..100
                            Text(
                                "Weight per cable (${weightUnit.name.lowercase()})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val currentWeightDisplay = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit).toInt()
                            Text(
                                "$currentWeightDisplay ${weightUnit.name.lowercase()}",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Slider(
                                value = kgToDisplay(workoutParameters.weightPerCableKg, weightUnit),
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(weightPerCableKg = kg))
                                },
                                valueRange = weightRange.first.toFloat()..weightRange.last.toFloat(),
                                steps = weightRange.last - weightRange.first - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Reps Picker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (!workoutParameters.isJustLift) {
                            Text(
                                "Target reps: ${workoutParameters.reps}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Slider(
                                value = workoutParameters.reps.toFloat(),
                                onValueChange = { reps ->
                                    onUpdateParameters(workoutParameters.copy(reps = reps.toInt()))
                                },
                                valueRange = 1f..50f,
                                steps = 49,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "Target reps",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "N/A",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Just Lift mode doesn't use target reps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                "Progression/Regression",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
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
                                }
                            )

                            Slider(
                                value = currentProgression,
                                onValueChange = { displayValue ->
                                    val kg = displayToKg(displayValue, weightUnit)
                                    onUpdateParameters(workoutParameters.copy(progressionRegressionKg = kg))
                                },
                                valueRange = -maxProgression..maxProgression,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                "Negative = Regression, Positive = Progression",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }

                // Just Lift Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Just Lift")
                    Switch(
                        checked = workoutParameters.isJustLift,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isJustLift = checked))
                        }
                    )
                }

                // Finish At Top Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Finish At Top")
                    Switch(
                        checked = workoutParameters.stopAtTop,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(stopAtTop = checked))
                        },
                        enabled = !workoutParameters.isJustLift
                    )
                }

                // AMRAP Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AMRAP Mode")
                        Text(
                            "As Many Reps As Possible",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = workoutParameters.isAMRAP,
                        onCheckedChange = { checked ->
                            onUpdateParameters(workoutParameters.copy(isAMRAP = checked))
                        },
                        enabled = !workoutParameters.isJustLift
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStartWorkout,
                enabled = selectedExercise != null
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start workout")
                Spacer(modifier = Modifier.width(Spacing.small))
                Text("Start Workout")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Exercise Picker Dialog
    if (showExercisePicker) {
        ExercisePickerDialog(
            exerciseRepository = exerciseRepository,
            onDismiss = { showExercisePicker = false },
            onExerciseSelected = { exercise ->
                onUpdateParameters(workoutParameters.copy(selectedExerciseId = exercise.id))
                showExercisePicker = false
            }
        )
    }

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
                onUpdateParameters(workoutParameters.copy(
                    programMode = newProgramMode,
                    echoLevel = newEchoLevel,
                    eccentricLoad = newEccentricLoad
                ))
                showModeSubSelector = false
            }
        )
    }
}

/**
 * Exercise Picker Dialog - Allows selecting an exercise from the library
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerDialog(
    exerciseRepository: ExerciseRepository,
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit
) {
    val exercises by exerciseRepository.getAllExercises().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ExerciseCategory?>(null) }

    // Filter exercises
    val filteredExercises = exercises.filter { exercise ->
        val matchesSearch = searchQuery.isEmpty() ||
            exercise.name.contains(searchQuery, ignoreCase = true)
        val matchesCategory = selectedCategory == null ||
            exercise.muscleGroup.equals(selectedCategory?.displayName, ignoreCase = true)
        matchesSearch && matchesCategory
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                "Select Exercise",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            BoxWithConstraints {
                val maxSheetHeight = (maxHeight * 0.8f).coerceIn(300.dp, 600.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                ) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search exercises") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Muscle group filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") }
                    )
                    ExerciseCategory.entries.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Exercise list
                if (filteredExercises.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No exercises found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
                    ) {
                        filteredExercises.forEach { exercise ->
                            Card(
                                onClick = { onExerciseSelected(exercise) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
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
                                            exercise.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            exercise.muscleGroup,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Select",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
