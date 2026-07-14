package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.preferences.JustLiftDefaults
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.SingleExerciseDefaults
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.BackupDestination
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
    // Issue #611 (PR-followup #613): backing field for the 18+ modal one-shot flag.
    private var _adultsOnlyPrompted: Boolean = false

    fun reset() {
        _preferencesFlow.value = UserPreferences()
        exerciseDefaults.clear()
        justLiftDefaults = JustLiftDefaults()
        _adultsOnlyPrompted = false
    }

    fun setPreferences(preferences: UserPreferences) {
        _preferencesFlow.value = preferences
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightUnit = unit)
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

    override suspend fun setDiscoModeUnlocked(unlocked: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(discoModeUnlocked = unlocked)
    }

    override suspend fun setAudioRepCountEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(audioRepCountEnabled = enabled)
    }

    override suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults? = exerciseDefaults[exerciseId]

    override suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        exerciseDefaults[defaults.exerciseId] = defaults
    }

    override suspend fun clearAllSingleExerciseDefaults() {
        exerciseDefaults.clear()
    }

    override suspend fun getJustLiftDefaults(): JustLiftDefaults = justLiftDefaults

    override suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        justLiftDefaults = defaults
    }

    override suspend fun clearJustLiftDefaults() {
        justLiftDefaults = JustLiftDefaults()
    }

    override suspend fun setSummaryCountdownSeconds(seconds: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(summaryCountdownSeconds = seconds)
    }

    override suspend fun setAutoStartCountdownSeconds(seconds: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartCountdownSeconds = seconds)
    }

    override suspend fun setRepCountTiming(timing: com.devil.phoenixproject.domain.model.RepCountTiming) {
        _preferencesFlow.value = _preferencesFlow.value.copy(repCountTiming = timing)
    }

    override suspend fun setGamificationEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(gamificationEnabled = enabled)
    }

    override suspend fun setWeightIncrement(increment: Float) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightIncrement = increment)
    }

    override suspend fun setAutoStartRoutine(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartRoutine = enabled)
    }

    override suspend fun setBodyWeightKg(weightKg: Float) {
        _preferencesFlow.value = _preferencesFlow.value.copy(bodyWeightKg = weightKg)
    }

    override suspend fun setCountdownBeepsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(countdownBeepsEnabled = enabled)
    }

    override suspend fun setRepSoundEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(repSoundEnabled = enabled)
    }

    override suspend fun setMotionStartEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(motionStartEnabled = enabled)
    }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoBackupEnabled = enabled)
    }

    override suspend fun setLanguage(language: String) {
        _preferencesFlow.value = _preferencesFlow.value.copy(language = language)
    }

    override suspend fun setVoiceStopEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(voiceStopEnabled = enabled)
    }

    override suspend fun setSafeWord(word: String?) {
        _preferencesFlow.value = _preferencesFlow.value.copy(safeWord = word)
    }

    override suspend fun setSafeWordCalibrated(calibrated: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(safeWordCalibrated = calibrated)
    }

    override suspend fun setBackupDestination(destination: BackupDestination) {
        _preferencesFlow.value = _preferencesFlow.value.copy(backupDestination = destination)
    }

    override suspend fun setVelocityLossThreshold(percent: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(
            velocityLossThresholdPercent = percent.coerceIn(10, 50),
        )
    }

    override suspend fun setAutoEndOnVelocityLoss(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoEndOnVelocityLoss = enabled)
    }

    override suspend fun setWeightSuggestionsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightSuggestionsEnabled = enabled)
    }

    override suspend fun setDefaultScalingBasis(basis: ScalingBasis) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultScalingBasis = basis)
    }

    override suspend fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultRoutineExerciseUsePercentOfPR = enabled)
    }

    override suspend fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultRoutineExerciseWeightPercentOfPR = percent.coerceIn(50, 120))
    }

    override suspend fun setVelocityOneRepMaxBackfillDone(done: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(velocityOneRepMaxBackfillDone = done)
    }

    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    // Cascade invariants mirror SettingsPreferencesManager.
    override suspend fun setVerbalEncouragementEnabled(enabled: Boolean) {
        _preferencesFlow.value = if (!enabled) {
            _preferencesFlow.value.copy(
                verbalEncouragementEnabled = false,
                vulgarModeEnabled = false,
                dominatrixModeActive = false,
            )
        } else {
            _preferencesFlow.value.copy(verbalEncouragementEnabled = true)
        }
    }

    override suspend fun setVulgarModeEnabled(enabled: Boolean) {
        val current = _preferencesFlow.value
        if (enabled && !current.adultsOnlyConfirmed) return
        _preferencesFlow.value = if (!enabled) {
            current.copy(vulgarModeEnabled = false, dominatrixModeActive = false)
        } else {
            current.copy(vulgarModeEnabled = true)
        }
    }

    override suspend fun setVulgarTier(tier: VulgarTier) {
        _preferencesFlow.value = _preferencesFlow.value.copy(vulgarTier = tier)
    }

    override suspend fun setDominatrixModeUnlocked(unlocked: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(dominatrixModeUnlocked = unlocked)
    }

    override suspend fun setDominatrixModeActive(active: Boolean) {
        val current = _preferencesFlow.value
        if (active && (!current.dominatrixModeUnlocked || !current.vulgarModeEnabled || !current.adultsOnlyConfirmed)) {
            return
        }
        _preferencesFlow.value = current.copy(dominatrixModeActive = active)
    }

    override suspend fun setAdultsOnlyConfirmed(confirmed: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(adultsOnlyConfirmed = confirmed)
        // Issue #611 (PR-followup #613): confirm implies prompted (one-shot flag
        // becomes irrelevant after confirm; mirror SettingsPreferencesManager).
        _adultsOnlyPrompted = true
    }

    override suspend fun setBleCompatibilityMode(setting: com.devil.phoenixproject.domain.model.BleCompatibilitySetting) {
        _preferencesFlow.value = _preferencesFlow.value.copy(bleCompatibilityMode = setting)
    }

    // Issue #611 (PR-followup #613): One-shot decline-remember backing field
    // for the 18+ Adults Only modal. Lives outside UserPreferences because the
    // modal-call site is the only consumer (architecture §3 — follow
    // DiscoModeUnlockDialog pattern).
    override fun isAdultsOnlyPrompted(): Boolean = _adultsOnlyPrompted

    override fun setAdultsOnlyPrompted(prompted: Boolean) {
        _adultsOnlyPrompted = prompted
    }
}
