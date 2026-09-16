package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_bluetooth_lost

/**
 * Critical alert dialog shown when BLE connection is lost during an active workout.
 * Addresses Issue #43: Connection lost during screen lock
 */
@Composable
fun ConnectionLostDialog(onReconnect: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = stringResource(Res.string.cd_bluetooth_lost),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Connection Lost",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    "Bluetooth to the trainer was lost. Phoenix cannot confirm whether resistance is still engaged. Do not assume the machine is unloaded.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "A successful reconnect can request a software stop, but that is not proof the cables are unloaded. Follow Vitruvian safety guidance; powering the trainer off is a valid last resort.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onReconnect) {
                Text(
                    "Reconnect to request release",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Remind me later")
            }
        },
    )
}
