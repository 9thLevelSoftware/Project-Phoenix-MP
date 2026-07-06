package com.devil.phoenixproject.data.preferences

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.ble.BleCompatibilityMode
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupDestination.Companion.toJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single exercise defaults for saving/loading exercise configurations
 */
@Serializable
data class SingleExerciseDefaults(
    val exerciseId: String,
    val setReps: List<Int?>,
    val weightPerCableKg: Float,
    val setWeightsPerCableKg: List<Float>,
    val progressionKg: Float,
    val setRestSeconds: List<Int>,
    val workoutModeId: Int,
    val eccentricLoadPercentage: Int,
    val echoLevelValue: Int,
    val duration: Int,
    val isAMRAP: Boolean,
    val perSetRestTime: Boolean,
    val defaultRackItemIds: List<String> = emptyList(),
) {
    fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
        // Handle legacy 125% -> fall back to 120%
        val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
        return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
            ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
    }

    fun getEchoLevel(): com.devil.phoenixproject.domain.model.EchoLevel = com.devil.phoenixproject.domain.model.EchoLevel.entries.find {
        it.levelValue == echoLevelValue
    }
        ?: com.devil.phoenixproject.domain.model.EchoLevel.HARDER

    fun toProgramMode(): com.devil.phoenixproject.domain.model.ProgramMode = when (workoutModeId) {
        0 -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
        2 -> com.devil.phoenixproject.domain.model.ProgramMode.Pump
        3 -> com.devil.phoenixproject.domain.model.ProgramMode.TUT
        4 -> com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast
        6 -> com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly
        10 -> com.devil.phoenixproject.domain.model.ProgramMode.Echo
        else -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
    }
}

/**
 * Just Lift defaults
 */
@Serializable
data class JustLiftDefaults(
    val workoutModeId: Int = 0,
    val weightPerCableKg: Float = 20f,
    val weightChangePerRep: Float = 0f,
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1,
    val stallDetectionEnabled: Boolean = true, // Stall detection auto-stop toggle
    val repCountTimingName: String = "TOP", // RepCountTiming enum name
    val restSeconds: Int = 60, // Rest timer between sets (0 = off, 5-300 in 5s increments)
) {
    fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
        // Handle legacy 125% -> fall back to 120%
        val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
        return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
            ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
    }

    fun getEchoLevel(): com.devil.phoenixproject.domain.model.EchoLevel = com.devil.phoenixproject.domain.model.EchoLevel.entries.find {
        it.levelValue == echoLevelValue
    }
        ?: com.devil.phoenixproject.domain.model.EchoLevel.HARDER

    fun toProgramMode(): com.devil.phoenixproject.domain.model.ProgramMode = when (workoutModeId) {
        0 -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
        2 -> com.devil.phoenixproject.domain.model.ProgramMode.Pump
        3 -> com.devil.phoenixproject.domain.model.ProgramMode.TUT
        4 -> com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast
        6 -> com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly
        10 -> com.devil.phoenixproject.domain.model.ProgramMode.Echo
        else -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
    }
}

/**
 * Preferences Manager interface
 * Implemented using multiplatform-settings for persistent storage
 */
interface PreferencesManager {
    val preferencesFlow: StateFlow<UserPreferences>

    suspend fun setWeightUnit(unit: WeightUnit)

    // Issue #167: setAutoplayEnabled removed - autoplay now derived from summaryCountdownSeconds
    suspend fun setStopAtTop(enabled: Boolean)
    suspend fun setEnableVideoPlayback(enabled: Boolean)
    suspend fun setBeepsEnabled(enabled: Boolean)
    suspend fun setColorScheme(scheme: Int)
    suspend fun setStallDetectionEnabled(enabled: Boolean)
    suspend fun setDiscoModeUnlocked(unlocked: Boolean)
    suspend fun setAudioRepCountEnabled(enabled: Boolean)
    suspend fun setRepCountTiming(timing: RepCountTiming)
    suspend fun setSummaryCountdownSeconds(seconds: Int)
    suspend fun setAutoStartCountdownSeconds(seconds: Int)
    suspend fun setGamificationEnabled(enabled: Boolean)

    // Issue #266: Configurable weight increment
    suspend fun setWeightIncrement(increment: Float)

