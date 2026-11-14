package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.devil.phoenixproject.data.media.ExerciseMediaDelta
import com.devil.phoenixproject.data.repository.ExerciseImageEntity
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCategory
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.rememberMediaPicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Dialog for creating or editing custom exercises.
 * Users can specify:
 * - Exercise name (required)
 * - Muscle group (required)
 * - Resistance type (Cables or Bodyweight)
 * - Equipment accessory (Cables only; issue #970, via CustomExerciseEquipment)
 * - Cable configuration (defaults to DOUBLE)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExerciseDialog(
    existingExercise: Exercise? = null,
    customEquipmentOverlay: Map<String, String> = emptyMap(),
    exerciseRepository: ExerciseRepository? = null,
    mediaCoordinator: ExerciseMediaSaveCoordinator? = null,
    savedImages: List<ExerciseImageEntity> = emptyList(),
    enablePhotoAttach: Boolean = true,
    onSave: (Exercise, ExerciseMediaDelta) -> Unit,
    onDelete: (() -> Unit)? = null,
    onViewHistory: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existingExercise?.name ?: "") }
    var selectedMuscleGroup by remember {
        mutableStateOf(
            existingExercise?.muscleGroup?.let { mg ->
                ExerciseCategory.entries.find { it.displayName.equals(mg, ignoreCase = true) }
            } ?: ExerciseCategory.CHEST,
        )
    }
    // Key on the whole exercise: classification now also depends on
    // isBodyweightOverride, not just the equipment string (#635).
    var usesCables by remember(existingExercise) {
        mutableStateOf(existingExercise?.let { !it.isBodyweight } ?: true)
    }
    // Issue #970: equipment preselect + preserved-unknown-token state. The listed
    // option token (explicit pick or known preselect) and the preserved unrecognized
    // existing token (BARBELL, CABLE, comma lists, ...) are tracked separately so a
    // save-without-change never coerces an unknown token to HANDLES.
    val equipmentPreselect = remember(existingExercise, customEquipmentOverlay) {
        preselectCustomEquipment(existingExercise, customEquipmentOverlay)
    }
    var selectedEquipmentToken by remember(existingExercise) {
        mutableStateOf(equipmentPreselect.selectedToken.takeIf { equipmentPreselect.isListedOption })
    }
    var preservedEquipmentToken by remember(existingExercise) {
        mutableStateOf(
            equipmentPreselect.selectedToken.takeIf {
                !equipmentPreselect.isListedOption && equipmentPreselect.usesCables
            },
        )
    }
    val selectedOrPreservedEquipmentToken = selectedEquipmentToken ?: preservedEquipmentToken ?: ""
    val equipmentFieldLabel = labelForCustomEquipmentToken(
        selectedOrPreservedEquipmentToken.ifBlank { "HANDLES" },
        customEquipmentOverlay,
    )

    var showMuscleGroupDropdown by remember { mutableStateOf(false) }
    var showEquipmentDropdown by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Issue #1358 (Phase 1): media draft state + picker (SD2/SD5).
    val coroutineScope = rememberCoroutineScope()
    val mediaPicker = rememberMediaPicker()
    var showMediaPicker by remember { mutableStateOf(false) }
    var isSavingExercise by remember { mutableStateOf(false) }
    var saveErrorText by remember { mutableStateOf<String?>(null) }
    val mediaSaveFailedMessage = stringResource(Res.string.media_save_media_failed)
    val mediaSaveFailedGenericMessage = stringResource(Res.string.media_save_failed)
    val mediaDeleteFailedMessage = stringResource(Res.string.media_delete_failed)

    if (showMediaPicker && mediaCoordinator != null) {
        mediaPicker.LaunchPhotoPicker { result ->
            showMediaPicker = false
            mediaCoordinator.onPickResult(result, existingCount = savedImages.size)
        }
    }

    val backgroundGradient = screenBackgroundBrush()

    // Issue #362: TalkBack label for the History button. Resolved outside the
    // clearAndSetSemantics block, which is not composable.
    val historyContentDescription = stringResource(Res.string.cd_exercise_history_detail)

    val isEditMode = existingExercise != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Box(modifier = Modifier.fillMaxWidth().background(backgroundGradient)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isEditMode) "Edit Exercise" else "Create Exercise",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Exercise Name
                    ConfirmEditTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            showError = false
                        },
                        label = { Text(stringResource(Res.string.exercise_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = showError && name.isBlank(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )

                    if (showError && name.isBlank()) {
                        Text(
                            "Exercise name is required",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(
                                start = Spacing.medium,
                                top = Spacing.extraSmall,
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Muscle Group Dropdown
                    Text(
                        "Muscle Group *",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )

                        // Transparent clickable overlay
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showMuscleGroupDropdown = true },
                        )

                        DropdownMenu(
                            expanded = showMuscleGroupDropdown,
                            onDismissRequest = { showMuscleGroupDropdown = false },
                        ) {
                            ExerciseCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.displayName) },
                                    onClick = {
                                        selectedMuscleGroup = category
                                        showMuscleGroupDropdown = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Resistance Type
                    Text(
                        "Resistance Type",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        FilterChip(
                            selected = usesCables,
                            onClick = { usesCables = true },
                            label = { Text(stringResource(Res.string.label_cables)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = !usesCables,
                            onClick = { usesCables = false },
                            label = { Text(stringResource(Res.string.label_bodyweight)) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Equipment (issue #970): single-select accessory dropdown, shown only
                    // for Cables. Same outlined-field + overlay + DropdownMenu pattern as the
                    // Muscle Group dropdown; BODYWEIGHT hides it and is saved instead.
                    if (usesCables) {
                        Spacer(modifier = Modifier.height(Spacing.medium))

                        Box {
                            OutlinedTextField(
                                value = equipmentFieldLabel,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        stringResource(Res.string.label_equipment),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )

                            // Transparent clickable overlay
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { showEquipmentDropdown = true },
                            )

                            DropdownMenu(
                                expanded = showEquipmentDropdown,
                                onDismissRequest = { showEquipmentDropdown = false },
                            ) {
                                // Issue #1227: official six in their exact order first, then the
                                // profile's custom names (signoff B9 — never edit the official list).
                                customEquipmentDropdownOptions(customEquipmentOverlay).forEach { (token, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            selectedEquipmentToken = token
                                            preservedEquipmentToken = null
                                            showEquipmentDropdown = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.large))
                    }

                    // Issue #1358 (Phase 1): photo attach/manage section (SD2/SD10/SD12).
                    if (mediaCoordinator != null) {
                        Spacer(modifier = Modifier.height(Spacing.large))
                        ExerciseMediaAttachments(
                            coordinator = mediaCoordinator,
                            savedImages = savedImages,
                            enableAttach = enablePhotoAttach,
                            onRequestPick = { showMediaPicker = true },
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.large))

                    // History (issue #362): edit mode + a host that opted in + a saved id.
                    // Full-width, above Delete/Save so localized labels never truncate.
                    if (isEditMode && onViewHistory != null && existingExercise?.id?.isNotBlank() == true) {
                        OutlinedButton(
                            onClick = { onViewHistory.invoke() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clearAndSetSemantics {
                                    contentDescription = historyContentDescription
                                    role = Role.Button
                                    onClick(label = historyContentDescription) {
                                        onViewHistory()
                                        true
                                    }
                                },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(Res.string.action_history))
                        }

                        Spacer(modifier = Modifier.height(Spacing.small))
                    }

                    // Issue #1358: inline save/delete error (retryable draft; SD6)
                    saveErrorText?.let { message ->
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        // Delete button (only for edit mode)
                        if (isEditMode && onDelete != null) {
                            OutlinedButton(
                                onClick = { showDeleteConfirmation = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(Res.string.action_delete))
                            }
                        } else {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(stringResource(Res.string.action_cancel))
                            }
                        }

                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    showError = true
                                } else {
                                    // Issue #970: BODYWEIGHT when Bodyweight is selected; otherwise
                                    // the picked accessory token, or the preserved unrecognized
                                    // existing token on save-without-change (never coerced).
                                    val equipmentValue = equipmentTokenForCustomSave(
                                        usesCables = usesCables,
                                        selectedOrPreservedToken = selectedOrPreservedEquipmentToken,
                                    )
                                    val exercise = Exercise(
                                        id = existingExercise?.id,
                                        name = name.trim(),
                                        muscleGroup = selectedMuscleGroup.displayName,
                                        muscleGroups = selectedMuscleGroup.displayName,
                                        equipment = equipmentValue,
                                        isFavorite = existingExercise?.isFavorite ?: false,
                                        isCustom = true,
                                    )
                                    // Issue #1358 (SD5/SD6): the shared coordinator runs the
                                    // create/update -> files -> rows -> cleanup tail for BOTH
                                    // entry points. A media failure keeps the minted id and the
                                    // draft retryable; only success closes the dialog.
                                    val coordinator = mediaCoordinator
                                    val repo = exerciseRepository
                                    if (coordinator != null && repo != null) {
                                        val delta = ExerciseMediaDelta(
                                            pendingAdditions = coordinator.stagedAdditions.toList(),
                                            pendingRemovals = coordinator.queuedRemovals.toList(),
                                        )
                                        isSavingExercise = true
                                        saveErrorText = null
                                        coroutineScope.launch {
                                            val outcome = coordinator.saveWithMedia(
                                                draftExercise = exercise,
                                                editingExerciseId = existingExercise?.id,
                                                repository = repo,
                                            )
                                            isSavingExercise = false
                                            when (outcome) {
                                                is CustomExerciseSaveOutcome.Saved -> {
                                                    onSave(outcome.exercise, delta)
                                                }
                                                is CustomExerciseSaveOutcome.MediaFailed -> {
                                                    // SD6: exercise kept; retry adds media only.
                                                    saveErrorText = mediaSaveFailedMessage
                                                }
                                                is CustomExerciseSaveOutcome.Failed -> {
                                                    saveErrorText = mediaSaveFailedGenericMessage
                                                }
                                            }
                                        }
                                    } else {
                                        onSave(exercise, ExerciseMediaDelta.EMPTY)
                                    }
                                }
                            },
                            enabled = !isSavingExercise,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                if (isEditMode) "Save" else "Create",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog (issue #1358: photo-aware copy)
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(Res.string.delete_exercise_title)) },
            text = {
                Text(stringResource(Res.string.delete_exercise_media_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        // Issue #1358 (P4/SD7): shared delete tail — rows cascade
                        // first, then managed files; a DB failure preserves files.
                        val deleteId = existingExercise?.id
                        val coordinator = mediaCoordinator
                        val repo = exerciseRepository
                        if (deleteId != null && coordinator != null && repo != null) {
                            coroutineScope.launch {
                                when (coordinator.deleteExerciseWithMedia(deleteId, repo)) {
                                    is CustomExerciseSaveOutcome.Saved -> onDelete?.invoke()
                                    else -> saveErrorText = mediaDeleteFailedMessage
                                }
                            }
                        } else {
                            onDelete?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}
