package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Header component for a superset showing name, exercise count, and rest time.
 *
 * Features:
 * - Tappable name for rename
 * - Tappable rest time chip for quick adjustment
 * - Overflow menu for other actions (add exercise, copy, delete)
 */
@Composable
fun SupersetHeader(
    superset: Superset,
    onRename: () -> Unit,
    onChangeRestTime: () -> Unit,
    onChangeColor: () -> Unit,
    onAddExercise: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    dragModifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(superset.colorIndex)
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SupersetTheme.backgroundTint(superset.colorIndex, false),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Name and count (tappable for rename)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onRename() }
            ) {
                Text(
                    text = superset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = "${superset.exerciseCount} exercise${if (superset.exerciseCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Rest time chip (tappable)
            Surface(
                modifier = Modifier.clickable { onChangeRestTime() },
                shape = RoundedCornerShape(16.dp),
                color = color.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${superset.restBetweenSeconds}s rest",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            if (showDragHandle) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Reorder superset",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp).then(dragModifier)
                )

                Spacer(Modifier.width(4.dp))
            }

            // Overflow menu
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                )
                DropdownMenuItem(
                    text = { Text("Change Color") },
                    onClick = {
                        showMenu = false
                        onChangeColor()
                    },
                    leadingIcon = { Icon(Icons.Default.ColorLens, null) }
                )
                DropdownMenuItem(
                    text = { Text("Add Exercise") },
                    onClick = {
                        showMenu = false
                        onAddExercise()
                    },
                    leadingIcon = { Icon(Icons.Default.Add, null) }
                )
                DropdownMenuItem(
                    text = { Text("Copy Superset") },
                    onClick = {
                        showMenu = false
                        onCopy()
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                )
                DropdownMenuItem(
                    text = { Text("Delete Superset") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}
