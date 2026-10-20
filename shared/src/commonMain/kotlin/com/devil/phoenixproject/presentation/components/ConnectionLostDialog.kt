package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_bluetooth_lost

/**
 * Critical alert dialog shown when BLE connection is lost during an active workout, or when a
 * durable machine-safety hazard is pending (Issue #43, #782).
 *
 * [hazard] is the stored record (null when only the in-session loss flag is set). Its cause
 * picks the title and its phase reports what the last release request did, so a tap on
 * [onReconnect] has a visible outcome.
 *
 * [onAcknowledgeUnloaded] is offered only for a stored safety hazard: it is the user's explicit
 * physical check that clears the start barrier. It lives in the pinned action row, never in
 * the scrolling body, so it cannot be clipped off-screen on short displays or large text
 * (#769 TestFlight reports). Resting cable tension (~8 lb) is normal, so the wording asks for
 * "at rest", never for zero load.
 */
@Composable
fun ConnectionLostDialog(
    hazard: MachineSafetyHazardDocument?,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    onAcknowledgeUnloaded: (() -> Unit)? = null,
) {
    val copy = connectionLostDialogCopy(hazard)
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
                copy.title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    copy.body,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    CONNECTION_LOST_SECONDARY_COPY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onAcknowledgeUnloaded != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        CONNECTION_LOST_ACK_COPY,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                copy.recoveryStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (copy.recoveryInProgress) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onReconnect, enabled = !copy.recoveryInProgress) {
                    Text(
                        "Reconnect to request release",
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (onAcknowledgeUnloaded != null) {
                    OutlinedButton(onClick = onAcknowledgeUnloaded) {
                        Text("I checked: handles at rest, clear warning")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Remind me later")
            }
        },
    )
}
