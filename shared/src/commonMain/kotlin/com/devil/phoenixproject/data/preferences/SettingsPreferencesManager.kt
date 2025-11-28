package com.devil.phoenixproject.data.preferences

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WeightUnit
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsPreferencesManager(
    private val settings: Settings
) : PreferencesManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _preferencesFlow = MutableStateFlow(loadPreferences())
    override val preferencesFlow: StateFlow<UserPreferences> = _preferencesFlow.asStateFlow()

    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            weightUnit = if (settings.getBoolean("is_kg", true)) WeightUnit.KG else WeightUnit.LB,
            autoplayEnabled = settings.getBoolean("autoplay_enabled", true),
            stopAtTop = settings.getBoolean("stop_at_top", false),
            enableVideoPlayback = settings.getBoolean("enable_video_playback", true),
            colorScheme = settings.getInt("color_scheme", 0)
        )
    }

    private fun updateState() {
        _preferencesFlow.value = loadPreferences()
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        settings["is_kg"] = (unit == WeightUnit.KG)
        updateState()
    }

    override suspend fun setAutoplayEnabled(enabled: Boolean) {
        settings["autoplay_enabled"] = enabled
        updateState()
    }

    override suspend fun setStopAtTop(enabled: Boolean) {
        settings["stop_at_top"] = enabled
        updateState()
    }

    override suspend fun setEnableVideoPlayback(enabled: Boolean) {
        settings["enable_video_playback"] = enabled
        updateState()
    }

    override suspend fun setColorScheme(scheme: Int) {
        settings["color_scheme"] = scheme
        updateState()
    }

    override suspend fun getSingleExerciseDefaults(exerciseId: String, cableConfig: String): SingleExerciseDefaults? {
        val key = "exercise_defaults_${exerciseId}_$cableConfig"
        val jsonString = settings.getStringOrNull(key)
        return if (jsonString != null) {
            try {
                json.decodeFromString<SingleExerciseDefaults>(jsonString)
            } catch (e: Exception) {
                Logger.w { "Failed to load exercise defaults for $exerciseId: ${e.message}" }
                null
            }
        } else {
            null
        }
    }

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        val key = "exercise_defaults_${defaults.exerciseId}_${defaults.cableConfig}"
        try {
            val jsonString = json.encodeToString(defaults)
            settings[key] = jsonString
            Logger.d { "Saved exercise defaults for ${defaults.exerciseId}" }
        } catch (e: Exception) {
            Logger.e { "Failed to save exercise defaults: ${e.message}" }
        }
    }

    override suspend fun getJustLiftDefaults(): JustLiftDefaults {
        val jsonString = settings.getStringOrNull("just_lift_defaults")
        return if (jsonString != null) {
            try {
                json.decodeFromString<JustLiftDefaults>(jsonString)
            } catch (e: Exception) {
                Logger.w { "Failed to load Just Lift defaults: ${e.message}" }
                JustLiftDefaults()
            }
        } else {
            JustLiftDefaults()
        }
    }

    override suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        try {
            val jsonString = json.encodeToString(defaults)
            settings["just_lift_defaults"] = jsonString
            Logger.d { "Saved Just Lift defaults" }
        } catch (e: Exception) {
            Logger.e { "Failed to save Just Lift defaults: ${e.message}" }
        }
    }
}
