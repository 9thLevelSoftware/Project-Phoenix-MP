package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.ble.PolarH10BleManager
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun PolarH10SettingsCard(
    modifier: Modifier = Modifier,
    polarBleManager: PolarH10BleManager = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val connectionState by polarBleManager.connectionState.collectAsState()
    val scannedDevices by polarBleManager.scannedDevices.collectAsState()
    val batteryLevel by polarBleManager.batteryLevel.collectAsState()
    val hrMeasurement by polarBleManager.hrMeasurement.collectAsState()

    val latestEcg by polarBleManager.latestEcg.collectAsState()
    val latestAcc by polarBleManager.latestAcc.collectAsState()

    val rememberedAddress by polarBleManager.rememberedAddress.collectAsState()
    val rememberedName by polarBleManager.rememberedName.collectAsState()
    val isAutoReconnectEnabled by polarBleManager.isAutoReconnectEnabled.collectAsState()

    val torsoPitchDeg by polarBleManager.torsoPitchDeg.collectAsState()
    val torsoRollDeg by polarBleManager.torsoRollDeg.collectAsState()
    val dynamicAccMg by polarBleManager.dynamicAccMg.collectAsState()
    val isContactDetected by polarBleManager.isContactDetected.collectAsState()
    val isAccStreaming by polarBleManager.isAccStreaming.collectAsState()
    val isEcgStreaming by polarBleManager.isEcgStreaming.collectAsState()

    val ecgSamples = remember { mutableStateListOf<Int>() }
    LaunchedEffect(isEcgStreaming) {
        if (!isEcgStreaming) {
            ecgSamples.clear()
        } else {
            polarBleManager.ecgStream.collect { ecg ->
                ecgSamples.addAll(ecg.samplesMicrovolts)
                while (ecgSamples.size > 260) {
                    ecgSamples.removeAt(0)
                }
            }
        }
    }



    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFE53935), Color(0xFFD81B60)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Polar H10",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Column {
                        Text(
                            "Polar H10 Sensor",
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
                                is ConnectionState.Connected -> Color(0xFF00C853)
                                is ConnectionState.Scanning, is ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
                                is ConnectionState.Error -> MaterialTheme.colorScheme.error
                                is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                // Header status badge / action
                if (connectionState is ConnectionState.Connected) {
                    OutlinedButton(
                        onClick = { scope.launch { polarBleManager.disconnect() } },
                    ) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Body based on ConnectionState
            when (val state = connectionState) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    Text(
                        "Connect your Polar H10 chest strap for real-time heart rate, R-R HRV intervals, 130 Hz ECG trace, and 50 Hz chest acceleration telemetry.",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Remembered Sensor",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        rememberedName ?: "Polar H10",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        rememberedAddress ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { polarBleManager.forgetRememberedDevice() },
                                    ) {
                                        Text("Forget", color = MaterialTheme.colorScheme.error)
                                    }
                                    Button(
                                        onClick = { scope.launch { polarBleManager.autoReconnectIfRemembered() } },
                                    ) {
                                        Text("Reconnect")
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(Spacing.small))
                    }

                    Button(
                        onClick = { scope.launch { polarBleManager.startScanning() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (rememberedAddress != null) "Scan for Other Sensors" else "Scan for Polar H10")
                    }
                }

                is ConnectionState.Scanning -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Looking for nearby Polar devices...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { scope.launch { polarBleManager.stopScanning() } },
                        ) {
                            Text("Stop")
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.small))

                    if (scannedDevices.isEmpty()) {
                        Text(
                            "Ensure your Polar H10 strap is moistened and worn firmly on your chest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            scannedDevices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainer,
                                            MaterialTheme.shapes.small,
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            device.name.ifBlank { "Polar H10" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Button(
                                        onClick = { scope.launch { polarBleManager.connect(device) } },
                                    ) {
                                        Text("Connect")
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
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Establishing secure BLE connection...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is ConnectionState.Connected -> {
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
                        // Device Name & Address
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Device: ${state.deviceName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Battery: ${batteryLevel?.let { "$it%" } ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if ((batteryLevel ?: 100) < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }

                        // Skin contact & live HR
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val contact = isContactDetected
                                Icon(
                                    if (contact) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (contact) Color(0xFF00C853) else Color(0xFFFFB300),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (contact) "Skin Contact OK" else "Poor Skin Contact",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (contact) Color(0xFF00C853) else Color(0xFFFFB300),
                                )
                            }

                            Text(
                                "Live HR: ${hrMeasurement?.hr?.let { "$it BPM" } ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
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
                                onCheckedChange = { polarBleManager.setAutoReconnectEnabled(it) },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "50 Hz ACC Stream",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Stream live chest accelerometer & posture",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isAccStreaming,
                                onCheckedChange = { enable ->
                                    scope.launch {
                                        if (enable) polarBleManager.startAccStream() else polarBleManager.stopAccStream()
                                    }
                                },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "130 Hz ECG Stream",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Stream live single-lead electrocardiogram trace",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isEcgStreaming,
                                onCheckedChange = { enable ->
                                    scope.launch {
                                        if (enable) polarBleManager.startEcgStream() else polarBleManager.stopEcgStream()
                                    }
                                },
                            )
                        }

                        AnimatedVisibility(visible = isEcgStreaming) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(Color(0xFF0D1B2A))
                                        .border(1.dp, Color(0xFF1E3A5F), MaterialTheme.shapes.small),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ecgSamples.isEmpty()) {
                                        Text(
                                            text = if (isContactDetected) "Waiting for ECG signal..." else "Ensure strap electrodes are moist and snug against skin",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    EcgWaveformCanvas(
                                        samples = ecgSamples,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight()
                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("Pitch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = torsoPitchDeg?.let { "${(it * 10.0).roundToInt() / 10.0}°" } ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column {
                                Text("Roll", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = torsoRollDeg?.let { "${(it * 10.0).roundToInt() / 10.0}°" } ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column {
                                Text("Dynamic Acc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = dynamicAccMg?.let { "$it mG" } ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { polarBleManager.tareCurrentPosture() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Tare Posture")
                            }
                            OutlinedButton(
                                onClick = { polarBleManager.resetPostureTare() },
                                modifier = Modifier.weight(1f),
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
