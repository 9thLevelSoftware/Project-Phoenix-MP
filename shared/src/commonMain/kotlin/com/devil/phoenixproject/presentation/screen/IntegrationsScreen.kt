package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.isIosPlatform
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.rememberHealthPermissionRequester
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.readUriContent
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    weightUnit: WeightUnit = WeightUnit.KG,
    onNavigateToExternalActivities: () -> Unit = {},
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: IntegrationsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val healthPermissionRequester = rememberHealthPermissionRequester()
    var triggerHealthPermissionRequest by remember { mutableStateOf(false) }

    // Set screen title
    LaunchedEffect(Unit) {
        onSetTitle("Integrations")
    }

    // ── Snackbar ──────────────────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.healthPermissionRequests.collect {
            triggerHealthPermissionRequest = true
        }
    }

    if (triggerHealthPermissionRequest) {
        healthPermissionRequester.LaunchPermissionRequest { granted ->
            triggerHealthPermissionRequest = false
            viewModel.onHealthPermissionResult(granted)
        }
    }

    // ── CSV file saver (triggered when csvContent is set) ────────────────────
    var triggerCsvSave by remember { mutableStateOf(false) }
    var csvExportWeightUnit by remember { mutableStateOf(weightUnit) }

    LaunchedEffect(uiState.csvContent) {
        if (uiState.csvContent != null) {
            triggerCsvSave = true
        }
    }

    if (triggerCsvSave && uiState.csvContent != null) {
        val filePicker = rememberFilePicker()
        filePicker.LaunchFileSaver(
            fileName = "phoenix_workouts.csv",
            content = uiState.csvContent!!,
            onSaved = { path ->
                triggerCsvSave = false
                viewModel.clearCsvContent()
                if (path != null) {
                    scope.launch {
                        snackbarHostState.showSnackbar("CSV saved successfully")
                    }
                }
            }
        )
    }

    // ── CSV file picker (import) ──────────────────────────────────────────────
    var triggerCsvImport by remember { mutableStateOf(false) }
    var importWeightUnit by remember { mutableStateOf(weightUnit) }

    if (triggerCsvImport) {
        val filePicker = rememberFilePicker()
        filePicker.LaunchCsvFilePicker { uri ->
            triggerCsvImport = false
            if (uri != null) {
                scope.launch {
                    val content = readUriContent(uri)
                    if (content != null) {
                        viewModel.previewCsvImport(
                            content = content,
                            weightUnit = importWeightUnit
                        )
                    } else {
                        snackbarHostState.showSnackbar("Could not read file")
                    }
                }
            }
        }
    }

    // ── Import preview dialog ─────────────────────────────────────────────────
    uiState.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Import Preview", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text("Format: ${preview.format.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("Workouts found: ${preview.workoutCount}", style = MaterialTheme.typography.bodyMedium)
                    preview.dateRange?.let { (earliest, latest) ->
                        Text(
                            "Date range: ${KmpUtils.formatTimestamp(earliest)} – ${KmpUtils.formatTimestamp(latest)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    val totalMins = preview.totalDurationSeconds / 60
                    if (totalMins > 0) {
                        Text("Total duration: ${totalMins}m", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (preview.errors.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${preview.errors.size} row(s) could not be parsed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmImport() },
                    enabled = !uiState.isImporting
                ) {
                    if (uiState.isImporting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Import ${preview.activities.size} workout(s)")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── API Key dialogs ───────────────────────────────────────────────────────
    var showHevyApiKeyDialog by remember { mutableStateOf(false) }
    var showLiftosaurApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }

    if (showHevyApiKeyDialog) {
        ApiKeyInputDialog(
            title = "Connect Hevy",
            hint = "Enter your Hevy API key",
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            onConfirm = {
                viewModel.connectProvider(IntegrationProvider.HEVY, apiKeyInput)
                showHevyApiKeyDialog = false
                apiKeyInput = ""
            },
            onDismiss = {
                showHevyApiKeyDialog = false
                apiKeyInput = ""
            }
        )
    }

    if (showLiftosaurApiKeyDialog) {
        ApiKeyInputDialog(
            title = "Connect Liftosaur",
            hint = "Enter your Liftosaur API key",
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            onConfirm = {
                viewModel.connectProvider(IntegrationProvider.LIFTOSAUR, apiKeyInput)
                showLiftosaurApiKeyDialog = false
                apiKeyInput = ""
            },
            onDismiss = {
                showLiftosaurApiKeyDialog = false
                apiKeyInput = ""
            }
        )
    }

    // ── Weight unit toggle for CSV export ─────────────────────────────────────
    var csvWeightUnit by remember { mutableStateOf(weightUnit) }

    // ── Main content ─────────────────────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium)
        ) {
            // ── Health Apps section ───────────────────────────────────────────
            SectionHeader(
                icon = Icons.Default.Favorite,
                title = "Health Apps",
                iconGradient = listOf(Color(0xFFEF4444), Color(0xFFEC4899))
            )

            val healthProvider = if (isIosPlatform) IntegrationProvider.APPLE_HEALTH else IntegrationProvider.GOOGLE_HEALTH
            val healthStatus = uiState.integrationStatuses[healthProvider]
            val healthConnected = healthStatus?.status == ConnectionStatus.CONNECTED

            IntegrationCard(
                title = if (isIosPlatform) "Apple Health" else "Google Health Connect",
                subtitle = if (healthConnected) "Connected" else "Not connected",
                statusConnected = healthConnected,
                trailingContent = {
                    Switch(
                        checked = healthConnected,
                        onCheckedChange = { enabled ->
                            viewModel.toggleHealthIntegration(enabled)
                        }
                    )
                }
            )

            // ── Workout Trackers section ──────────────────────────────────────
            SectionHeader(
                icon = Icons.Default.FitnessCenter,
                title = "Workout Trackers",
                iconGradient = listOf(Color(0xFFFF6B35), Color(0xFFDC2626))
            )

            // Hevy card
            val hevyStatus = uiState.integrationStatuses[IntegrationProvider.HEVY]
            val hevyConnected = hevyStatus?.status == ConnectionStatus.CONNECTED
            IntegrationCard(
                title = "Hevy",
                subtitle = "Requires Hevy PRO for API sync",
                statusConnected = hevyConnected,
                trailingContent = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (hevyConnected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.syncProvider(IntegrationProvider.HEVY)
                                    },
                                    enabled = !uiState.isSyncing,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (uiState.isSyncing) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Sync", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.disconnectProvider(IntegrationProvider.HEVY)
                                    },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        "Disconnect",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    apiKeyInput = ""
                                    showHevyApiKeyDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Connect", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            )

            // Liftosaur card
            val liftosaurStatus = uiState.integrationStatuses[IntegrationProvider.LIFTOSAUR]
            val liftosaurConnected = liftosaurStatus?.status == ConnectionStatus.CONNECTED
            IntegrationCard(
                title = "Liftosaur",
                subtitle = "Requires Liftosaur Premium",
                statusConnected = liftosaurConnected,
                trailingContent = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (liftosaurConnected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.syncProvider(IntegrationProvider.LIFTOSAUR)
                                    },
                                    enabled = !uiState.isSyncing,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (uiState.isSyncing) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Sync", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.disconnectProvider(IntegrationProvider.LIFTOSAUR)
                                    },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        "Disconnect",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    apiKeyInput = ""
                                    showLiftosaurApiKeyDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Connect", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            )

            // Strong / CSV card (no connection state — always available)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Icon(
                            Icons.Default.TableChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Strong / CSV",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "Import or export workouts in Strong-compatible CSV format",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Weight unit toggle for CSV
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Weight unit:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = csvWeightUnit == WeightUnit.KG,
                            onClick = { csvWeightUnit = WeightUnit.KG },
                            label = { Text("kg", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = csvWeightUnit == WeightUnit.LB,
                            onClick = { csvWeightUnit = WeightUnit.LB },
                            label = { Text("lbs", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                csvExportWeightUnit = csvWeightUnit
                                viewModel.exportCsv(csvWeightUnit)
                            },
                            enabled = !uiState.isExporting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Export CSV", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                importWeightUnit = csvWeightUnit
                                triggerCsvImport = true
                            },
                            enabled = !uiState.isImporting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isImporting) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Import CSV", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // ── External Activities navigation link ───────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                onClick = onNavigateToExternalActivities
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF10B981), Color(0xFF06B6D4))
                                    ),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.List,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                "External Activities",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val count = uiState.externalActivities.size
                            Text(
                                if (count == 0) "No activities imported" else "$count imported",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "View external activities",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.medium))
        }
    }
}

// ─── Shared private components ──────────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    iconGradient: List<Color>
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    Brush.linearGradient(iconGradient),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    subtitle: String,
    statusConnected: Boolean,
    trailingContent: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    // Status badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (statusConnected)
                            Color(0xFF10B981).copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (statusConnected) "Connected" else "Not connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (statusConnected) Color(0xFF10B981)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.small))
            trailingContent()
        }
    }
}

@Composable
private fun ApiKeyInputDialog(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    "Enter your API key to enable automatic sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
