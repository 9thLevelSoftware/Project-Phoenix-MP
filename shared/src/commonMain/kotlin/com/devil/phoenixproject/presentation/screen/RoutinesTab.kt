package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * Routines tab showing list of saved routines with create/edit/delete functionality.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RoutinesTab(
    routines: List<Routine>,
    exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
    formatWeight: (Float, WeightUnit) -> String,
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    onStartWorkout: (Routine) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onDeleteRoutines: (Set<String>) -> Unit,  // Batch delete for multi-select
    onSaveRoutine: (Routine) -> Unit,
    // onUpdateRoutine removed as it is replaced by Editor Screen
    onEditRoutine: (String) -> Unit,
    onCreateRoutine: () -> Unit,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier
) {
    // showRoutineBuilder and routineToEdit states removed

    Logger.d { "RoutinesTab: ${routines.size} routines loaded" }

    // Selection mode state
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateSetOf<String>() }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCopyDialog by remember { mutableStateOf(false) }

    // Helper to clear selection
    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
    }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .pointerInput(selectionMode) {
                if (selectionMode) {
                    detectTapGestures {
                        clearSelection()
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            if (routines.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.FitnessCenter,
                    title = "No Routines Yet",
                    message = "Create your first workout routine to get started",
                    actionText = "Create Your First Routine",
                    onAction = {
                        onCreateRoutine()
                    }
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    contentPadding = PaddingValues(bottom = 80.dp) // Space for FAB
                ) {
                    items(routines, key = { it.id }) { routine ->
                        RoutineCard(
                            routine = routine,
                            isSelectionMode = selectionMode,
                            isSelected = selectedIds.contains(routine.id),
                            onLongPress = {
                                selectionMode = true
                                selectedIds.add(routine.id)
                            },
                            onSelectionToggle = {
                                if (selectedIds.contains(routine.id)) {
                                    selectedIds.remove(routine.id)
                                    // Exit selection mode if nothing selected
                                    if (selectedIds.isEmpty()) {
                                        selectionMode = false
                                    }
                                } else {
                                    selectedIds.add(routine.id)
                                }
                            },
                            onStartWorkout = { onStartWorkout(routine) },
                            onEdit = { onEditRoutine(routine.id) },
                            onDelete = { onDeleteRoutine(routine.id) },
                            onDuplicate = {
                                // Generate new IDs explicitly and create deep copies
                                val newRoutineId = generateUUID()
                                val newExercises = routine.exercises.map { exercise ->
                                    Logger.d { "Duplicating exercise '${exercise.exercise.name}': setReps=${exercise.setReps}" }
                                    val copied = exercise.copy(
                                        id = generateUUID(),
                                        exercise = exercise.exercise.copy()
                                    )
                                    copied
                                }

                                // Smart duplicate naming: extract base name and find next copy number
                                val baseName = routine.name.replace(Regex(""" \(Copy( \d+)?\)$"""), "")
                                val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
                                val existingCopyNumbers = routines
                                    .mapNotNull { r ->
                                        when {
                                            r.name == baseName -> 0 // Original has number 0
                                            r.name == "$baseName (Copy)" -> 1 // First copy is 1
                                            else -> copyPattern.find(r.name)?.groups?.get(2)?.value?.toIntOrNull()
                                        }
                                    }
                                val nextCopyNumber = (existingCopyNumbers.maxOrNull() ?: 0) + 1
                                val newName = if (nextCopyNumber == 1) {
                                    "$baseName (Copy)"
                                } else {
                                    "$baseName (Copy $nextCopyNumber)"
                                }

                                val duplicated = routine.copy(
                                    id = newRoutineId,
                                    name = newName,
                                    createdAt = KmpUtils.currentTimeMillis(),
                                    useCount = 0,
                                    lastUsed = null,
                                    exercises = newExercises
                                )
                                onSaveRoutine(duplicated)
                            }
                        )
                    }
                }
            }
        }

        // FAB Area - transforms based on selection mode
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.medium)
        ) {
            // Normal mode: Single + FAB
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = onCreateRoutine,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add new routine",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Selection mode: Vertical stack of action buttons
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // Cancel button (small)
                    SmallFloatingActionButton(
                        onClick = { clearSelection() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }

                    // Copy button
                    FloatingActionButton(
                        onClick = { showBatchCopyDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("${selectedIds.size}")
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected")
                        }
                    }

                    // Delete button (red)
                    FloatingActionButton(
                        onClick = { showBatchDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    ) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("${selectedIds.size}")
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    }
                }
            }
        }
    }

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete ${selectedIds.size} routines?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRoutines(selectedIds.toSet())
                        showBatchDeleteDialog = false
                        clearSelection()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Batch Copy Confirmation Dialog
    if (showBatchCopyDialog) {
        AlertDialog(
            onDismissRequest = { showBatchCopyDialog = false },
            title = { Text("Duplicate ${selectedIds.size} routines?") },
            text = { Text("New copies will be created with '(Copy)' suffix.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Copy each selected routine using existing duplicate logic
                        selectedIds.forEach { routineId ->
                            routines.find { it.id == routineId }?.let { routine ->
                                val newRoutineId = generateUUID()
                                val newExercises = routine.exercises.map { exercise ->
                                    exercise.copy(
                                        id = generateUUID(),
                                        exercise = exercise.exercise.copy()
                                    )
                                }

                                // Smart duplicate naming
                                val baseName = routine.name.replace(Regex(""" \(Copy( \d+)?\)$"""), "")
                                val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
                                val existingCopyNumbers = routines
                                    .mapNotNull { r ->
                                        when {
                                            r.name == baseName -> 0
                                            r.name == "$baseName (Copy)" -> 1
                                            else -> copyPattern.find(r.name)?.groups?.get(2)?.value?.toIntOrNull()
                                        }
                                    }
                                val nextCopyNumber = (existingCopyNumbers.maxOrNull() ?: 0) + 1
                                val newName = if (nextCopyNumber == 1) {
                                    "$baseName (Copy)"
                                } else {
                                    "$baseName (Copy $nextCopyNumber)"
                                }

                                val duplicated = routine.copy(
                                    id = newRoutineId,
                                    name = newName,
                                    createdAt = KmpUtils.currentTimeMillis(),
                                    useCount = 0,
                                    lastUsed = null,
                                    exercises = newExercises
                                )
                                onSaveRoutine(duplicated)
                            }
                        }
                        showBatchCopyDialog = false
                        clearSelection()
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchCopyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Card displaying a single routine with expandable details.
 * Supports multi-select mode with checkbox and long-press gestures.
 */
@Composable
fun RoutineCard(
    routine: Routine,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onSelectionToggle: () -> Unit,
    onStartWorkout: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    @OptIn(ExperimentalFoundationApi::class)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (isSelectionMode) onSelectionToggle()
                    else expanded = !expanded
                },
                onLongClick = {
                    if (!isSelectionMode) onLongPress()
                    else onSelectionToggle()
                }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (expanded) 8.dp else 2.dp
        ),
        border = BorderStroke(
            2.dp,
            if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox - only visible in selection mode
                AnimatedVisibility(visible = isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectionToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                // Header Content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = routine.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${routine.exercises.size} exercises • ${formatEstimatedDuration(routine)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Expand Icon - hide in selection mode
                if (!isSelectionMode) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded Content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Exercise List
                    routine.exercises.forEachIndexed { index, exercise ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${index + 1}. ${exercise.exercise.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatSetRepsForCard(exercise.setReps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action Buttons
                    Button(
                        onClick = onStartWorkout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Start Workout",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Edit", maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onDuplicate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Routine") },
            text = { Text("Are you sure you want to delete '${routine.name}'?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Format set/reps for display in routine card.
 */
private fun formatSetRepsForCard(setReps: List<Int?>): String {
    if (setReps.isEmpty()) return "0 sets"

    // Group consecutive identical reps
    val groups = mutableListOf<Pair<Int, String>>()
    var currentReps = setReps[0]
    var currentCount = 1

    for (i in 1 until setReps.size) {
        if (setReps[i] == currentReps) {
            currentCount++
        } else {
            groups.add(Pair(currentCount, currentReps?.toString() ?: "AMRAP"))
            currentReps = setReps[i]
            currentCount = 1
        }
    }
    groups.add(Pair(currentCount, currentReps?.toString() ?: "AMRAP"))

    // Format as "3x10, 2x8" or "3xAMRAP"
    return groups.joinToString(", ") { (count, reps) -> "${count}x${reps}" }
}

/**
 * Estimate workout duration based on reps and rest times.
 */
private fun formatEstimatedDuration(routine: Routine): String {
    val totalReps = routine.exercises.sumOf { exercise ->
        exercise.setReps.filterNotNull().sum()
    }
    val totalRestSeconds = routine.exercises.sumOf { exercise ->
        val restCount = maxOf(0, exercise.setReps.size - 1)
        exercise.setRestSeconds.take(restCount).sum()
    }

    val estimatedSeconds = (totalReps * 3) + totalRestSeconds // 3 seconds per rep estimate
    val minutes = estimatedSeconds / 60

    return if (minutes < 60) {
        "${minutes} min"
    } else {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        "${hours}h ${remainingMinutes}m"
    }
}
