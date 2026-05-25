package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.ble.DiagnosticFault
import com.devil.phoenixproject.data.ble.DiagnosticPacket
import com.devil.phoenixproject.data.ble.decodeDiagnosticFaults
import com.devil.phoenixproject.data.ble.formatDiagnosticUInt32
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.util.Constants
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class DiagnosticsUiState(
    val connectionLabel: String = "Disconnected",
    val isConnected: Boolean = false,
    val packet: DiagnosticPacket? = null,
    val faults: List<DiagnosticFault> = emptyList(),
    val lastUpdatedLabel: String = "Not read yet",
    val exportText: String = buildDiagnosticsExportText(
        connectionLabel = "Disconnected",
        packet = null,
        faults = emptyList(),
        exportedAtMillis = Clock.System.now().toEpochMilliseconds(),
    ),
)

class DiagnosticsViewModel(
    private val bleRepository: BleRepository,
) : ViewModel() {

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        bleRepository.connectionState,
        bleRepository.diagnostics,
    ) { connectionState, packet ->
        val faults = packet?.let(::decodeDiagnosticFaults).orEmpty()
        val connectionLabel = connectionState.toDiagnosticsConnectionLabel()
        DiagnosticsUiState(
            connectionLabel = connectionLabel,
            isConnected = connectionState is ConnectionState.Connected,
            packet = packet,
            faults = faults,
            lastUpdatedLabel = packet?.receivedAtMillis?.takeIf { it > 0L }?.let(::formatTimestamp) ?: "Not read yet",
            exportText = buildDiagnosticsExportText(
                connectionLabel = connectionLabel,
                packet = packet,
                faults = faults,
                exportedAtMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DiagnosticsUiState(),
    )
}

fun buildDiagnosticsExportText(
    connectionLabel: String,
    packet: DiagnosticPacket?,
    faults: List<DiagnosticFault>,
    exportedAtMillis: Long,
): String = buildString {
    appendLine("=== Vitruvian Machine Diagnostics ===")
    appendLine("App version: ${Constants.APP_VERSION}")
    appendLine("Exported: ${formatTimestamp(exportedAtMillis)}")
    appendLine("Connection: $connectionLabel")

    if (packet == null) {
        appendLine()
        appendLine("No diagnostic snapshot has been read.")
        return@buildString
    }

    appendLine("Last update: ${packet.receivedAtMillis.takeIf { it > 0L }?.let(::formatTimestamp) ?: "Not recorded"}")
    appendLine("Uptime (s): ${packet.runtimeSeconds}")
    appendLine("Contains faults: ${packet.hasFaults}")
    appendLine()
    appendLine("Faults:")
    faults.forEach { fault ->
        appendLine("  ${fault.category.displayName}: ${fault.label} (${fault.rawHex} / ${fault.code})")
    }
    appendLine()
    appendLine("Temperatures:")
    if (packet.temperatures.isEmpty()) {
        appendLine("  None reported")
    } else {
        packet.temperatures.forEachIndexed { index, temp ->
            appendLine("  T${index + 1}: $temp")
        }
    }

    packet.crash?.let { crash ->
        appendLine()
        appendLine("Crash:")
        appendLine("  Seconds: ${crash.seconds}")
        appendLine("  Stack Base64: ${crash.stackBase64}")
    }

    packet.warnings?.let { warnings ->
        appendLine()
        appendLine("Warnings: $warnings (${formatDiagnosticUInt32(warnings)})")
    }
}

private fun ConnectionState.toDiagnosticsConnectionLabel(): String = when (this) {
    is ConnectionState.Connected -> "Connected to $deviceName ($deviceAddress)"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Scanning -> "Scanning"
    is ConnectionState.Error -> "Error: $message"
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.date} ${localDateTime.hour.toString().padStart(2, '0')}:" +
        "${localDateTime.minute.toString().padStart(2, '0')}:" +
        "${localDateTime.second.toString().padStart(2, '0')}"
}
