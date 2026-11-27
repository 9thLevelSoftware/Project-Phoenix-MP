package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Dialog for selecting rest time from chip options.
 * Default options (superset): 0, 5, 10, 15, 20, 25, 30 seconds.
 * Override [options], [title], and [formatLabel] for different contexts (e.g. Just Lift).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RestTimePickerDialog(
    currentRestSeconds: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    options: List<Int> = listOf(0, 5, 10, 15, 20, 25, 30),
    title: String = stringResource(Res.string.rest_between_exercises),
    formatLabel: (Int) -> String = { "${it}s" },
) {
    PhoenixModalDialogFrame(
        title = title,
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { seconds ->
                val isSelected = seconds == currentRestSeconds
                Surface(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .semantics { role = Role.Button }
                        .clickable { onSelect(seconds) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatLabel(seconds),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
