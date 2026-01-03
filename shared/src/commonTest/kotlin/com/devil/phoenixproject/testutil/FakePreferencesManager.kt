package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.preferences.JustLiftDefaults
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SingleExerciseDefaults
import com.devil.phoenixproject.data.preferences.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake preferences manager for testing.
 * Stores preferences in memory without any persistence.
 */
class FakePreferencesManager : PreferencesManager {

    private val _preferencesFlow = MutableStateFlow(UserPreferences())
    override val preferencesFlow: StateFlow<UserPreferences> = _preferencesFlow.asStateFlow()

    private val exerciseDefaults = mutableMapOf<String, SingleExerciseDefaults>()
    private var justLiftDefaults = JustLiftDefaults()

    fun reset() {
        _preferencesFlow.value = UserPreferences()
        exerciseDefaults.clear()
        justLiftDefaults = JustLiftDefaults()
    }

    fun setPreferences(preferences: UserPreferences) {
        _preferencesFlow.value = preferences
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightUnit = unit)
    }

    override suspend fun setAutoplayEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoplayEnabled = enabled)
    }

    override suspend fun setStopAtTop(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(stopAtTop = enabled)
    }

    override suspend fun setEnableVideoPlayback(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(enableVideoPlayback = enabled)
    }

    override suspend fun setBeepsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(beepsEnabled = enabled)
    }

    override suspend fun setColorScheme(scheme: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(colorScheme = scheme)
    }

    override suspend fun setStallDetectionEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(stallDetectionEnabled = enabled)
    }

    override suspend fun setDiscoModeUnlocked(unlocked: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(discoModeUnlocked = unlocked)
    }

    override suspend fun setAudioRepCountEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(audioRepCountEnabled = enabled)
    }

    override suspend fun getSingleExerciseDefaults(exerciseId: String, cableConfig: String): SingleExerciseDefaults? {
        return exerciseDefaults["${exerciseId}_$cableConfig"]
    }

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        exerciseDefaults["${defaults.exerciseId}_${defaults.cableConfig}"] = defaults
    }

    override suspend fun clearAllSingleExerciseDefaults() {
        exerciseDefaults.clear()
    }

    override suspend fun getJustLiftDefaults(): JustLiftDefaults {
        return justLiftDefaults
    }

    override suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        justLiftDefaults = defaults
    }

    override suspend fun clearJustLiftDefaults() {
        justLiftDefaults = JustLiftDefaults()
    }
}