    // Issue #190: Auto-start routine
    suspend fun setAutoStartRoutine(enabled: Boolean)

    // Issue #229: Body weight for bodyweight exercise volume
    suspend fun setBodyWeightKg(weightKg: Float)

    // Issue #100: Per-sound toggles
    suspend fun setCountdownBeepsEnabled(enabled: Boolean)
    suspend fun setRepSoundEnabled(enabled: Boolean)

    // Issue #237: Motion-triggered set start
    suspend fun setMotionStartEnabled(enabled: Boolean)

    // Issue #293: Per-session auto-backup
    suspend fun setAutoBackupEnabled(enabled: Boolean)

    // Issue #293: Custom backup destination
    suspend fun setBackupDestination(destination: BackupDestination)

    // Issue #238: Language/locale preference
    suspend fun setLanguage(language: String)

    // Issue #141: Voice-activated emergency stop
    suspend fun setVoiceStopEnabled(enabled: Boolean)
    suspend fun setSafeWord(word: String?)
    suspend fun setSafeWordCalibrated(calibrated: Boolean)

    // Issue #313: Velocity-Based Training (VBT)
    suspend fun setVelocityLossThreshold(percent: Int)
    suspend fun setAutoEndOnVelocityLoss(enabled: Boolean)

    // Issue #424: Suggestion-only next-set weight recommendations
    suspend fun setWeightSuggestionsEnabled(enabled: Boolean)

    // Issue #517: Default scaling basis for % of 1RM routine weight resolution
    suspend fun setDefaultScalingBasis(basis: ScalingBasis)

    // Issue #595: Routine-builder defaults for newly added exercises
    suspend fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean)
    suspend fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int)

    // Issue #517: Run-once flag for velocity 1RM backfill
    suspend fun setVelocityOneRepMaxBackfillDone(done: Boolean)

    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    suspend fun setVerbalEncouragementEnabled(enabled: Boolean)
    suspend fun setVulgarModeEnabled(enabled: Boolean)
    suspend fun setVulgarTier(tier: VulgarTier)
    suspend fun setDominatrixModeUnlocked(unlocked: Boolean)
    suspend fun setDominatrixModeActive(active: Boolean)
    suspend fun setAdultsOnlyConfirmed(confirmed: Boolean)

    // Issue #333: BLE small-MTU compatibility path (Auto/On/Off)
    suspend fun setBleCompatibilityMode(setting: BleCompatibilitySetting)

    // Issue #611 (PR-followup #613): One-shot decline-remember gate for the 18+
    // Adults Only modal. The modal must not re-prompt on subsequent vulgar-on toggles
    // once either confirm OR decline has been recorded (architecture §3 — follow
    // DiscoModeUnlockDialog pattern). Booleans stored in
    // KEY_ADULTS_ONLY_PROMPTED; not exposed via UserPreferences.
    fun isAdultsOnlyPrompted(): Boolean
    fun setAdultsOnlyPrompted(prompted: Boolean)

    suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults?
    suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults)
    suspend fun clearAllSingleExerciseDefaults()

    suspend fun getJustLiftDefaults(): JustLiftDefaults
    suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults)
    suspend fun clearJustLiftDefaults()
}

/**
 * Multiplatform Settings-based Preferences Manager
 * Provides persistent storage using platform-native mechanisms:
 * - Android: SharedPreferences
 * - iOS: NSUserDefaults
 */
