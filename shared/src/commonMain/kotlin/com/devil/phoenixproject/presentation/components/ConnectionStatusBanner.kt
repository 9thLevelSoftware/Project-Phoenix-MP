package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.banner_action_connect
import vitruvianprojectphoenix.shared.generated.resources.banner_not_connected_to_machine

/**
 * Connection status banner that displays when not connected to the machine.
 * Shows a warning message and a Connect button for easy manual connection.
 *
 * Uses Material Design 3 styling with theme-aware colors.
 *
 * @param onConnect Callback when Connect button is tapped
 * @param modifier Optional modifier for the banner
 */
@Composable
fun ConnectionStatusBanner(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon and message
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = stringResource(Res.string.cd_bluetooth_status),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(Res.string.banner_not_connected_to_machine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Connect button
            TextButton(
                onClick = onConnect,
                modifier = Modifier.padding(start = Spacing.medium),
            ) {
                Text(
                    stringResource(Res.string.banner_action_connect),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
