package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Error dialog shown when auto-connect fails
 * Includes helpful troubleshooting suggestions for users
 */
@Composable
fun ConnectionErrorDialog(message: String, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(Res.string.cd_connection_error),
            )
        },
        title = { Text(stringResource(Res.string.connection_failed)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = stringResource(Res.string.connection_troubleshooting_tips),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TroubleshootingItem(stringResource(Res.string.connection_tip_powered_on))
                    TroubleshootingItem(stringResource(Res.string.connection_tip_bluetooth))
                    TroubleshootingItem(stringResource(Res.string.connection_tip_move_closer))
                    TroubleshootingItem(stringResource(Res.string.connection_tip_no_other_device))
                }
            }
        },
        confirmButton = {
            if (onRetry != null) {
                TextButton(
                    onClick = {
                        onDismiss()
                        onRetry()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.action_ok))
            }
        },
    )
}

@Composable
private fun TroubleshootingItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