class SettingsPreferencesManager(private val settings: Settings) : PreferencesManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        // Preference keys
        private const val KEY_WEIGHT_UNIT = "weight_unit"

        // Issue #167: KEY_AUTOPLAY_ENABLED removed - autoplay now derived from summaryCountdownSeconds
        private const val KEY_STOP_AT_TOP = "stop_at_top"
        private const val KEY_VIDEO_PLAYBACK = "video_playback"
        private const val KEY_BEEPS_ENABLED = "beeps_enabled"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_STALL_DETECTION = "stall_detection_enabled"
        private const val KEY_DISCO_MODE_UNLOCKED = "disco_mode_unlocked"
        private const val KEY_AUDIO_REP_COUNT = "audio_rep_count_enabled"
        private const val LEGACY_KEY_HUD_PRESET = "hud_preset"
        private const val KEY_SUMMARY_COUNTDOWN_SECONDS = "summary_countdown_seconds"
        private const val KEY_AUTOSTART_COUNTDOWN_SECONDS = "autostart_countdown_seconds"
        private const val KEY_REP_COUNT_TIMING = "rep_count_timing"
        private const val KEY_JUST_LIFT_DEFAULTS = "just_lift_defaults"
        private const val KEY_PREFIX_EXERCISE = "exercise_defaults_"
        private const val KEY_ECHO_HARD_DEFAULT_MIGRATION_JUST_LIFT = "echo_hard_default_migrated_just_lift"
        private const val KEY_ECHO_HARD_DEFAULT_MIGRATION_EXERCISE_PREFIX = "echo_hard_default_migrated_exercise_"
        private const val KEY_GAMIFICATION_ENABLED = "gamification_enabled"
        private const val KEY_WEIGHT_INCREMENT = "weight_increment"
        private const val KEY_AUTO_START_ROUTINE = "auto_start_routine"
        private const val KEY_BODY_WEIGHT_KG = "body_weight_kg"
        private const val KEY_COUNTDOWN_BEEPS_ENABLED = "countdown_beeps_enabled"
        private const val KEY_REP_SOUND_ENABLED = "rep_sound_enabled"
        private const val KEY_MOTION_START = "motion_start_enabled"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_BACKUP_DESTINATION = "backup_destination"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOICE_STOP_ENABLED = "voice_stop_enabled"
        private const val KEY_SAFE_WORD = "safe_word"
        private const val KEY_SAFE_WORD_CALIBRATED = "safe_word_calibrated"
        private const val KEY_VELOCITY_LOSS_THRESHOLD = "velocity_loss_threshold_percent"
        private const val KEY_AUTO_END_VELOCITY_LOSS = "auto_end_on_velocity_loss"
        private const val KEY_WEIGHT_SUGGESTIONS_ENABLED = "weight_suggestions_enabled"
        private const val KEY_DEFAULT_SCALING_BASIS = "default_scaling_basis"
        private const val KEY_DEFAULT_ROUTINE_EXERCISE_USE_PERCENT_OF_PR = "default_routine_exercise_use_percent_of_pr"
        private const val KEY_DEFAULT_ROUTINE_EXERCISE_WEIGHT_PERCENT_OF_PR = "default_routine_exercise_weight_percent_of_pr"
        private const val KEY_VELOCITY_1RM_BACKFILL_DONE = "velocity_1rm_backfill_done"

        // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
        private const val KEY_VERBAL_ENCOURAGEMENT_ENABLED = "verbal_encouragement_enabled"
        private const val KEY_VULGAR_MODE_ENABLED = "vulgar_mode_enabled"
        private const val KEY_VULGAR_TIER = "vulgar_tier"
        private const val KEY_DOMINATRIX_MODE_UNLOCKED = "dominatrix_mode_unlocked"
        private const val KEY_DOMINATRIX_MODE_ACTIVE = "dominatrix_mode_active"
        private const val KEY_ADULTS_ONLY_CONFIRMED = "adults_only_confirmed"
        // One-shot decline-remember flag, read at the modal-call site only (not in UserPreferences).
        private const val KEY_ADULTS_ONLY_PROMPTED = "adults_only_prompted"

        // Permissions onboarding (health + microphone)
        private const val KEY_PERMISSIONS_ONBOARDING_SHOWN = "permissions_onboarding_shown"

        // Issue #333: BLE small-MTU compatibility path (Auto/On/Off)
        private const val KEY_BLE_COMPATIBILITY_MODE = "ble_compatibility_mode"
    }

    private val _preferencesFlow = MutableStateFlow(loadPreferences())
    override val preferencesFlow: StateFlow<UserPreferences> = _preferencesFlow

    private fun loadPreferences(): UserPreferences {
        // HUD preset rollback: ignore and remove the retired key on upgraded installs.
        settings.remove(LEGACY_KEY_HUD_PRESET)

        return UserPreferences(
            weightUnit = settings.getStringOrNull(KEY_WEIGHT_UNIT)?.let {
                WeightUnit.entries.find { unit -> unit.name == it }
            } ?: WeightUnit.LB,
            // Issue #167: autoplayEnabled removed - now derived from summaryCountdownSeconds
            stopAtTop = settings.getBoolean(KEY_STOP_AT_TOP, false),
            enableVideoPlayback = settings.getBoolean(KEY_VIDEO_PLAYBACK, true),
            beepsEnabled = settings.getBoolean(KEY_BEEPS_ENABLED, true),
            colorScheme = settings.getInt(KEY_COLOR_SCHEME, 0),
            stallDetectionEnabled = settings.getBoolean(KEY_STALL_DETECTION, true),
            discoModeUnlocked = settings.getBoolean(KEY_DISCO_MODE_UNLOCKED, false),
            audioRepCountEnabled = settings.getBoolean(KEY_AUDIO_REP_COUNT, false),
            repCountTiming = settings.getStringOrNull(KEY_REP_COUNT_TIMING)?.let {
                try {
                    RepCountTiming.valueOf(it)
                } catch (_: Exception) {
                    null
                }
            } ?: RepCountTiming.TOP,
            summaryCountdownSeconds = settings.getInt(KEY_SUMMARY_COUNTDOWN_SECONDS, 10),
            autoStartCountdownSeconds = settings.getInt(KEY_AUTOSTART_COUNTDOWN_SECONDS, 5),
            gamificationEnabled = settings.getBoolean(KEY_GAMIFICATION_ENABLED, true),
            weightIncrement = settings.getFloat(KEY_WEIGHT_INCREMENT, -1f),
            autoStartRoutine = settings.getBoolean(KEY_AUTO_START_ROUTINE, false),
            bodyWeightKg = settings.getFloat(KEY_BODY_WEIGHT_KG, 0f),
            countdownBeepsEnabled = settings.getBoolean(KEY_COUNTDOWN_BEEPS_ENABLED, true),
            repSoundEnabled = settings.getBoolean(KEY_REP_SOUND_ENABLED, true),
            motionStartEnabled = settings.getBoolean(KEY_MOTION_START, false),
            autoBackupEnabled = settings.getBoolean(KEY_AUTO_BACKUP_ENABLED, false),
            backupDestination = BackupDestination.fromJson(settings.getStringOrNull(KEY_BACKUP_DESTINATION)),
            language = settings.getStringOrNull(KEY_LANGUAGE) ?: "en",
            voiceStopEnabled = settings.getBoolean(KEY_VOICE_STOP_ENABLED, false),
            safeWord = settings.getStringOrNull(KEY_SAFE_WORD),
            safeWordCalibrated = settings.getBoolean(KEY_SAFE_WORD_CALIBRATED, false),
            velocityLossThresholdPercent = settings.getInt(KEY_VELOCITY_LOSS_THRESHOLD, 20).coerceIn(10, 50),
            autoEndOnVelocityLoss = settings.getBoolean(KEY_AUTO_END_VELOCITY_LOSS, false),
            weightSuggestionsEnabled = settings.getBoolean(KEY_WEIGHT_SUGGESTIONS_ENABLED, true),
            defaultScalingBasis = settings.getStringOrNull(KEY_DEFAULT_SCALING_BASIS)?.let {
                runCatching { ScalingBasis.valueOf(it) }.getOrNull()
            } ?: ScalingBasis.MAX_WEIGHT_PR,
            defaultRoutineExerciseUsePercentOfPR = settings.getBoolean(KEY_DEFAULT_ROUTINE_EXERCISE_USE_PERCENT_OF_PR, false),
            defaultRoutineExerciseWeightPercentOfPR = settings.getInt(
                KEY_DEFAULT_ROUTINE_EXERCISE_WEIGHT_PERCENT_OF_PR,
                80,
            ).coerceIn(50, 120),
            velocityOneRepMaxBackfillDone = settings.getBoolean(KEY_VELOCITY_1RM_BACKFILL_DONE, false),
            // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
            verbalEncouragementEnabled = settings.getBoolean(KEY_VERBAL_ENCOURAGEMENT_ENABLED, false),
            vulgarModeEnabled = settings.getBoolean(KEY_VULGAR_MODE_ENABLED, false),
            vulgarTier = settings.getStringOrNull(KEY_VULGAR_TIER)?.let {
                runCatching { VulgarTier.valueOf(it) }.getOrNull()
            } ?: VulgarTier.STRONG,
            dominatrixModeUnlocked = settings.getBoolean(KEY_DOMINATRIX_MODE_UNLOCKED, false),
            dominatrixModeActive = settings.getBoolean(KEY_DOMINATRIX_MODE_ACTIVE, false),
            adultsOnlyConfirmed = settings.getBoolean(KEY_ADULTS_ONLY_CONFIRMED, false),
            adultsOnlyPrompted = settings.getBoolean(KEY_ADULTS_ONLY_PROMPTED, false),
            bleCompatibilityMode = BleCompatibilitySetting.fromStorage(
                settings.getStringOrNull(KEY_BLE_COMPATIBILITY_MODE),
            ).also {
                // Issue #333: the BLE layer reads the resolved mode via this global
                // before any preference flow is collected, so sync it at load time.
                BleCompatibilityMode.setting = it
            },
        )
    }

    private fun updateAndEmit(update: UserPreferences.() -> UserPreferences) {
        _preferencesFlow.update { it.update() }
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        settings.putString(KEY_WEIGHT_UNIT, unit.name)
        updateAndEmit { copy(weightUnit = unit) }
    }

    // Issue #167: setAutoplayEnabled removed - autoplay now derived from summaryCountdownSeconds

    override suspend fun setStopAtTop(enabled: Boolean) {
        settings.putBoolean(KEY_STOP_AT_TOP, enabled)
        updateAndEmit { copy(stopAtTop = enabled) }
    }

    override suspend fun setEnableVideoPlayback(enabled: Boolean) {
        settings.putBoolean(KEY_VIDEO_PLAYBACK, enabled)
        updateAndEmit { copy(enableVideoPlayback = enabled) }
    }

    override suspend fun setBeepsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_BEEPS_ENABLED, enabled)
        updateAndEmit { copy(beepsEnabled = enabled) }
    }

    override suspend fun setColorScheme(scheme: Int) {
        settings.putInt(KEY_COLOR_SCHEME, scheme)
        updateAndEmit { copy(colorScheme = scheme) }
    }
    override suspend fun setStallDetectionEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_STALL_DETECTION, enabled)
        updateAndEmit { copy(stallDetectionEnabled = enabled) }
    }

    override suspend fun setDiscoModeUnlocked(unlocked: Boolean) {
        settings.putBoolean(KEY_DISCO_MODE_UNLOCKED, unlocked)
        updateAndEmit { copy(discoModeUnlocked = unlocked) }
    }

    override suspend fun setAudioRepCountEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUDIO_REP_COUNT, enabled)
        updateAndEmit { copy(audioRepCountEnabled = enabled) }
    }

    override suspend fun setRepCountTiming(timing: RepCountTiming) {
        settings.putString(KEY_REP_COUNT_TIMING, timing.name)
        updateAndEmit { copy(repCountTiming = timing) }
    }

    override suspend fun setSummaryCountdownSeconds(seconds: Int) {
        settings.putInt(KEY_SUMMARY_COUNTDOWN_SECONDS, seconds)
        updateAndEmit { copy(summaryCountdownSeconds = seconds) }
    }

    override suspend fun setAutoStartCountdownSeconds(seconds: Int) {
        settings.putInt(KEY_AUTOSTART_COUNTDOWN_SECONDS, seconds)
        updateAndEmit { copy(autoStartCountdownSeconds = seconds) }
    }

    fun isPermissionsOnboardingShown(): Boolean = settings.getBoolean(KEY_PERMISSIONS_ONBOARDING_SHOWN, false)

    fun setPermissionsOnboardingShown(shown: Boolean) {
        settings.putBoolean(KEY_PERMISSIONS_ONBOARDING_SHOWN, shown)
    }

    override suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults? {
        val key = "$KEY_PREFIX_EXERCISE$exerciseId"

        // Try new key format first
        var jsonString = settings.getStringOrNull(key)

        // Migration: If not found, try legacy key formats that included cableConfig
        if (jsonString == null) {
            val legacyCableConfigs = listOf("DOUBLE", "SINGLE", "EITHER")
            for (cableConfig in legacyCableConfigs) {
                val legacyKey = "${KEY_PREFIX_EXERCISE}${exerciseId}_$cableConfig"
                jsonString = settings.getStringOrNull(legacyKey)
                if (jsonString != null) {
                    // Found with legacy key - migrate to new format
                    settings.putString(key, jsonString)
                    settings.remove(legacyKey)
                    break
                }
            }
        }

        if (jsonString == null) return null

        return try {
            migrateSavedEchoHardDefault(
                exerciseId = exerciseId,
                key = key,
                defaults = json.decodeFromString<SingleExerciseDefaults>(jsonString),
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        val normalizedDefaults = defaults.withNormalizedNonEchoEchoLevel()
        val key = "$KEY_PREFIX_EXERCISE${defaults.exerciseId}"
        settings.putString(key, json.encodeToString(normalizedDefaults))
        settings.putBoolean(getEchoHardDefaultMigrationKey(defaults.exerciseId), true)
    }

    override suspend fun clearAllSingleExerciseDefaults() {
        // Get all keys and remove those starting with exercise prefix
        settings.keys.filter { it.startsWith(KEY_PREFIX_EXERCISE) }.forEach { key ->
            settings.remove(key)
        }
    }

    override suspend fun getJustLiftDefaults(): JustLiftDefaults {
        val jsonString = settings.getStringOrNull(KEY_JUST_LIFT_DEFAULTS) ?: return JustLiftDefaults()
        return try {
            migrateSavedEchoHardDefault(json.decodeFromString<JustLiftDefaults>(jsonString))
        } catch (_: Exception) {
            JustLiftDefaults()
        }
    }

    override suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        val normalizedDefaults = defaults.withNormalizedNonEchoEchoLevel()
        settings.putString(KEY_JUST_LIFT_DEFAULTS, json.encodeToString(normalizedDefaults))
        settings.putBoolean(KEY_ECHO_HARD_DEFAULT_MIGRATION_JUST_LIFT, true)
    }

    override suspend fun clearJustLiftDefaults() {
        settings.remove(KEY_JUST_LIFT_DEFAULTS)
    }

    private fun migrateSavedEchoHardDefault(defaults: JustLiftDefaults): JustLiftDefaults {
        if (settings.getBoolean(KEY_ECHO_HARD_DEFAULT_MIGRATION_JUST_LIFT, false)) return defaults

        settings.putBoolean(KEY_ECHO_HARD_DEFAULT_MIGRATION_JUST_LIFT, true)
        if (defaults.echoLevelValue != EchoLevel.HARD.levelValue) return defaults

        val migrated = defaults.copy(echoLevelValue = EchoLevel.HARDER.levelValue)
        settings.putString(KEY_JUST_LIFT_DEFAULTS, json.encodeToString(migrated))
        return migrated
    }

    private fun migrateSavedEchoHardDefault(
        exerciseId: String,
        key: String,
        defaults: SingleExerciseDefaults,
    ): SingleExerciseDefaults {
        val migrationKey = getEchoHardDefaultMigrationKey(exerciseId)
        if (settings.getBoolean(migrationKey, false)) return defaults

        settings.putBoolean(migrationKey, true)
        if (defaults.echoLevelValue != EchoLevel.HARD.levelValue) return defaults

        val migrated = defaults.copy(echoLevelValue = EchoLevel.HARDER.levelValue)
        settings.putString(key, json.encodeToString(migrated))
        return migrated
    }

    private fun getEchoHardDefaultMigrationKey(exerciseId: String): String =
        "$KEY_ECHO_HARD_DEFAULT_MIGRATION_EXERCISE_PREFIX$exerciseId"

    private fun SingleExerciseDefaults.withNormalizedNonEchoEchoLevel(): SingleExerciseDefaults =
        if (workoutModeId != ProgramMode.Echo.modeValue && echoLevelValue == EchoLevel.HARD.levelValue) {
            copy(echoLevelValue = EchoLevel.HARDER.levelValue)
        } else {
            this
        }

    private fun JustLiftDefaults.withNormalizedNonEchoEchoLevel(): JustLiftDefaults =
        if (workoutModeId != ProgramMode.Echo.modeValue && echoLevelValue == EchoLevel.HARD.levelValue) {
            copy(echoLevelValue = EchoLevel.HARDER.levelValue)
        } else {
            this
        }

    override suspend fun setGamificationEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_GAMIFICATION_ENABLED, enabled)
        updateAndEmit { copy(gamificationEnabled = enabled) }
    }

    override suspend fun setWeightIncrement(increment: Float) {
        settings.putFloat(KEY_WEIGHT_INCREMENT, increment)
        updateAndEmit { copy(weightIncrement = increment) }
    }

    override suspend fun setAutoStartRoutine(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_START_ROUTINE, enabled)
        updateAndEmit { copy(autoStartRoutine = enabled) }
    }

    override suspend fun setBodyWeightKg(weightKg: Float) {
        settings.putFloat(KEY_BODY_WEIGHT_KG, weightKg)
        updateAndEmit { copy(bodyWeightKg = weightKg) }
    }

    override suspend fun setCountdownBeepsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_COUNTDOWN_BEEPS_ENABLED, enabled)
        updateAndEmit { copy(countdownBeepsEnabled = enabled) }
    }

    override suspend fun setRepSoundEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_REP_SOUND_ENABLED, enabled)
        updateAndEmit { copy(repSoundEnabled = enabled) }
    }

    override suspend fun setMotionStartEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MOTION_START, enabled)
        updateAndEmit { copy(motionStartEnabled = enabled) }
    }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
        updateAndEmit { copy(autoBackupEnabled = enabled) }
    }

    override suspend fun setBackupDestination(destination: BackupDestination) {
        settings.putString(KEY_BACKUP_DESTINATION, destination.toJson())
        updateAndEmit { copy(backupDestination = destination) }
    }

    override suspend fun setLanguage(language: String) {
        settings.putString(KEY_LANGUAGE, language)
        updateAndEmit { copy(language = language) }
    }

    override suspend fun setVoiceStopEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_VOICE_STOP_ENABLED, enabled)
        updateAndEmit { copy(voiceStopEnabled = enabled) }
    }

    override suspend fun setSafeWord(word: String?) {
        if (word != null) {
            settings.putString(KEY_SAFE_WORD, word)
        } else {
            settings.remove(KEY_SAFE_WORD)
        }
        updateAndEmit { copy(safeWord = word) }
    }

    override suspend fun setSafeWordCalibrated(calibrated: Boolean) {
        settings.putBoolean(KEY_SAFE_WORD_CALIBRATED, calibrated)
        updateAndEmit { copy(safeWordCalibrated = calibrated) }
    }

    override suspend fun setVelocityLossThreshold(percent: Int) {
        val clamped = percent.coerceIn(10, 50)
        settings.putInt(KEY_VELOCITY_LOSS_THRESHOLD, clamped)
        updateAndEmit { copy(velocityLossThresholdPercent = clamped) }
    }

    override suspend fun setAutoEndOnVelocityLoss(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_END_VELOCITY_LOSS, enabled)
        updateAndEmit { copy(autoEndOnVelocityLoss = enabled) }
    }

    override suspend fun setWeightSuggestionsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_WEIGHT_SUGGESTIONS_ENABLED, enabled)
        updateAndEmit { copy(weightSuggestionsEnabled = enabled) }
    }

    override suspend fun setDefaultScalingBasis(basis: ScalingBasis) {
        settings.putString(KEY_DEFAULT_SCALING_BASIS, basis.name)
        updateAndEmit { copy(defaultScalingBasis = basis) }
    }

    override suspend fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean) {
        settings.putBoolean(KEY_DEFAULT_ROUTINE_EXERCISE_USE_PERCENT_OF_PR, enabled)
        updateAndEmit { copy(defaultRoutineExerciseUsePercentOfPR = enabled) }
    }

    override suspend fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int) {
        val clamped = percent.coerceIn(50, 120)
        settings.putInt(KEY_DEFAULT_ROUTINE_EXERCISE_WEIGHT_PERCENT_OF_PR, clamped)
        updateAndEmit { copy(defaultRoutineExerciseWeightPercentOfPR = clamped) }
    }

    override suspend fun setVelocityOneRepMaxBackfillDone(done: Boolean) {
        settings.putBoolean(KEY_VELOCITY_1RM_BACKFILL_DONE, done)
        updateAndEmit { copy(velocityOneRepMaxBackfillDone = done) }
    }

    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    override suspend fun setVerbalEncouragementEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_VERBAL_ENCOURAGEMENT_ENABLED, enabled)
        // Cascade invariant: master-off forces both vulgar-off AND dominatrix-off.
        if (!enabled) {
            settings.putBoolean(KEY_VULGAR_MODE_ENABLED, false)
            settings.putBoolean(KEY_DOMINATRIX_MODE_ACTIVE, false)
            updateAndEmit {
                copy(
                    verbalEncouragementEnabled = false,
                    vulgarModeEnabled = false,
                    dominatrixModeActive = false,
                )
            }
        } else {
            updateAndEmit { copy(verbalEncouragementEnabled = true) }
        }
    }

    override suspend fun setVulgarModeEnabled(enabled: Boolean) {
        // Cascade invariant: prompted gates first vulgar-on activation.
        // UI must invoke the 18+ modal flow BEFORE calling this setter with enabled=true.
        // Checks prompted (not confirmed) so that users who declined can still enable later.
        if (enabled && !isAdultsOnlyPrompted()) {
            Logger.w { "VBT: setVulgarModeEnabled(true) blocked — adultsOnlyPrompted=false; UI must show 18+ modal first" }
            return
        }
        settings.putBoolean(KEY_VULGAR_MODE_ENABLED, enabled)
        // Cascade invariant: vulgar-off forces dominatrix-off.
        if (!enabled) {
            settings.putBoolean(KEY_DOMINATRIX_MODE_ACTIVE, false)
            updateAndEmit {
                copy(
                    vulgarModeEnabled = false,
                    dominatrixModeActive = false,
                )
            }
        } else {
            updateAndEmit { copy(vulgarModeEnabled = true) }
        }
    }

    override suspend fun setVulgarTier(tier: VulgarTier) {
        settings.putString(KEY_VULGAR_TIER, tier.name)
        updateAndEmit { copy(vulgarTier = tier) }
    }

    override suspend fun setDominatrixModeUnlocked(unlocked: Boolean) {
        settings.putBoolean(KEY_DOMINATRIX_MODE_UNLOCKED, unlocked)
        updateAndEmit { copy(dominatrixModeUnlocked = unlocked) }
    }

    override suspend fun setDominatrixModeActive(active: Boolean) {
        // Cascade invariant: dominatrix-on requires vulgar-on AND adultsConfirmed AND unlocked.
        if (active) {
            val current = _preferencesFlow.value
            if (!current.dominatrixModeUnlocked || !current.vulgarModeEnabled || !current.adultsOnlyConfirmed) {
                Logger.w {
                    "VBT: setDominatrixModeActive(true) blocked — unlock=${current.dominatrixModeUnlocked}" +
                        " vulgar=${current.vulgarModeEnabled} adultsConfirmed=${current.adultsOnlyConfirmed}"
                }
                return
            }
        }
        settings.putBoolean(KEY_DOMINATRIX_MODE_ACTIVE, active)
        updateAndEmit { copy(dominatrixModeActive = active) }
    }

    override suspend fun setAdultsOnlyConfirmed(confirmed: Boolean) {
        settings.putBoolean(KEY_ADULTS_ONLY_CONFIRMED, confirmed)
        // Confirmed implies prompted (the one-shot decline-remember flag is irrelevant after confirm).
        settings.putBoolean(KEY_ADULTS_ONLY_PROMPTED, true)
        updateAndEmit { copy(adultsOnlyConfirmed = confirmed) }
    }

    override suspend fun setBleCompatibilityMode(setting: BleCompatibilitySetting) {
        settings.putString(KEY_BLE_COMPATIBILITY_MODE, setting.name)
        BleCompatibilityMode.setting = setting
        updateAndEmit { copy(bleCompatibilityMode = setting) }
    }

    override fun isAdultsOnlyPrompted(): Boolean = settings.getBoolean(KEY_ADULTS_ONLY_PROMPTED, false)

    /**
     * Issue #611 (PR-followup #613): Persist the one-shot decline-remember flag
     * without touching the `adultsOnlyConfirmed` boolean. Used by the 18+ modal
     * decline path so the modal never re-appears for this install. Symmetric with
     * [setAdultsOnlyConfirmed] which writes `KEY_ADULTS_ONLY_PROMPTED = true` on
     * the confirm path; this setter covers the decline path.
     */
    override fun setAdultsOnlyPrompted(prompted: Boolean) {
        settings.putBoolean(KEY_ADULTS_ONLY_PROMPTED, prompted)
        updateAndEmit { copy(adultsOnlyPrompted = prompted) }
    }
}
