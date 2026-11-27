package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.presentation.components.ConfirmEditTextField
import com.devil.phoenixproject.presentation.components.PhoenixModalDialogFrame
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Dialog for moving routine(s) to a group.
 * Shows existing groups, a "New Group..." option, and "Remove from Group" option.
 * Follows the ProfilePickerDialog visual pattern.
 */
@Composable
fun MoveToGroupDialog(
    title: String,
    subtitle: String,
    groups: List<RoutineGroup>,
    currentGroupId: String?,
    onGroupSelected: (String) -> Unit,
    onRemoveFromGroup: () -> Unit,
    onCreateNewGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    PhoenixModalDialogFrame(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        actions = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Existing groups
            groups.forEach { group ->
                val isCurrentGroup = group.id == currentGroupId
                Surface(
                    onClick = {
                        if (!isCurrentGroup) {
                            onGroupSelected(group.id)
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    color = if (isCurrentGroup) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    enabled = !isCurrentGroup,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrentGroup) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrentGroup) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrentGroup) {
                            Text(
                                text = stringResource(Res.string.external_status_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // stringResource(Res.string.group_move_new_group) option
            Surface(
                onClick = onCreateNewGroup,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.group_move_new_group),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // stringResource(Res.string.group_move_remove_from_group) option (only if currently in a group)
            if (currentGroupId != null) {
                Surface(
                    onClick = onRemoveFromGroup,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.FolderOff,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(Res.string.group_move_remove_from_group),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simple text input dialog for creating or renaming a group.
 */
@Composable
fun GroupNameDialog(
    title: String,
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    PhoenixModalDialogFrame(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.action_save),
        onConfirm = { onConfirm(name.trim()) },
        dismissLabel = stringResource(Res.string.action_cancel),
        confirmEnabled = name.isNotBlank(),
    ) {
        ConfirmEditTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.group_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
