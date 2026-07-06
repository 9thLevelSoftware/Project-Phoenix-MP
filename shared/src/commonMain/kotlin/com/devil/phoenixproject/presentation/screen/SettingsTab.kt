package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.components.ConfirmEditTextField
import com.devil.phoenixproject.presentation.components.CountdownDropdown
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupProgress
import com.devil.phoenixproject.util.ColorSchemes
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.ImportResult
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.rememberBackupLocationPicker
import com.devil.phoenixproject.util.rememberFilePicker
import kotlin.math.abs
import kotlin.math.roundToInt
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
import vitruvianprojectphoenix.shared.generated.resources.cd_achievements
import vitruvianprojectphoenix.shared.generated.resources.cd_advanced_settings
import vitruvianprojectphoenix.shared.generated.resources.cd_app_info
import vitruvianprojectphoenix.shared.generated.resources.cd_appearance
import vitruvianprojectphoenix.shared.generated.resources.cd_backup_data
import vitruvianprojectphoenix.shared.generated.resources.cd_calibration_check
import vitruvianprojectphoenix.shared.generated.resources.cd_cloud_sync
import vitruvianprojectphoenix.shared.generated.resources.cd_connection_logs
import vitruvianprojectphoenix.shared.generated.resources.cd_delete_workouts
import vitruvianprojectphoenix.shared.generated.resources.cd_developer_tools
import vitruvianprojectphoenix.shared.generated.resources.cd_dynamic_color
import vitruvianprojectphoenix.shared.generated.resources.cd_equipment_rack
import vitruvianprojectphoenix.shared.generated.resources.cd_led_scheme
import vitruvianprojectphoenix.shared.generated.resources.cd_leds_off
import vitruvianprojectphoenix.shared.generated.resources.cd_link_portal
import vitruvianprojectphoenix.shared.generated.resources.cd_open_backup_folder
import vitruvianprojectphoenix.shared.generated.resources.cd_restore_data
import vitruvianprojectphoenix.shared.generated.resources.cd_support_developer
import vitruvianprojectphoenix.shared.generated.resources.cd_sync_error
import vitruvianprojectphoenix.shared.generated.resources.cd_test_sounds
import vitruvianprojectphoenix.shared.generated.resources.cd_view_badges
import vitruvianprojectphoenix.shared.generated.resources.cd_weight_unit
import vitruvianprojectphoenix.shared.generated.resources.diagnostics_title
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_description
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_title
import vitruvianprojectphoenix.shared.generated.resources.import_completed
import vitruvianprojectphoenix.shared.generated.resources.import_records_imported
import vitruvianprojectphoenix.shared.generated.resources.import_records_skipped
import vitruvianprojectphoenix.shared.generated.resources.label_kg
import vitruvianprojectphoenix.shared.generated.resources.label_lbs
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
import vitruvianprojectphoenix.shared.generated.resources.settings_calibrate_button
import vitruvianprojectphoenix.shared.generated.resources.settings_calibrate_first
import vitruvianprojectphoenix.shared.generated.resources.settings_calibrated_badge
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_fail
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_listening
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_mic_error
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_open_settings
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_progress
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_prompt
import vitruvianprojectphoenix.shared.generated.resources.settings_calibration_title
import vitruvianprojectphoenix.shared.generated.resources.settings_cloud_sync
import vitruvianprojectphoenix.shared.generated.resources.settings_sync_error_tap_to_dismiss
import vitruvianprojectphoenix.shared.generated.resources.settings_dynamic_color
import vitruvianprojectphoenix.shared.generated.resources.settings_dynamic_color_description
import vitruvianprojectphoenix.shared.generated.resources.settings_language
import vitruvianprojectphoenix.shared.generated.resources.settings_language_help
import vitruvianprojectphoenix.shared.generated.resources.settings_machine_diagnostics_description
import vitruvianprojectphoenix.shared.generated.resources.settings_safe_word_hint
import vitruvianprojectphoenix.shared.generated.resources.settings_safe_word_label
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_dark
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_light
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_mode
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_mode_description
import vitruvianprojectphoenix.shared.generated.resources.settings_theme_system
import vitruvianprojectphoenix.shared.generated.resources.settings_title
import vitruvianprojectphoenix.shared.generated.resources.settings_version
import vitruvianprojectphoenix.shared.generated.resources.settings_voice_stop_description
import vitruvianprojectphoenix.shared.generated.resources.settings_voice_stop_title
import vitruvianprojectphoenix.shared.generated.resources.settings_weight_suggestions_description
import vitruvianprojectphoenix.shared.generated.resources.settings_weight_suggestions_title
import vitruvianprojectphoenix.shared.generated.resources.settings_weight_unit
import vitruvianprojectphoenix.shared.generated.resources.settings_verbal_encouragement_title
import vitruvianprojectphoenix.shared.generated.resources.settings_verbal_encouragement_description
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_title
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_description
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_headphone_warning
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_tier_label
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_tier_mild
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_tier_strong
import vitruvianprojectphoenix.shared.generated.resources.settings_vulgar_mode_tier_mix
import vitruvianprojectphoenix.shared.generated.resources.settings_dominatrix_mode_title
import vitruvianprojectphoenix.shared.generated.resources.settings_dominatrix_mode_description
import vitruvianprojectphoenix.shared.generated.resources.settings_dominatrix_unlock_hint
import vitruvianprojectphoenix.shared.generated.resources.settings_dominatrix_unlock_toast
import vitruvianprojectphoenix.shared.generated.resources.settings_adults_only_title
import vitruvianprojectphoenix.shared.generated.resources.settings_adults_only_body
import vitruvianprojectphoenix.shared.generated.resources.settings_adults_only_compliance_footer
import vitruvianprojectphoenix.shared.generated.resources.settings_adults_only_confirm
import vitruvianprojectphoenix.shared.generated.resources.settings_adults_only_decline

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
fun SettingsTab(
    weightUnit: WeightUnit,
    enableVideoPlayback: Boolean,
    themeMode: ThemeMode,
    dynamicColorAvailable: Boolean = false,
    dynamicColorEnabled: Boolean = false,
    audioRepCountEnabled: Boolean = false,
    // Issue #100: Per-sound toggles
    countdownBeepsEnabled: Boolean = true,
    repSoundEnabled: Boolean = true,
    onCountdownBeepsChange: (Boolean) -> Unit = {},
    onRepSoundChange: (Boolean) -> Unit = {},
    // Issue #237: Motion-triggered set start
    motionStartEnabled: Boolean = false,
    onMotionStartChange: (Boolean) -> Unit = {},
    // Issue #190: Auto-start routine (skip overview)
    autoStartRoutine: Boolean = false,
    onAutoStartRoutineChange: (Boolean) -> Unit = {},
    weightSuggestionsEnabled: Boolean = true,
    onWeightSuggestionsEnabledChange: (Boolean) -> Unit = {},
    summaryCountdownSeconds: Int = 10,
    autoStartCountdownSeconds: Int = 5,
    selectedColorSchemeIndex: Int = 0,
    onWeightUnitChange: (WeightUnit) -> Unit,
    onEnableVideoPlaybackChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorEnabledChange: (Boolean) -> Unit = {},
    onAudioRepCountChange: (Boolean) -> Unit,
    onSummaryCountdownChange: (Int) -> Unit = {},
    onAutoStartCountdownChange: (Int) -> Unit = {},
    onColorSchemeChange: (Int) -> Unit,
    onDeleteAllWorkouts: () -> Unit,
    onNavigateToConnectionLogs: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToBadges: () -> Unit = {},
    onNavigateToLinkAccount: () -> Unit = {},
    onNavigateToIntegrations: () -> Unit = {},
    onNavigateToEquipmentRack: () -> Unit = {},
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
    // Issue #333: BLE small-MTU compatibility path (Auto = on for Pixel 6/7 family)
    bleCompatibilityMode: BleCompatibilitySetting = BleCompatibilitySetting.AUTO,
    onBleCompatibilityModeChange: (BleCompatibilitySetting) -> Unit = {},
    // Auto-backup (Phase 36)
    autoBackupEnabled: Boolean = false,
    onAutoBackupEnabledChange: (Boolean) -> Unit = {},
    backupStats: com.devil.phoenixproject.util.BackupStats? = null,
    onOpenBackupFolder: () -> Unit = {},
    // Custom backup destination (Phase 42)
    backupDestination: com.devil.phoenixproject.util.BackupDestination = com.devil.phoenixproject.util.BackupDestination.Default,
    onBackupDestinationChange: (com.devil.phoenixproject.util.BackupDestination) -> Unit = {},
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
    // Issue #266: Configurable weight increment
    weightIncrement: Float = -1f,
    onWeightIncrementChange: (Float) -> Unit = {},
    // Issue #229: Body weight for bodyweight exercise volume
    bodyWeightKg: Float = 0f,
    onBodyWeightKgChange: (Float) -> Unit = {},
    // Issue #313: VBT power loss threshold
    velocityLossThresholdPercent: Int = 20,
    onVelocityLossThresholdChange: (Int) -> Unit = {},
    autoEndOnVelocityLoss: Boolean = false,
    onAutoEndOnVelocityLossChange: (Boolean) -> Unit = {},
    stallDetectionEnabled: Boolean = true,
    // Issue #517: System-wide default scaling basis for % of 1RM
    defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
    onDefaultScalingBasisChange: (ScalingBasis) -> Unit = {},
    // Issue #595: Routine-builder defaults for newly added cable exercises
    defaultRoutineExerciseUsePercentOfPR: Boolean = false,
    defaultRoutineExerciseWeightPercentOfPR: Int = 80,
    onDefaultRoutineExerciseUsePercentOfPRChange: (Boolean) -> Unit = {},
    onDefaultRoutineExerciseWeightPercentOfPRChange: (Int) -> Unit = {},
    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    verbalEncouragementEnabled: Boolean = false,
    onVerbalEncouragementEnabledChange: (Boolean) -> Unit = {},
    vulgarModeEnabled: Boolean = false,
    onVulgarModeEnabledChange: (Boolean) -> Unit = {},
    vulgarTier: VulgarTier = VulgarTier.STRONG,
    onVulgarTierChange: (VulgarTier) -> Unit = {},
    dominatrixModeUnlocked: Boolean = false,
    onDominatrixModeUnlockedChange: (Boolean) -> Unit = {},
    dominatrixModeActive: Boolean = false,
    onDominatrixModeActiveChange: (Boolean) -> Unit = {},
    adultsOnlyConfirmed: Boolean = false,
    onAdultsOnlyConfirmedChange: (Boolean) -> Unit = {},
    // Issue #611 (PR-followup #613): one-shot 18+ modal gate reader + writer. Lives
    // outside UserPreferences by design (architecture §3); read on every vulgar-on
    // toggle, written once per install by either confirm OR decline callback.
    adultsOnlyPrompted: Boolean = false,
    onAdultsOnlyPromptedChange: (Boolean) -> Unit = {},
    onPlayDominatrixUnlockSound: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val userProfileRepository = koinInject<UserProfileRepository>()
    val externalMeasurementRepository = koinInject<ExternalMeasurementRepository>()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    val activeProfileId = activeProfile?.id ?: "default"
    val healthBodyWeightMeasurements by remember(activeProfileId) {
        externalMeasurementRepository.observeMeasurementsByType(
            profileId = activeProfileId,
            measurementType = HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT,
        )
    }.collectAsState(initial = emptyList())
    val latestMatchingHealthBodyWeight = healthBodyWeightMeasurements
        .filter { measurement ->
            measurement.unit == HealthBodyWeightSyncManager.UNIT_KG &&
                measurement.provider in setOf(IntegrationProvider.APPLE_HEALTH, IntegrationProvider.GOOGLE_HEALTH) &&
                abs(measurement.value.toFloat() - bodyWeightKg) < 0.05f
        }
        .maxByOrNull { it.measuredAt }
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
    // Issue #611: Gated 7-tap counter for Dominatrix easter egg on the VBT card header.
    // Only counts when vulgarModeEnabled=true; otherwise clickable is a no-op.
    var dominatrixEasterEggTapCount by remember { mutableStateOf(0) }
    var lastDominatrixTapTime by remember { mutableStateOf(0L) }
    var showDominatrixUnlockDialog by remember { mutableStateOf(false) }
    // Issue #611: 18+ Adults Only modal state. Fires once per install on first vulgar-on.
    var showAdultsOnlyDialog by remember { mutableStateOf(false) }
    // Voice emergency stop state (moved from VoiceEmergencyStopSection for consolidation)
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var localSafeWord by remember(safeWord) { mutableStateOf(safeWord ?: "") }
    // Optimistic UI state for immediate visual feedback
    var localWeightUnit by remember(weightUnit) { mutableStateOf(weightUnit) }
    // Issue #266: Weight increment picker dialog state
    var showWeightIncrementDialog by remember { mutableStateOf(false) }
    // Issue #229: Body weight input dialog state
    var showBodyWeightDialog by remember { mutableStateOf(false) }
    var bodyWeightInput by remember(bodyWeightKg) {
        mutableStateOf(
            if (bodyWeightKg > 0f) {
                if (weightUnit == WeightUnit.KG) {
                    UnitConverter.formatDecimal(bodyWeightKg)
                } else {
                    UnitConverter.formatDecimal(UnitConverter.kgToLb(bodyWeightKg))
                }
            } else {
                ""
            },
        )
    }

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

        // Weight Unit Section - Material 3 Expressive
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
                                    colors = listOf(Color(0xFF8B5CF6), Color(0xFF9333EA)),
                                ),
                                MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Scale,
                            contentDescription = stringResource(Res.string.cd_weight_unit),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp), // Material 3 Expressive: Larger icon
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        stringResource(Res.string.settings_weight_unit),
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    FilterChip(
                        selected = localWeightUnit == WeightUnit.KG,
                        onClick = {
                            val changed = localWeightUnit != WeightUnit.KG
                            localWeightUnit = WeightUnit.KG
                            onWeightUnitChange(WeightUnit.KG)
                            // Reset increment to default when unit changes (options differ per system)
                            if (changed) onWeightIncrementChange(-1f)
                        },
                        label = { Text(stringResource(Res.string.label_kg)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = localWeightUnit == WeightUnit.KG,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    FilterChip(
                        selected = localWeightUnit == WeightUnit.LB,
                        onClick = {
                            val changed = localWeightUnit != WeightUnit.LB
                            localWeightUnit = WeightUnit.LB
                            onWeightUnitChange(WeightUnit.LB)
                            // Reset increment to default when unit changes (options differ per system)
                            if (changed) onWeightIncrementChange(-1f)
                        },
                        label = { Text(stringResource(Res.string.label_lbs)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = localWeightUnit == WeightUnit.LB,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                // Issue #266: Weight Increment Picker
                Spacer(modifier = Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(Spacing.small))

                val incrementOptions = if (localWeightUnit == WeightUnit.KG) {
                    Constants.WEIGHT_INCREMENT_OPTIONS_KG
                } else {
                    Constants.WEIGHT_INCREMENT_OPTIONS_LB
                }
                val effectiveIncrement = if (weightIncrement > 0f) {
                    weightIncrement
                } else if (localWeightUnit == WeightUnit.KG) {
                    Constants.DEFAULT_WEIGHT_INCREMENT_KG
                } else {
                    Constants.DEFAULT_WEIGHT_INCREMENT_LB
                }
                val unitLabel = if (localWeightUnit == WeightUnit.KG) "kg" else "lb"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWeightIncrementDialog = true }
                        .padding(vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Weight Increment",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${UnitConverter.formatDecimal(effectiveIncrement)} $unitLabel per step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Weight increment selection dialog
                if (showWeightIncrementDialog) {
                    AlertDialog(
                        onDismissRequest = { showWeightIncrementDialog = false },
                        title = { Text("Weight Increment") },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                            ) {
                                Text(
                                    text = "Choose how much weight changes with each +/- tap",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(Spacing.small))
                                incrementOptions.forEach { option ->
                                    val isSelected = kotlin.math.abs(effectiveIncrement - option) < 0.001f
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onWeightIncrementChange(option)
                                                showWeightIncrementDialog = false
                                            }
                                            .padding(vertical = Spacing.small),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                onWeightIncrementChange(option)
                                                showWeightIncrementDialog = false
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.small))
                                        Text(
                                            text = "${UnitConverter.formatDecimal(option)} $unitLabel",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showWeightIncrementDialog = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }

                // Issue #229: Body Weight Input
                Spacer(modifier = Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(Spacing.small))

                val bodyWeightUnitLabel = if (localWeightUnit == WeightUnit.KG) "kg" else "lb"
                val displayBodyWeight = if (bodyWeightKg > 0f) {
                    if (localWeightUnit == WeightUnit.KG) {
                        "${UnitConverter.formatDecimal(bodyWeightKg)} $bodyWeightUnitLabel"
                    } else {
                        "${UnitConverter.formatDecimal(UnitConverter.kgToLb(bodyWeightKg))} $bodyWeightUnitLabel"
                    }
                } else {
                    "Not set"
                }
                val bodyWeightDescription = latestMatchingHealthBodyWeight?.let { measurement ->
                    "$displayBodyWeight - synced from ${measurement.provider.displayName}"
                } ?: "$displayBodyWeight — for bodyweight exercise volume"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBodyWeightDialog = true }
                        .padding(vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Body Weight",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = bodyWeightDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(Spacing.small))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToEquipmentRack() }
                        .padding(vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = stringResource(Res.string.cd_equipment_rack),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Column {
                            Text(
                                text = stringResource(Res.string.equipment_rack_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(Res.string.equipment_rack_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Body weight input dialog
                if (showBodyWeightDialog) {
                    AlertDialog(
                        onDismissRequest = { showBodyWeightDialog = false },
                        title = { Text("Body Weight") },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Text(
                                    text = "Used to estimate volume for bodyweight exercises (push-ups, pull-ups, etc.)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ConfirmEditTextField(
                                    value = bodyWeightInput,
                                    onValueChange = { input ->
                                        // Allow only valid numeric input
                                        if (input.isEmpty() || input.matches(Regex("^\\d{0,3}(\\.\\d{0,1})?$"))) {
                                            bodyWeightInput = input
                                        }
                                    },
                                    label = { Text("Weight ($bodyWeightUnitLabel)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val minDisplay = if (localWeightUnit == WeightUnit.KG) "20" else "44"
                                val maxDisplay = if (localWeightUnit == WeightUnit.KG) "300" else "660"
                                Text(
                                    text = "Range: $minDisplay–$maxDisplay $bodyWeightUnitLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val parsed = bodyWeightInput.toFloatOrNull()
                                    if (parsed != null) {
                                        val inKg = if (localWeightUnit == WeightUnit.LB) {
                                            UnitConverter.lbToKg(parsed)
                                        } else {
                                            parsed
                                        }
                                        // Clamp to valid range: 20-300 kg
                                        val clamped = inKg.coerceIn(20f, 300f)
                                        onBodyWeightKgChange(clamped)
                                    }
                                    showBodyWeightDialog = false
                                },
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBodyWeightDialog = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
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

        // Workout Preferences Section - Material 3 Expressive
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
                                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                                ),
                                MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(Res.string.cd_advanced_settings),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp), // Material 3 Expressive: Larger icon
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Workout Preferences",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger (was titleMedium)
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Issue #167: Summary Countdown now controls autoplay behavior
                // - Off (-1): Skip summary, auto-advance immediately
                // - Unlimited (0): Show summary, wait for manual tap (like old autoplay OFF)
                // - 5-30s: Show summary, auto-advance after countdown (like old autoplay ON)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Set Summary",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Off = skip summary, Unlimited = manual, 5-30s = auto-advance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                -1 -> "Off"

                                // Skip summary entirely
                                0 -> "Unlimited"

                                // Show summary, no auto-advance
                                else -> "${it}s"
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Autostart Countdown - always visible
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autostart Countdown",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Just Lift countdown when handles are grabbed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    CountdownDropdown(
                        label = "",
                        selectedValue = autoStartCountdownSeconds,
                        options = (2..10).toList(),
                        onValueSelected = { onAutoStartCountdownChange(it) },
                        modifier = Modifier.width(100.dp),
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #190: Auto-start routine toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-start Routine",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Skip overview and start first exercise immediately",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoStartRoutine,
                        onCheckedChange = onAutoStartRoutineChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #424: Suggest-only next-set weight recommendations
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.settings_weight_suggestions_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.settings_weight_suggestions_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = weightSuggestionsEnabled,
                        onCheckedChange = onWeightSuggestionsEnabledChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #517: Default scaling basis for % of 1RM / VBT weight scaling
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Default Weight Scaling Basis",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Baseline used when scaling routine weights by percentage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    val scalingBasisOptions = listOf(
                        ScalingBasis.MAX_WEIGHT_PR to "Max-weight PR",
                        ScalingBasis.MAX_VOLUME_PR to "Max-volume PR",
                        ScalingBasis.ESTIMATED_1RM to "Est. 1RM",
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        scalingBasisOptions.forEachIndexed { index, (basis, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = scalingBasisOptions.size),
                                onClick = { onDefaultScalingBasisChange(basis) },
                                selected = defaultScalingBasis == basis,
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #595: Opt-in starting weights for newly added routine exercises.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Routine starting weights",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (defaultRoutineExerciseUsePercentOfPR) {
                                    "When a baseline exists, newly added routine exercises start at your chosen percentage. You can still edit each exercise before saving."
                                } else {
                                    "New routine exercises start from the normal manual weight."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = defaultRoutineExerciseUsePercentOfPR,
                            onCheckedChange = onDefaultRoutineExerciseUsePercentOfPRChange,
                        )
                    }

                    if (defaultRoutineExerciseUsePercentOfPR) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        val routineDefaultPercent = defaultRoutineExerciseWeightPercentOfPR.coerceIn(50, 120)
                        Text(
                            "$routineDefaultPercent% of selected baseline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        ExpressiveSlider(
                            value = routineDefaultPercent.toFloat(),
                            onValueChange = { value ->
                                val roundedToFive = (value / 5f).roundToInt() * 5
                                onDefaultRoutineExerciseWeightPercentOfPRChange(roundedToFive.coerceIn(50, 120))
                            },
                            valueRange = 50f..120f,
                            steps = 13,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            listOf(70, 80, 90, 100).forEach { percent ->
                                FilterChip(
                                    selected = routineDefaultPercent == percent,
                                    onClick = { onDefaultRoutineExerciseWeightPercentOfPRChange(percent) },
                                    label = { Text("$percent%") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Enable Video Playback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Show Exercise Videos",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Display exercise demonstration videos (disable to avoid slow loading)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enableVideoPlayback,
                        onCheckedChange = onEnableVideoPlaybackChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Audio Rep Counter toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Audio Rep Counter",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Play spoken rep numbers during working sets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = audioRepCountEnabled,
                        onCheckedChange = onAudioRepCountChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #100: Countdown beeps toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Countdown Beeps",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Beep during the last 10 seconds of rest timers and timed sets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = countdownBeepsEnabled,
                        onCheckedChange = onCountdownBeepsChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #100: Rep completion sound toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Rep Completion Sound",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Play sound when a rep is completed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = repSoundEnabled,
                        onCheckedChange = onRepSoundChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Issue #237: Motion-triggered set start toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Motion-Triggered Set Start",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start sets by holding the cable instead of countdown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = motionStartEnabled,
                        onCheckedChange = onMotionStartChange,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Gamification toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Gamification",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Show PR celebrations and award badges after workouts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = gamificationEnabled,
                        onCheckedChange = onGamificationEnabledChange,
                    )
                }

                // Voice Emergency Stop - consolidated from standalone section
                Spacer(modifier = Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(Spacing.medium))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(Res.string.settings_voice_stop_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.settings_voice_stop_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = voiceStopEnabled,
                        onCheckedChange = onVoiceStopEnabledChange,
                    )
                }

                // Safe word configuration (shown when voice stop is enabled)
                if (voiceStopEnabled) {
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    ConfirmEditTextField(
                        value = localSafeWord,
                        onValueChange = { newValue ->
                            localSafeWord = newValue.uppercase().trim()
                        },
                        label = { Text(stringResource(Res.string.settings_safe_word_label)) },
                        placeholder = { Text(stringResource(Res.string.settings_safe_word_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    )

                    Spacer(modifier = Modifier.height(Spacing.small))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (safeWordCalibrated && safeWord == localSafeWord) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(Res.string.cd_calibration_check),
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(Res.string.settings_calibrated_badge),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            Text(
                                stringResource(Res.string.settings_calibrate_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
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
                            shape = MaterialTheme.shapes.small,
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
                },
            )
        }

        // Color Scheme Section - Compact with visual previews
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
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = if (discoModeActive) {
                                        // Rainbow gradient when disco mode is active
                                        listOf(
                                            Color(0xFFFF0000),
                                            Color(0xFFFF7F00),
                                            Color(0xFFFFFF00),
                                            Color(0xFF00FF00),
                                            Color(0xFF0000FF),
                                            Color(0xFF8B00FF),
                                        )
                                    } else {
                                        listOf(Color(0xFF3B82F6), Color(0xFF6366F1))
                                    },
                                ),
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.ColorLens,
                            contentDescription = stringResource(Res.string.cd_led_scheme),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "LED Color Scheme",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Color scheme picker — row of tappable color circles
                val colorSchemes = ColorSchemes.ALL

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    colorSchemes.forEachIndexed { index, scheme ->
                        val isSelected = index == selectedColorSchemeIndex
                        val isNone = scheme.name == "None"

                        // Selection ring + circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(3.dp)
                                .clip(CircleShape)
                                .then(
                                    if (isNone) {
                                        Modifier.background(Color.DarkGray, CircleShape)
                                    } else {
                                        Modifier.background(
                                            Brush.radialGradient(
                                                scheme.colors.map { Color(it.r, it.g, it.b) },
                                            ),
                                            CircleShape,
                                        )
                                    },
                                )
                                .clickable { onColorSchemeChange(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isNone) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = stringResource(Res.string.cd_leds_off),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // Selected scheme name
                val currentScheme = colorSchemes.getOrElse(selectedColorSchemeIndex) { colorSchemes.first() }
                Text(
                    text = currentScheme.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.small),
                )

                // Disco mode toggle (only visible when unlocked)
                if (discoModeUnlocked) {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.small),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🕺",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.width(Spacing.small))
                            Column {
                                Text(
                                    "Disco Mode",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (!isConnected) {
                                        "Connect to enable"
                                    } else if (discoModeActive) {
                                        "Party time!"
                                    } else {
                                        "Cycle through colors"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = discoModeActive,
                            onCheckedChange = { onDiscoModeToggle(it) },
                            enabled = isConnected,
                        )
                    }
                }
            }
        }

        // Issue #313: Velocity-Based Training Section
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
                Row(
                    modifier = Modifier.clickable(
                        // Issue #611: 7-tap easter egg counter gated to only count
                        // when vulgar mode is on. The clickable is a no-op otherwise,
                        // so the Dominatrix feature stays unreachable from a clean install.
                        enabled = verbalEncouragementEnabled && vulgarModeEnabled,
                    ) {
                        val currentTime = KmpUtils.currentTimeMillis()
                        // Reset if more than 2 seconds since last tap
                        if (currentTime - lastDominatrixTapTime > 2000L) {
                            dominatrixEasterEggTapCount = 1
                        } else {
                            dominatrixEasterEggTapCount++
                        }
                        lastDominatrixTapTime = currentTime

                        // Unlock Dominatrix Mode after 7 rapid taps
                        if (dominatrixEasterEggTapCount >= 7 && !dominatrixModeUnlocked) {
                            showDominatrixUnlockDialog = true
                            onPlayDominatrixUnlockSound()
                            onDominatrixModeUnlockedChange(true)
                            dominatrixEasterEggTapCount = 0
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                            Icons.Default.Speed,
                            contentDescription = "Velocity-Based Training",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.medium))
                    Text(
                        "Velocity-Based Training",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Issue #611: 7-tap hint pill (visible when master + vulgar on, dominatrix locked)
                if (
                    AdultModePresentation.shouldShowDominatrixHint(
                        verbalEncouragementEnabled = verbalEncouragementEnabled,
                        vulgarModeEnabled = vulgarModeEnabled,
                        dominatrixModeUnlocked = dominatrixModeUnlocked,
                    )
                ) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(Res.string.settings_dominatrix_unlock_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = 8.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.small))

                // Power Loss Threshold slider
                Column {
                    Text(
                        "Power Loss Threshold",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Velocity drop percentage that signals fatigue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "10%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ExpressiveSlider(
                            value = velocityLossThresholdPercent.toFloat(),
                            onValueChange = { onVelocityLossThresholdChange(it.roundToInt()) },
                            valueRange = 10f..50f,
                            steps = 7,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            "50%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Current: $velocityLossThresholdPercent%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "Changes take effect on next workout",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Auto-end toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-End on Velocity Loss",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (stallDetectionEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (stallDetectionEnabled) {
                                "Automatically end set when threshold is reached"
                            } else {
                                "Enable Stall Detection in Workout Settings to use auto-end"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoEndOnVelocityLoss,
                        onCheckedChange = onAutoEndOnVelocityLossChange,
                        enabled = stallDetectionEnabled,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.medium))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.small),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // ===== Issue #611: Verbal Encouragement (master toggle) =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.settings_verbal_encouragement_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.settings_verbal_encouragement_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = verbalEncouragementEnabled,
                        onCheckedChange = { checked ->
                            // Master-off cascades vulgar + dominatrix off via the preferences setter
                            // (see SettingsPreferencesManager.setVerbalEncouragementEnabled).
                            // When the user is enabling the master for the first time, the
                            // existing audio cues master switch (beepsEnabled) gates everything.
                            onVerbalEncouragementEnabledChange(checked)
                        },
                    )
                }

                // ===== Issue #611: Vulgar Mode sub-row (visible iff master on) =====
                if (verbalEncouragementEnabled) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.settings_vulgar_mode_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.settings_vulgar_mode_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(Res.string.settings_vulgar_mode_headphone_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = vulgarModeEnabled,
                            onCheckedChange = { checked ->
                                // Issue #611 (PR-followup #613): gate on the one-shot
                                // decline-remember flag, not the adultsOnlyConfirmed
                                // confirm flag. confirmed=false (decline path) must NOT
                                // re-trigger the modal — see VerbalEncouragementPreferenceCascadeTest.
                                if (checked && !adultsOnlyPrompted) {
                                    // Show the 18+ modal flow. Confirm flips
                                    // adultsOnlyConfirmed + marks prompted; decline only
                                    // marks prompted so the modal never re-appears.
                                    showAdultsOnlyDialog = true
                                } else {
                                    onVulgarModeEnabledChange(checked)
                                }
                            },
                        )
                    }

                    // ===== Issue #611: Tier chip selector (MILD / STRONG / MIX) =====
                    if (vulgarModeEnabled) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            stringResource(Res.string.settings_vulgar_mode_tier_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            VulgarTier.entries.forEach { tier ->
                                FilterChip(
                                    selected = vulgarTier == tier,
                                    onClick = { onVulgarTierChange(tier) },
                                    label = {
                                        Text(
                                            stringResource(
                                                when (tier) {
                                                    VulgarTier.MILD -> Res.string.settings_vulgar_mode_tier_mild
                                                    VulgarTier.STRONG -> Res.string.settings_vulgar_mode_tier_strong
                                                    VulgarTier.MIX -> Res.string.settings_vulgar_mode_tier_mix
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // ===== Issue #611: Dominatrix Mode sub-sub-row =====
                    if (vulgarModeEnabled && dominatrixModeUnlocked && adultsOnlyConfirmed) {
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.settings_dominatrix_mode_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(Res.string.settings_dominatrix_mode_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = dominatrixModeActive,
                                onCheckedChange = onDominatrixModeActiveChange,
                            )
                        }
                    }
                }
            }
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

        // Achievements Section - Material 3 Expressive (hidden when gamification is disabled)
        if (gamificationEnabled) {
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
                                Icons.Default.MilitaryTech,
                                contentDescription = stringResource(Res.string.cd_achievements),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.medium))
                        Text(
                            "Achievements",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.small))

                    OutlinedButton(
                        onClick = onNavigateToBadges,
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
                            Icons.Default.EmojiEvents,
                            contentDescription = stringResource(Res.string.cd_view_badges),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            "View Badges & Streaks",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Track your progress, earn badges, and maintain your workout streak",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

                // Issue #333: BLE small-MTU compatibility path (fixes Pixel 6/7
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
                        if (DeviceInfo.isPixel6Or7()) {
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

    // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
    connectionError?.let { error ->
        com.devil.phoenixproject.presentation.components.ConnectionErrorDialog(
            message = error,
            onDismiss = onClearConnectionError,
        )
    }

    // Disco Mode Unlock Celebration Dialog
    if (showDiscoUnlockDialog) {
        DiscoModeUnlockDialog(
            onDismiss = { showDiscoUnlockDialog = false },
        )
    }

    // Issue #611: Dominatrix Mode Unlock Celebration Dialog
    if (showDominatrixUnlockDialog) {
        DominatrixUnlockDialog(
            onDismiss = { showDominatrixUnlockDialog = false },
        )
    }

    // Issue #611: 18+ Adults Only confirmation modal (fires once per install on first vulgar-on)
    if (showAdultsOnlyDialog) {
        AdultsOnlyConfirmDialog(
            onConfirm = {
                showAdultsOnlyDialog = false
                // Confirm: write both confirmed (cascade-enable path) and prompted
                // (one-shot gate). Mirrors SettingsPreferencesManager.setAdultsOnlyConfirmed.
                onAdultsOnlyConfirmedChange(true)
                onAdultsOnlyPromptedChange(true)
                onVulgarModeEnabledChange(true)
            },
            onDecline = {
                showAdultsOnlyDialog = false
                // Decline: ONLY mark the one-shot prompt gate. The cascade
                // invariant now checks prompted (not confirmed), so declining
                // does not block future vulgar-on attempts.
                onAdultsOnlyPromptedChange(true)
            },
            onDismiss = {
                showAdultsOnlyDialog = false
                // Dismiss (back-press / scrim) without confirmation — user must
                // explicitly tap a button to either confirm or decline. Does NOT
                // mark prompted so a subsequent vulgar-on toggle can re-prompt.
            },
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

/**
 * Calibration dialog: user must say the safe word 3 times successfully.
 * Uses SafeWordListenerFactory via Koin to create a listener.
 */
@Composable
private fun SafeWordCalibrationDialog(
    safeWord: String,
    onCalibrated: () -> Unit,
    onDismiss: () -> Unit,
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
        } catch (_: Exception) {
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
        shape = MaterialTheme.shapes.medium,
        title = {
            Text(
                stringResource(Res.string.settings_calibration_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                when {
                    micError -> {
                        Text(
                            stringResource(Res.string.settings_calibration_mic_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = { com.devil.phoenixproject.util.openAppSettings() },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(stringResource(Res.string.settings_calibration_open_settings))
                        }
                    }

                    calibrationFailed -> {
                        Text(
                            stringResource(Res.string.settings_calibration_fail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        // Prompt
                        Text(
                            stringResource(Res.string.settings_calibration_prompt),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            safeWord,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        // 3 circles showing progress
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                            modifier = Modifier.padding(vertical = Spacing.small),
                        ) {
                            repeat(3) { index ->
                                val filled = index < detectionCount
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            color = if (filled) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(50),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (filled) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(Res.string.cd_calibration_check),
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Progress text
                        Text(
                            stringResource(Res.string.settings_calibration_progress, detectionCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Listening indicator
                        Text(
                            stringResource(Res.string.settings_calibration_listening),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 10_000 -> "${count / 1_000}K"
    count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}K"
    else -> count.toString()
}

@Composable
private fun AdultModeDialogCard(
    presentation: AdultModePresentation,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = adultModeContainerColor(presentation.containerTone),
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(2.dp, adultModeBorderColor(presentation)),
    ) {
        content()
    }
}

@Composable
private fun adultModeContainerColor(tone: AdultModeDialogTone): Color = when (tone) {
    AdultModeDialogTone.ThemeSurface -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun adultModeBorderColor(presentation: AdultModePresentation): Color = when {
    // Keep the policy honest without reintroducing a bespoke palette: even if a
    // future policy asks for a risky accent, map it back through theme tokens.
    presentation.usesBespokePinkAccent -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
    presentation.usesBrandAccent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    else -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun AdultModeActions(
    presentation: AdultModePresentation,
    confirmLabel: String,
    onConfirm: () -> Unit,
    declineLabel: String? = null,
    onDecline: (() -> Unit)? = null,
) {
    when (presentation.actionLayout) {
        AdultModeActionLayout.SinglePrimary -> AdultModeActionButton(
            label = confirmLabel,
            tone = presentation.confirmTone,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )

        AdultModeActionLayout.StackedFullWidth -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            AdultModeActionButton(
                label = confirmLabel,
                tone = presentation.confirmTone,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            if (declineLabel != null && onDecline != null) {
                AdultModeActionButton(
                    label = declineLabel,
                    tone = presentation.declineTone ?: AdultModeActionTone.Outline,
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AdultModeActionButton(
    label: String,
    tone: AdultModeActionTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tone) {
        AdultModeActionTone.Primary -> Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            AdultModeActionLabel(
                text = label,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        AdultModeActionTone.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            ),
        ) {
            AdultModeActionLabel(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AdultModeActionLabel(
    text: String,
    color: Color,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = fontWeight,
        color = color,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * Issue #611: Dominatrix Mode unlock celebration dialog. Uses the same Phoenix
 * Material 3 surface/card/button idioms as the rest of Settings instead of a
 * bespoke dark + pink palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DominatrixUnlockDialog(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }

    val presentation = AdultModePresentation.dominatrixUnlock()
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dominatrix_dialog_scale",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
    ) {
        AdultModeDialogCard(presentation = presentation) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "👑",
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(Res.string.settings_dominatrix_unlock_toast),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(Res.string.settings_dominatrix_mode_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.large))

                AdultModeActions(
                    presentation = presentation,
                    confirmLabel = stringResource(Res.string.action_ok),
                    onConfirm = onDismiss,
                )
            }
        }
    }
}

/**
 * Issue #611: 18+ Adults Only confirmation modal. Fires once per install when
 * the user toggles Vulgar Mode from off to on. Confirm flips
 * adultsOnlyConfirmed first then re-issues the vulgar-mode setter; decline
 * only persists the one-shot decline-remember flag (`adultsOnlyPrompted`) so
 * the modal never re-prompts — see VerbalEncouragementPreferenceCascadeTest
 * "decline path leaves modal dormant on subsequent vulgar-on toggles".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdultsOnlyConfirmDialog(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = AdultModePresentation.adultsOnlyConfirmation()

    BasicAlertDialog(onDismissRequest = onDismiss) {
        AdultModeDialogCard(presentation = presentation) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.large),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            text = stringResource(Res.string.settings_adults_only_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(Res.string.settings_adults_only_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = stringResource(Res.string.settings_adults_only_compliance_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.medium),
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.large))

                AdultModeActions(
                    presentation = presentation,
                    confirmLabel = stringResource(Res.string.settings_adults_only_confirm),
                    onConfirm = onConfirm,
                    declineLabel = stringResource(Res.string.settings_adults_only_decline),
                    onDecline = onDecline,
                )
            }
        }
    }
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
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dialog_scale",
    )

    // Rotating disco ball effect - rememberInfiniteTransition for variable-refresh compatibility
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val discoTransition = rememberInfiniteTransition(label = "disco")

    // reduceMotion: bake the flag into targetValue so the channel settles at initial (no movement)
    val rotation by discoTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1920, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "disco_rotation",
    )

    // 400ms half-cycle matches original +0.02f/frame @ 60fps (0.5 range ÷ 0.02 = 25 frames × 16ms)
    val glowAlpha by discoTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (reduceMotion) 0.3f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "disco_glow",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
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
                                    Color.Transparent,
                                ),
                            ),
                            shape = RoundedCornerShape(40.dp),
                        ),
                ) {
                    Text(
                        "🪩",
                        style = MaterialTheme.typography.displayLarge,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "DISCO MODE UNLOCKED!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFF0000),
                                Color(0xFFFF7F00),
                                Color(0xFFFFFF00),
                                Color(0xFF00FF00),
                                Color(0xFF0000FF),
                                Color(0xFF8B00FF),
                            ),
                        ),
                        shape = MaterialTheme.shapes.extraSmall,
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "🕺 Time to get funky! 💃",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Toggle Disco Mode in the LED Color Scheme section to make your trainer party!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    "🎉 Let's Party!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
