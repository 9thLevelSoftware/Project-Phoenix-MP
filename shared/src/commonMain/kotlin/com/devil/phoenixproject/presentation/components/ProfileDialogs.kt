package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_add
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_delete
import vitruvianprojectphoenix.shared.generated.resources.action_save
import vitruvianprojectphoenix.shared.generated.resources.add_profile
import vitruvianprojectphoenix.shared.generated.resources.cd_select_profile_color
import vitruvianprojectphoenix.shared.generated.resources.choose_color
import vitruvianprojectphoenix.shared.generated.resources.color_amber
import vitruvianprojectphoenix.shared.generated.resources.color_blue
import vitruvianprojectphoenix.shared.generated.resources.color_cyan
import vitruvianprojectphoenix.shared.generated.resources.color_green
import vitruvianprojectphoenix.shared.generated.resources.color_orange
import vitruvianprojectphoenix.shared.generated.resources.color_pink
import vitruvianprojectphoenix.shared.generated.resources.color_purple
import vitruvianprojectphoenix.shared.generated.resources.color_red
import vitruvianprojectphoenix.shared.generated.resources.delete_profile
import vitruvianprojectphoenix.shared.generated.resources.edit_profile
import vitruvianprojectphoenix.shared.generated.resources.label_name
import vitruvianprojectphoenix.shared.generated.resources.profile_delete_reassign_message

@Composable
fun ProfileAddDialog(
    existingProfileCount: Int,
    isSubmitting: Boolean,
    onConfirm: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedColorIndex by remember(existingProfileCount) {
        mutableIntStateOf(suggestedProfileColorIndex(existingProfileCount))
    }

    ProfileIdentityDialog(
        title = stringResource(Res.string.add_profile),
        name = name,
        selectedColorIndex = selectedColorIndex,
        isSubmitting = isSubmitting,
        confirmLabel = stringResource(Res.string.action_add),
        onNameChange = { name = it },
        onColorSelected = { selectedColorIndex = it },
        onConfirm = {
            onConfirm(name.trim(), normalizedProfileColorIndex(selectedColorIndex))
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun ProfileEditDialog(
    profile: UserProfile,
    isSubmitting: Boolean,
    onConfirm: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var selectedColorIndex by remember(profile.id) {
        mutableIntStateOf(normalizedProfileColorIndex(profile.colorIndex))
    }

    ProfileIdentityDialog(
        title = stringResource(Res.string.edit_profile),
        name = name,
        selectedColorIndex = selectedColorIndex,
        isSubmitting = isSubmitting,
        confirmLabel = stringResource(Res.string.action_save),
        onNameChange = { name = it },
        onColorSelected = { selectedColorIndex = it },
        onConfirm = {
            onConfirm(name.trim(), normalizedProfileColorIndex(selectedColorIndex))
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun ProfileDeleteDialog(
    profile: UserProfile,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    require(canDeleteProfile(profile)) {
        "Only the active non-default profile may be deleted from Profile"
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(stringResource(Res.string.delete_profile)) },
        text = {
            Text(
                text = stringResource(
                    Res.string.profile_delete_reassign_message,
                    profile.name,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProfileIdentityDialog(
    title: String,
    name: String,
    selectedColorIndex: Int,
    isSubmitting: Boolean,
    confirmLabel: String,
    onNameChange: (String) -> Unit,
    onColorSelected: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trimmedName = name.trim()
    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    enabled = !isSubmitting,
                    label = { Text(stringResource(Res.string.label_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.choose_color),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProfileColorSelector(
                    selectedColorIndex = selectedColorIndex,
                    enabled = !isSubmitting,
                    onColorSelected = onColorSelected,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = trimmedName.isNotEmpty() && !isSubmitting,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProfileColorSelector(
    selectedColorIndex: Int,
    enabled: Boolean,
    onColorSelected: (Int) -> Unit,
) {
    val colorNames = listOf(
        stringResource(Res.string.color_blue),
        stringResource(Res.string.color_green),
        stringResource(Res.string.color_amber),
        stringResource(Res.string.color_red),
        stringResource(Res.string.color_purple),
        stringResource(Res.string.color_pink),
        stringResource(Res.string.color_cyan),
        stringResource(Res.string.color_orange),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProfileColors.indices.chunked(4).forEach { indices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                indices.forEach { index ->
                    val color = ProfileColors[index]
                    val selected = normalizedProfileColorIndex(selectedColorIndex) == index
                    val description = stringResource(
                        Res.string.cd_select_profile_color,
                        colorNames[index],
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onColorSelected(index) },
                            )
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(color, CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = profileInitialsColor(color),
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = profileInitialsColor(color),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
