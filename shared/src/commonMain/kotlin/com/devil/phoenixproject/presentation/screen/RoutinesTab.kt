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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.generateSupersetId
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.ProfileColors
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_copy
import vitruvianprojectphoenix.shared.generated.resources.action_delete
import vitruvianprojectphoenix.shared.generated.resources.action_edit
import vitruvianprojectphoenix.shared.generated.resources.cannot_be_undone
import vitruvianprojectphoenix.shared.generated.resources.cd_add_routine
import vitruvianprojectphoenix.shared.generated.resources.cd_cancel_selection
import vitruvianprojectphoenix.shared.generated.resources.cd_collapse
import vitruvianprojectphoenix.shared.generated.resources.cd_copy_selected
import vitruvianprojectphoenix.shared.generated.resources.cd_delete_selected
import vitruvianprojectphoenix.shared.generated.resources.cd_expand
import vitruvianprojectphoenix.shared.generated.resources.copy_routines_confirm
import vitruvianprojectphoenix.shared.generated.resources.copy_to_profile
import vitruvianprojectphoenix.shared.generated.resources.create_new_routine
import vitruvianprojectphoenix.shared.generated.resources.delete_routine
import vitruvianprojectphoenix.shared.generated.resources.delete_routine_message
import vitruvianprojectphoenix.shared.generated.resources.delete_selected_routines
import vitruvianprojectphoenix.shared.generated.resources.duplicate_routines_message
import vitruvianprojectphoenix.shared.generated.resources.duplicate_selected_routines
import vitruvianprojectphoenix.shared.generated.resources.empty_no_routines_message
import vitruvianprojectphoenix.shared.generated.resources.empty_no_routines_title
import vitruvianprojectphoenix.shared.generated.resources.move_routines_confirm
import vitruvianprojectphoenix.shared.generated.resources.move_to_profile
import vitruvianprojectphoenix.shared.generated.resources.no_other_profiles
import vitruvianprojectphoenix.shared.generated.resources.select_target_profile
import vitruvianprojectphoenix.shared.generated.resources.start_workout

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
    // Routine group support
    routineGroups: List<RoutineGroup> = emptyList(),
    onCreateGroup: (String) -> Unit = {},
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onMoveToGroup: (routineIds: Set<String>, groupId: String?) -> Unit = { _, _ -> },
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
) {
    // showRoutineBuilder and routineToEdit states removed

    Logger.d { "RoutinesTab: ${routines.size} routines loaded" }

    // Historical time estimates (Issue #225)
    val timeEstimator: RoutineTimeEstimator = koinInject()
    val timeEstimates = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(routines) {
        for (routine in routines) {
            if (routine.exercises.isEmpty()) continue
            try {
                val estimate = timeEstimator.estimateRoutineDuration(routine, routine.profileId)
                val text = buildString {
                    if (estimate.hasRange) {
                        append(estimate.formattedRange)
                    } else {
                        append(estimate.formattedDuration)
                    }
                    if (estimate.isEntirelyFallback) append(" (est.)")
                }
                timeEstimates[routine.id] = text
            } catch (e: Exception) {
                Logger.w(e) { "Failed to estimate duration for routine ${routine.id}" }
            }
        }
    }

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

    // Routine group state
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showMoveToGroupDialog by remember { mutableStateOf(false) }
    var moveToGroupRoutineId by remember { mutableStateOf<String?>(null) }
    var showBatchMoveToGroupDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renameGroupTarget by remember { mutableStateOf<RoutineGroup?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<RoutineGroup?>(null) }
    // Pending group creation from MoveToGroupDialog "New Group..." option
    var pendingCreateGroupForMove by remember { mutableStateOf(false) }
    // Routine IDs that should be moved into the newly created group
    var pendingMoveRoutineIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Snapshot of known group IDs before creating a new group (to detect the new one)
    var preCreateGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val routineGroupIds = remember(routineGroups) {
        routineGroups.map { it.id }.toSet()
    }

    // Group routines: ungrouped first, then by known group.
    // Stale group references can happen after profile moves/imports; keep those routines visible.
    val ungroupedRoutines = remember(routines, routineGroupIds) {
        routines.filter { routine ->
            val groupId = routine.groupId
            groupId == null || groupId !in routineGroupIds
        }
    }
    val groupedRoutineMap = remember(routines, routineGroupIds) {
        routines
            .mapNotNull { routine ->
                val groupId = routine.groupId
                if (groupId != null && groupId in routineGroupIds) groupId to routine else null
            }
            .groupBy({ it.first }, { it.second })
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
                    // --- Ungrouped routines first ---
                    items(ungroupedRoutines, key = { it.id }) { routine ->
                        RoutineCardWithActions(
                            routine = routine,
                            routines = routines,
                            selectionMode = selectionMode,
                            selectedIds = selectedIds,
                            targetProfiles = targetProfiles,
                            onSelectionModeActivate = { selectionMode = true },
                            onSelectionModeDeactivate = { selectionMode = false },
                            onStartWorkout = onStartWorkout,
                            onEditRoutine = onEditRoutine,
                            onDeleteRoutine = onDeleteRoutine,
                            onSaveRoutine = onSaveRoutine,
                            profilePickerRoutineId = { profilePickerRoutineId = it },
                            profilePickerIsMoveAction = { profilePickerIsMoveAction = it },
                            hasGroups = routineGroups.isNotEmpty(),
                            onMoveToGroup = {
                                moveToGroupRoutineId = routine.id
                                showMoveToGroupDialog = true
                            },
                            historicalTimeEstimate = timeEstimates[routine.id],
                        )
                    }

                    // --- Grouped sections ---
                    routineGroups.sortedBy { it.orderIndex }.forEach { group ->
                        val groupRoutines = groupedRoutineMap[group.id] ?: emptyList()
                        val isExpanded = collapsedGroups[group.id] != true // default expanded

                        item(key = "group_header_${group.id}") {
                            RoutineGroupHeader(
                                group = group,
                                routineCount = groupRoutines.size,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    collapsedGroups[group.id] = isExpanded // toggle
                                },
                                onRename = { renameGroupTarget = group },
                                onDelete = { deleteGroupTarget = group },
                            )
                        }

                        if (isExpanded) {
                            items(groupRoutines, key = { it.id }) { routine ->
                                RoutineCardWithActions(
                                    routine = routine,
                                    routines = routines,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    targetProfiles = targetProfiles,
                                    onSelectionModeActivate = { selectionMode = true },
                                    onSelectionModeDeactivate = { selectionMode = false },
                                    onStartWorkout = onStartWorkout,
                                    onEditRoutine = onEditRoutine,
                                    onDeleteRoutine = onDeleteRoutine,
                                    onSaveRoutine = onSaveRoutine,
                                    profilePickerRoutineId = { profilePickerRoutineId = it },
                                    profilePickerIsMoveAction = { profilePickerIsMoveAction = it },
                                    hasGroups = routineGroups.isNotEmpty(),
                                    onMoveToGroup = {
                                        moveToGroupRoutineId = routine.id
                                        showMoveToGroupDialog = true
                                    },
                                    historicalTimeEstimate = timeEstimates[routine.id],
                                )
                            }
                        }
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

                    // Move to Group button
                    SmallFloatingActionButton(
                        onClick = { showBatchMoveToGroupDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Move to Group")
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

    // === Routine Group Dialogs ===

    // Individual routine "Move to Group" dialog
    moveToGroupRoutineId?.let { routineId ->
        val routine = routines.find { it.id == routineId }
        if (routine != null) {
            MoveToGroupDialog(
                title = "Move to Group",
                subtitle = routine.name,
                groups = routineGroups,
                currentGroupId = routine.groupId,
                onGroupSelected = { groupId ->
                    onMoveToGroup(setOf(routineId), groupId)
                    moveToGroupRoutineId = null
                },
                onRemoveFromGroup = {
                    onMoveToGroup(setOf(routineId), null)
                    moveToGroupRoutineId = null
                },
                onCreateNewGroup = {
                    pendingMoveRoutineIds = setOf(routineId)
                    preCreateGroupIds = routineGroups.map { it.id }.toSet()
                    moveToGroupRoutineId = null
                    pendingCreateGroupForMove = true
                    showCreateGroupDialog = true
                },
                onDismiss = { moveToGroupRoutineId = null },
            )
        }
    }

    // Batch "Move to Group" dialog (selection mode)
    if (showBatchMoveToGroupDialog) {
        val firstSelectedGroupId = selectedIds.firstOrNull()?.let { id ->
            routines.find { it.id == id }?.groupId
        }
        MoveToGroupDialog(
            title = "Move to Group",
            subtitle = "${selectedIds.size} routine(s)",
            groups = routineGroups,
            currentGroupId = firstSelectedGroupId,
            onGroupSelected = { groupId ->
                onMoveToGroup(selectedIds.toSet(), groupId)
                showBatchMoveToGroupDialog = false
                clearSelection()
            },
            onRemoveFromGroup = {
                onMoveToGroup(selectedIds.toSet(), null)
                showBatchMoveToGroupDialog = false
                clearSelection()
            },
            onCreateNewGroup = {
                pendingMoveRoutineIds = selectedIds.toSet()
                preCreateGroupIds = routineGroups.map { it.id }.toSet()
                showBatchMoveToGroupDialog = false
                pendingCreateGroupForMove = true
                showCreateGroupDialog = true
            },
            onDismiss = { showBatchMoveToGroupDialog = false },
        )
    }

    // Create Group dialog
    if (showCreateGroupDialog) {
        GroupNameDialog(
            title = "New Group",
            onConfirm = { name ->
                onCreateGroup(name)
                showCreateGroupDialog = false
                // If this was triggered from "Move to Group" -> "New Group...",
                // keep pendingCreateGroupForMove=true so the LaunchedEffect below
                // can detect the new group and chain the move operation.
                if (!pendingCreateGroupForMove) {
                    pendingMoveRoutineIds = emptySet()
                    preCreateGroupIds = emptySet()
                }
            },
            onDismiss = {
                showCreateGroupDialog = false
                pendingCreateGroupForMove = false
                pendingMoveRoutineIds = emptySet()
                preCreateGroupIds = emptySet()
            },
        )
    }

    // Reactive handler: when a new group appears after "New Group..." from move dialog,
    // automatically move the pending routines into it.
    LaunchedEffect(routineGroups, pendingCreateGroupForMove) {
        if (pendingCreateGroupForMove && pendingMoveRoutineIds.isNotEmpty()) {
            val newGroup = routineGroups.firstOrNull { it.id !in preCreateGroupIds }
            if (newGroup != null) {
                Logger.d { "ROUTINE_GROUPS: Auto-moving ${pendingMoveRoutineIds.size} routine(s) into new group '${newGroup.name}' (${newGroup.id})" }
                onMoveToGroup(pendingMoveRoutineIds, newGroup.id)
                pendingCreateGroupForMove = false
                pendingMoveRoutineIds = emptySet()
                preCreateGroupIds = emptySet()
                clearSelection()
            }
        }
    }

    // Rename Group dialog
    renameGroupTarget?.let { group ->
        GroupNameDialog(
            title = "Rename Group",
            initialName = group.name,
            onConfirm = { newName ->
                onRenameGroup(group.id, newName)
                renameGroupTarget = null
            },
            onDismiss = { renameGroupTarget = null },
        )
    }

    // Delete Group confirmation
    deleteGroupTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text("Delete Group") },
            text = {
                Text("Delete group \"${group.name}\"? Routines in this group will become ungrouped.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(group.id)
                    deleteGroupTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroupTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Wrapper composable that attaches all action callbacks to a RoutineCard.
 * Extracted to avoid duplicating the long callback chain in both ungrouped and grouped sections.
 */
@Composable
private fun RoutineCardWithActions(
    routine: Routine,
    routines: List<Routine>,
    selectionMode: Boolean,
    selectedIds: MutableSet<String>,
    targetProfiles: List<UserProfile>,
    onSelectionModeActivate: () -> Unit,
    onSelectionModeDeactivate: () -> Unit,
    onStartWorkout: (Routine) -> Unit,
    onEditRoutine: (String) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onSaveRoutine: (Routine) -> Unit,
    profilePickerRoutineId: (String?) -> Unit,
    profilePickerIsMoveAction: (Boolean) -> Unit,
    hasGroups: Boolean,
    onMoveToGroup: () -> Unit,
    historicalTimeEstimate: String? = null,
) {
    RoutineCard(
        routine = routine,
        isSelectionMode = selectionMode,
        isSelected = selectedIds.contains(routine.id),
        onLongPress = {
            onSelectionModeActivate()
            selectedIds.add(routine.id)
        },
        onSelectionToggle = {
            if (selectedIds.contains(routine.id)) {
                selectedIds.remove(routine.id)
                if (selectedIds.isEmpty()) onSelectionModeDeactivate()
            } else {
                selectedIds.add(routine.id)
            }
        },
        onStartWorkout = { onStartWorkout(routine) },
        onEdit = { onEditRoutine(routine.id) },
        onDelete = { onDeleteRoutine(routine.id) },
        onMoveToProfile = {
            profilePickerRoutineId(routine.id)
            profilePickerIsMoveAction(true)
        },
        onCopyToProfile = {
            profilePickerRoutineId(routine.id)
            profilePickerIsMoveAction(false)
        },
        hasOtherProfiles = targetProfiles.isNotEmpty(),
        hasGroups = hasGroups,
        onMoveToGroup = onMoveToGroup,
        onDuplicate = {
            val newRoutineId = generateUUID()
            val supersetIdMap = routine.supersets.associate { it.id to generateSupersetId() }
            val newSupersets = routine.supersets.map { superset ->
                superset.copy(
                    id = supersetIdMap[superset.id] ?: generateSupersetId(),
                    routineId = newRoutineId,
                )
            }
            val newExercises = routine.exercises.map { exercise ->
                Logger.d { "Duplicating exercise '${exercise.exercise.name}': setReps=${exercise.setReps}" }
                exercise.copy(
                    id = generateUUID(),
                    exercise = exercise.exercise.copy(),
                    supersetId = exercise.supersetId?.let { supersetIdMap[it] },
                )
            }
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
        },
        historicalTimeEstimate = historicalTimeEstimate,
    )
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
    hasGroups: Boolean = false,
    onMoveToGroup: () -> Unit = {},
    historicalTimeEstimate: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val useCompactAccessibility = isCompactAccessibilityLayout()
    val cardPadding = if (useCompactAccessibility) 16.dp else 20.dp

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
                .padding(cardPadding),
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
                        style = if (useCompactAccessibility) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${routine.exercises.size} exercises • ${historicalTimeEstimate ?: formatEstimatedDuration(routine)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${index + 1}. ${exercise.exercise.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatSetRepsForCard(exercise.setReps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                            contentPadding = if (useCompactAccessibility) {
                                PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            } else {
                                PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            },
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.action_edit),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onDuplicate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = if (useCompactAccessibility) {
                                PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            } else {
                                PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.action_copy),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = if (useCompactAccessibility) {
                                PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            } else {
                                PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.action_delete),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Overflow menu for Move/Copy to Profile and Move to Group
                        if (hasOtherProfiles || hasGroups) {
                            var showOverflow by remember { mutableStateOf(false) }
                            Spacer(Modifier.width(2.dp))
                            Box {
                                IconButton(
                                    onClick = { showOverflow = true },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflow,
                                    onDismissRequest = { showOverflow = false },
                                ) {
                                    // Move to Group option
                                    DropdownMenuItem(
                                        text = { Text("Move to Group") },
                                        onClick = {
                                            showOverflow = false
                                            onMoveToGroup()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        },
                                    )
                                    if (hasOtherProfiles) {
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
