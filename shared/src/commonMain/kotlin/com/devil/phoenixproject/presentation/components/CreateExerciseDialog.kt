package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCategory
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Dialog for creating or editing custom exercises.
 * Users can specify:
 * - Exercise name (required)
 * - Muscle group (required)
 * - Equipment (optional)
 * - Cable configuration (defaults to DOUBLE)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExerciseDialog(
    existingExercise: Exercise? = null,
    onSave: (Exercise) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    themeMode: ThemeMode
) {
    var name by remember { mutableStateOf(existingExercise?.name ?: "") }
    var selectedMuscleGroup by remember {
        mutableStateOf(
            existingExercise?.muscleGroup?.let { mg ->
                ExerciseCategory.entries.find { it.displayName.equals(mg, ignoreCase = true) }
            } ?: ExerciseCategory.CHEST
        )
    }
    var equipment by remember { mutableStateOf(existingExercise?.equipment ?: "") }

    var showMuscleGroupDropdown by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val backgroundGradient = screenBackgroundBrush()

    val isEditMode = existingExercise != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(modifier = Modifier.fillMaxWidth().background(backgroundGradient)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEditMode) "Edit Exercise" else "Create Exercise",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Exercise Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; showError = false },
                        label = { Text("Exercise Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = showError && name.isBlank(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    if (showError && name.isBlank()) {
                        Text(
                            "Exercise name is required",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = Spacing.medium, top = Spacing.extraSmall)
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Muscle Group Dropdown
                    Text(
                        "Muscle Group *",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))

                    Box {
                        OutlinedTextField(
                            value = selectedMuscleGroup.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    "Select muscle group",
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
                                .clickable { showMuscleGroupDropdown = true }
                        )

                        DropdownMenu(
                            expanded = showMuscleGroupDropdown,
                            onDismissRequest = { showMuscleGroupDropdown = false }
                        ) {
                            ExerciseCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.displayName) },
                                    onClick = {
                                        selectedMuscleGroup = category
                                        showMuscleGroupDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Equipment (Optional)
                    OutlinedTextField(
                        value = equipment,
                        onValueChange = { equipment = it },
                        label = { Text("Equipment (optional)") },
                        placeholder = { Text("e.g., Barbell, Cable Handle") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(Spacing.large))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        // Delete button (only for edit mode)
                        if (isEditMode && onDelete != null) {
                            OutlinedButton(
                                onClick = { showDeleteConfirmation = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Delete")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text("Cancel")
                            }
                        }

                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    showError = true
                                } else {
                                    val exercise = Exercise(
                                        id = existingExercise?.id,
                                        name = name.trim(),
                                        muscleGroup = selectedMuscleGroup.displayName,
                                        muscleGroups = selectedMuscleGroup.displayName,
                                        equipment = equipment.trim(),
                                        isFavorite = existingExercise?.isFavorite ?: false,
                                        isCustom = true
                                    )
                                    onSave(exercise)
                                }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                if (isEditMode) "Save" else "Create",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Exercise?") },
            text = {
                Text("Are you sure you want to delete \"$name\"? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
