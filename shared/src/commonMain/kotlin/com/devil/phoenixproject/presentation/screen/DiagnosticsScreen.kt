package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.ble.DiagnosticFault
import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.data.ble.formatDiagnosticUInt32
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import com.devil.phoenixproject.presentation.viewmodel.DiagnosticsUiState
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.presentation.viewmodel.DiagnosticsViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_copied_to_clipboard
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_copy_action
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_crash
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_empty_description
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_empty_title
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_faults
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_none_reported
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_raw
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_seconds
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_stack_base64
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_status_faults_detected
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_status_no_active_faults
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_status_no_snapshot
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_temperatures
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_title
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_uptime
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_waiting_description
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_waiting_title
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_warnings

@Composable
fun DiagnosticsScreen(
    mainViewModel: MainViewModel,
    diagnosticsViewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val uiState by diagnosticsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCopiedMessage by remember { mutableStateOf(false) }
    val copiedMessage = stringResource(Res.string.diagnostics_copied_to_clipboard)

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        mainViewModel.updateTopBarTitle("")
    }

    LaunchedEffect(showCopiedMessage, copiedMessage) {
        if (showCopiedMessage) {
            snackbarHostState.showSnackbar(
                message = copiedMessage,
                duration = SnackbarDuration.Short,
            )
            showCopiedMessage = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiagnosticsHeader(
                    uiState = uiState,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(uiState.exportText))
                        showCopiedMessage = true
                    },
                )
            }

            val packet = uiState.packet
            if (!uiState.isConnected && packet == null) {
                item { DiagnosticsEmptyState() }
            } else if (packet == null) {
                item { DiagnosticsWaitingState() }
            } else {
                item { UptimeSection(packet) }
                item { FaultsSection(uiState.faults) }
                item { TemperaturesSection(packet) }
                packet.crash?.let { crash ->
                    item {
                        DetailSection(title = stringResource(Res.string.diagnostics_crash)) {
                            DiagnosticKeyValue(stringResource(Res.string.diagnostics_seconds), crash.seconds.toString())
                            DiagnosticKeyValue(
                                stringResource(Res.string.diagnostics_stack_base64),
                                crash.stackBase64,
                                monospace = true,
                            )
                        }
                    }
                }
                packet.warnings?.let { warnings ->
                    item {
                        DetailSection(title = stringResource(Res.string.diagnostics_warnings)) {
                            DiagnosticKeyValue(
                                stringResource(Res.string.diagnostics_raw),
                                "$warnings (${formatDiagnosticUInt32(warnings)})",
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DiagnosticsHeader(uiState: DiagnosticsUiState, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.diagnostics_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = uiState.connectionLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = when {
                        uiState.packet == null -> stringResource(Res.string.diagnostics_status_no_snapshot)
                        uiState.packet.hasFaults -> stringResource(Res.string.diagnostics_status_faults_detected)
                        else -> stringResource(Res.string.diagnostics_status_no_active_faults)
                    },
                    isWarning = uiState.packet?.hasFaults == true,
                )
                Text(
                    text = uiState.lastUpdatedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraSmall,
                enabled = uiState.packet != null,
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.diagnostics_copy_action))
            }
        }
    }
}

@Composable
private fun DiagnosticsEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.diagnostics_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.diagnostics_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun DiagnosticsWaitingState() {
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    DetailSection(title = stringResource(Res.string.diagnostics_waiting_title)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // Active polling indicator — static Icon when reduceMotion is on.
            if (reduceMotion) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Phase 3 convention: LoadingIndicator is the only spinner component.
                LoadingIndicator(size = LoadingIndicatorSize.Small)
            }
            Text(
                text = stringResource(Res.string.diagnostics_waiting_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UptimeSection(packet: DiagnosticPacket) {
    DetailSection(title = stringResource(Res.string.diagnostics_uptime)) {
        DiagnosticKeyValue(stringResource(Res.string.diagnostics_seconds), packet.runtimeSeconds.toString())
    }
}

@Composable
private fun FaultsSection(faults: List<DiagnosticFault>) {
    DetailSection(title = stringResource(Res.string.diagnostics_faults)) {
        if (faults.isEmpty()) {
            Text(
                text = stringResource(Res.string.diagnostics_none_reported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            faults.forEachIndexed { index, fault ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                FaultRow(fault)
            }
        }
    }
}

@Composable
private fun FaultRow(fault: DiagnosticFault) {
    val color = if (fault.hasFault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fault.category.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = fault.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
        Text(
            text = "${fault.rawHex} / ${fault.code}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

@Composable
private fun TemperaturesSection(packet: DiagnosticPacket) {
    DetailSection(title = stringResource(Res.string.diagnostics_temperatures)) {
        if (packet.temperatures.isEmpty()) {
            Text(
                text = stringResource(Res.string.diagnostics_none_reported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            packet.temperatures.forEachIndexed { index, temp ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DiagnosticKeyValue("T${index + 1}", temp.toString())
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticKeyValue(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.6f),
            maxLines = if (monospace) 4 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPill(text: String, isWarning: Boolean) {
    val color = if (isWarning) MaterialTheme.colorScheme.error else AccessibilityTheme.colors.success
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
