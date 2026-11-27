package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.local.ConnectionLogEntity
import com.devil.phoenixproject.data.repository.LogLevel
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.PhoenixModalDialogFrame
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.ConnectionLogsViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.auto_scroll
import projectphoenix.shared.generated.resources.cd_clear_logs
import projectphoenix.shared.generated.resources.cd_clear_search
import projectphoenix.shared.generated.resources.cd_export_logs
import projectphoenix.shared.generated.resources.cd_search
import projectphoenix.shared.generated.resources.clear_all
import projectphoenix.shared.generated.resources.clear_logs_message
import projectphoenix.shared.generated.resources.clear_logs_title
import projectphoenix.shared.generated.resources.copy_to_clipboard
import projectphoenix.shared.generated.resources.export_logs
import projectphoenix.shared.generated.resources.log_level_count
import projectphoenix.shared.generated.resources.logs_content_preview
import projectphoenix.shared.generated.resources.logs_copied_to_clipboard
import projectphoenix.shared.generated.resources.logs_count
import projectphoenix.shared.generated.resources.logs_device_info
import projectphoenix.shared.generated.resources.logs_device_unknown
import projectphoenix.shared.generated.resources.logs_empty_subtitle
import projectphoenix.shared.generated.resources.logs_empty_title
import projectphoenix.shared.generated.resources.logs_filter_hrm
import projectphoenix.shared.generated.resources.logs_logging_off
import projectphoenix.shared.generated.resources.logs_logging_on
import projectphoenix.shared.generated.resources.logs_na
import projectphoenix.shared.generated.resources.logs_ready_to_export
import projectphoenix.shared.generated.resources.search_logs

