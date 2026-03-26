package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.ColorSchemes
import com.devil.phoenixproject.util.BackupProgress
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.ImportResult
import com.devil.phoenixproject.util.rememberFilePicker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.devil.phoenixproject.presentation.components.CountdownDropdown
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

@Composable
fun SettingsTab(
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    darkModeEnabled: Boolean,
    audioRepCountEnabled: Boolean = false,
    // Issue #100: Per-sound toggles
    countdownBeepsEnabled: Boolean = true,
    repSoundEnabled: Boolean = true,
    onCountdownBeepsChange: (Boolean) -> Unit = {},
    onRepSoundChange: (Boolean) -> Unit = {},
    // Issue #237: Motion-triggered set start
    motionStartEnabled: Boolean = false,
    onMotionStartChange: (Boolean) -> Unit = {},
    summaryCountdownSeconds: Int = 10,
    autoStartCountdownSeconds: Int = 5,
    selectedColorSchemeIndex: Int = 0,
    onWeightUnitChange: (WeightUnit) -> Unit,
    onEnableVideoPlaybackChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onAudioRepCountChange: (Boolean) -> Unit,
    onSummaryCountdownChange: (Int) -> Unit = {},
    onAutoStartCountdownChange: (Int) -> Unit = {},
    onColorSchemeChange: (Int) -> Unit,
    onDeleteAllWorkouts: () -> Unit,
    onNavigateToConnectionLogs: () -> Unit = {},
    onNavigateToBadges: () -> Unit = {},
    onNavigateToLinkAccount: () -> Unit = {},
    onNavigateToIntegrations: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") // Reserved for future connecting overlay
    isAutoConnecting: Boolean = false,
    connectionError: String? = null,
    onClearConnectionError: () -> Unit = {},
    onCancelAutoConnecting: () -> Unit = {},
    onSetTitle: (String) -> Unit,
    // Disco mode Easter egg
    discoModeUnlocked: Boolean = false,
    discoModeActive: Boolean = false,
    isConnected: Boolean = false,
    onDiscoModeUnlocked: () -> Unit = {},
    onDiscoModeToggle: (Boolean) -> Unit = {},
    onPlayDiscoSound: () -> Unit = {},
    onTestSounds: () -> Unit = {},
    // Gamification toggle
    gamificationEnabled: Boolean = true,
    onGamificationEnabledChange: (Boolean) -> Unit = {},
    // Auto-backup (Phase 36)
    autoBackupEnabled: Boolean = false,
    onAutoBackupEnabledChange: (Boolean) -> Unit = {},
    backupStats: com.devil.phoenixproject.util.BackupStats? = null,
    onOpenBackupFolder: () -> Unit = {},
    // Issue #238: Language preference
    selectedLanguage: String = "en",
    onLanguageChange: (String) -> Unit = {},
    // Issue #141: Voice emergency stop
    voiceStopEnabled: Boolean = false,
    onVoiceStopEnabledChange: (Boolean) -> Unit = {},
    safeWord: String? = null,
    onSafeWordChange: (String?) -> Unit = {},
    safeWordCalibrated: Boolean = false,
    onSafeWordCalibratedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
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
    // Easter egg tap counter for disco mode
    var easterEggTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    // Disco mode unlock celebration dialog
    var showDiscoUnlockDialog by remember { mutableStateOf(false) }
    // Voice emergency stop state (moved from VoiceEmergencyStopSection for consolidation)
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var localSafeWord by remember(safeWord) { mutableStateOf(safeWord ?: "") }
    // Optimistic UI state for immediate visual feedback
    var localWeightUnit by remember(weightUnit) { mutableStateOf(weightUnit) }

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
        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
    ) {
        // Header removed for global scaffold integration

        // Donation Card - Material 3 Expressive (top of settings for visibility)
        val uriHandler = LocalUriHandler.current
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
                                ),
                                RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(Res.string.cd_support_developer),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Like My Work?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "This app is 100% free with no ads, but I graciously accept donations if you are so inclined!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "ko-fi.com/vitruvianredux",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://ko-fi.com/vitruvianredux")
                    }
                )
            }
        }

        // Cloud Sync Section - Material 3 Expressive
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6))
                                ),
                                RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(Res.string.cd_cloud_sync),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        stringResource(Res.string.settings_cloud_sync),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                OutlinedButton(
                    onClick = onNavigateToLinkAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = stringResource(Res.string.cd_link_portal),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Link Portal Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Sync your workouts to the Phoenix Portal for cross-device access",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (hasSyncError) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(Res.string.cd_sync_error),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            "Sync error — tap above to retry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                OutlinedButton(
                    onClick = onNavigateToIntegrations,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = "Integrations",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Integrations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Connect Hevy, Liftosaur, Health apps, and import/export CSV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

    // Weight Unit Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp)) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8B5CF6), Color(0xFF9333EA))
                            ),
                            RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded (was 16dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Scale,
                        contentDescription = stringResource(Res.string.cd_weight_unit),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    stringResource(Res.string.settings_weight_unit),
                    style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
                Spacer(modifier = Modifier.height(Spacing.small))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    FilterChip(
                        selected = localWeightUnit == WeightUnit.KG,
                        onClick = {
                            localWeightUnit = WeightUnit.KG
                            onWeightUnitChange(WeightUnit.KG)
                        },
                        label = { Text(stringResource(Res.string.label_kg)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = localWeightUnit == WeightUnit.KG,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = localWeightUnit == WeightUnit.LB,
                        onClick = {
                            localWeightUnit = WeightUnit.LB
                            onWeightUnitChange(WeightUnit.LB)
                        },
                        label = { Text(stringResource(Res.string.label_lbs)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = localWeightUnit == WeightUnit.LB,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

    // Appearance Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF6366F1), Color(0xFFA855F7))
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = stringResource(Res.string.cd_appearance),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    stringResource(Res.string.settings_appearance),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))

            // Dark Mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(Res.string.settings_dark_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.settings_dark_mode_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = darkModeEnabled,
                    onCheckedChange = onDarkModeChange
                )
            }
        }
    }

    // Language Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF10B981), Color(0xFF059669))
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    stringResource(Res.string.settings_language),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))

            // Language selection dropdown
            val languageOptions = listOf(
                "en" to stringResource(Res.string.language_english),
                "nl" to stringResource(Res.string.language_dutch),
                "de" to stringResource(Res.string.language_german),
                "es" to stringResource(Res.string.language_spanish),
                "fr" to stringResource(Res.string.language_french)
            )
            val selectedLabel = languageOptions.firstOrNull { it.first == selectedLanguage }?.second
                ?: languageOptions.first().second
            var languageExpanded by remember { mutableStateOf(false) }

            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = !languageExpanded }
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    languageOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            onClick = {
                                onLanguageChange(code)
                                languageExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
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
                }
            )
        }
    }

    // Workout Preferences Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp)) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                            ),
                            RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded (was 16dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = stringResource(Res.string.cd_advanced_settings),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "Workout Preferences",
                    style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Issue #167: Summary Countdown now controls autoplay behavior
                // - Off (-1): Skip summary, auto-advance immediately
                // - Unlimited (0): Show summary, wait for manual tap (like old autoplay OFF)
                // - 5-30s: Show summary, auto-advance after countdown (like old autoplay ON)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Set Summary",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Off = skip summary, Unlimited = manual, 5-30s = auto-advance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    CountdownDropdown(
                        label = "",
                        selectedValue = summaryCountdownSeconds,
                        options = listOf(-1, 0, 5, 10, 15, 20, 25, 30),
                        onValueSelected = { onSummaryCountdownChange(it) },
                        modifier = Modifier.width(120.dp),
                        formatLabel = {
                            when (it) {
                                -1 -> "Off"        // Skip summary entirely
                                0 -> "Unlimited"   // Show summary, no auto-advance
                                else -> "${it}s"
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Autostart Countdown - always visible
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autostart Countdown",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Just Lift countdown when handles are grabbed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    CountdownDropdown(
                        label = "",
                        selectedValue = autoStartCountdownSeconds,
                        options = (2..10).toList(),
                        onValueSelected = { onAutoStartCountdownChange(it) },
                        modifier = Modifier.width(100.dp)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Enable Video Playback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Show Exercise Videos",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Display exercise demonstration videos (disable to avoid slow loading)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableVideoPlayback,
                        onCheckedChange = onEnableVideoPlaybackChange
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Audio Rep Counter toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Audio Rep Counter",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Play spoken rep numbers during working sets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = audioRepCountEnabled,
                        onCheckedChange = onAudioRepCountChange
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #100: Countdown beeps toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Countdown Beeps",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Beep during last 10 seconds of rest timer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = countdownBeepsEnabled,
                        onCheckedChange = onCountdownBeepsChange
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #100: Rep completion sound toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Rep Completion Sound",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Play sound when a rep is completed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = repSoundEnabled,
                        onCheckedChange = onRepSoundChange
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #237: Motion-triggered set start toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Motion-Triggered Set Start",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start sets by holding the cable instead of countdown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = motionStartEnabled,
                        onCheckedChange = onMotionStartChange
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Gamification toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Gamification",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Show PR celebrations and award badges after workouts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = gamificationEnabled,
                        onCheckedChange = onGamificationEnabledChange
                    )
                }

                // Voice Emergency Stop - consolidated from standalone section
                Spacer(modifier = Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(Spacing.medium))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(Res.string.settings_voice_stop_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.settings_voice_stop_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = voiceStopEnabled,
                        onCheckedChange = onVoiceStopEnabledChange
                    )
                }

                // Safe word configuration (shown when voice stop is enabled)
                if (voiceStopEnabled) {
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    OutlinedTextField(
                        value = localSafeWord,
                        onValueChange = { newValue ->
                            localSafeWord = newValue.uppercase().trim()
                        },
                        label = { Text(stringResource(Res.string.settings_safe_word_label)) },
                        placeholder = { Text(stringResource(Res.string.settings_safe_word_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.small))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (safeWordCalibrated && safeWord == localSafeWord) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(Res.string.cd_calibration_check),
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(Res.string.settings_calibrated_badge),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Text(
                                stringResource(Res.string.settings_calibrate_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(Spacing.small))

                        Button(
                            onClick = {
                                if (localSafeWord.isNotBlank()) {
                                    onSafeWordCalibratedChange(false)
                                    onSafeWordChange(localSafeWord)
                                    showCalibrationDialog = true
                                }
                            },
                            enabled = localSafeWord.isNotBlank(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(Res.string.settings_calibrate_button))
                        }
                    }
                }
            }
        }

    // Calibration dialog for voice emergency stop
    if (showCalibrationDialog && localSafeWord.isNotBlank()) {
        SafeWordCalibrationDialog(
            safeWord = localSafeWord,
            onCalibrated = {
                onSafeWordChange(localSafeWord)
                onSafeWordCalibratedChange(true)
                showCalibrationDialog = false
            },
            onDismiss = {
                showCalibrationDialog = false
            }
        )
    }

    // Color Scheme Section - Compact with visual previews
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Easter egg: tap the header 7 times rapidly to unlock disco mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    val currentTime = KmpUtils.currentTimeMillis()
                    // Reset if more than 2 seconds since last tap
                    if (currentTime - lastTapTime > 2000L) {
                        easterEggTapCount = 1
                    } else {
                        easterEggTapCount++
                    }
                    lastTapTime = currentTime

                    // Unlock disco mode after 7 rapid taps
                    if (easterEggTapCount >= 7 && !discoModeUnlocked) {
                        showDiscoUnlockDialog = true
                        onPlayDiscoSound()
                        onDiscoModeUnlocked()
                        easterEggTapCount = 0
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = if (discoModeActive) {
                                    // Rainbow gradient when disco mode is active
                                    listOf(Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00),
                                           Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF8B00FF))
                                } else {
                                    listOf(Color(0xFF3B82F6), Color(0xFF6366F1))
                                }
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ColorLens,
                        contentDescription = stringResource(Res.string.cd_led_scheme),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "LED Color Scheme",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Color scheme slider with preview
            val colorSchemes = ColorSchemes.ALL
            val currentScheme = colorSchemes.getOrElse(selectedColorSchemeIndex) { colorSchemes.first() }
            val isNoneScheme = currentScheme.name == "None"

            // Convert RGB colors to Compose Color for preview
            val previewColors = if (isNoneScheme) {
                listOf(Color.DarkGray, Color.Gray, Color.DarkGray)
            } else {
                currentScheme.colors.map { Color(it.r, it.g, it.b) }
            }

            // Color preview box with current scheme name
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(previewColors),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isNoneScheme) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = stringResource(Res.string.cd_leds_off),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = currentScheme.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Slider for color selection
            Slider(
                value = selectedColorSchemeIndex.toFloat(),
                onValueChange = { onColorSchemeChange(it.toInt()) },
                valueRange = 0f..(colorSchemes.size - 1).toFloat(),
                steps = colorSchemes.size - 2, // steps = divisions - 1
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Disco mode toggle (only visible when unlocked)
            if (discoModeUnlocked) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.small),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🕺",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Column {
                            Text(
                                "Disco Mode",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (!isConnected) "Connect to enable"
                                else if (discoModeActive) "Party time!"
                                else "Cycle through colors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = discoModeActive,
                        onCheckedChange = { onDiscoModeToggle(it) },
                        enabled = isConnected
                    )
                }
            }
        }
    }

    // Data Management Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border, error color for destructive action
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp)) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFF97316), Color(0xFFEF4444))
                            ),
                            RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded (was 16dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = stringResource(Res.string.cd_delete_workouts),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "Data Management",
                    style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Auto-backup toggle (Phase 36)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-Backup Workouts",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Automatically save each workout to a local backup file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = onAutoBackupEnabledChange
                    )
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
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${stats.fileCount} backup file${if (stats.fileCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stats.formattedSize,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(Spacing.small))

                        // Open backup folder shortcut
                        OutlinedButton(
                            onClick = onOpenBackupFolder,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(Res.string.cd_open_backup_folder),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Text(
                                "Open Backup Folder",
                                style = MaterialTheme.typography.bodyMedium
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
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = stringResource(Res.string.cd_backup_data),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Backup All Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Restore Button
                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = stringResource(Res.string.cd_restore_data),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Restore from Backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                Button(
                    onClick = { showDeleteAllDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_workouts),
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Delete All Workouts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

    // Achievements Section - Material 3 Expressive (hidden when gamification is disabled)
    if (gamificationEnabled) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MilitaryTech,
                        contentDescription = stringResource(Res.string.cd_achievements),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "Achievements",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))

            OutlinedButton(
                onClick = onNavigateToBadges,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = stringResource(Res.string.cd_view_badges),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    "View Badges & Streaks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Track your progress, earn badges, and maintain your workout streak",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    }

    // Developer Tools Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
                            ),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = stringResource(Res.string.cd_developer_tools),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "Developer Tools",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
                Spacer(modifier = Modifier.height(Spacing.small))

                OutlinedButton(
                    onClick = onNavigateToConnectionLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.Timeline,
                        contentDescription = stringResource(Res.string.cd_connection_logs),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Connection Logs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "View Bluetooth connection debug logs to diagnose connectivity issues",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                OutlinedButton(
                    onClick = onTestSounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = stringResource(Res.string.cd_test_sounds),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        "Test Sounds",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Play workout sounds to test audio configuration and volume",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            }
        }

    // App Info Section - Material 3 Expressive
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.medium)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp) // Material 3 Expressive: Larger (was 40dp)
                        .shadow(8.dp, RoundedCornerShape(20.dp)) // Material 3 Expressive: More shadow, more rounded (was 16dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF22C55E), Color(0xFF3B82F6))
                            ),
                            RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded (was 16dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(Res.string.cd_app_info),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp) // Material 3 Expressive: Larger icon
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.medium))
                Text(
                    "App Info",
                    style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(stringResource(Res.string.settings_version, DeviceInfo.appVersionName), color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    "Open source community project to control Vitruvian Trainer machines locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Material 3 Expressive: Delete All dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = {
                Text(
                    "Delete All Workouts?",
                    style = MaterialTheme.typography.headlineSmall, // Material 3 Expressive: Larger
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This will permanently delete all workout history. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge // Material 3 Expressive: Larger
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, // Material 3 Expressive: Higher contrast
            shape = RoundedCornerShape(28.dp), // Material 3 Expressive: Very rounded for dialogs (was 16dp)
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllWorkouts()
                        showDeleteAllDialog = false
                    },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Delete All",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
    connectionError?.let { error ->
        com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
            message = error,
            onDismiss = onClearConnectionError
        )
    }

    // Disco Mode Unlock Celebration Dialog
    if (showDiscoUnlockDialog) {
        DiscoModeUnlockDialog(
            onDismiss = { showDiscoUnlockDialog = false }
        )
    }

    // Backup confirmation dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(stringResource(Res.string.backup_all_data), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(Res.string.backup_description))
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    // Save to Files button (streaming export)
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
                        }
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                    // Share button (streaming export)
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
                        }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.action_share))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(Res.string.restore_from_backup), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(Res.string.restore_description))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreDialog = false
                        launchFilePicker = true
                    }
                ) {
                    Text(stringResource(Res.string.select_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
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
                    fontWeight = FontWeight.Bold
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
            }
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Text(
                                "${formatCount(progress.current)} / ${formatCount(progress.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } ?: Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.label_please_wait))
                    }
                }
            },
            confirmButton = { }
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
                            backupError = "Import failed: ${error.message ?: "Unknown error"}"
                            showResultDialog = true
                        }
                    } finally {
                        restoreInProgress = false
                    }
                }
            }
        }
    }
}

