package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages user settings and preference-derived state flows.
 * Extracted from MainViewModel during monolith decomposition.
 */
class SettingsManager(
    private val preferencesManager: PreferencesManager,
    private val bleRepository: BleRepository,
    private val scope: CoroutineScope
) {
    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(scope, SharingStarted.Eagerly, WeightUnit.KG)

    val stopAtTop: StateFlow<Boolean> = userPreferences
        .map { it.stopAtTop }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(scope, SharingStarted.Eagerly, true)

    // Issue #167: Autoplay is now derived from summaryCountdownSeconds
    // - summaryCountdownSeconds == 0 (Unlimited) = autoplay OFF (manual control)
    // - summaryCountdownSeconds != 0 (-1 or 5-30) = autoplay ON (auto-advance)
    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.summaryCountdownSeconds != 0 }
        .stateIn(scope, SharingStarted.Eagerly, true)

    fun setWeightUnit(unit: WeightUnit) {
        scope.launch { preferencesManager.setWeightUnit(unit) }
    }

    fun setStopAtTop(enabled: Boolean) {
        scope.launch { preferencesManager.setStopAtTop(enabled) }
    }

    fun setEnableVideoPlayback(enabled: Boolean) {
        scope.launch { preferencesManager.setEnableVideoPlayback(enabled) }
    }

    fun setStallDetectionEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setStallDetectionEnabled(enabled) }
    }

    fun setAudioRepCountEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setAudioRepCountEnabled(enabled) }
    }

    fun setSummaryCountdownSeconds(seconds: Int) {
        Logger.d("setSummaryCountdownSeconds: Setting value to $seconds")
        scope.launch { preferencesManager.setSummaryCountdownSeconds(seconds) }
    }

    fun setAutoStartCountdownSeconds(seconds: Int) {
        scope.launch { preferencesManager.setAutoStartCountdownSeconds(seconds) }
    }

    fun setColorScheme(schemeIndex: Int) {
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
            preferencesManager.setColorScheme(schemeIndex)
            // Update disco mode's restore color index
            (bleRepository as? KableBleRepository)?.setLastColorSchemeIndex(schemeIndex)
        }
    }

    // Weight conversion functions — keep original signatures with explicit unit parameter
    // to preserve backward compatibility with all call sites
    fun kgToDisplay(kg: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> kg
            WeightUnit.LB -> kg * 2.20462f
        }

    fun displayToKg(display: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> display
            WeightUnit.LB -> display / 2.20462f
        }

    fun formatWeight(kg: Float, unit: WeightUnit): String {
        val value = kgToDisplay(kg, unit)
        // Format with up to 2 decimals, trimming trailing zeros
        val formatted = if (value % 1 == 0f) {
            value.toInt().toString()
        } else {
            value.format(2).trimEnd('0').trimEnd('.')
        }
        return "$formatted ${unit.name.lowercase()}"
    }
}
