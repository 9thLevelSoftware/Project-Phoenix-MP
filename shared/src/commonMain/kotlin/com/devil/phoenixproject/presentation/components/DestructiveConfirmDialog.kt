package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel

/**
 * Canonical destructive-action confirmation dialog.
 *
 * Uses the "M3 Expressive" style established in HistoryTab: surfaceContainerHighest container,
 * large shape, 56 dp tall buttons with medium shape.
 *
 * The dismiss button always shows "Cancel" (Res.string.action_cancel) — callers supply only
 * the confirm label (e.g. stringResource(Res.string.action_delete)).
 *
 * Callers are responsible for dismissing the dialog inside onConfirm and onDismiss.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
        icon = icon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    confirmText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    stringResource(Res.string.action_cancel),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