/**
 * Calibration dialog: user must say the safe word 3 times successfully.
 * Uses SafeWordListenerFactory via Koin to create a listener.
 */
@Composable
private fun SafeWordCalibrationDialog(
    safeWord: String,
    onCalibrated: () -> Unit,
    onDismiss: () -> Unit
) {
    val listenerFactory: com.devil.phoenixproject.domain.voice.SafeWordListenerFactory = koinInject()
    var detectionCount by remember { mutableStateOf(0) }
    var calibrationFailed by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf(false) }
    var listener by remember { mutableStateOf<com.devil.phoenixproject.domain.voice.SafeWordListener?>(null) }
    val scope = rememberCoroutineScope()

    // Create and start listener
    DisposableEffect(safeWord) {
        val newListener = try {
            listenerFactory.create(safeWord)
        } catch (e: Exception) {
            micError = true
            null
        }
        listener = newListener
        newListener?.startListening()

        onDispose {
            newListener?.stopListening()
            listener = null
        }
    }

    // Collect detected words
    val currentListener = listener
    if (currentListener != null) {
        val isListening by currentListener.isListening.collectAsState()

        LaunchedEffect(currentListener) {
            currentListener.detectedWord.collect {
                detectionCount++
                if (detectionCount >= 3) {
                    currentListener.stopListening()
                    onCalibrated()
                }
            }
        }

        // Timeout: if not listening after initial start and we haven't completed, mark mic error
        LaunchedEffect(isListening) {
            if (!isListening && detectionCount == 0) {
                // Give a brief moment for the listener to start
                kotlinx.coroutines.delay(3000)
                val stillNotListening = !currentListener.isListening.value
                if (stillNotListening && detectionCount == 0) {
                    micError = true
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(Res.string.settings_calibration_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                when {
                    micError -> {
                        Text(
                            stringResource(Res.string.settings_calibration_mic_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { com.devil.phoenixproject.util.openAppSettings() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(Res.string.settings_calibration_open_settings))
                        }
                    }
                    calibrationFailed -> {
                        Text(
                            stringResource(Res.string.settings_calibration_fail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        // Prompt
                        Text(
                            stringResource(Res.string.settings_calibration_prompt),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            safeWord,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 3 circles showing progress
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                            modifier = Modifier.padding(vertical = Spacing.small)
                        ) {
                            repeat(3) { index ->
                                val filled = index < detectionCount
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            color = if (filled) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(50)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (filled) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(Res.string.cd_calibration_check),
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Progress text
                        Text(
                            stringResource(Res.string.settings_calibration_progress, detectionCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Listening indicator
                        Text(
                            stringResource(Res.string.settings_calibration_listening),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 10_000 -> "${count / 1_000}K"
    count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}K"
    else -> count.toString()
}

/**
 * Fun animated dialog celebrating disco mode unlock
 */
@Composable
private fun DiscoModeUnlockDialog(onDismiss: () -> Unit) {
    // Auto-dismiss after 4 seconds
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }

    // Animate the scale for a fun pop-in effect
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dialog_scale"
    )

    // Rotating disco ball effect - use coroutine-based animation
    var rotation by remember { mutableStateOf(0f) }
    var glowAlpha by remember { mutableStateOf(0.3f) }
    var glowUp by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(16) // ~60fps
            rotation = (rotation + 3f) % 360f
            // Pulsing glow effect
            if (glowUp) {
                glowAlpha += 0.02f
                if (glowAlpha >= 0.8f) glowUp = false
            } else {
                glowAlpha -= 0.02f
                if (glowAlpha <= 0.3f) glowUp = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
        containerColor = Color(0xFF1A1A2E),
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Spinning disco ball emoji with glow
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .rotate(rotation)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = glowAlpha),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(40.dp)
                        )
                ) {
                    Text(
                        "🪩",
                        style = MaterialTheme.typography.displayLarge
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "DISCO MODE UNLOCKED!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00),
                                Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF8B00FF)
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "🕺 Time to get funky! 💃",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Toggle Disco Mode in the LED Color Scheme section to make your trainer party!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "🎉 Let's Party!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700) // Gold
                )
            }
        }
    )
}
