package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.ui.theme.SupersetTheme
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Header component for a superset showing name, exercise count, and rest time.
 *
 * Features:
 * - Tappable name for rename
 * - Tappable rest badge + overflow action for rest time between exercises
 * - Overflow menu for other actions (add exercise, copy, delete)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupersetHeader(
    superset: Superset,
    onRename: () -> Unit,
    onChangeColor: () -> Unit,
    onAddExercise: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    dragModifier: Modifier = Modifier,
    onEditRestTime: (() -> Unit)? = null,
) {
    val color = SupersetTheme.colorForIndex(superset.colorIndex)
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = SupersetTheme.backgroundTint(superset.colorIndex, MaterialTheme.colorScheme.background.luminance() < 0.5f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Name, count, and rest time chip
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onRename() },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = superset.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (superset.exerciseCount == 1) {
                            stringResource(Res.string.superset_exercise_count_one)
                        } else {
                            stringResource(Res.string.exercise_count_other, superset.exerciseCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onEditRestTime != null) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = color.copy(alpha = 0.14f),
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { onEditRestTime() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = stringResource(Res.string.rest_between_exercises),
                                    tint = color,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.superset_rest_short, superset.restBetweenSeconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            if (showDragHandle) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .then(dragModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = stringResource(Res.string.cd_reorder_superset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Overflow menu (48dp touch target)
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.cd_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_rename)) },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                )
                if (onEditRestTime != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.rest_between_exercises)) },
                        onClick = {
                            showMenu = false
                            onEditRestTime()
                        },
                        leadingIcon = { Icon(Icons.Default.Timer, null) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.change_color)) },
                    onClick = {
                        showMenu = false
                        onChangeColor()
                    },
                    leadingIcon = { Icon(Icons.Default.ColorLens, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.add_exercise)) },
                    onClick = {
                        showMenu = false
                        onAddExercise()
                    },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.copy_superset)) },
                    onClick = {
                        showMenu = false
                        onCopy()
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.delete_superset)) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}
