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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.generateSupersetId
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.ProfileColors
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

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
    onDeleteRoutines: (Set<String>) -> Unit, // Batch delete for multi-select
    onSaveRoutine: (Routine) -> Unit,
    // onUpdateRoutine removed as it is replaced by Editor Screen
    onEditRoutine: (String) -> Unit,
    onCreateRoutine: () -> Unit,
    profiles: List<UserProfile> = emptyList(),
    activeProfileId: String = "default",
    onMoveToProfile: (routineIds: Set<String>, targetProfileId: String) -> Unit = { _, _ -> },
    onSaveRoutineToProfile: (routine: Routine, targetProfileId: String) -> Unit = { _, _ -> },
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
) {
    // showRoutineBuilder and routineToEdit states removed

    Logger.d { "RoutinesTab: ${routines.size} routines loaded" }

    // Selection mode state
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateSetOf<String>() }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchCopyDialog by remember { mutableStateOf(false) }

    // Profile picker state for move/copy to profile
    var profilePickerRoutineId by remember { mutableStateOf<String?>(null) }
    var profilePickerIsMoveAction by remember { mutableStateOf(true) }
    var showBatchMoveProfilePicker by remember { mutableStateOf(false) }
    var showBatchCopyProfilePicker by remember { mutableStateOf(false) }

    // Profiles available as targets (exclude current active profile)
    val targetProfiles = remember(profiles, activeProfileId) {
        profiles.filter { it.id != activeProfileId }
    }

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
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            if (routines.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.FitnessCenter,
                    title = stringResource(Res.string.empty_no_routines_title),
                    message = stringResource(Res.string.empty_no_routines_message),
                    actionText = stringResource(Res.string.create_new_routine),
                    onAction = {
                        onCreateRoutine()
                    },
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    contentPadding = PaddingValues(bottom = 80.dp), // Space for FAB
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
                            onMoveToProfile = {
                                profilePickerRoutineId = routine.id
                                profilePickerIsMoveAction = true
                            },
                            onCopyToProfile = {
                                profilePickerRoutineId = routine.id
                                profilePickerIsMoveAction = false
                            },
                            hasOtherProfiles = targetProfiles.isNotEmpty(),
                            onDuplicate = {
                                // Generate new IDs explicitly and create deep copies
                                val newRoutineId = generateUUID()

                                // Deep-copy supersets with new IDs and remap to new routine
                                val supersetIdMap = routine.supersets.associate { it.id to generateSupersetId() }
                                val newSupersets = routine.supersets.map { superset ->
                                    superset.copy(
                                        id = supersetIdMap[superset.id] ?: generateSupersetId(),
                                        routineId = newRoutineId,
                                    )
                                }

                                // Deep-copy exercises, remapping supersetId references
                                val newExercises = routine.exercises.map { exercise ->
                                    Logger.d { "Duplicating exercise '${exercise.exercise.name}': setReps=${exercise.setReps}" }
                                    exercise.copy(
                                        id = generateUUID(),
                                        exercise = exercise.exercise.copy(),
                                        supersetId = exercise.supersetId?.let { supersetIdMap[it] },
                                    )
                                }

                                // Smart duplicate naming: extract base name and find next copy number
                                val baseName = routine.name.replace(Regex(""" \(Copy( \d+)?\)$"""), "")
                                val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
                                val existingCopyNumbers = routines
                                    .mapNotNull { r ->
                                        when {
                                            r.name == baseName -> 0

                                            // Original has number 0
                                            r.name == "$baseName (Copy)" -> 1

                                            // First copy is 1
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
                                    exercises = newExercises,
                                    supersets = newSupersets,
                                )
                                onSaveRoutine(duplicated)
                            },
                        )
                    }
                }
            }
        }

        // FAB Area - transforms based on selection mode
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.medium),
        ) {
            // Normal mode: Single + FAB
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(
                    onClick = onCreateRoutine,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(Res.string.cd_add_routine),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Selection mode: Vertical stack of action buttons
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    // Cancel button (small)
                    SmallFloatingActionButton(
                        onClick = { clearSelection() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_cancel_selection))
                    }

                    // Move to Profile button (only if other profiles exist)
                    if (targetProfiles.isNotEmpty()) {
                        SmallFloatingActionButton(
                            onClick = { showBatchMoveProfilePicker = true },
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Icon(Icons.AutoMirrored.Default.DriveFileMove, contentDescription = stringResource(Res.string.move_to_profile))
                        }

                        // Copy to Profile button
                        SmallFloatingActionButton(
                            onClick = { showBatchCopyProfilePicker = true },
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Icon(Icons.Default.FileCopy, contentDescription = stringResource(Res.string.copy_to_profile))
                        }
                    }

                    // Copy (duplicate) button
                    FloatingActionButton(
                        onClick = { showBatchCopyDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("${selectedIds.size}")
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(Res.string.cd_copy_selected))
                        }
                    }

                    // Delete button (red)
                    FloatingActionButton(
                        onClick = { showBatchDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error,
                    ) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("${selectedIds.size}")
                                }
                            },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete_selected))
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
            title = { Text(stringResource(Res.string.delete_selected_routines, selectedIds.size)) },
            text = { Text(stringResource(Res.string.cannot_be_undone)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRoutines(selectedIds.toSet())
                        showBatchDeleteDialog = false
                        clearSelection()
                    },
                ) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // Batch Copy Confirmation Dialog
    if (showBatchCopyDialog) {
        AlertDialog(
            onDismissRequest = { showBatchCopyDialog = false },
            title = { Text(stringResource(Res.string.duplicate_selected_routines, selectedIds.size)) },
            text = { Text(stringResource(Res.string.duplicate_routines_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Copy each selected routine using existing duplicate logic
                        selectedIds.forEach { routineId ->
                            routines.find { it.id == routineId }?.let { routine ->
                                val newRoutineId = generateUUID()

                                // Deep-copy supersets with new IDs and remap to new routine
                                val supersetIdMap = routine.supersets.associate { it.id to generateSupersetId() }
                                val newSupersets = routine.supersets.map { superset ->
                                    superset.copy(
                                        id = supersetIdMap[superset.id] ?: generateSupersetId(),
                                        routineId = newRoutineId,
                                    )
                                }

                                // Deep-copy exercises, remapping supersetId references
                                val newExercises = routine.exercises.map { exercise ->
                                    exercise.copy(
                                        id = generateUUID(),
                                        exercise = exercise.exercise.copy(),
                                        supersetId = exercise.supersetId?.let { supersetIdMap[it] },
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
                                    exercises = newExercises,
                                    supersets = newSupersets,
                                )
                                onSaveRoutine(duplicated)
                            }
                        }
                        showBatchCopyDialog = false
                        clearSelection()
                    },
                ) {
                    Text(stringResource(Res.string.action_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchCopyDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // Individual routine profile picker (Move or Copy)
    profilePickerRoutineId?.let { routineId ->
        val routine = routines.find { it.id == routineId }
        if (routine != null) {
            ProfilePickerDialog(
                title = if (profilePickerIsMoveAction) {
                    stringResource(Res.string.move_to_profile)
                } else {
                    stringResource(Res.string.copy_to_profile)
                },
                subtitle = routine.name,
                profiles = targetProfiles,
                onProfileSelected = { targetProfile ->
                    if (profilePickerIsMoveAction) {
                        onMoveToProfile(setOf(routineId), targetProfile.id)
                    } else {
                        val copied = deepCopyRoutine(routine, routines)
                        onSaveRoutineToProfile(copied, targetProfile.id)
                    }
                    profilePickerRoutineId = null
                },
                onDismiss = { profilePickerRoutineId = null },
            )
        }
    }

    // Batch Move to Profile picker
    if (showBatchMoveProfilePicker) {
        ProfilePickerDialog(
            title = stringResource(Res.string.move_to_profile),
            subtitle = stringResource(Res.string.move_routines_confirm, selectedIds.size, "…"),
            profiles = targetProfiles,
            onProfileSelected = { targetProfile ->
                onMoveToProfile(selectedIds.toSet(), targetProfile.id)
                showBatchMoveProfilePicker = false
                clearSelection()
            },
            onDismiss = { showBatchMoveProfilePicker = false },
        )
    }

    // Batch Copy to Profile picker
    if (showBatchCopyProfilePicker) {
        ProfilePickerDialog(
            title = stringResource(Res.string.copy_to_profile),
            subtitle = stringResource(Res.string.copy_routines_confirm, selectedIds.size, "…"),
            profiles = targetProfiles,
            onProfileSelected = { targetProfile ->
                selectedIds.forEach { routineId ->
                    routines.find { it.id == routineId }?.let { routine ->
                        val copied = deepCopyRoutine(routine, routines)
                        onSaveRoutineToProfile(copied, targetProfile.id)
                    }
                }
                showBatchCopyProfilePicker = false
                clearSelection()
            },
            onDismiss = { showBatchCopyProfilePicker = false },
        )
    }
}

/**
 * Deep-copy a routine with new IDs for duplication/cross-profile copy.
 */
private fun deepCopyRoutine(routine: Routine, allRoutines: List<Routine>): Routine {
    val newRoutineId = generateUUID()

    // Deep-copy supersets with new IDs and remap to new routine
    val supersetIdMap = routine.supersets.associate { it.id to generateSupersetId() }
    val newSupersets = routine.supersets.map { superset ->
        superset.copy(
            id = supersetIdMap[superset.id] ?: generateSupersetId(),
            routineId = newRoutineId,
        )
    }

    // Deep-copy exercises, remapping supersetId references
    val newExercises = routine.exercises.map { exercise ->
        exercise.copy(
            id = generateUUID(),
            exercise = exercise.exercise.copy(),
            supersetId = exercise.supersetId?.let { supersetIdMap[it] },
        )
    }

    // Smart duplicate naming: extract base name and find next copy number
    val baseName = routine.name.replace(Regex(""" \(Copy( \d+)?\)$"""), "")
    val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
    val existingCopyNumbers = allRoutines
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

    return routine.copy(
        id = newRoutineId,
        name = newName,
        createdAt = KmpUtils.currentTimeMillis(),
        useCount = 0,
        lastUsed = null,
        exercises = newExercises,
        supersets = newSupersets,
    )
}

/**
 * Dialog for selecting a target profile for move/copy operations.
 * Shows a list of available profiles with colored avatars.
 */
@Composable
private fun ProfilePickerDialog(
    title: String,
    subtitle: String,
    profiles: List<UserProfile>,
    onProfileSelected: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (profiles.isEmpty()) {
                Text(stringResource(Res.string.no_other_profiles))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    profiles.forEach { profile ->
                        val profileColor = ProfileColors.getOrElse(profile.colorIndex) { ProfileColors[0] }
                        Surface(
                            onClick = { onProfileSelected(profile) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Profile color dot
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = profileColor,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = profile.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                        )
                                    }
                                }
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
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
    onDuplicate: () -> Unit,
    onMoveToProfile: () -> Unit = {},
    onCopyToProfile: () -> Unit = {},
    hasOtherProfiles: Boolean = false,
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
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        expanded = !expanded
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) {
                        onLongPress()
                    } else {
                        onSelectionToggle()
                    }
                },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (expanded) 8.dp else 2.dp,
        ),
        border = BorderStroke(
            2.dp,
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Checkbox - only visible in selection mode
                AnimatedVisibility(visible = isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectionToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                // Header Content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = routine.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${routine.exercises.size} exercises • ${formatEstimatedDuration(routine)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Expand Icon - hide in selection mode
                if (!isSelectionMode) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Expanded Content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Exercise List
                    routine.exercises.forEachIndexed { index, exercise ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${index + 1}. ${exercise.exercise.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatSetRepsForCard(exercise.setReps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(Res.string.start_workout),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(Res.string.action_edit), maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onDuplicate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(Res.string.action_copy), maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(Res.string.action_delete), maxLines = 1)
                        }

                        // Overflow menu for Move/Copy to Profile
                        if (hasOtherProfiles) {
                            var showOverflow by remember { mutableStateOf(false) }
                            Spacer(Modifier.width(2.dp))
                            Box {
                                IconButton(
                                    onClick = { showOverflow = true },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(Res.string.select_target_profile),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflow,
                                    onDismissRequest = { showOverflow = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.move_to_profile)) },
                                        onClick = {
                                            showOverflow = false
                                            onMoveToProfile()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Default.DriveFileMove,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.copy_to_profile)) },
                                        onClick = {
                                            showOverflow = false
                                            onCopyToProfile()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.FileCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(Res.string.delete_routine)) },
            text = { Text(stringResource(Res.string.delete_routine_message, routine.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
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
    return groups.joinToString(", ") { (count, reps) -> "${count}x$reps" }
}

/**
 * Estimate workout duration based on reps and rest times.
 */
private fun formatEstimatedDuration(routine: Routine): String {
    if (routine.exercises.isEmpty()) return "0 min"

    var totalWorkSeconds = 0
    var totalRestSeconds = 0

    var step: Pair<Int, Int>? = 0 to 0
    while (step != null) {
        val (exerciseIndex, setIndex) = step
        val currentExercise = routine.exercises.getOrNull(exerciseIndex) ?: break

        totalWorkSeconds += estimateSetWorkSeconds(currentExercise, setIndex)

        val nextStep = getNextStepForEstimate(routine, exerciseIndex, setIndex)
        if (nextStep != null) {
            val nextExercise = routine.exercises.getOrNull(nextStep.first)
            val sameSuperset = currentExercise.supersetId != null &&
                currentExercise.supersetId == nextExercise?.supersetId

            val restForTransition = if (sameSuperset) {
                val supersetExercises = routine.exercises
                    .filter { it.supersetId == currentExercise.supersetId }
                    .sortedBy { it.orderInSuperset }
                val isCurrentLastInSuperset = supersetExercises.lastOrNull()?.id == currentExercise.id

                // End of superset round uses exercise rest; in-round transitions use superset quick rest.
                if (isCurrentLastInSuperset) {
                    currentExercise.getRestForSet(setIndex)
                } else {
                    routine.supersets.find { it.id == currentExercise.supersetId }?.restBetweenSeconds ?: 10
                }
            } else {
                currentExercise.getRestForSet(setIndex)
            }

            totalRestSeconds += restForTransition.coerceAtLeast(0)
        }

        step = nextStep
    }

    val estimatedSeconds = totalWorkSeconds + totalRestSeconds
    val minutes = estimatedSeconds / 60

    return if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        "${hours}h ${remainingMinutes}m"
    }
}

private fun estimateSetWorkSeconds(exercise: com.devil.phoenixproject.domain.model.RoutineExercise, setIndex: Int): Int {
    val reps = exercise.setReps.getOrNull(setIndex)
    return when {
        !exercise.exercise.hasCableAccessory -> (exercise.duration ?: 30).coerceAtLeast(0)

        reps == null -> 30

        // AMRAP estimate
        else -> (reps * 3).coerceAtLeast(0) // ~3s per rep estimate
    }
}

private fun getNextStepForEstimate(routine: Routine, currentExIndex: Int, currentSetIndex: Int): Pair<Int, Int>? {
    val currentExercise = routine.exercises.getOrNull(currentExIndex) ?: return null

    // Superset interleaving: A1 -> B1 -> A2 -> B2 ...
    if (currentExercise.supersetId != null) {
        val supersetExercises = routine.exercises
            .filter { it.supersetId == currentExercise.supersetId }
            .sortedBy { it.orderInSuperset }

        val currentSupersetPos = supersetExercises.indexOf(currentExercise)

        // Next exercise in the same set cycle
        for (i in (currentSupersetPos + 1) until supersetExercises.size) {
            val nextEx = supersetExercises[i]
            if (currentSetIndex < nextEx.setReps.size) {
                val nextExIndex = routine.exercises.indexOf(nextEx)
                return nextExIndex to currentSetIndex
            }
        }

        // First exercise in next cycle that still has a set
        val nextSetIndex = currentSetIndex + 1
        for (ex in supersetExercises) {
            if (nextSetIndex < ex.setReps.size) {
                val nextExIndex = routine.exercises.indexOf(ex)
                return nextExIndex to nextSetIndex
            }
        }

        // Superset complete -> next standalone exercise
        val maxIndex = supersetExercises.maxOf { routine.exercises.indexOf(it) }
        val nextExIndex = maxIndex + 1
        return if (nextExIndex < routine.exercises.size) nextExIndex to 0 else null
    }

    // Standard linear progression
    if (currentSetIndex < currentExercise.setReps.size - 1) {
        return currentExIndex to (currentSetIndex + 1)
    }
    if (currentExIndex < routine.exercises.size - 1) {
        return (currentExIndex + 1) to 0
    }
    return null
}
