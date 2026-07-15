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

    suspend fun setWeightUnit(unit: WeightUnit) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightUnit = unit)
    }

    suspend fun setStopAtTop(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(stopAtTop = enabled)
    }

    override suspend fun setEnableVideoPlayback(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(enableVideoPlayback = enabled)
    }

    suspend fun setBeepsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(beepsEnabled = enabled)
    }

    suspend fun setColorScheme(scheme: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(colorScheme = scheme)
    }

    suspend fun setDiscoModeUnlocked(unlocked: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(discoModeUnlocked = unlocked)
    }

    suspend fun setAudioRepCountEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(audioRepCountEnabled = enabled)
    }

    @Deprecated("Legacy migration read only")
    override suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults? = exerciseDefaults[exerciseId]

    suspend fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) {
        exerciseDefaults[defaults.exerciseId] = defaults
    }

    suspend fun clearAllSingleExerciseDefaults() {
        exerciseDefaults.clear()
    }

    @Deprecated("Legacy migration read only")
    override suspend fun getJustLiftDefaults(): JustLiftDefaults = justLiftDefaults

    suspend fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        justLiftDefaults = defaults
    }

    suspend fun clearJustLiftDefaults() {
        justLiftDefaults = JustLiftDefaults()
    }

    suspend fun setSummaryCountdownSeconds(seconds: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(summaryCountdownSeconds = seconds)
    }

    suspend fun setAutoStartCountdownSeconds(seconds: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartCountdownSeconds = seconds)
    }

    suspend fun setRepCountTiming(timing: com.devil.phoenixproject.domain.model.RepCountTiming) {
        _preferencesFlow.value = _preferencesFlow.value.copy(repCountTiming = timing)
    }

    suspend fun setGamificationEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(gamificationEnabled = enabled)
    }

    suspend fun setWeightIncrement(increment: Float) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightIncrement = increment)
    }

    suspend fun setAutoStartRoutine(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartRoutine = enabled)
    }

    suspend fun setBodyWeightKg(weightKg: Float) {
        _preferencesFlow.value = _preferencesFlow.value.copy(bodyWeightKg = weightKg)
    }

    suspend fun setCountdownBeepsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(countdownBeepsEnabled = enabled)
    }

    suspend fun setRepSoundEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(repSoundEnabled = enabled)
    }

    suspend fun setMotionStartEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(motionStartEnabled = enabled)
    }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoBackupEnabled = enabled)
    }

    override suspend fun setLanguage(language: String) {
        _preferencesFlow.value = _preferencesFlow.value.copy(language = language)
    }

    suspend fun setVoiceStopEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(voiceStopEnabled = enabled)
    }

    suspend fun setSafeWord(word: String?) {
        _preferencesFlow.value = _preferencesFlow.value.copy(safeWord = word)
    }

    suspend fun setSafeWordCalibrated(calibrated: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(safeWordCalibrated = calibrated)
    }

    override suspend fun setBackupDestination(destination: BackupDestination) {
        _preferencesFlow.value = _preferencesFlow.value.copy(backupDestination = destination)
    }

    suspend fun setVelocityLossThreshold(percent: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(
            velocityLossThresholdPercent = percent.coerceIn(10, 50),
        )
    }

    suspend fun setAutoEndOnVelocityLoss(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoEndOnVelocityLoss = enabled)
    }

    suspend fun setWeightSuggestionsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightSuggestionsEnabled = enabled)
    }

    suspend fun setDefaultScalingBasis(basis: ScalingBasis) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultScalingBasis = basis)
    }

    suspend fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultRoutineExerciseUsePercentOfPR = enabled)
    }

    suspend fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(defaultRoutineExerciseWeightPercentOfPR = percent.coerceIn(50, 120))
    }

    override suspend fun setVelocityOneRepMaxBackfillDone(done: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(velocityOneRepMaxBackfillDone = done)
    }

    // Issue #611: Verbal encouragement + opt-in vulgar mode + Dominatrix mode + 18+ gate
    // Cascade invariants mirror SettingsPreferencesManager.
    suspend fun setVerbalEncouragementEnabled(enabled: Boolean) {
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

    suspend fun setVulgarModeEnabled(enabled: Boolean) {
        val current = _preferencesFlow.value
        if (enabled && !current.adultsOnlyConfirmed) return
        _preferencesFlow.value = if (!enabled) {
            current.copy(vulgarModeEnabled = false, dominatrixModeActive = false)
        } else {
            current.copy(vulgarModeEnabled = true)
        }
    }

    suspend fun setVulgarTier(tier: VulgarTier) {
        _preferencesFlow.value = _preferencesFlow.value.copy(vulgarTier = tier)
    }

    suspend fun setDominatrixModeUnlocked(unlocked: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(dominatrixModeUnlocked = unlocked)
    }

    suspend fun setDominatrixModeActive(active: Boolean) {
        val current = _preferencesFlow.value
        if (active && (!current.dominatrixModeUnlocked || !current.vulgarModeEnabled || !current.adultsOnlyConfirmed)) {
            return
        }
        _preferencesFlow.value = current.copy(dominatrixModeActive = active)
    }

    suspend fun setAdultsOnlyConfirmed(confirmed: Boolean) {
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
    fun isAdultsOnlyPrompted(): Boolean = _adultsOnlyPrompted

    fun setAdultsOnlyPrompted(prompted: Boolean) {
        _adultsOnlyPrompted = prompted
    }
}
