package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.data.local.ConnectionLogEntity
import com.devil.phoenixproject.data.repository.LogLevel
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * Connection logs screen - shows BLE connection history with filtering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionLogsScreen(
    onNavigateBack: () -> Unit,
    mainViewModel: MainViewModel,
    logsViewModel: ConnectionLogsViewModel = koinViewModel()
) {
    val logs by logsViewModel.logs.collectAsState()
    val filter by logsViewModel.filter.collectAsState()
    val isAutoScrollEnabled by logsViewModel.isAutoScrollEnabled.collectAsState()
    val isLoggingEnabled by logsViewModel.isLoggingEnabled.collectAsState()

    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    var showExportDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var exportContent by remember { mutableStateOf("") }
    var showCopiedMessage by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        mainViewModel.updateTopBarTitle("")
    }

    // Auto-scroll to top when new logs arrive
    LaunchedEffect(logs.size, isAutoScrollEnabled) {
        if (isAutoScrollEnabled && logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Show snackbar when content is copied
    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            snackbarHostState.showSnackbar(
                message = "Logs copied to clipboard",
                duration = SnackbarDuration.Short
            )
            showCopiedMessage = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // Search bar
        OutlinedTextField(
            value = filter.searchQuery,
            onValueChange = { logsViewModel.setSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search logs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (filter.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { logsViewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogLevelFilterChip(
                level = LogLevel.DEBUG,
                selected = filter.showDebug,
                count = logs.count { it.level == LogLevel.DEBUG.name },
                onClick = { logsViewModel.toggleLevel(LogLevel.DEBUG) }
            )
            LogLevelFilterChip(
                level = LogLevel.INFO,
                selected = filter.showInfo,
                count = logs.count { it.level == LogLevel.INFO.name },
                onClick = { logsViewModel.toggleLevel(LogLevel.INFO) }
            )
            LogLevelFilterChip(
                level = LogLevel.WARNING,
                selected = filter.showWarning,
                count = logs.count { it.level == LogLevel.WARNING.name },
                onClick = { logsViewModel.toggleLevel(LogLevel.WARNING) }
            )
            LogLevelFilterChip(
                level = LogLevel.ERROR,
                selected = filter.showError,
                count = logs.count { it.level == LogLevel.ERROR.name },
                onClick = { logsViewModel.toggleLevel(LogLevel.ERROR) }
            )
        }

        // Action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${logs.size} logs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Auto-scroll toggle
                FilterChip(
                    selected = isAutoScrollEnabled,
                    onClick = { logsViewModel.toggleAutoScroll() },
                    label = { Text("Auto-scroll") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                // Logging toggle
                FilterChip(
                    selected = isLoggingEnabled,
                    onClick = { logsViewModel.setLoggingEnabled(!isLoggingEnabled) },
                    label = { Text(if (isLoggingEnabled) "Logging ON" else "Logging OFF") }
                )

                // Export button
                IconButton(onClick = {
                    exportContent = logsViewModel.exportLogsAsText()
                    showExportDialog = true
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Export logs")
                }

                // Clear button
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
            }
        }

        HorizontalDivider()

        // Logs list
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No logs yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connection events will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogEntryCard(log)
                }
            }
        }
        }

        // Snackbar for clipboard confirmation
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Export dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Logs") },
            text = {
                Column {
                    Text("${logs.size} log entries ready to export.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Log content preview:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    BoxWithConstraints {
                        val previewHeight = (maxHeight * 0.4f).coerceIn(150.dp, 400.dp)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(previewHeight)
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = exportContent.take(2000) + if (exportContent.length > 2000) "\n..." else "",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Issue #112 fix: Actually copy to clipboard
                    clipboardManager.setText(AnnotatedString(exportContent))
                    showCopiedMessage = true
                    showExportDialog = false
                }) {
                    Text("Copy to Clipboard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs?") },
            text = { Text("This will permanently delete all ${logs.size} log entries.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logsViewModel.clearAllLogs()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LogLevelFilterChip(
    level: LogLevel,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = getLevelColor(level),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("${level.name} ($count)")
            }
        }
    )
}

@Composable
private fun LogEntryCard(log: ConnectionLogEntity) {
    val level = LogLevel.entries.find { it.name == log.level } ?: LogLevel.DEBUG
    val levelColor = getLevelColor(level)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = levelColor.copy(alpha = 0.05f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header row: timestamp + level + event type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Level indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(levelColor, RoundedCornerShape(5.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Event type
                    Text(
                        text = log.eventType,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = levelColor
                    )
                }

                // Timestamp
                Text(
                    text = formatLogTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Device info if present
            if (log.deviceName != null || log.deviceAddress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Device: ${log.deviceName ?: "Unknown"} (${log.deviceAddress ?: "N/A"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Details if present
            if (log.details != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = log.details,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun getLevelColor(level: LogLevel): Color {
    return when (level) {
        LogLevel.DEBUG -> Color(0xFF9E9E9E) // Gray
        LogLevel.INFO -> Color(0xFF2196F3)  // Blue
        LogLevel.WARNING -> Color(0xFFFF9800) // Orange
        LogLevel.ERROR -> Color(0xFFF44336) // Red
    }
}

private fun formatLogTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.hour.toString().padStart(2, '0')}:" +
           "${localDateTime.minute.toString().padStart(2, '0')}:" +
           "${localDateTime.second.toString().padStart(2, '0')}.${(timestamp % 1000).toString().padStart(3, '0')}"
}
