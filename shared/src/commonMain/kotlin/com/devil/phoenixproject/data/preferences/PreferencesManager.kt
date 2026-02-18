package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val perSetRestTime: Boolean
) {
    fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
        // Handle legacy 125% -> fall back to 120%
        val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
        return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
            ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
    }

    fun getEchoLevel(): com.devil.phoenixproject.domain.model.EchoLevel {
        return com.devil.phoenixproject.domain.model.EchoLevel.entries.find { it.levelValue == echoLevelValue }
            ?: com.devil.phoenixproject.domain.model.EchoLevel.HARDER
    }

    fun toProgramMode(): com.devil.phoenixproject.domain.model.ProgramMode {
        return when (workoutModeId) {
            0 -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
            2 -> com.devil.phoenixproject.domain.model.ProgramMode.Pump
            3 -> com.devil.phoenixproject.domain.model.ProgramMode.TUT
            4 -> com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast
            6 -> com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly
            10 -> com.devil.phoenixproject.domain.model.ProgramMode.Echo
            else -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
        }
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
    val echoLevelValue: Int = 2,
    val stallDetectionEnabled: Boolean = true,  // Stall detection auto-stop toggle
    val repCountTimingName: String = "TOP"  // RepCountTiming enum name
) {
    fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
        // Handle legacy 125% -> fall back to 120%
        val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
        return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
            ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
    }

    fun getEchoLevel(): com.devil.phoenixproject.domain.model.EchoLevel {
        return com.devil.phoenixproject.domain.model.EchoLevel.entries.find { it.levelValue == echoLevelValue }
            ?: com.devil.phoenixproject.domain.model.EchoLevel.HARDER
    }

    fun toProgramMode(): com.devil.phoenixproject.domain.model.ProgramMode {
        return when (workoutModeId) {
            0 -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
            2 -> com.devil.phoenixproject.domain.model.ProgramMode.Pump
            3 -> com.devil.phoenixproject.domain.model.ProgramMode.TUT
            4 -> com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast
            6 -> com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly
            10 -> com.devil.phoenixproject.domain.model.ProgramMode.Echo
            else -> com.devil.phoenixproject.domain.model.ProgramMode.OldSchool
        }
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
    suspend fun setSimulatorModeUnlocked(unlocked: Boolean)
    fun isSimulatorModeUnlocked(): Boolean
    suspend fun setSimulatorModeEnabled(enabled: Boolean)
    fun isSimulatorModeEnabled(): Boolean

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
class SettingsPreferencesManager(
    private val settings: Settings
) : PreferencesManager {

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
        private const val KEY_SUMMARY_COUNTDOWN_SECONDS = "summary_countdown_seconds"
        private const val KEY_AUTOSTART_COUNTDOWN_SECONDS = "autostart_countdown_seconds"
        private const val KEY_REP_COUNT_TIMING = "rep_count_timing"
        private const val KEY_JUST_LIFT_DEFAULTS = "just_lift_defaults"
        private const val KEY_PREFIX_EXERCISE = "exercise_defaults_"
        private const val KEY_SIMULATOR_MODE_UNLOCKED = "simulator_mode_unlocked"
        private const val KEY_SIMULATOR_MODE_ENABLED = "simulator_mode_enabled"
        private const val KEY_GAMIFICATION_ENABLED = "gamification_enabled"
    }

    private val _preferencesFlow = MutableStateFlow(loadPreferences())
    override val preferencesFlow: StateFlow<UserPreferences> = _preferencesFlow

    private fun loadPreferences(): UserPreferences {
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
                try { RepCountTiming.valueOf(it) } catch (_: Exception) { null }
            } ?: RepCountTiming.TOP,
            summaryCountdownSeconds = settings.getInt(KEY_SUMMARY_COUNTDOWN_SECONDS, 10),
            autoStartCountdownSeconds = settings.getInt(KEY_AUTOSTART_COUNTDOWN_SECONDS, 5),
            gamificationEnabled = settings.getBoolean(KEY_GAMIFICATION_ENABLED, true),
            simulatorModeUnlocked = settings.getBoolean(KEY_SIMULATOR_MODE_UNLOCKED, false),
            simulatorModeEnabled = settings.getBoolean(KEY_SIMULATOR_MODE_ENABLED, false)
        )
    }

    private fun updateAndEmit(update: UserPreferences.() -> UserPreferences) {
        _preferencesFlow.value = _preferencesFlow.value.update()
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
            json.decodeFromString<SingleExerciseDefaults>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        val key = "$KEY_PREFIX_EXERCISE${defaults.exerciseId}"
        settings.putString(key, json.encodeToString(defaults))
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
            json.decodeFromString<JustLiftDefaults>(jsonString)
        } catch (e: Exception) {
            JustLiftDefaults()
        }
    }

    override suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        settings.putString(KEY_JUST_LIFT_DEFAULTS, json.encodeToString(defaults))
    }

    override suspend fun clearJustLiftDefaults() {
        settings.remove(KEY_JUST_LIFT_DEFAULTS)
    }

    override suspend fun setGamificationEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_GAMIFICATION_ENABLED, enabled)
        updateAndEmit { copy(gamificationEnabled = enabled) }
    }

    override suspend fun setSimulatorModeUnlocked(unlocked: Boolean) {
        settings.putBoolean(KEY_SIMULATOR_MODE_UNLOCKED, unlocked)
        updateAndEmit { copy(simulatorModeUnlocked = unlocked) }
    }

    override fun isSimulatorModeUnlocked(): Boolean {
        return settings.getBoolean(KEY_SIMULATOR_MODE_UNLOCKED, false)
    }

    override suspend fun setSimulatorModeEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SIMULATOR_MODE_ENABLED, enabled)
        updateAndEmit { copy(simulatorModeEnabled = enabled) }
    }

    override fun isSimulatorModeEnabled(): Boolean {
        return settings.getBoolean(KEY_SIMULATOR_MODE_ENABLED, false)
    }
}
