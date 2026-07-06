package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
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
    private val scope: CoroutineScope,
) {
    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(scope, SharingStarted.Eagerly, WeightUnit.KG)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val gamificationEnabled: StateFlow<Boolean> = userPreferences
        .map { it.gamificationEnabled }
        .stateIn(scope, SharingStarted.Eagerly, true)

    // Issue #517: Default scaling basis for % of 1RM routine weight resolution
    val defaultScalingBasis: StateFlow<ScalingBasis> = userPreferences
        .map { it.defaultScalingBasis }
        .stateIn(scope, SharingStarted.Eagerly, ScalingBasis.MAX_WEIGHT_PR)

    // Issue #595: Routine-builder defaults for newly added cable exercises
    val defaultRoutineExerciseUsePercentOfPR: StateFlow<Boolean> = userPreferences
        .map { it.defaultRoutineExerciseUsePercentOfPR }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val defaultRoutineExerciseWeightPercentOfPR: StateFlow<Int> = userPreferences
        .map { it.defaultRoutineExerciseWeightPercentOfPR }
        .stateIn(scope, SharingStarted.Eagerly, 80)

    // Issue #517: Run-once flag — true after the velocity 1RM backfill has completed
    val velocityOneRepMaxBackfillDone: StateFlow<Boolean> = userPreferences
        .map { it.velocityOneRepMaxBackfillDone }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Issue #167: Autoplay is now derived from summaryCountdownSeconds
    // - summaryCountdownSeconds == 0 (Unlimited) = autoplay OFF (manual control)
    // - summaryCountdownSeconds != 0 (-1 or 5-30) = autoplay ON (auto-advance)
    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.summaryCountdownSeconds != 0 }
        .stateIn(scope, SharingStarted.Eagerly, true)

    // Issue #333: BLE small-MTU compatibility path (Auto = on for Pixel 6/7 family)
    val bleCompatibilityMode: StateFlow<BleCompatibilitySetting> = userPreferences
        .map { it.bleCompatibilityMode }
        .stateIn(scope, SharingStarted.Eagerly, BleCompatibilitySetting.AUTO)

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

    fun setRepCountTiming(timing: RepCountTiming) {
        scope.launch { preferencesManager.setRepCountTiming(timing) }
    }

    fun setSummaryCountdownSeconds(seconds: Int) {
        Logger.d("setSummaryCountdownSeconds: Setting value to $seconds")
        scope.launch { preferencesManager.setSummaryCountdownSeconds(seconds) }
    }

    fun setAutoStartCountdownSeconds(seconds: Int) {
        scope.launch { preferencesManager.setAutoStartCountdownSeconds(seconds) }
    }

    fun setWeightIncrement(increment: Float) {
        scope.launch { preferencesManager.setWeightIncrement(increment) }
    }

    fun setAutoStartRoutine(enabled: Boolean) {
        scope.launch { preferencesManager.setAutoStartRoutine(enabled) }
    }

    fun setBodyWeightKg(weightKg: Float) {
        scope.launch { preferencesManager.setBodyWeightKg(weightKg) }
    }

    fun setGamificationEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setGamificationEnabled(enabled) }
    }

    // Issue #333: BLE small-MTU compatibility path (Auto/On/Off)
    fun setBleCompatibilityMode(setting: BleCompatibilitySetting) {
        scope.launch { preferencesManager.setBleCompatibilityMode(setting) }
    }

    fun setCountdownBeepsEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setCountdownBeepsEnabled(enabled) }
    }

    fun setRepSoundEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setRepSoundEnabled(enabled) }
    }

    fun setMotionStartEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setMotionStartEnabled(enabled) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setAutoBackupEnabled(enabled) }
    }

    fun setBackupDestination(destination: com.devil.phoenixproject.util.BackupDestination) {
        scope.launch { preferencesManager.setBackupDestination(destination) }
    }

    fun setLanguage(language: String) {
        scope.launch { preferencesManager.setLanguage(language) }
        // Apply locale to the platform so the UI updates immediately (Android)
        // or on next launch (iOS). The preference is persisted above for cold starts.
        com.devil.phoenixproject.util.applyAppLocale(language)
    }

    // Issue #141: Voice-activated emergency stop
    fun setVoiceStopEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setVoiceStopEnabled(enabled) }
    }

    fun setSafeWord(word: String?) {
        scope.launch { preferencesManager.setSafeWord(word) }
    }

    fun setSafeWordCalibrated(calibrated: Boolean) {
        scope.launch { preferencesManager.setSafeWordCalibrated(calibrated) }
    }

    fun setVelocityLossThreshold(percent: Int) {
        scope.launch { preferencesManager.setVelocityLossThreshold(percent) }
    }

    fun setAutoEndOnVelocityLoss(enabled: Boolean) {
        scope.launch { preferencesManager.setAutoEndOnVelocityLoss(enabled) }
    }

    fun setWeightSuggestionsEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setWeightSuggestionsEnabled(enabled) }
    }

    fun setDefaultScalingBasis(basis: ScalingBasis) {
        scope.launch { preferencesManager.setDefaultScalingBasis(basis) }
    }

    fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean) {
        scope.launch { preferencesManager.setDefaultRoutineExerciseUsePercentOfPR(enabled) }
    }

    fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int) {
        scope.launch { preferencesManager.setDefaultRoutineExerciseWeightPercentOfPR(percent.coerceIn(50, 120)) }
    }

    fun setVelocityOneRepMaxBackfillDone(done: Boolean) {
        scope.launch { preferencesManager.setVelocityOneRepMaxBackfillDone(done) }
    }

    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    fun setVerbalEncouragementEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setVerbalEncouragementEnabled(enabled) }
    }

    fun setVulgarModeEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setVulgarModeEnabled(enabled) }
    }

    fun setVulgarTier(tier: VulgarTier) {
        scope.launch { preferencesManager.setVulgarTier(tier) }
    }

    fun setDominatrixModeUnlocked(unlocked: Boolean) {
        scope.launch { preferencesManager.setDominatrixModeUnlocked(unlocked) }
    }

    fun setDominatrixModeActive(active: Boolean) {
        scope.launch { preferencesManager.setDominatrixModeActive(active) }
    }

    fun setAdultsOnlyConfirmed(confirmed: Boolean) {
        scope.launch { preferencesManager.setAdultsOnlyConfirmed(confirmed) }
    }

    // Issue #611 (PR-followup #613): One-shot decline-remember gate accessors for
    // the 18+ Adults Only modal. Non-suspend so the modal-call site can read the
    // gate on the main thread without dispatching into the preference scope.
    fun isAdultsOnlyPrompted(): Boolean = preferencesManager.isAdultsOnlyPrompted()

    fun setAdultsOnlyPrompted(prompted: Boolean) {
        // Synchronous write is acceptable — KEY_ADULTS_ONLY_PROMPTED is a single
        // boolean backed by NSUserDefaults/SharedPreferences putBoolean which both
        // platforms guarantee immediate visibility to subsequent reads.
        preferencesManager.setAdultsOnlyPrompted(prompted)
    }

    fun setColorScheme(schemeIndex: Int) {
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
            preferencesManager.setColorScheme(schemeIndex)
            // Update disco mode's restore color index (Issue #144: via interface method)
            bleRepository.setLastColorSchemeIndex(schemeIndex)
        }
    }

    // Weight conversion functions — keep original signatures with explicit unit parameter
    // to preserve backward compatibility with all call sites
    fun kgToDisplay(kg: Float, unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> kg
        WeightUnit.LB -> kg * 2.20462f
    }

    fun displayToKg(display: Float, unit: WeightUnit): Float = when (unit) {
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
