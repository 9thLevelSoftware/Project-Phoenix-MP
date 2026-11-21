package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.ui.theme.SupersetTheme
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Dialog for choosing which superset to add exercises to.
 * Shows existing supersets and option to create new.
 */
@Composable
fun SupersetPickerDialog(
    existingSupersets: List<Superset>,
    canCreateNew: Boolean = true,
    onCreateNew: () -> Unit,
    onSelectExisting: (Superset) -> Unit,
    onDismiss: () -> Unit,
) {
    PhoenixModalDialogFrame(
        title = stringResource(Res.string.add_to_superset),
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
        Column {
            // Create New option (only when enough exercises are selected)
            if (canCreateNew) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .semantics(mergeDescendants = true) { role = Role.Button }
                        .clickable { onCreateNew() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(Res.string.superset_create_new),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (existingSupersets.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    stringResource(
                        if (canCreateNew) {
                            Res.string.superset_or_add_existing
                        } else {
                            Res.string.superset_add_existing
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                existingSupersets.forEach { superset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .semantics(mergeDescendants = true) { role = Role.Button }
                            .clickable { onSelectExisting(superset) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    SupersetTheme.colorForIndex(superset.colorIndex),
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                superset.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (superset.exerciseCount == 1) {
                                    stringResource(Res.string.exercise_count_one)
                                } else {
                                    stringResource(Res.string.exercise_count_other, superset.exerciseCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
