package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.isIosPlatform
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import com.devil.phoenixproject.presentation.components.PhoenixModalDialogFrame
import com.devil.phoenixproject.presentation.components.PhoenixSectionCard
import com.devil.phoenixproject.presentation.components.ResponsiveSegmentedSelector
import com.devil.phoenixproject.presentation.components.SectionHeaderIconBadge
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.IntegrationUiEvent
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.readUriContent
import com.devil.phoenixproject.util.rememberFilePicker
import com.devil.phoenixproject.util.rememberHealthPermissionRequester
import com.devil.phoenixproject.util.rememberHealthPermissionSettingsLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    weightUnit: WeightUnit = WeightUnit.KG,
    onNavigateToExternalData: () -> Unit = {},
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: IntegrationsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val healthPermissionRequester = rememberHealthPermissionRequester()
    val healthPermissionSettingsLauncher = rememberHealthPermissionSettingsLauncher()
    var triggerHealthPermissionRequest by remember { mutableStateOf(false) }
    var awaitingHealthPermissionSettingsReturn by remember { mutableStateOf(false) }

    // Set screen title
    val screenTitle = stringResource(Res.string.settings_integrations)
    LaunchedEffect(screenTitle) {
        onSetTitle(screenTitle)
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
        viewModel.uiEvents.collect { event ->
            when (event) {
                is IntegrationUiEvent.Snackbar -> snackbarHostState.showSnackbar(event.message)
                IntegrationUiEvent.OpenHealthPermissionSettings -> {
                    awaitingHealthPermissionSettingsReturn = true
                    healthPermissionSettingsLauncher.openSettings()
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, awaitingHealthPermissionSettingsReturn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingHealthPermissionSettingsReturn) {
                awaitingHealthPermissionSettingsReturn = false
                viewModel.checkHealthPermissionsAfterSettingsReturn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

    LaunchedEffect(uiState.csvContent) {
        if (uiState.csvContent != null) {
            triggerCsvSave = true
        }
    }

    val csvSavedMessage = stringResource(Res.string.integrations_csv_saved)
    val fileReadErrorMessage = stringResource(Res.string.integrations_file_read_error)
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
                        snackbarHostState.showSnackbar(csvSavedMessage)
                    }
                }
            },
        )
    }

    // ── CSV file picker (import) ──────────────────────────────────────────────
    var triggerCsvImport by remember { mutableStateOf(false) }

    if (triggerCsvImport) {
        val filePicker = rememberFilePicker()
        filePicker.LaunchCsvFilePicker { uri ->
            triggerCsvImport = false
            if (uri != null) {
                scope.launch {
                    val content = readUriContent(uri)
                    if (content != null) {
                        viewModel.previewCsvImport(content)
                    } else {
                        snackbarHostState.showSnackbar(fileReadErrorMessage)
                    }
                }
            }
        }
    }

    // ── Import preview dialog ─────────────────────────────────────────────────
    uiState.importPreview?.let { preview ->
        PhoenixModalDialogFrame(
            title = stringResource(Res.string.integrations_import_preview),
            onDismiss = { if (!uiState.isImporting) viewModel.cancelImport() },
            dismissOnClickOutside = !uiState.isImporting,
            actions = {
                OutlinedButton(
                    onClick = { viewModel.cancelImport() },
                    enabled = !uiState.isImporting,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(stringResource(Res.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { viewModel.confirmImport() },
                    enabled = !uiState.isImporting,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    if (uiState.isImporting) {
                        LoadingIndicator(LoadingIndicatorSize.Small)
                    } else {
                        Text(
                            stringResource(Res.string.integrations_import_count, preview.activities.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) {
            Text(stringResource(Res.string.integrations_format, preview.format.name), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.integrations_workouts_found, preview.workoutCount), style = MaterialTheme.typography.bodyMedium)
            preview.dateRange?.let { (earliest, latest) ->
                Text(
                    stringResource(
                        Res.string.integrations_date_range,
                        KmpUtils.formatTimestamp(earliest),
                        KmpUtils.formatTimestamp(latest),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val totalMins = preview.totalDurationSeconds / 60
            if (totalMins > 0) {
                Text(stringResource(Res.string.integrations_total_duration_mins, totalMins), style = MaterialTheme.typography.bodyMedium)
            }
            if (preview.errors.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (preview.errors.size == 1) {
                            stringResource(Res.string.integrations_rows_unparsed_one)
                        } else {
                            stringResource(Res.string.integrations_rows_unparsed_other, preview.errors.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Spacing.small),
                    )
                }
            }
        }
    }

    // ── API Key dialogs ───────────────────────────────────────────────────────
    var showHevyApiKeyDialog by remember { mutableStateOf(false) }
    var showLiftosaurApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }

    if (showHevyApiKeyDialog) {
        ApiKeyInputDialog(
            title = stringResource(Res.string.integrations_connect_hevy),
            hint = stringResource(Res.string.integrations_hevy_api_key_hint),
            helperNote = stringResource(Res.string.integrations_hevy_api_key_note),
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            onConfirm = {
                viewModel.connectProvider(IntegrationProvider.HEVY, apiKeyInput.trim())
                showHevyApiKeyDialog = false
                apiKeyInput = ""
            },
            onDismiss = {
                showHevyApiKeyDialog = false
                apiKeyInput = ""
            },
        )
    }

    if (showLiftosaurApiKeyDialog) {
        ApiKeyInputDialog(
            title = stringResource(Res.string.integrations_connect_liftosaur),
            hint = stringResource(Res.string.integrations_liftosaur_api_key_hint),
            helperNote = stringResource(Res.string.integrations_liftosaur_api_key_note),
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            onConfirm = {
                viewModel.connectProvider(IntegrationProvider.LIFTOSAUR, apiKeyInput.trim())
                showLiftosaurApiKeyDialog = false
                apiKeyInput = ""
            },
            onDismiss = {
                showLiftosaurApiKeyDialog = false
                apiKeyInput = ""
            },
        )
    }

    // ── Integration disconnect confirmation ───────────────────────────────────
    var disconnectTarget by remember { mutableStateOf<IntegrationProvider?>(null) }
    disconnectTarget?.let { provider ->
        DestructiveConfirmDialog(
            title = stringResource(Res.string.disconnect_integration_title),
            message = stringResource(Res.string.disconnect_integration_message, provider.displayName),
            confirmText = stringResource(Res.string.disconnect),
            onConfirm = {
                viewModel.disconnectProvider(provider)
                disconnectTarget = null
            },
            onDismiss = { disconnectTarget = null },
            icon = Icons.Default.LinkOff,
        )
    }

    // ── Weight unit toggle for CSV export ─────────────────────────────────────
    var csvWeightUnit by remember(weightUnit) { mutableStateOf(weightUnit) }
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()

    // ── Main content ─────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // ── Health Apps section ───────────────────────────────────────────
            SectionHeader(
                icon = Icons.Default.Favorite,
                title = stringResource(Res.string.integrations_health_apps),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )

            val healthProvider = if (isIosPlatform) IntegrationProvider.APPLE_HEALTH else IntegrationProvider.GOOGLE_HEALTH
            val healthStatus = uiState.integrationStatuses[healthProvider]
            val healthConnected = healthStatus?.status == ConnectionStatus.CONNECTED
            val healthError = healthStatus?.status == ConnectionStatus.ERROR
            val healthBusy = uiState.operationLoading.any { it.startsWith("${healthProvider.key}:") }
            val latestHealthBodyWeight = uiState.latestHealthBodyWeight
            val latestHealthBodyWeightLabel = latestHealthBodyWeight?.let { measurement ->
                if (weightUnit == WeightUnit.KG) {
                    "${UnitConverter.formatDecimal(measurement.value.toFloat())} ${stringResource(Res.string.label_kg)}"
                } else {
                    "${UnitConverter.formatDecimal(UnitConverter.kgToLb(measurement.value.toFloat()))} ${stringResource(Res.string.label_lb)}"
                }
            }

            IntegrationCard(
                title = stringResource(
                    if (isIosPlatform) Res.string.integrations_apple_health else Res.string.integrations_health_connect,
                ),
                subtitle = when {
                    healthError -> healthStatus.errorMessage ?: stringResource(Res.string.integrations_permissions_attention)
                    healthConnected && latestHealthBodyWeightLabel != null ->
                        stringResource(Res.string.integrations_connected_latest_weight, latestHealthBodyWeightLabel)
                    healthConnected -> stringResource(Res.string.hrm_status_connected)
                    else -> stringResource(Res.string.integrations_status_not_connected)
                },
                statusConnected = healthConnected,
                badges = buildList {
                    latestHealthBodyWeightLabel?.let {
                        add(stringResource(Res.string.integrations_body_weight_value, it))
                    }
                    healthStatus?.lastSyncAt?.let {
                        add(stringResource(Res.string.integrations_last_sync, KmpUtils.formatTimestamp(it)))
                    }
                },
                toggleRow = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .toggleable(
                                value = healthConnected,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    viewModel.toggleHealthIntegration(enabled)
                                },
                            )
                            .padding(vertical = Spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                if (healthConnected) {
                                    Res.string.integrations_health_sync_enabled
                                } else {
                                    Res.string.integrations_enable_health_sync
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = healthConnected, onCheckedChange = null)
                    }
                },
                actions = {
                    if (healthError) {
                        OutlinedButton(
                            onClick = { viewModel.retryHealthPermissions() },
                            enabled = !healthBusy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.integrations_retry_permissions), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (healthConnected || healthError) {
                        OutlinedButton(
                            onClick = { viewModel.syncPreviousHealthWorkouts() },
                            enabled = !healthBusy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (healthBusy) {
                                LoadingIndicator(LoadingIndicatorSize.Small)
                            } else {
                                Text(stringResource(Res.string.integrations_sync_previous), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
            )

            // ── Workout Trackers section ──────────────────────────────────────
            SectionHeader(
                icon = Icons.Default.FitnessCenter,
                title = stringResource(Res.string.integrations_workout_trackers),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            // Hevy card
            val hevyStatus = uiState.integrationStatuses[IntegrationProvider.HEVY]
            val hevyConnected = hevyStatus?.status == ConnectionStatus.CONNECTED
            val hevyEntitlement = uiState.entitlementStateByProvider[IntegrationProvider.HEVY]
            val hevyBusy = uiState.operationLoading.any { it.startsWith("${IntegrationProvider.HEVY.key}:") }
            IntegrationCard(
                title = stringResource(Res.string.integrations_hevy),
                subtitle = hevyEntitlement?.upgradeReason
                    ?: hevyEntitlement?.providerPlanName?.let {
                        stringResource(Res.string.integrations_provider_plan, it)
                    }
                    ?: stringResource(Res.string.integrations_hevy_description),
                statusConnected = hevyConnected,
                badges = listOf(
                    stringResource(
                        Res.string.integrations_workouts_chip,
                        uiState.externalActivities.count { it.provider == IntegrationProvider.HEVY },
                    ),
                    stringResource(
                        Res.string.integrations_routines_chip,
                        uiState.externalRoutines.count { it.provider == IntegrationProvider.HEVY },
                    ),
                    stringResource(
                        Res.string.integrations_folders_chip,
                        uiState.externalRoutineFolders.count { it.provider == IntegrationProvider.HEVY },
                    ),
                    stringResource(
                        Res.string.integrations_templates_chip,
                        uiState.externalExerciseTemplateCountByProvider[IntegrationProvider.HEVY] ?: 0,
                    ),
                    stringResource(
                        Res.string.integrations_measurements_chip,
                        uiState.externalMeasurements.count { it.provider == IntegrationProvider.HEVY },
                    ),
                ),
                actions = {
                    if (hevyConnected) {
                        OutlinedButton(
                            onClick = { viewModel.syncProvider(IntegrationProvider.HEVY) },
                            enabled = !hevyBusy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (hevyBusy) {
                                LoadingIndicator(LoadingIndicatorSize.Small)
                            } else {
                                Text(stringResource(Res.string.integrations_action_sync), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = { disconnectTarget = IntegrationProvider.HEVY },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(Res.string.disconnect),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                apiKeyInput = ""
                                showHevyApiKeyDialog = true
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.hrm_action_connect), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )

            // Liftosaur card
            val liftosaurStatus = uiState.integrationStatuses[IntegrationProvider.LIFTOSAUR]
            val liftosaurConnected = liftosaurStatus?.status == ConnectionStatus.CONNECTED
            val liftosaurEntitlement = uiState.entitlementStateByProvider[IntegrationProvider.LIFTOSAUR]
            val liftosaurBusy = uiState.operationLoading.any { it.startsWith("${IntegrationProvider.LIFTOSAUR.key}:") }
            IntegrationCard(
                title = stringResource(Res.string.integrations_liftosaur),
                subtitle = liftosaurEntitlement?.upgradeReason
                    ?: liftosaurEntitlement?.providerPlanName?.let {
                        stringResource(Res.string.integrations_provider_plan, it)
                    }
                    ?: stringResource(Res.string.integrations_liftosaur_description),
                statusConnected = liftosaurConnected,
                badges = listOf(
                    stringResource(
                        Res.string.integrations_workouts_chip,
                        uiState.externalActivities.count { it.provider == IntegrationProvider.LIFTOSAUR },
                    ),
                    stringResource(
                        Res.string.integrations_programs_chip,
                        uiState.externalPrograms.count { it.provider == IntegrationProvider.LIFTOSAUR },
                    ),
                    stringResource(
                        Res.string.integrations_current_chip,
                        if (uiState.activeProgram != null) 1 else 0,
                    ),
                    stringResource(
                        Res.string.integrations_stats_chip,
                        uiState.externalProgramStatsByProgramId.size,
                    ),
                ),
                actions = {
                    if (liftosaurConnected) {
                        OutlinedButton(
                            onClick = { viewModel.syncProvider(IntegrationProvider.LIFTOSAUR) },
                            enabled = !liftosaurBusy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (liftosaurBusy) {
                                LoadingIndicator(LoadingIndicatorSize.Small)
                            } else {
                                Text(stringResource(Res.string.integrations_action_sync), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = { disconnectTarget = IntegrationProvider.LIFTOSAUR },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(Res.string.disconnect),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                apiKeyInput = ""
                                showLiftosaurApiKeyDialog = true
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.hrm_action_connect), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )

            // Strong / CSV card (no connection state — always available)
            PhoenixSectionCard(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    SectionHeaderIconBadge(
                        imageVector = Icons.Default.TableChart,
                        contentDescription = null,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.integrations_strong_csv),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(Res.string.integrations_strong_csv_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    stringResource(Res.string.integrations_export_weight_unit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val kgUnitLabel = stringResource(Res.string.label_kg)
                val lbsUnitLabel = stringResource(Res.string.label_lbs)
                ResponsiveSegmentedSelector(
                    options = listOf(WeightUnit.KG, WeightUnit.LB),
                    selectedOption = csvWeightUnit,
                    onSelect = { csvWeightUnit = it },
                    labelForOption = { unit -> if (unit == WeightUnit.KG) kgUnitLabel else lbsUnitLabel },
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.exportCsv(csvWeightUnit) },
                        enabled = !uiState.isExporting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        if (uiState.isExporting) {
                            LoadingIndicator(LoadingIndicatorSize.Small)
                        } else {
                            Icon(
                                Icons.Default.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.integrations_export_csv),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { triggerCsvImport = true },
                        enabled = !uiState.isImporting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        if (uiState.isImporting) {
                            LoadingIndicator(LoadingIndicatorSize.Small)
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.integrations_import_csv),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ── External Activities navigation link ───────────────────────────
            ExpressiveCard(
                onClick = onNavigateToExternalData,
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    SectionHeaderIconBadge(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.integrations_data_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val count = uiState.externalActivities.size
                        Text(
                            when (count) {
                                0 -> stringResource(Res.string.integrations_data_empty_subtitle)
                                1 -> stringResource(Res.string.integrations_data_count_subtitle_one)
                                else -> stringResource(Res.string.integrations_data_count_subtitle_other, count)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = stringResource(Res.string.integrations_cd_view_data),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.medium))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

// ─── Shared private components ──────────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SectionHeaderIconBadge(
            imageVector = icon,
            contentDescription = null,
            containerColor = containerColor,
            contentColor = contentColor,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    subtitle: String,
    statusConnected: Boolean,
    badges: List<String> = emptyList(),
    toggleRow: (@Composable () -> Unit)? = null,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    val connectedGreen = AccessibilityTheme.colors.connectedGreen
    PhoenixSectionCard(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (statusConnected) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                border = if (statusConnected) {
                    BorderStroke(1.dp, connectedGreen.copy(alpha = 0.5f))
                } else {
                    null
                },
            ) {
                Text(
                    if (statusConnected) {
                        stringResource(Res.string.hrm_status_connected)
                    } else {
                        stringResource(Res.string.integrations_status_not_connected)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (statusConnected) {
                        connectedGreen
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        if (badges.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges.forEach { badge ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        toggleRow?.invoke()

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            content = actions,
        )
    }
}

@Composable
private fun ApiKeyInputDialog(
    title: String,
    hint: String,
    helperNote: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }

    PhoenixModalDialogFrame(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.hrm_action_connect),
        onConfirm = onConfirm,
        dismissLabel = stringResource(Res.string.action_cancel),
        confirmEnabled = value.isNotBlank(),
    ) {
        Text(
            text = helperNote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(hint) },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                IconButton(
                    onClick = { showApiKey = !showApiKey },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showApiKey) {
                                Res.string.integrations_cd_hide_api_key
                            } else {
                                Res.string.integrations_cd_show_api_key
                            },
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
    }
}
