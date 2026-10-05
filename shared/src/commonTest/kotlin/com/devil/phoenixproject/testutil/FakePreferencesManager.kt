package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
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

    // Issue #611 (PR-followup #613): backing field for the 18+ modal one-shot flag.
    private var _adultsOnlyPrompted: Boolean = false
    private var exerciseCatalogSource: String = ""

    fun reset() {
        _preferencesFlow.value = UserPreferences()
        _adultsOnlyPrompted = false
        exerciseCatalogSource = ""
    }

    fun setPreferences(preferences: UserPreferences) {
        _preferencesFlow.value = preferences
    }

    override suspend fun setEnableVideoPlayback(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(enableVideoPlayback = enabled)
    }

    override fun getExerciseCatalogSource(): String = exerciseCatalogSource

    override suspend fun setExerciseCatalogSource(source: String) {
        exerciseCatalogSource = source
    }

    override suspend fun setLastConnectedModel(model: PhoenixModel) {
        _preferencesFlow.value = _preferencesFlow.value.copy(lastConnectedModel = model)
    }

    suspend fun setAutoStartCountdownSeconds(seconds: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartCountdownSeconds = seconds)
    }

    suspend fun setAutoStartRoutine(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoStartRoutine = enabled)
    }

    suspend fun setBodyWeightKg(weightKg: Float) {
        _preferencesFlow.value = _preferencesFlow.value.copy(bodyWeightKg = weightKg)
    }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(autoBackupEnabled = enabled)
    }

    var oneShotResetCount = 0
        private set

    override suspend fun resetOneShotWorkAfterRestore() {
        oneShotResetCount++
        _preferencesFlow.value = _preferencesFlow.value.copy(velocityOneRepMaxBackfillDone = false)
    }

    override suspend fun setIncludeRawTelemetryInBackups(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(includeRawTelemetryInBackups = enabled)
    }

    override suspend fun setLanguage(language: String) {
        _preferencesFlow.value = _preferencesFlow.value.copy(language = language)
    }

    override suspend fun setBackupDestination(destination: BackupDestination) {
        _preferencesFlow.value = _preferencesFlow.value.copy(backupDestination = destination)
    }

    suspend fun setVelocityLossThreshold(percent: Int) {
        _preferencesFlow.value = _preferencesFlow.value.copy(
            velocityLossThresholdPercent = percent.coerceIn(10, 50),
        )
    }

    suspend fun setWeightSuggestionsEnabled(enabled: Boolean) {
        _preferencesFlow.value = _preferencesFlow.value.copy(weightSuggestionsEnabled = enabled)
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
