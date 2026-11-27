package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.WeightTextScale
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.HrmSensorSettingsCard
import com.devil.phoenixproject.presentation.components.PhoenixModalDialogFrame
import com.devil.phoenixproject.presentation.components.PhoenixSectionCard
import com.devil.phoenixproject.presentation.components.ProfileRecoverySettingsSection
import com.devil.phoenixproject.presentation.components.ResponsiveSegmentedSelector
import com.devil.phoenixproject.presentation.components.SectionHeaderIconBadge
import com.devil.phoenixproject.presentation.components.SettingsActionRow
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupProgress
import com.devil.phoenixproject.util.BackupStats
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.ImportResult
import com.devil.phoenixproject.isIosPlatform
import com.devil.phoenixproject.util.autoBackupLocationNote
import com.devil.phoenixproject.util.canOpenBackupFolder
import com.devil.phoenixproject.util.defaultBackupLocationLabel
import com.devil.phoenixproject.util.rememberBackupLocationPicker
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.settings_exercise_demos_photos_note
import projectphoenix.shared.generated.resources.action_cancel
import projectphoenix.shared.generated.resources.action_ok
import projectphoenix.shared.generated.resources.delete_all
import projectphoenix.shared.generated.resources.delete_all_workouts_message
import projectphoenix.shared.generated.resources.delete_all_workouts_title
import projectphoenix.shared.generated.resources.action_save
import projectphoenix.shared.generated.resources.action_share
import projectphoenix.shared.generated.resources.backup_all_data
import projectphoenix.shared.generated.resources.backup_description
import projectphoenix.shared.generated.resources.backup_success
import projectphoenix.shared.generated.resources.cd_app_info
import projectphoenix.shared.generated.resources.cd_appearance
import projectphoenix.shared.generated.resources.cd_backup_data
import projectphoenix.shared.generated.resources.cd_cloud_sync
import projectphoenix.shared.generated.resources.cd_connection_logs
import projectphoenix.shared.generated.resources.cd_delete_workouts
import projectphoenix.shared.generated.resources.cd_developer_tools
import projectphoenix.shared.generated.resources.cd_link_portal
import projectphoenix.shared.generated.resources.cd_open_backup_folder
import projectphoenix.shared.generated.resources.cd_restore_data
import projectphoenix.shared.generated.resources.cd_support_developer
import projectphoenix.shared.generated.resources.cd_sync_error
import projectphoenix.shared.generated.resources.cd_test_sounds
import projectphoenix.shared.generated.resources.diagnostics_title
import projectphoenix.shared.generated.resources.import_completed
import projectphoenix.shared.generated.resources.label_please_wait
import projectphoenix.shared.generated.resources.language_dutch
import projectphoenix.shared.generated.resources.language_english
import projectphoenix.shared.generated.resources.backup_all_data
import projectphoenix.shared.generated.resources.label_error
import projectphoenix.shared.generated.resources.language_french
import projectphoenix.shared.generated.resources.language_italian
import projectphoenix.shared.generated.resources.language_system_default
import projectphoenix.shared.generated.resources.restore_from_backup
import projectphoenix.shared.generated.resources.settings_app_info
import projectphoenix.shared.generated.resources.settings_app_info_description
import projectphoenix.shared.generated.resources.settings_auto_backup_description
import projectphoenix.shared.generated.resources.settings_auto_backup_description_with_location
import projectphoenix.shared.generated.resources.settings_auto_backup_title
import projectphoenix.shared.generated.resources.settings_backup_complete
import projectphoenix.shared.generated.resources.settings_backup_file_one
import projectphoenix.shared.generated.resources.settings_backup_file_other
import projectphoenix.shared.generated.resources.settings_backup_location
import projectphoenix.shared.generated.resources.settings_backup_location_default
import projectphoenix.shared.generated.resources.settings_change_location
import projectphoenix.shared.generated.resources.settings_cloud_sync_description
import projectphoenix.shared.generated.resources.settings_connection_logs
import projectphoenix.shared.generated.resources.settings_connection_logs_description
import projectphoenix.shared.generated.resources.settings_creating_backup
import projectphoenix.shared.generated.resources.settings_data_management
import projectphoenix.shared.generated.resources.settings_delete_all_workouts
import projectphoenix.shared.generated.resources.settings_developer_tools
import projectphoenix.shared.generated.resources.settings_donate_description
import projectphoenix.shared.generated.resources.settings_import_failed_detail
import projectphoenix.shared.generated.resources.settings_include_raw_telemetry_description
import projectphoenix.shared.generated.resources.settings_include_raw_telemetry_title
import projectphoenix.shared.generated.resources.settings_integrations
import projectphoenix.shared.generated.resources.settings_integrations_description
import projectphoenix.shared.generated.resources.settings_language_ios_restart_hint
import projectphoenix.shared.generated.resources.settings_like_work
import projectphoenix.shared.generated.resources.settings_link_portal_account
import projectphoenix.shared.generated.resources.settings_open_backup_folder
import projectphoenix.shared.generated.resources.settings_reset_to_default
import projectphoenix.shared.generated.resources.settings_restore_already_present
import projectphoenix.shared.generated.resources.settings_restore_complete
import projectphoenix.shared.generated.resources.settings_restore_failed
import projectphoenix.shared.generated.resources.settings_restore_imported
import projectphoenix.shared.generated.resources.settings_restore_incomplete
import projectphoenix.shared.generated.resources.settings_restore_incomplete_detail_one
import projectphoenix.shared.generated.resources.settings_restore_incomplete_detail_other
import projectphoenix.shared.generated.resources.settings_restore_repaired_references
import projectphoenix.shared.generated.resources.settings_restoring_data
import projectphoenix.shared.generated.resources.settings_test_sounds
import projectphoenix.shared.generated.resources.settings_test_sounds_description
import projectphoenix.shared.generated.resources.settings_unknown_database_error
import projectphoenix.shared.generated.resources.settings_unknown_error
import projectphoenix.shared.generated.resources.language_german
import projectphoenix.shared.generated.resources.language_spanish
import projectphoenix.shared.generated.resources.restore_description
import projectphoenix.shared.generated.resources.restore_from_backup
import projectphoenix.shared.generated.resources.select_file
import projectphoenix.shared.generated.resources.settings_appearance
import projectphoenix.shared.generated.resources.settings_ble_compat_auto
import projectphoenix.shared.generated.resources.settings_ble_compat_description
import projectphoenix.shared.generated.resources.settings_ble_compat_description_affected
import projectphoenix.shared.generated.resources.settings_ble_compat_off
import projectphoenix.shared.generated.resources.settings_ble_compat_on
import projectphoenix.shared.generated.resources.settings_ble_compat_reconnect_hint
import projectphoenix.shared.generated.resources.settings_ble_compat_title
import projectphoenix.shared.generated.resources.settings_cloud_sync
import projectphoenix.shared.generated.resources.settings_sync_error_tap_to_dismiss
import projectphoenix.shared.generated.resources.settings_dynamic_color
import projectphoenix.shared.generated.resources.settings_dynamic_color_description
import projectphoenix.shared.generated.resources.settings_language
import projectphoenix.shared.generated.resources.settings_language_help
import projectphoenix.shared.generated.resources.settings_machine_diagnostics_description
import projectphoenix.shared.generated.resources.settings_refresh_wger_catalog
import projectphoenix.shared.generated.resources.settings_refresh_wger_catalog_description
import projectphoenix.shared.generated.resources.settings_refresh_wger_catalog_error
import projectphoenix.shared.generated.resources.settings_refresh_wger_catalog_in_progress
import projectphoenix.shared.generated.resources.settings_refresh_wger_catalog_success
import projectphoenix.shared.generated.resources.settings_show_exercise_videos
import projectphoenix.shared.generated.resources.settings_show_exercise_videos_description
import projectphoenix.shared.generated.resources.settings_theme_dark
import projectphoenix.shared.generated.resources.settings_theme_light
import projectphoenix.shared.generated.resources.settings_theme_mode
import projectphoenix.shared.generated.resources.settings_theme_mode_description
import projectphoenix.shared.generated.resources.settings_theme_system
import projectphoenix.shared.generated.resources.settings_title
import projectphoenix.shared.generated.resources.settings_video_behavior
import projectphoenix.shared.generated.resources.settings_version
import projectphoenix.shared.generated.resources.settings_weight_text_size
import projectphoenix.shared.generated.resources.settings_weight_text_size_extra_large
import projectphoenix.shared.generated.resources.settings_weight_text_size_large
import projectphoenix.shared.generated.resources.settings_weight_text_size_standard