/**
 * Connection logs screen - shows BLE connection history with filtering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionLogsScreen(
    mainViewModel: MainViewModel,
    logsViewModel: ConnectionLogsViewModel = koinViewModel(),
) {
    val logs by logsViewModel.logs.collectAsState()
    val filter by logsViewModel.filter.collectAsState()
    val isAutoScrollEnabled by logsViewModel.isAutoScrollEnabled.collectAsState()
    val isLoggingEnabled by logsViewModel.isLoggingEnabled.collectAsState()
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()

    val listState = rememberLazyListState()

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

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
    val copiedMessage = stringResource(Res.string.logs_copied_to_clipboard)
    LaunchedEffect(showCopiedMessage, copiedMessage) {
        if (showCopiedMessage) {
            snackbarHostState.showSnackbar(
                message = copiedMessage,
                duration = SnackbarDuration.Short,
            )
            showCopiedMessage = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
        ) {
            // Search bar
            OutlinedTextField(
                value = filter.searchQuery,
                onValueChange = { logsViewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(Res.string.search_logs)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(Res.string.cd_search),
                    )
                },
                trailingIcon = {
                    if (filter.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { logsViewModel.setSearchQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(Res.string.cd_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )

            // Filter chips wrap cleanly with FlowRow across all screen widths and font scales
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LogLevelFilterChip(
                    level = LogLevel.DEBUG,
                    selected = filter.showDebug,
                    count = logs.count { it.level == LogLevel.DEBUG.name },
                    onClick = { logsViewModel.toggleLevel(LogLevel.DEBUG) },
                )
                LogLevelFilterChip(
                    level = LogLevel.INFO,
                    selected = filter.showInfo,
                    count = logs.count { it.level == LogLevel.INFO.name },
                    onClick = { logsViewModel.toggleLevel(LogLevel.INFO) },
                )
                LogLevelFilterChip(
                    level = LogLevel.WARNING,
                    selected = filter.showWarning,
                    count = logs.count { it.level == LogLevel.WARNING.name },
                    onClick = { logsViewModel.toggleLevel(LogLevel.WARNING) },
                )
                LogLevelFilterChip(
                    level = LogLevel.ERROR,
                    selected = filter.showError,
                    count = logs.count { it.level == LogLevel.ERROR.name },
                    onClick = { logsViewModel.toggleLevel(LogLevel.ERROR) },
                )
                FilterChip(
                    selected = filter.hrmOnly,
                    onClick = { logsViewModel.toggleHrmOnly() },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(Res.string.logs_filter_hrm)) },
                )
            }

            // Action bar wrapped in FlowRow so controls never clip or require horizontal scrolling
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.logs_count, logs.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )

                    FilterChip(
                        selected = isAutoScrollEnabled,
                        onClick = { logsViewModel.toggleAutoScroll() },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(stringResource(Res.string.auto_scroll)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )

                    FilterChip(
                        selected = isLoggingEnabled,
                        onClick = { logsViewModel.setLoggingEnabled(!isLoggingEnabled) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Text(
                                stringResource(
                                    if (isLoggingEnabled) Res.string.logs_logging_on else Res.string.logs_logging_off,
                                ),
                            )
                        },
                    )

                    IconButton(
                        onClick = {
                            exportContent = logsViewModel.exportLogsAsText()
                            showExportDialog = true
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(Res.string.cd_export_logs),
                        )
                    }

                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(Res.string.cd_clear_logs),
                        )
                    }
                }
            }

            HorizontalDivider()

            // Logs list
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.logs_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.logs_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Export dialog
    if (showExportDialog) {
        PhoenixModalDialogFrame(
            title = stringResource(Res.string.export_logs),
            onDismiss = { showExportDialog = false },
            confirmLabel = stringResource(Res.string.copy_to_clipboard),
            dismissLabel = stringResource(Res.string.action_cancel),
            onConfirm = {
                // Issue #112 fix: Actually copy to clipboard
                clipboardManager.setText(AnnotatedString(exportContent))
                showCopiedMessage = true
                showExportDialog = false
            },
        ) {
            Text(stringResource(Res.string.logs_ready_to_export, logs.size))
            Text(
                text = stringResource(Res.string.logs_content_preview),
                style = MaterialTheme.typography.bodySmall,
            )
            val previewHeight = (LocalWindowSizeClass.current.heightDp * 0.35f).coerceIn(140.dp, 320.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = exportContent.take(2000) +
                        if (exportContent.length > 2000) "\n..." else "",
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        DestructiveConfirmDialog(
            title = stringResource(Res.string.clear_logs_title),
            message = stringResource(Res.string.clear_logs_message, logs.size),
            confirmText = stringResource(Res.string.clear_all),
            onConfirm = {
                logsViewModel.clearAllLogs()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

@Composable
private fun LogLevelFilterChip(level: LogLevel, selected: Boolean, count: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = getLevelColor(level),
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.log_level_count, level.name, count))
            }
        },
    )
}

@Composable
private fun LogEntryCard(log: ConnectionLogEntity) {
    val level = LogLevel.entries.find { it.name == log.level } ?: LogLevel.DEBUG
    val levelColor = getLevelColor(level)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, levelColor.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // Header row: level + event type + optional tag (weighted) alongside timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Level indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(levelColor, RoundedCornerShape(5.dp)),
                    )
                    // Event type
                    Text(
                        text = log.eventType,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = levelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (log.tag != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = log.tag,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Timestamp
                Text(
                    text = formatLogTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Device info if present
            if (log.deviceName != null || log.deviceAddress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        Res.string.logs_device_info,
                        log.deviceName ?: stringResource(Res.string.logs_device_unknown),
                        log.deviceAddress ?: stringResource(Res.string.logs_na),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Details if present
            if (log.details != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = log.details,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun getLevelColor(level: LogLevel): Color {
    val colors = AccessibilityTheme.colors
    return when (level) {
        LogLevel.DEBUG -> colors.neutral

        // Blue (informational, not semantic status)
        LogLevel.INFO -> colors.info

        LogLevel.WARNING -> colors.warning

        LogLevel.ERROR -> colors.error
    }
}

private fun formatLogTimestamp(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.hour.toString().padStart(2, '0')}:" +
        "${localDateTime.minute.toString().padStart(2, '0')}:" +
        "${localDateTime.second.toString().padStart(
            2,
            '0',
        )}.${(timestamp % 1000).toString().padStart(3, '0')}"
}
