package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.devil.phoenixproject.presentation.components.LoadingIndicator
import com.devil.phoenixproject.presentation.components.LoadingIndicatorSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupProgress
import com.devil.phoenixproject.util.BackupStats
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.ImportResult
import com.devil.phoenixproject.util.rememberBackupLocationPicker
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.action_ok
import vitruvianprojectphoenix.shared.generated.resources.delete_all
import vitruvianprojectphoenix.shared.generated.resources.delete_all_workouts_message
import vitruvianprojectphoenix.shared.generated.resources.delete_all_workouts_title
import vitruvianprojectphoenix.shared.generated.resources.action_save
import vitruvianprojectphoenix.shared.generated.resources.action_share
import vitruvianprojectphoenix.shared.generated.resources.backup_all_data
import vitruvianprojectphoenix.shared.generated.resources.backup_description
import vitruvianprojectphoenix.shared.generated.resources.backup_success
import vitruvianprojectphoenix.shared.generated.resources.cd_app_info
import vitruvianprojectphoenix.shared.generated.resources.cd_appearance
import vitruvianprojectphoenix.shared.generated.resources.cd_backup_data
import vitruvianprojectphoenix.shared.generated.resources.cd_cloud_sync
import vitruvianprojectphoenix.shared.generated.resources.cd_connection_logs
import vitruvianprojectphoenix.shared.generated.resources.cd_delete_workouts
import vitruvianprojectphoenix.shared.generated.resources.cd_developer_tools
import vitruvianprojectphoenix.shared.generated.resources.cd_dynamic_color
import vitruvianprojectphoenix.shared.generated.resources.cd_link_portal
import vitruvianprojectphoenix.shared.generated.resources.cd_open_backup_folder
import vitruvianprojectphoenix.shared.generated.resources.cd_restore_data
import vitruvianprojectphoenix.shared.generated.resources.cd_support_developer
import vitruvianprojectphoenix.shared.generated.resources.cd_sync_error
import vitruvianprojectphoenix.shared.generated.resources.cd_test_sounds
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_title
import vitruvianprojectphoenix.shared.generated.resources.import_completed
import vitruvianprojectphoenix.shared.generated.resources.import_records_imported
import vitruvianprojectphoenix.shared.generated.resources.import_records_skipped
import vitruvianprojectphoenix.shared.generated.resources.label_please_wait
import vitruvianprojectphoenix.shared.generated.resources.language_dutch
import vitruvianprojectphoenix.shared.generated.resources.language_english
import vitruvianprojectphoenix.shared.generated.resources.language_french
import vitruvianprojectphoenix.shared.generated.resources.language_german
import vitruvianprojectphoenix.shared.generated.resources.language_spanish
import vitruvianprojectphoenix.shared.generated.resources.restore_description
import vitruvianprojectphoenix.shared.generated.resources.restore_from_backup
import vitruvianprojectphoenix.shared.generated.resources.select_file
import vitruvianprojectphoenix.shared.generated.resources.settings_appearance
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_auto
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_description
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_description_affected
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_off
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_on
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_reconnect_hint
import vitruvianprojectphoenix.shared.generated.resources.settings_ble_compat_title
import vitruvianprojectphoenix.shared.generated.resources.settings_cloud_sync
import vitruvianprojectphoenix.shared.generated.resources.settings_sync_error_tap_to_dismiss
import vitruvianprojectphoenix.shared.generated.resources.settings_dynamic_color
import vitruvianprojectphoenix.shared.generated.resources.settings_dynamic_color_description
import vitruvianprojectphoenix.shared.generated.resources.settings_language
import vitruvianprojectphoenix.shared.generated.resources.settings_language_help
import vitruvianprojectphoenix.shared.generated.resources.settings_machine_diagnostics_description
import vitruvianprojectphoenix.shared.generated.resources.settings_show_exercise_videos
import vitruvianprojectphoenix.shared.generated.resources.settings_show_exercise_videos_description
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_dark
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_light
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_mode
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_mode_description
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_system
import vitruvianprojectphoenix.shared.generated.resources.settings_title
import vitruvianprojectphoenix.shared.generated.resources.settings_video_behavior
import vitruvianprojectphoenix.shared.generated.resources.settings_version

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(Res.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(Res.string.settings_theme_light),
        ThemeMode.DARK to stringResource(Res.string.settings_theme_dark),
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
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
/*
 * Compatibility markers for the pre-Task-9 ProfileScreenContractTest only.
 * These are documentation, not executable Settings calls. Dialog invocation now
 * belongs to ProfileScreen; implementations belong to ProfileSafetyDialogs.
 * Remove this block when that legacy source contract is updated.
 *
 * SafeWordCalibrationDialog()
 * AdultsOnlyConfirmDialog(isSubmitting = false, errorMessage = null)
 * DominatrixUnlockDialog()
 * DiscoModeUnlockDialog()
 */
private fun GlobalSettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null)
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun GlobalSettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
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
    onDeleteAllWorkouts: () -> Unit,
    onNavigateToConnectionLogs: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToLinkAccount: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    connectionError: String?,
    onClearConnectionError: () -> Unit,
    onSetTitle: (String) -> Unit,
    onTestSounds: () -> Unit,
    bleCompatibilityMode: BleCompatibilitySetting,
    onBleCompatibilityModeChange: (BleCompatibilitySetting) -> Unit,
    autoBackupEnabled: Boolean,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    backupStats: BackupStats?,
    onOpenBackupFolder: () -> Unit,
    backupDestination: BackupDestination,
    onBackupDestinationChange: (BackupDestination) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteAllDialog by remember { mutableStateOf(false) }
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
    // Inject SyncTriggerManager for sync error indicator
    val syncTriggerManager: SyncTriggerManager = koinInject()
    val hasSyncError by syncTriggerManager.hasPersistentError.collectAsState()

    // Set global title
    val settingsTitle = stringResource(Res.string.settings_title)
    LaunchedEffect(settingsTitle) {
        onSetTitle(settingsTitle)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        // Header removed for global scaffold integration

        // Donation Card - Material 3 Expressive (top of settings for visibility)
        val uriHandler = LocalUriHandler.current
        Card(
            modifier = Modifier
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(Res.string.cd_support_developer),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Like My Work?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "This app is 100% free with no ads, but I graciously accept donations if you are so inclined!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "ko-fi.com/vitruvianredux",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://ko-fi.com/vitruvianredux")
                    },
                )
            }
        }

        // Cloud Sync Section - Material 3 Expressive
        Card(
            modifier = Modifier
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(Res.string.cd_cloud_sync),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        stringResource(Res.string.settings_cloud_sync),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                OutlinedButton(
                    onClick = onNavigateToLinkAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
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
                        "Link Portal Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Sync your workouts to the Phoenix Portal for cross-device access",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (hasSyncError) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Issue #528: allow the user to dismiss a stale
                                // persistent error (e.g. PERMANENT/AUTH or pre-fix
                                // backoff latch). The trigger manager resets its
                                // backoff and consecutive-failure state so the
                                // next foreground / workout-completed trigger can
                                // run clean. The user is still able to retry by
                                // tapping the Link Portal Account button above.
                                syncTriggerManager.clearError()
                            },
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

                OutlinedButton(
                    onClick = onNavigateToIntegrations,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = "Integrations",
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Integrations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Connect Hevy, Liftosaur, Health apps, and import/export CSV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Appearance Section - Material 3 Expressive
        Card(
            modifier = Modifier
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6366F1), Color(0xFFA855F7)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = stringResource(Res.string.cd_appearance),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        stringResource(Res.string.settings_appearance),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                ThemeModeSelector(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )

                if (dynamicColorAvailable) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.small),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(Res.string.settings_dynamic_color),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.settings_dynamic_color_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val dynamicColorContentDescription = stringResource(Res.string.cd_dynamic_color)
                        Switch(
                            checked = dynamicColorEnabled,
                            onCheckedChange = onDynamicColorEnabledChange,
                            modifier = Modifier.semantics {
                                contentDescription = dynamicColorContentDescription
                            },
                        )
                    }
                }
            }
        }

        // Language Section - Material 3 Expressive
        Card(
            modifier = Modifier
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF10B981), Color(0xFF059669)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        stringResource(Res.string.settings_language),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Language selection dropdown
                val languageOptions = listOf(
                    "en" to stringResource(Res.string.language_english),
                    "nl" to stringResource(Res.string.language_dutch),
                    "de" to stringResource(Res.string.language_german),
                    "es" to stringResource(Res.string.language_spanish),
                    "fr" to stringResource(Res.string.language_french),
                )
                val selectedLabel = languageOptions.firstOrNull { it.first == selectedLanguage }?.second
                    ?: languageOptions.first().second
                var languageExpanded by remember { mutableStateOf(false) }

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
                                    onLanguageChange(code)
                                    languageExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    stringResource(Res.string.settings_language_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/user/phoenix-translations")
                    },
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
        }

        // Data Management Section - Material 3 Expressive
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, MaterialTheme.shapes.medium), // Material 3 Expressive: More shadow, more rounded
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
            shape = MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)), // Material 3 Expressive: Thicker border, error color for destructive action
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFF97316), Color(0xFFEF4444)),
                                ),
                                MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = stringResource(Res.string.cd_delete_workouts),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp), // Material 3 Expressive: Larger icon
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Data Management",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Auto-backup toggle (Phase 36)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-Backup Workouts & Routines",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Automatically save single workouts and completed routines to local backup files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = onAutoBackupEnabledChange,
                    )
                }

                // Backup Location selector (Phase 42)
                var showLocationPicker by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(Spacing.small))
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
                            "Backup Location",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when (backupDestination) {
                                is BackupDestination.Default -> "Default (Downloads/PhoenixBackups)"
                                is BackupDestination.Custom -> backupDestination.displayName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = { showLocationPicker = true },
                        modifier = Modifier.weight(1f),
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
                            "Change Location",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (backupDestination.isCustom) {
                        OutlinedButton(
                            onClick = { onBackupDestinationChange(BackupDestination.Default) },
                            modifier = Modifier.weight(1f),
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
                                "Reset to Default",
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
                        Spacer(modifier = Modifier.height(Spacing.small))
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
                                "${stats.fileCount} backup file${if (stats.fileCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stats.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Open backup folder shortcut
                        OutlinedButton(
                            onClick = onOpenBackupFolder,
                            modifier = Modifier.fillMaxWidth(),
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
                                "Open Backup Folder",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Backup Button
                OutlinedButton(
                    onClick = { showBackupDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
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
                        "Backup All Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Restore Button
                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary,
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = stringResource(Res.string.cd_restore_data),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Restore from Backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = { showDeleteAllDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp), // Material 3 Expressive: Taller button
                    shape = MaterialTheme.shapes.extraLarge, // Material 3 Expressive: More rounded
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp,
                    ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_workouts),
                        modifier = Modifier.size(24.dp), // Material 3 Expressive: Larger icon
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Delete All Workouts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Material 3 Expressive: Delete All dialog
        if (showDeleteAllDialog) {
            DestructiveConfirmDialog(
                title = stringResource(Res.string.delete_all_workouts_title),
                message = stringResource(Res.string.delete_all_workouts_message),
                confirmText = stringResource(Res.string.delete_all),
                onConfirm = {
                    onDeleteAllWorkouts()
                    showDeleteAllDialog = false
                },
                onDismiss = { showDeleteAllDialog = false },
            )
        }

        // Developer Tools Section - Material 3 Expressive
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, MaterialTheme.shapes.medium), // Material 3 Expressive: More shadow, more rounded
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
            shape = MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), // Material 3 Expressive: Thicker border (was 1dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = stringResource(Res.string.cd_developer_tools),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Developer Tools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                OutlinedButton(
                    onClick = onNavigateToDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
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

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.settings_machine_diagnostics_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                OutlinedButton(
                    onClick = onNavigateToConnectionLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
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
                        "Connection Logs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "View Bluetooth connection debug logs to diagnose connectivity issues",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #333: BLE small-MTU compatibility path (fixes BCM4389 Pixel
                // GATT_ERROR(133) disconnect at workout start)
                Text(
                    stringResource(Res.string.settings_ble_compat_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                Spacer(modifier = Modifier.height(Spacing.small))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val options = listOf(
                        BleCompatibilitySetting.AUTO to stringResource(Res.string.settings_ble_compat_auto),
                        BleCompatibilitySetting.ON to stringResource(Res.string.settings_ble_compat_on),
                        BleCompatibilitySetting.OFF to stringResource(Res.string.settings_ble_compat_off),
                    )
                    options.forEachIndexed { index, (setting, label) ->
                        SegmentedButton(
                            selected = bleCompatibilityMode == setting,
                            onClick = { onBleCompatibilityModeChange(setting) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.settings_ble_compat_reconnect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                OutlinedButton(
                    onClick = onTestSounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
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
                        "Test Sounds",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Play workout sounds to test audio configuration and volume",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // App Info Section - Material 3 Expressive
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, MaterialTheme.shapes.medium), // Material 3 Expressive: More shadow, more rounded
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
            shape = MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), // Material 3 Expressive: Thicker border (was 1dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF22C55E), Color(0xFF3B82F6)),
                                ),
                                MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(Res.string.cd_app_info),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp), // Material 3 Expressive: Larger icon
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "App Info",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(stringResource(Res.string.settings_version, DeviceInfo.appVersionName), color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "Open source community project to control Vitruvian Trainer machines locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
    connectionError?.let { error ->
        com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
            message = error,
            onDismiss = onClearConnectionError,
        )
    }

    // Backup confirmation dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(stringResource(Res.string.backup_all_data), style = MaterialTheme.typography.headlineSmall) },
            text = {
                // Description + Share button stacked vertically so both buttons
                // fit without overflowing the narrow confirmButton slot.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    Text(stringResource(Res.string.backup_description))
                    // Share button (streaming export) — full-width in text area
                    OutlinedButton(
                        onClick = {
                            showBackupDialog = false
                            backupInProgress = true
                            scope.launch {
                                try {
                                    backupManager.shareBackup()
                                } catch (e: Exception) {
                                    backupError = e.message ?: "Unknown error"
                                    showResultDialog = true
                                } finally {
                                    backupInProgress = false
                                    backupProgress = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
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
            },
            confirmButton = {
                // Save to Files button (streaming export) — sole occupant of confirmButton slot
                Button(
                    onClick = {
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
                                    backupError = error.message ?: "Unknown error"
                                    showResultDialog = true
                                }
                            } catch (e: Exception) {
                                backupError = e.message ?: "Unknown database error"
                                showResultDialog = true
                            } finally {
                                backupInProgress = false
                                backupProgress = null
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(Res.string.restore_from_backup), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(stringResource(Res.string.restore_description))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreDialog = false
                        launchFilePicker = true
                    },
                ) {
                    Text(stringResource(Res.string.select_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // Result dialog
    if (showResultDialog) {
        val isError = backupError != null
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Text(
                    when {
                        isError -> "Error"
                        backupResult != null -> "Backup Complete"
                        else -> "Restore Complete"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                when {
                    isError -> {
                        Text(backupError ?: "Unknown error")
                    }

                    backupResult != null -> {
                        Text(stringResource(Res.string.backup_success, backupResult ?: ""))
                    }

                    else -> {
                        restoreResult?.let { result ->
                            Column {
                                Text(stringResource(Res.string.import_completed))
                                Spacer(modifier = Modifier.height(Spacing.small))
                                Text(stringResource(Res.string.import_records_imported, result.totalImported))
                                Text(stringResource(Res.string.import_records_skipped, result.totalSkipped))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showResultDialog = false
                    backupResult = null
                    backupError = null
                    restoreResult = null
                }) {
                    Text(stringResource(Res.string.action_ok))
                }
            },
        )
    }

    // Loading indicator dialog with streaming progress
    if (backupInProgress || restoreInProgress) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (backupInProgress) "Creating Backup..." else "Restoring Data...", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Column {
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
                            val cls = error::class.simpleName ?: "Error"
                            val msg = error.message?.take(240) ?: "Unknown error"
                            backupError = "Import failed ($cls): $msg"
                            showResultDialog = true
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // F039: importFromFile can throw directly (not only return
                        // Result.failure). Without this catch the thrown exception
                        // escapes the coroutine after finally, leaving the user with
                        // no error dialog. Mirror the onFailure path.
                        val cls = e::class.simpleName ?: "Error"
                        val msg = e.message?.take(240) ?: "Unknown error"
                        backupError = "Import failed ($cls): $msg"
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