@Composable
private fun ThemeModeSelector(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val systemLabel = stringResource(Res.string.settings_theme_system)
    val lightLabel = stringResource(Res.string.settings_theme_light)
    val darkLabel = stringResource(Res.string.settings_theme_dark)
    val modeLabels = mapOf(
        ThemeMode.SYSTEM to systemLabel,
        ThemeMode.LIGHT to lightLabel,
        ThemeMode.DARK to darkLabel,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(Res.string.settings_theme_mode),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(Res.string.settings_theme_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.small))
        ResponsiveSegmentedSelector(
            options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            selectedOption = themeMode,
            onSelect = onThemeModeChange,
            labelForOption = { modeLabels.getValue(it) },
        )
    }
}

/**
 * Issue #1367 SD7: Workout control size selection (Standard / Large / Extra large).
 *
 * SD7: native selected-state radio rows (whole row selectable, >=48dp bounds, child
 * radio with a null callback so the announcement is not duplicated) for compact-width
 * and large-text layouts; a horizontal segmented selection only where the three labels
 * fit without truncation.
 */
@Composable
private fun WeightTextSizeSelector(
    weightTextScale: WeightTextScale,
    onWeightTextScaleChange: (WeightTextScale) -> Unit,
) {
    val options = listOf(
        WeightTextScale.STANDARD to stringResource(Res.string.settings_weight_text_size_standard),
        WeightTextScale.LARGE to stringResource(Res.string.settings_weight_text_size_large),
        WeightTextScale.EXTRA_LARGE to stringResource(Res.string.settings_weight_text_size_extra_large),
    )
    val useRadioRows = isCompactAccessibilityLayout() ||
        LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(Res.string.settings_weight_text_size),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.small))

        if (useRadioRows) {
            options.forEach { (option, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .toggleable(
                            value = weightTextScale == option,
                            role = Role.RadioButton,
                            onValueChange = { onWeightTextScaleChange(option) },
                        )
                        .padding(horizontal = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = weightTextScale == option,
                        onClick = null, // selection is handled by the row (no duplicate announcement)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEachIndexed { index, (option, label) ->
                    SegmentedButton(
                        selected = weightTextScale == option,
                        onClick = { onWeightTextScaleChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSettingsSectionCard(
    title: String,
    icon: ImageVector,
    iconContentDescription: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    PhoenixSectionCard(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            SectionHeaderIconBadge(
                imageVector = icon,
                contentDescription = iconContentDescription,
                containerColor = badgeContainerColor,
                contentColor = badgeContentColor,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        content()
    }
}

@Composable
private fun GlobalSettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun SettingsTab(
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    dynamicColorAvailable: Boolean,
    dynamicColorEnabled: Boolean,
    onEnableVideoPlaybackChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    onDeleteAllWorkouts: (String) -> Unit,
    onNavigateToConnectionLogs: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToLinkAccount: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    onSetTitle: (String) -> Unit,
    onTestSounds: () -> Unit,
    bleCompatibilityMode: BleCompatibilitySetting,
    onBleCompatibilityModeChange: (BleCompatibilitySetting) -> Unit,
    autoBackupEnabled: Boolean,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    includeRawTelemetryInBackups: Boolean,
    onIncludeRawTelemetryInBackupsChange: (Boolean) -> Unit,
    backupStats: BackupStats?,
    onOpenBackupFolder: () -> Unit,
    backupDestination: BackupDestination,
    onBackupDestinationChange: (BackupDestination) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    weightTextScale: WeightTextScale,
    onWeightTextScaleChange: (WeightTextScale) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteAllTarget by remember { mutableStateOf<UserProfile?>(null) }
    // Backup/Restore state
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backupInProgress by remember { mutableStateOf(false) }
    var restoreInProgress by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf<BackupProgress?>(null) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var restoreResult by remember { mutableStateOf<ImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var launchFilePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Inject DataBackupManager for manual backup/restore operations
    val backupManager: DataBackupManager = koinInject()
    val exerciseRepository: ExerciseRepository = koinInject()
    val userProfileRepository: UserProfileRepository = koinInject()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    var wgerRefreshInProgress by remember { mutableStateOf(false) }
    var wgerRefreshMessage by remember { mutableStateOf<String?>(null) }
    // Inject SyncTriggerManager for sync error indicator
    val syncTriggerManager: SyncTriggerManager = koinInject()
    val hasSyncError by syncTriggerManager.hasPersistentError.collectAsState()

    // Set global title
    val settingsTitle = stringResource(Res.string.settings_title)
    LaunchedEffect(settingsTitle) {
        onSetTitle(settingsTitle)
    }

    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Header removed for global scaffold integration

            ProfileRecoverySettingsSection()

            // Donation Card (top of settings for visibility)
            val uriHandler = LocalUriHandler.current
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_like_work),
                icon = Icons.Default.Favorite,
                iconContentDescription = stringResource(Res.string.cd_support_developer),
                badgeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    stringResource(Res.string.settings_donate_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            runCatching { uriHandler.openUri("https://ko-fi.com/phoenixredux") }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "ko-fi.com/phoenixredux",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Cloud Sync Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_cloud_sync),
                icon = Icons.Default.Cloud,
                iconContentDescription = stringResource(Res.string.cd_cloud_sync),
                badgeContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                SettingsActionRow(
                    description = stringResource(Res.string.settings_cloud_sync_description),
                ) {
                    OutlinedButton(
                        onClick = onNavigateToLinkAccount,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = stringResource(Res.string.cd_link_portal),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_link_portal_account),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (hasSyncError) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                // Issue #528: allow the user to dismiss a stale
                                // persistent error (e.g. PERMANENT/AUTH or pre-fix
                                // backoff latch). The trigger manager resets its
                                // backoff and consecutive-failure state so the
                                // next foreground / workout-completed trigger can
                                // run clean. The user is still able to retry by
                                // tapping the Link Portal Account button above.
                                syncTriggerManager.clearError()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(Res.string.cd_sync_error),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_sync_error_tap_to_dismiss),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                SettingsActionRow(
                    description = stringResource(Res.string.settings_integrations_description),
                ) {
                    OutlinedButton(
                        onClick = onNavigateToIntegrations,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = stringResource(Res.string.settings_integrations),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_integrations),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Appearance Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_appearance),
                icon = Icons.Default.Palette,
                iconContentDescription = stringResource(Res.string.cd_appearance),
            ) {
                ThemeModeSelector(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.small),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                // Issue #1367 SD8: Weight text size sits after Theme and before Dynamic color.
                WeightTextSizeSelector(
                    weightTextScale = weightTextScale,
                    onWeightTextScaleChange = onWeightTextScaleChange,
                )

                if (dynamicColorAvailable) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.small),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )

                    GlobalSettingsSwitchRow(
                        title = stringResource(Res.string.settings_dynamic_color),
                        description = stringResource(Res.string.settings_dynamic_color_description),
                        checked = dynamicColorEnabled,
                        onCheckedChange = onDynamicColorEnabledChange,
                    )
                }
            }

            // Language Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_language),
                icon = Icons.Default.Language,
                badgeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                // Language selection dropdown
                val languageOptions = listOf(
                    "" to stringResource(Res.string.language_system_default),
                    "en" to stringResource(Res.string.language_english),
                    "nl" to stringResource(Res.string.language_dutch),
                    "de" to stringResource(Res.string.language_german),
                    "es" to stringResource(Res.string.language_spanish),
                    "fr" to stringResource(Res.string.language_french),
                    "it" to stringResource(Res.string.language_italian),
                )
                val selectedLabel = languageOptions.firstOrNull { it.first == selectedLanguage }?.second
                    ?: languageOptions.first().second
                var languageExpanded by remember { mutableStateOf(false) }
                var iosLanguageRestartRequired by remember { mutableStateOf(false) }

                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = !languageExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        languageOptions.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                onClick = {
                                    if (isIosPlatform && code != selectedLanguage) {
                                        iosLanguageRestartRequired = true
                                    }
                                    onLanguageChange(code)
                                    languageExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }

                if (isIosPlatform) {
                    Text(
                        stringResource(Res.string.settings_language_ios_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (iosLanguageRestartRequired) FontWeight.Medium else FontWeight.Normal,
                        color = if (iosLanguageRestartRequired) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            runCatching {
                                uriHandler.openUri("https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues")
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(Res.string.settings_language_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_video_behavior),
                icon = Icons.Default.Tune,
            ) {
                GlobalSettingsSwitchRow(
                    title = stringResource(Res.string.settings_show_exercise_videos),
                    description = stringResource(
                        Res.string.settings_show_exercise_videos_description,
                    ),
                    checked = enableVideoPlayback,
                    onCheckedChange = onEnableVideoPlaybackChange,
                )
                // Issue #1358 (SD10/SD15): Exercise Demos also gates photos attached to
                // custom exercises — no new preference key is introduced.
                Text(
                    text = stringResource(Res.string.settings_exercise_demos_photos_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.medium),
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                SettingsActionRow(
                    description = stringResource(Res.string.settings_refresh_wger_catalog_description),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (wgerRefreshInProgress) return@OutlinedButton
                            scope.launch {
                                wgerRefreshInProgress = true
                                wgerRefreshMessage = null
                                val result = exerciseRepository.updateFromWger()
                                wgerRefreshInProgress = false
                                wgerRefreshMessage = result.fold(
                                    onSuccess = { count ->
                                        getString(Res.string.settings_refresh_wger_catalog_success, count)
                                    },
                                    onFailure = { getString(Res.string.settings_refresh_wger_catalog_error) },
                                )
                            }
                        },
                        enabled = !wgerRefreshInProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (wgerRefreshInProgress) {
                                stringResource(Res.string.settings_refresh_wger_catalog_in_progress)
                            } else {
                                stringResource(Res.string.settings_refresh_wger_catalog)
                            },
                        )
                    }
                }
                wgerRefreshMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Data Management Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_data_management),
                icon = Icons.Default.DeleteForever,
                iconContentDescription = stringResource(Res.string.cd_delete_workouts),
                badgeContainerColor = MaterialTheme.colorScheme.errorContainer,
                badgeContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                GlobalSettingsSwitchRow(
                    title = stringResource(Res.string.settings_auto_backup_title),
                    description = autoBackupLocationNote?.let {
                        stringResource(Res.string.settings_auto_backup_description_with_location, it)
                    } ?: stringResource(Res.string.settings_auto_backup_description),
                    checked = autoBackupEnabled,
                    onCheckedChange = onAutoBackupEnabledChange,
                )

                // Raw telemetry is out of backups unless opted in (F-033)
                GlobalSettingsSwitchRow(
                    title = stringResource(Res.string.settings_include_raw_telemetry_title),
                    description = stringResource(Res.string.settings_include_raw_telemetry_description),
                    checked = includeRawTelemetryInBackups,
                    onCheckedChange = onIncludeRawTelemetryInBackupsChange,
                )

                // Backup Location selector (Phase 42)
                var showLocationPicker by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.settings_backup_location),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when (backupDestination) {
                                is BackupDestination.Default -> stringResource(
                                    Res.string.settings_backup_location_default,
                                    defaultBackupLocationLabel,
                                )
                                is BackupDestination.Custom -> backupDestination.displayName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = { showLocationPicker = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(Res.string.settings_change_location),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (backupDestination.isCustom) {
                        OutlinedButton(
                            onClick = { onBackupDestinationChange(BackupDestination.Default) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.settings_reset_to_default),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Directory picker launcher
                if (showLocationPicker) {
                    val locationPicker = rememberBackupLocationPicker()
                    locationPicker.LaunchDirectoryPicker { destination ->
                        showLocationPicker = false
                        destination?.let { onBackupDestinationChange(it) }
                    }
                }

                // Backup stats: file count and total size
                backupStats?.let { stats ->
                    if (stats.fileCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (stats.fileCount == 1) {
                                    stringResource(Res.string.settings_backup_file_one)
                                } else {
                                    stringResource(Res.string.settings_backup_file_other, stats.fileCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stats.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (canOpenBackupFolder) {
                            OutlinedButton(
                                onClick = onOpenBackupFolder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = stringResource(Res.string.cd_open_backup_folder),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(Spacing.small))
                                Text(
                                    stringResource(Res.string.settings_open_backup_folder),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Backup Button
                SettingsActionRow(
                    description = stringResource(Res.string.backup_description),
                ) {
                    OutlinedButton(
                        onClick = { showBackupDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = stringResource(Res.string.cd_backup_data),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.backup_all_data),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Restore Button — FilledTonalButton for cohesive tonal hierarchy
                SettingsActionRow(
                    description = stringResource(Res.string.restore_description),
                ) {
                    FilledTonalButton(
                        onClick = { showRestoreDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = stringResource(Res.string.cd_restore_data),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.restore_from_backup),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                Button(
                    onClick = { deleteAllTarget = activeProfile },
                    enabled = activeProfile != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_workouts),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        stringResource(Res.string.settings_delete_all_workouts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            deleteAllTarget?.let { targetProfile ->
                DestructiveConfirmDialog(
                    title = stringResource(Res.string.delete_all_workouts_title),
                    message = stringResource(Res.string.delete_all_workouts_message, targetProfile.name),
                    confirmText = stringResource(Res.string.delete_all),
                    onConfirm = {
                        onDeleteAllWorkouts(targetProfile.id)
                        deleteAllTarget = null
                    },
                    onDismiss = { deleteAllTarget = null },
                )
            }

            // HRM Heart Rate & Telemetry Sensor
            HrmSensorSettingsCard()

            // Developer Tools Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_developer_tools),
                icon = Icons.Default.BugReport,
                iconContentDescription = stringResource(Res.string.cd_developer_tools),
                badgeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                SettingsActionRow(
                    description = stringResource(Res.string.settings_machine_diagnostics_description),
                ) {
                    OutlinedButton(
                        onClick = onNavigateToDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.diagnostics_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                SettingsActionRow(
                    description = stringResource(Res.string.settings_connection_logs_description),
                ) {
                    OutlinedButton(
                        onClick = onNavigateToConnectionLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.Timeline,
                            contentDescription = stringResource(Res.string.cd_connection_logs),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_connection_logs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Issue #333: BLE small-MTU compatibility path (fixes BCM4389 Pixel
                // GATT_ERROR(133) disconnect at workout start)
                Text(
                    stringResource(Res.string.settings_ble_compat_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        if (DeviceInfo.isBcm4389Pixel()) {
                            Res.string.settings_ble_compat_description_affected
                        } else {
                            Res.string.settings_ble_compat_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val bleAutoLabel = stringResource(Res.string.settings_ble_compat_auto)
                val bleOnLabel = stringResource(Res.string.settings_ble_compat_on)
                val bleOffLabel = stringResource(Res.string.settings_ble_compat_off)
                val bleLabels = mapOf(
                    BleCompatibilitySetting.AUTO to bleAutoLabel,
                    BleCompatibilitySetting.ON to bleOnLabel,
                    BleCompatibilitySetting.OFF to bleOffLabel,
                )
                ResponsiveSegmentedSelector(
                    options = listOf(
                        BleCompatibilitySetting.AUTO,
                        BleCompatibilitySetting.ON,
                        BleCompatibilitySetting.OFF,
                    ),
                    selectedOption = bleCompatibilityMode,
                    onSelect = onBleCompatibilityModeChange,
                    labelForOption = { bleLabels.getValue(it) },
                )
                Text(
                    stringResource(Res.string.settings_ble_compat_reconnect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                SettingsActionRow(
                    description = stringResource(Res.string.settings_test_sounds_description),
                ) {
                    OutlinedButton(
                        onClick = onTestSounds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = stringResource(Res.string.cd_test_sounds),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_test_sounds),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // App Info Section
            GlobalSettingsSectionCard(
                title = stringResource(Res.string.settings_app_info),
                icon = Icons.Default.Info,
                iconContentDescription = stringResource(Res.string.cd_app_info),
                badgeContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(
                    stringResource(Res.string.settings_version, DeviceInfo.appVersionName),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Res.string.settings_app_info_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Backup confirmation dialog
    if (showBackupDialog) {
        PhoenixModalDialogFrame(
            title = stringResource(Res.string.backup_all_data),
            onDismiss = { showBackupDialog = false },
            confirmLabel = stringResource(Res.string.action_save),
            dismissLabel = stringResource(Res.string.action_cancel),
            onConfirm = {
                showBackupDialog = false
                backupInProgress = true
                scope.launch {
                    try {
                        val result = backupManager.exportToFile { progress ->
                            backupProgress = progress
                        }
                        result.onSuccess { path ->
                            backupResult = path
                            showResultDialog = true
                        }.onFailure { error ->
                            backupError = error.message ?: getString(Res.string.settings_unknown_error)
                            showResultDialog = true
                        }
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        backupError = e.message ?: getString(Res.string.settings_unknown_database_error)
                        showResultDialog = true
                    } finally {
                        backupInProgress = false
                        backupProgress = null
                    }
                }
            },
        ) {
            Text(stringResource(Res.string.backup_description))
            Spacer(modifier = Modifier.height(Spacing.small))
            OutlinedButton(
                onClick = {
                    showBackupDialog = false
                    backupInProgress = true
                    scope.launch {
                        try {
                            backupManager.shareBackup()
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            backupError = e.message ?: getString(Res.string.settings_unknown_error)
                            showResultDialog = true
                        } finally {
                            backupInProgress = false
                            backupProgress = null
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.action_share))
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        PhoenixModalDialogFrame(
            title = stringResource(Res.string.restore_from_backup),
            onDismiss = { showRestoreDialog = false },
            confirmLabel = stringResource(Res.string.select_file),
            dismissLabel = stringResource(Res.string.action_cancel),
            onConfirm = {
                showRestoreDialog = false
                launchFilePicker = true
            },
        ) {
            Text(stringResource(Res.string.restore_description))
        }
    }

    // Result dialog
    if (showResultDialog) {
        val isError = backupError != null
        val resetResultState = {
            showResultDialog = false
            backupResult = null
            backupError = null
            restoreResult = null
        }
        PhoenixModalDialogFrame(
            title = when {
                isError -> stringResource(Res.string.label_error)
                backupResult != null -> stringResource(Res.string.settings_backup_complete)
                restoreResult?.hasPartialFailure == true -> stringResource(Res.string.settings_restore_incomplete)
                else -> stringResource(Res.string.settings_restore_complete)
            },
            onDismiss = resetResultState,
            confirmLabel = stringResource(Res.string.action_ok),
            onConfirm = resetResultState,
        ) {
            when {
                isError -> {
                    Text(backupError ?: stringResource(Res.string.settings_unknown_error))
                }

                backupResult != null -> {
                    Text(stringResource(Res.string.backup_success, backupResult ?: ""))
                }

                else -> {
                    restoreResult?.let { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.import_completed))
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(stringResource(Res.string.settings_restore_imported, result.totalImported))
                            Text(stringResource(Res.string.settings_restore_already_present, result.totalSkipped))
                            Text(stringResource(Res.string.settings_restore_failed, result.entitiesFailed))
                            Text(stringResource(Res.string.settings_restore_repaired_references, result.repairedReferences))
                            if (result.hasPartialFailure) {
                                Spacer(modifier = Modifier.height(Spacing.small))
                                Text(
                                    if (result.entitiesFailed == 1) {
                                        stringResource(Res.string.settings_restore_incomplete_detail_one)
                                    } else {
                                        stringResource(Res.string.settings_restore_incomplete_detail_other, result.entitiesFailed)
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Loading indicator dialog with streaming progress
    if (backupInProgress || restoreInProgress) {
        AlertDialog(
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            title = {
                Text(
                    if (backupInProgress) {
                        stringResource(Res.string.settings_creating_backup)
                    } else {
                        stringResource(Res.string.settings_restoring_data)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    backupProgress?.let { progress ->
                        Text(
                            progress.phase.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                "${formatCount(progress.current)} / ${formatCount(progress.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } ?: Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        LoadingIndicator(LoadingIndicatorSize.Medium)
                        Text(stringResource(Res.string.label_please_wait))
                    }
                }
            },
            confirmButton = { },
        )
    }

    // File picker for restore operation
    if (launchFilePicker) {
        val filePicker = rememberFilePicker()
        filePicker.LaunchFilePicker { selectedFile ->
            launchFilePicker = false
            if (selectedFile != null) {
                restoreInProgress = true
                scope.launch {
                    try {
                        val result = backupManager.importFromFile(selectedFile)
                        result.onSuccess { importResult ->
                            restoreResult = importResult
                            showResultDialog = true
                        }.onFailure { error ->
                            // Include exception class so users sharing a screenshot give
                            // us enough to diagnose without needing a full logcat.
                            val cls = error::class.simpleName ?: getString(Res.string.label_error)
                            val msg = error.message?.take(240) ?: getString(Res.string.settings_unknown_error)
                            backupError = getString(Res.string.settings_import_failed_detail, cls, msg)
                            showResultDialog = true
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // F039: importFromFile can throw directly (not only return
                        // Result.failure). Without this catch the thrown exception
                        // escapes the coroutine after finally, leaving the user with
                        // no error dialog. Mirror the onFailure path.
                        val cls = e::class.simpleName ?: getString(Res.string.label_error)
                        val msg = e.message?.take(240) ?: getString(Res.string.settings_unknown_error)
                        backupError = getString(Res.string.settings_import_failed_detail, cls, msg)
                        showResultDialog = true
                    } finally {
                        restoreInProgress = false
                    }
                }
            }
        }
    }
}


private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 10_000 -> "${count / 1_000}K"
    count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}K"
    else -> count.toString()
}
