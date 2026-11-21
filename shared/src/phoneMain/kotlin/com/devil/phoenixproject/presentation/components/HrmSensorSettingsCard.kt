package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.ble.ContactReportState
import com.devil.phoenixproject.data.ble.HrmBleManager
import com.devil.phoenixproject.data.ble.HrmCapabilityState
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Capability-gated heart rate sensor settings card (SD2/SD6).
 *
 * Gating rules:
 * - Connected device name (advertised, blank -> "Heart Rate Sensor") + address: always.
 * - Live HR: always after HR observation, with distinct Unknown / waiting-for-first-sample
 *   / failed presentations.
 * - Skin contact: always, three-state wording (not reported / detected / not detected).
 * - Battery: a value ONLY when the battery capability is CONFIRMED — never fabricated.
 * - RR/HRV: only when rrIntervals is CONFIRMED, otherwise "HRV unavailable" (never promised).
 * - "Save raw ECG" + "Torso Biomechanics" + Tare/Reset: ONLY when polarPmd is CONFIRMED.
 * - ECG copy distinguishes the default explanation ("Off by default") from the
 *   current toggle state (SD6).
 */
@Composable
fun HrmSensorSettingsCard(
    modifier: Modifier = Modifier,
    hrmBleManager: HrmBleManager = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val connectionState by hrmBleManager.connectionState.collectAsState()
    val scannedDevices by hrmBleManager.scannedDevices.collectAsState()
    val batteryLevel by hrmBleManager.batteryLevel.collectAsState()
    val hrMeasurement by hrmBleManager.hrMeasurement.collectAsState()
    val capabilities by hrmBleManager.capabilities.collectAsState()

    val rememberedAddress by hrmBleManager.rememberedAddress.collectAsState()
    val rememberedName by hrmBleManager.rememberedName.collectAsState()
    val isAutoReconnectEnabled by hrmBleManager.isAutoReconnectEnabled.collectAsState()
    val isRawEcgStorageEnabled by hrmBleManager.isRawEcgStorageEnabled.collectAsState()

    val torsoPitchDeg by hrmBleManager.torsoPitchDeg.collectAsState()
    val torsoRollDeg by hrmBleManager.torsoRollDeg.collectAsState()
    val dynamicAccMg by hrmBleManager.dynamicAccMg.collectAsState()

    val accessibilityColors = AccessibilityTheme.colors
    val isCompactLayout = isCompactAccessibilityLayout()

    PhoenixSectionCard(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stackActions = isCompactLayout || maxWidth < 340.dp
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        SectionHeaderIconBadge(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Heart Rate Sensor",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Heart Rate Sensor",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                when (connectionState) {
                                    is ConnectionState.Connected -> "Connected"
                                    is ConnectionState.Connecting -> "Connecting..."
                                    is ConnectionState.Scanning -> "Scanning for sensors..."
                                    is ConnectionState.Error -> "Connection error"
                                    is ConnectionState.Disconnected -> "Disconnected"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (connectionState) {
                                    is ConnectionState.Connected -> accessibilityColors.success
                                    is ConnectionState.Scanning, is ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
                                    is ConnectionState.Error -> MaterialTheme.colorScheme.error
                                    is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    // Header status action (inline when wide, stacked below when compact/scaled)
                    if (!stackActions && connectionState is ConnectionState.Connected) {
                        OutlinedButton(
                            onClick = { scope.launch { hrmBleManager.disconnect() } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                "Disconnect",
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                if (stackActions && connectionState is ConnectionState.Connected) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { hrmBleManager.disconnect() } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                "Disconnect",
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Body based on ConnectionState
                when (val state = connectionState) {
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        Text(
                            "Connect a heart rate sensor for real-time heart rate. Chest straps may also provide R-R intervals, ECG, and motion telemetry when supported.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.medium))

                        if (rememberedAddress != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        "Remembered Sensor",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        rememberedName?.takeIf { it.isNotBlank() } ?: "Heart Rate Sensor",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        rememberedAddress ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { hrmBleManager.forgetRememberedDevice() },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Forget", color = MaterialTheme.colorScheme.error)
                                    }
                                    Button(
                                        onClick = { scope.launch { hrmBleManager.autoReconnectIfRemembered() } },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Reconnect")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(Spacing.small))
                        }

                        Button(
                            onClick = { scope.launch { hrmBleManager.startScanning() } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (rememberedAddress != null) "Scan for Other Sensors" else "Scan for Heart Rate Sensors")
                        }
                    }

                    is ConnectionState.Scanning -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LoadingIndicator(LoadingIndicatorSize.Medium)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Looking for nearby heart rate sensors...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (!stackActions) {
                                OutlinedButton(
                                    onClick = { scope.launch { hrmBleManager.stopScanning() } },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Stop")
                                }
                            }
                        }
                        if (stackActions) {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { hrmBleManager.stopScanning() } },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Stop")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            "Garmin watch or strap? Enable its Heart Rate Broadcast mode (e.g. Settings > Sensors & Accessories > Broadcast Heart Rate) so it appears here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        if (scannedDevices.isEmpty()) {
                            Text(
                                "Ensure your heart rate sensor strap is moistened and worn firmly on your chest.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                scannedDevices.forEach { device ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainer,
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    device.name.ifBlank { "Heart Rate Sensor" },
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    device.address,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (!stackActions) {
                                                Button(
                                                    onClick = { scope.launch { hrmBleManager.connect(device) } },
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    Text("Connect")
                                                }
                                            }
                                        }
                                        if (stackActions) {
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                            ) {
                                                Button(
                                                    onClick = { scope.launch { hrmBleManager.connect(device) } },
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    Text("Connect")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is ConnectionState.Connecting -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            LoadingIndicator(LoadingIndicatorSize.Medium)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Connecting to heart rate sensor...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is ConnectionState.Connected -> {
                        val caps = capabilities
                        // Device info & metrics grid
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Device Name & Address (advertised name; blank -> generic) — always shown.
                            Text(
                                "Device: ${state.deviceName.ifBlank { "Heart Rate Sensor" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Address: ${state.deviceAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            // Battery — a value ONLY when CONFIRMED; never fabricated.
                            if (caps.battery == HrmCapabilityState.CONFIRMED) {
                                Text(
                                    "Battery: ${batteryLevel?.let { "$it%" } ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if ((batteryLevel ?: 100) < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    "Battery unavailable",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Live HR — distinct Unknown / waiting-for-first-sample / failed states (SD2).
                            when (caps.standardHr) {
                                HrmCapabilityState.UNKNOWN -> Text(
                                    "Live HR: unknown",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HrmCapabilityState.CHECKING -> Text(
                                    "Live HR: subscribing to heart rate data...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                HrmCapabilityState.WAITING_FIRST_SAMPLE -> Text(
                                    "Live HR: waiting for first sample...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                HrmCapabilityState.CONFIRMED -> Text(
                                    "Live HR: ${hrMeasurement?.hr?.let { "$it BPM" } ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                HrmCapabilityState.ABSENT -> Text(
                                    "Live HR: unavailable — not a standard heart rate sensor",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                HrmCapabilityState.FAILED -> Text(
                                    "Live HR: unavailable (heart rate sensor error)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            // Skin contact — always, three-state wording (SD2).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val (contactLabel, contactColor) = when (caps.contact) {
                                    ContactReportState.REPORTED_DETECTED ->
                                        "Skin contact detected" to accessibilityColors.success
                                    ContactReportState.REPORTED_NOT_DETECTED ->
                                        "Skin contact not detected" to accessibilityColors.warning
                                    ContactReportState.NOT_REPORTED ->
                                        "Skin contact: not reported" to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(
                                    if (caps.contact == ContactReportState.REPORTED_DETECTED) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.Warning
                                    },
                                    contentDescription = null,
                                    tint = contactColor,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    contactLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contactColor,
                                )
                            }

                            // RR/HRV — only promised when RR intervals were actually observed (SD1).
                            if (caps.rrIntervals == HrmCapabilityState.CONFIRMED) {
                                Text(
                                    "R-R intervals available · HRV (RMSSD)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accessibilityColors.connectedGreen,
                                )
                            } else {
                                Text(
                                    "HRV unavailable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .toggleable(
                                        value = isAutoReconnectEnabled,
                                        role = Role.Switch,
                                        onValueChange = { hrmBleManager.setAutoReconnectEnabled(it) },
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Auto-reconnect on workout",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "Remember this sensor for future sessions",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = isAutoReconnectEnabled,
                                    onCheckedChange = null,
                                )
                            }
                        }

                        // PMD-gated controls (SD2/SD3): raw ECG storage, Torso Biomechanics and
                        // Tare/Reset only exist for a sensor whose Polar PMD service is CONFIRMED.
                        if (caps.polarPmd == HrmCapabilityState.CONFIRMED) {
                            Spacer(modifier = Modifier.height(Spacing.medium))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .toggleable(
                                        value = isRawEcgStorageEnabled,
                                        role = Role.Switch,
                                        onValueChange = hrmBleManager::setRawEcgStorageEnabled,
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Save raw ECG", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Off by default. Save up to 5 minutes per recording locally. Raw ECG is removed after 30 days or when its workout is deleted; a storage limit can remove older traces sooner. ECG is not synced to the portal.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Currently: ${if (isRawEcgStorageEnabled) "On" else "Off"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRawEcgStorageEnabled) accessibilityColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = isRawEcgStorageEnabled,
                                    onCheckedChange = null,
                                )
                            }

                            // Torso Biomechanics Posture Section
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                "Torso Biomechanics (50 Hz ACC)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Pitch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = torsoPitchDeg?.let { "${(it * 10.0).roundToInt() / 10.0}°" } ?: "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Roll", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = torsoRollDeg?.let { "${(it * 10.0).roundToInt() / 10.0}°" } ?: "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Dynamic Acc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = dynamicAccMg?.let { "$it mG" } ?: "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { hrmBleManager.tareCurrentPosture() },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Tare Posture")
                                    }
                                    OutlinedButton(
                                        onClick = { hrmBleManager.resetPostureTare() },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Reset Tare")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
