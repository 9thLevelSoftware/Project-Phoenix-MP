package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.WeightUnit
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User preferences data class
 */
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.LB,
    val autoplayEnabled: Boolean = true,
    val stopAtTop: Boolean = false,
    val enableVideoPlayback: Boolean = true,
    val beepsEnabled: Boolean = true,
    val colorScheme: Int = 0,
    val stallDetectionEnabled: Boolean = true,  // NEW - default enabled
    val discoModeUnlocked: Boolean = false,  // Easter egg - unlocked by tapping LED header 7 times
    val audioRepCountEnabled: Boolean = false  // Audio rep count announcements during workout
)

/**
 * Single exercise defaults for saving/loading exercise configurations
 */
@Serializable
data class SingleExerciseDefaults(
    val exerciseId: String,
    val cableConfig: String,
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
    fun getCableConfiguration(): com.devil.phoenixproject.domain.model.CableConfiguration {
        return com.devil.phoenixproject.domain.model.CableConfiguration.entries.find { it.name == cableConfig }
            ?: com.devil.phoenixproject.domain.model.CableConfiguration.DOUBLE
    }

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

    fun toWorkoutType(): com.devil.phoenixproject.domain.model.WorkoutType {
        return when (workoutModeId) {
            0 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.OldSchool)
            2 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.Pump)
            3 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.TUT)
            4 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast)
            6 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly)
            10 -> com.devil.phoenixproject.domain.model.WorkoutType.Echo(getEchoLevel(), getEccentricLoad())
            else -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.OldSchool)
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
    val stallDetectionEnabled: Boolean = true  // Stall detection auto-stop toggle
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

    fun toWorkoutType(): com.devil.phoenixproject.domain.model.WorkoutType {
        return when (workoutModeId) {
            0 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.OldSchool)
            2 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.Pump)
            3 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.TUT)
            4 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.TUTBeast)
            6 -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.EccentricOnly)
            10 -> com.devil.phoenixproject.domain.model.WorkoutType.Echo(getEchoLevel(), getEccentricLoad())
            else -> com.devil.phoenixproject.domain.model.WorkoutType.Program(com.devil.phoenixproject.domain.model.ProgramMode.OldSchool)
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
    suspend fun setAutoplayEnabled(enabled: Boolean)
    suspend fun setStopAtTop(enabled: Boolean)
    suspend fun setEnableVideoPlayback(enabled: Boolean)
    suspend fun setBeepsEnabled(enabled: Boolean)
    suspend fun setColorScheme(scheme: Int)
    suspend fun setStallDetectionEnabled(enabled: Boolean)
    suspend fun setDiscoModeUnlocked(unlocked: Boolean)
    suspend fun setAudioRepCountEnabled(enabled: Boolean)

    suspend fun getSingleExerciseDefaults(exerciseId: String, cableConfig: String): SingleExerciseDefaults?
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
        private const val KEY_AUTOPLAY_ENABLED = "autoplay_enabled"
        private const val KEY_STOP_AT_TOP = "stop_at_top"
        private const val KEY_VIDEO_PLAYBACK = "video_playback"
        private const val KEY_BEEPS_ENABLED = "beeps_enabled"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_STALL_DETECTION = "stall_detection_enabled"
        private const val KEY_DISCO_MODE_UNLOCKED = "disco_mode_unlocked"
        private const val KEY_AUDIO_REP_COUNT = "audio_rep_count_enabled"
        private const val KEY_JUST_LIFT_DEFAULTS = "just_lift_defaults"
        private const val KEY_PREFIX_EXERCISE = "exercise_defaults_"
    }

    private val _preferencesFlow = MutableStateFlow(loadPreferences())
    override val preferencesFlow: StateFlow<UserPreferences> = _preferencesFlow

    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            weightUnit = settings.getStringOrNull(KEY_WEIGHT_UNIT)?.let {
                WeightUnit.entries.find { unit -> unit.name == it }
            } ?: WeightUnit.LB,
            autoplayEnabled = settings.getBoolean(KEY_AUTOPLAY_ENABLED, true),
            stopAtTop = settings.getBoolean(KEY_STOP_AT_TOP, false),
            enableVideoPlayback = settings.getBoolean(KEY_VIDEO_PLAYBACK, true),
            beepsEnabled = settings.getBoolean(KEY_BEEPS_ENABLED, true),
            colorScheme = settings.getInt(KEY_COLOR_SCHEME, 0),
            stallDetectionEnabled = settings.getBoolean(KEY_STALL_DETECTION, true),
            discoModeUnlocked = settings.getBoolean(KEY_DISCO_MODE_UNLOCKED, false),
            audioRepCountEnabled = settings.getBoolean(KEY_AUDIO_REP_COUNT, false)
        )
    }

    private fun updateAndEmit(update: UserPreferences.() -> UserPreferences) {
        _preferencesFlow.value = _preferencesFlow.value.update()
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        settings.putString(KEY_WEIGHT_UNIT, unit.name)
        updateAndEmit { copy(weightUnit = unit) }
    }

    override suspend fun setAutoplayEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTOPLAY_ENABLED, enabled)
        updateAndEmit { copy(autoplayEnabled = enabled) }
    }

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

    override suspend fun getSingleExerciseDefaults(exerciseId: String, cableConfig: String): SingleExerciseDefaults? {
        val key = "$KEY_PREFIX_EXERCISE${exerciseId}_$cableConfig"
        val jsonString = settings.getStringOrNull(key) ?: return null
        return try {
            json.decodeFromString<SingleExerciseDefaults>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        val key = "$KEY_PREFIX_EXERCISE${defaults.exerciseId}_${defaults.cableConfig}"
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
}
