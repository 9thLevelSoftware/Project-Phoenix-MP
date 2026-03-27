package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Troubleshooting tips:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TroubleshootingItem("• Ensure the machine is powered on")
                    TroubleshootingItem("• Try turning Bluetooth off and on")
                    TroubleshootingItem("• Move closer to the machine")
                    TroubleshootingItem("• Check that no other device is connected")
                }
            }
        },
        confirmButton = {
            if (onRetry != null) {
                TextButton(onClick = {
                    onDismiss()
                    onRetry()
                }) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
