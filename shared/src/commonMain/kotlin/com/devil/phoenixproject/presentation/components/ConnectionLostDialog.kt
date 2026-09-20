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
import androidx.compose.material3.OutlinedButton
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
 * Critical alert dialog shown when BLE connection is lost during an active workout, or when a
 * durable machine-safety hazard is pending (Issue #43, #782).
 *
 * [onAcknowledgeUnloaded] is offered only for a stored safety hazard: it is the user's explicit
 * physical check that clears the start barrier. Resting cable tension (~8 lb) is normal, so the
 * wording asks for "at rest", never for zero load.
 */
@Composable
fun ConnectionLostDialog(
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    onAcknowledgeUnloaded: (() -> Unit)? = null,
) {
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
                    "A successful reconnect can request a software stop, but that is not proof the cables are unloaded. Follow the trainer's safety guidance; powering the trainer off is a valid last resort.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onAcknowledgeUnloaded != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "New sets stay blocked until you confirm. Check the trainer yourself: handles back at rest and cables not pulling beyond their normal light resting tension.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onAcknowledgeUnloaded) {
                        Text("I checked: handles at rest, clear warning")
                    }
                }
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
