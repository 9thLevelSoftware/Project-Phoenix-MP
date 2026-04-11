package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.viewmodel.ExerciseConfigViewModel
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Bottom sheet for editing exercise configuration in a routine.
 * Allows configuration of sets, reps, weight, rest time, and mode-specific settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditBottomSheet(
    exercise: RoutineExercise,
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
    formatWeight: (Float, WeightUnit) -> String,
    onSave: (RoutineExercise) -> Unit,
    onDismiss: () -> Unit,
    buttonText: String = "Save",
    viewModel: ExerciseConfigViewModel,
) {
    // State for editable fields
    var setReps by remember { mutableStateOf(exercise.setReps.toMutableList()) }
    var weightPerCable by remember {
        mutableStateOf(kgToDisplay(exercise.weightPerCableKg, weightUnit))
    }
    var restSeconds by remember { mutableStateOf(exercise.setRestSeconds.firstOrNull() ?: 60) }
    var selectedEccentricLoad by remember { mutableStateOf(exercise.eccentricLoad) }
    var selectedEchoLevel by remember { mutableStateOf(exercise.echoLevel) }
    var isAMRAP by remember { mutableStateOf(exercise.isAMRAP) }

    // Determine if Echo mode settings should be shown
    val isEchoMode = exercise.programMode == ProgramMode.Echo

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = "Configure Exercise",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = exercise.exercise.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            HorizontalDivider()

            // Sets and Reps Section
            Text(
                text = "Sets & Reps",
                style = MaterialTheme.typography.titleSmall,
            )

            // Number of sets control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.label_sets), modifier = Modifier.weight(0.3f))
                Row(
                    modifier = Modifier.weight(0.7f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (setReps.size > 1) {
                                setReps = setReps.dropLast(1).toMutableList()
                            }
                        },
                        enabled = setReps.size > 1,
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = stringResource(Res.string.cd_remove_set),
                        )
                    }
                    Text(
                        text = "${setReps.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(
                        onClick = {
                            if (setReps.size < 10) {
                                setReps =
                                    (setReps + listOf(setReps.lastOrNull() ?: 10)).toMutableList()
                            }
                        },
                        enabled = setReps.size < 10,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(Res.string.cd_add_set),
                        )
                    }
                }
            }

            // Reps per set
            setReps.forEachIndexed { index, reps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Set ${index + 1}:",
                        modifier = Modifier.weight(0.3f),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (isAMRAP && index == setReps.lastIndex) {
                        Text(
                            "AMRAP",
                            modifier = Modifier.weight(0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        OutlinedTextField(
                            value = reps?.toString() ?: "",
                            onValueChange = { newValue ->
                                val newReps = newValue.toIntOrNull()
                                setReps = setReps.toMutableList().apply {
                                    this[index] = newReps
                                }
                            },
                            label = { Text(stringResource(Res.string.label_reps)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.7f),
                            singleLine = true,
                        )
                    }
                }
            }

            // AMRAP toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Last set AMRAP",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isAMRAP,
                    onCheckedChange = { isAMRAP = it },
                )
            }

            HorizontalDivider()

            // Weight Section
            Text(
                text = "Weight",
                style = MaterialTheme.typography.titleSmall,
            )

            OutlinedTextField(
                value = weightPerCable.toString(),
                onValueChange = { newValue ->
                    newValue.toFloatOrNull()?.let { weightPerCable = it }
                },
                label = {
                    val unitLabel = if (weightUnit ==
                        WeightUnit.KG
                    ) {
                        stringResource(Res.string.label_kg)
                    } else {
                        stringResource(Res.string.label_lbs)
                    }
                    Text(stringResource(Res.string.weight_per_cable, unitLabel))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // Rest Time
            Text(
                text = "Rest Time",
                style = MaterialTheme.typography.titleSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = restSeconds.toString(),
                    onValueChange = { newValue ->
                        newValue.toIntOrNull()?.let { restSeconds = it.coerceIn(0, 300) }
                    },
                    label = { Text(stringResource(Res.string.label_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.35f),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Quick select buttons
                Row(
                    modifier = Modifier.weight(0.65f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(30, 60, 90, 120).forEach { seconds ->
                        FilterChip(
                            selected = restSeconds == seconds,
                            onClick = { restSeconds = seconds },
                            label = { Text(stringResource(Res.string.seconds_label, seconds)) },
                        )
                    }
                }
            }

            // Echo Mode Settings (only show if Echo mode)
            if (isEchoMode) {
                HorizontalDivider()

                Text(
                    text = "Echo Mode Settings",
                    style = MaterialTheme.typography.titleSmall,
                )

                // Eccentric Load
                Text(
                    text = "Eccentric Load",
                    style = MaterialTheme.typography.bodyMedium,
                )

                var eccentricLoadExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = eccentricLoadExpanded,
                    onExpandedChange = { eccentricLoadExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedEccentricLoad.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = eccentricLoadExpanded,
                            )
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = eccentricLoadExpanded,
                        onDismissRequest = { eccentricLoadExpanded = false },
                    ) {
                        EccentricLoad.entries.forEach { load ->
                            DropdownMenuItem(
                                text = { Text(load.displayName) },
                                onClick = {
                                    selectedEccentricLoad = load
                                    eccentricLoadExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Echo Level
                Text(
                    text = "Echo Level",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EchoLevel.entries.forEach { level ->
                        FilterChip(
                            selected = selectedEchoLevel == level,
                            onClick = { selectedEchoLevel = level },
                            label = { Text(level.displayName) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }

                Button(
                    onClick = {
                        val updatedExercise = exercise.copy(
                            setReps = setReps.toList(),
                            weightPerCableKg = displayToKg(weightPerCable, weightUnit),
                            setRestSeconds = listOf(restSeconds),
                            eccentricLoad = selectedEccentricLoad,
                            echoLevel = selectedEchoLevel,
                            isAMRAP = isAMRAP,
                        )
                        onSave(updatedExercise)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(buttonText)
                }
            }
        }
    }
}
