package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileContextUnavailableException
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.format
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compatibility facade that combines global application preferences with the
 * active profile's training preferences.
 */
class SettingsManager(
    private val globalPreferences: PreferencesManager,
    private val userProfileRepository: UserProfileRepository,
    private val scope: CoroutineScope,
) {
    private val coreUpdates = Mutex()
    private val workoutUpdates = Mutex()
    private val ledUpdates = Mutex()
    private val vbtAndSafetyUpdates = Mutex()

    private fun overlayProfile(
        global: UserPreferences,
        ready: ActiveProfileContext.Ready,
    ): UserPreferences {
        val core = ready.preferences.core.value
        val workout = ready.preferences.workout.value
        val led = ready.preferences.led.value
        val vbt = ready.preferences.vbt.value
        val safety = ready.localSafety
        return global.copy(
            weightUnit = core.weightUnit,
            weightIncrement = core.weightIncrement,
            bodyWeightKg = core.bodyWeightKg,
            stopAtTop = workout.stopAtTop,
            beepsEnabled = workout.beepsEnabled,
            stallDetectionEnabled = workout.stallDetectionEnabled,
            audioRepCountEnabled = workout.audioRepCountEnabled,
            repCountTiming = workout.repCountTiming,
            summaryCountdownSeconds = workout.summaryCountdownSeconds,
            autoStartCountdownSeconds = workout.autoStartCountdownSeconds,
            gamificationEnabled = workout.gamificationEnabled,
            autoStartRoutine = workout.autoStartRoutine,
            countdownBeepsEnabled = workout.countdownBeepsEnabled,
            repSoundEnabled = workout.repSoundEnabled,
            motionStartEnabled = workout.motionStartEnabled,
            weightSuggestionsEnabled = workout.weightSuggestionsEnabled,
            defaultRoutineExerciseUsePercentOfPR = workout.defaultRoutineExerciseUsePercentOfPR,
            defaultRoutineExerciseWeightPercentOfPR = workout.defaultRoutineExerciseWeightPercentOfPR,
            voiceStopEnabled = workout.voiceStopEnabled,
            safeWord = safety.safeWord,
            safeWordCalibrated = safety.safeWordCalibrated,
            colorScheme = led.colorScheme,
            discoModeUnlocked = led.discoModeUnlocked,
            vbtEnabled = vbt.enabled,
            velocityLossThresholdPercent = vbt.velocityLossThresholdPercent,
            autoEndOnVelocityLoss = vbt.autoEndOnVelocityLoss,
            defaultScalingBasis = vbt.defaultScalingBasis,
            verbalEncouragementEnabled = vbt.verbalEncouragementEnabled,
            vulgarModeEnabled = vbt.vulgarModeEnabled,
            vulgarTier = vbt.vulgarTier,
            dominatrixModeUnlocked = vbt.dominatrixModeUnlocked,
            dominatrixModeActive = vbt.dominatrixModeActive,
            adultsOnlyConfirmed = safety.adultsOnlyConfirmed,
            adultsOnlyPrompted = safety.adultsOnlyPrompted,
        )
    }

    private fun initialUserPreferences(): UserPreferences {
        val global = globalPreferences.preferencesFlow.value
        val ready = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return global
        return overlayProfile(global, ready)
    }

    val userPreferences: StateFlow<UserPreferences> = combine(
        globalPreferences.preferencesFlow,
        userProfileRepository.activeProfileContext.filterIsInstance<ActiveProfileContext.Ready>(),
    ) { global, ready ->
        overlayProfile(global, ready)
    }.stateIn(scope, SharingStarted.Eagerly, initialUserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.weightUnit)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.enableVideoPlayback)

    val gamificationEnabled: StateFlow<Boolean> = userPreferences
        .map { it.gamificationEnabled }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.gamificationEnabled)

    val defaultScalingBasis: StateFlow<ScalingBasis> = userPreferences
        .map { it.defaultScalingBasis }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.defaultScalingBasis)

    val defaultRoutineExerciseUsePercentOfPR: StateFlow<Boolean> = userPreferences
        .map { it.defaultRoutineExerciseUsePercentOfPR }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            userPreferences.value.defaultRoutineExerciseUsePercentOfPR,
        )

    val defaultRoutineExerciseWeightPercentOfPR: StateFlow<Int> = userPreferences
        .map { it.defaultRoutineExerciseWeightPercentOfPR }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            userPreferences.value.defaultRoutineExerciseWeightPercentOfPR,
        )

    val velocityOneRepMaxBackfillDone: StateFlow<Boolean> = userPreferences
        .map { it.velocityOneRepMaxBackfillDone }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.velocityOneRepMaxBackfillDone)

    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.summaryCountdownSeconds != 0 }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.summaryCountdownSeconds != 0)

    val bleCompatibilityMode: StateFlow<BleCompatibilitySetting> = userPreferences
        .map { it.bleCompatibilityMode }
        .stateIn(scope, SharingStarted.Eagerly, userPreferences.value.bleCompatibilityMode)

    private fun ready(): ActiveProfileContext.Ready =
        userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()

    private fun readyFor(expectedId: String): ActiveProfileContext.Ready {
        val current = ready()
        if (current.profile.id != expectedId) {
            throw StaleProfileContextException(expectedId, current.profile.id)
        }
        return current
    }

    private fun <T> updateSection(
        mutex: Mutex,
        read: (ActiveProfileContext.Ready) -> T,
        write: suspend (String, T) -> Unit,
        transform: (ActiveProfileContext.Ready, T) -> T?,
    ) {
        val expectedId = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
            ?.profile?.id
            ?: run {
                Logger.w { "Profile preference update ignored while switching" }
                return
            }
        scope.launch {
            try {
                mutex.withLock {
                    val current = readyFor(expectedId)
                    val next = transform(current, read(current)) ?: return@withLock
                    write(expectedId, next)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextUnavailableException) {
                Logger.w(error) { "Profile preference update skipped while switching" }
            } catch (error: StaleProfileContextException) {
                Logger.w(error) { "Profile preference update skipped after profile switch" }
            }
        }
    }

    private fun updateCore(transform: (CoreProfilePreferences) -> CoreProfilePreferences) {
        val expectedId = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
            ?.profile?.id
            ?: run {
                Logger.w { "Profile preference update ignored while switching" }
                return
            }
        scope.launch {
            try {
                coreUpdates.withLock {
                    userProfileRepository.mutateCore(expectedId, transform)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextUnavailableException) {
                Logger.w(error) { "Profile preference update skipped while switching" }
            } catch (error: StaleProfileContextException) {
                Logger.w(error) { "Profile preference update skipped after profile switch" }
            }
        }
    }

    private fun updateWorkout(transform: (WorkoutPreferences) -> WorkoutPreferences) =
        updateSection(
            mutex = workoutUpdates,
            read = { it.preferences.workout.value },
            write = userProfileRepository::updateWorkout,
        ) { _, value -> transform(value) }

    private fun updateLed(transform: (LedPreferences) -> LedPreferences) =
        updateSection(
            mutex = ledUpdates,
            read = { it.preferences.led.value },
            write = userProfileRepository::updateLed,
        ) { _, value -> transform(value) }

    private fun updateVbt(
        transform: (ActiveProfileContext.Ready, VbtPreferences) -> VbtPreferences?,
    ) = updateSection(
        mutex = vbtAndSafetyUpdates,
        read = { it.preferences.vbt.value },
        write = userProfileRepository::updateVbt,
        transform = transform,
    )

    private fun updateSafety(
        transform: (ActiveProfileContext.Ready, ProfileLocalSafetyPreferences) -> ProfileLocalSafetyPreferences,
    ) = updateSection(
        mutex = vbtAndSafetyUpdates,
        read = { it.localSafety },
        write = userProfileRepository::updateLocalSafety,
        transform = transform,
    )

    fun setWeightUnit(unit: WeightUnit) = updateCore { it.copy(weightUnit = unit) }
    fun setWeightIncrement(increment: Float) = updateCore { it.copy(weightIncrement = increment) }
    fun setBodyWeightKg(weightKg: Float) = updateCore { it.copy(bodyWeightKg = weightKg) }

    fun setStopAtTop(enabled: Boolean) = updateWorkout { it.copy(stopAtTop = enabled) }
    fun setBeepsEnabled(enabled: Boolean) = updateWorkout { it.copy(beepsEnabled = enabled) }
    fun setStallDetectionEnabled(enabled: Boolean) = updateWorkout { it.copy(stallDetectionEnabled = enabled) }
    fun setAudioRepCountEnabled(enabled: Boolean) = updateWorkout { it.copy(audioRepCountEnabled = enabled) }
    fun setRepCountTiming(timing: RepCountTiming) = updateWorkout { it.copy(repCountTiming = timing) }
    fun setSummaryCountdownSeconds(seconds: Int) {
        Logger.d("setSummaryCountdownSeconds: Setting value to $seconds")
        updateWorkout { it.copy(summaryCountdownSeconds = seconds) }
    }
    fun setAutoStartCountdownSeconds(seconds: Int) = updateWorkout { it.copy(autoStartCountdownSeconds = seconds) }
    fun setGamificationEnabled(enabled: Boolean) = updateWorkout { it.copy(gamificationEnabled = enabled) }
    fun setAutoStartRoutine(enabled: Boolean) = updateWorkout { it.copy(autoStartRoutine = enabled) }
    fun setCountdownBeepsEnabled(enabled: Boolean) = updateWorkout { it.copy(countdownBeepsEnabled = enabled) }
    fun setRepSoundEnabled(enabled: Boolean) = updateWorkout { it.copy(repSoundEnabled = enabled) }
    fun setMotionStartEnabled(enabled: Boolean) = updateWorkout { it.copy(motionStartEnabled = enabled) }
    fun setWeightSuggestionsEnabled(enabled: Boolean) = updateWorkout { it.copy(weightSuggestionsEnabled = enabled) }
    fun setDefaultRoutineExerciseUsePercentOfPR(enabled: Boolean) =
        updateWorkout { it.copy(defaultRoutineExerciseUsePercentOfPR = enabled) }
    fun setDefaultRoutineExerciseWeightPercentOfPR(percent: Int) =
        updateWorkout { it.copy(defaultRoutineExerciseWeightPercentOfPR = percent.coerceIn(50, 120)) }
    fun setVoiceStopEnabled(enabled: Boolean) = updateWorkout { it.copy(voiceStopEnabled = enabled) }

    fun setColorScheme(schemeIndex: Int) = updateLed { it.copy(colorScheme = schemeIndex) }
    fun setDiscoModeUnlocked(unlocked: Boolean) = updateLed { it.copy(discoModeUnlocked = unlocked) }

    fun setVbtEnabled(enabled: Boolean) = updateVbt { _, current -> current.copy(enabled = enabled) }
    fun setVelocityLossThreshold(percent: Int) = updateVbt { _, current ->
        current.copy(velocityLossThresholdPercent = percent.coerceIn(10, 50))
    }
    fun setAutoEndOnVelocityLoss(enabled: Boolean) = updateVbt { _, current ->
        current.copy(autoEndOnVelocityLoss = enabled)
    }
    fun setDefaultScalingBasis(basis: ScalingBasis) = updateVbt { _, current ->
        current.copy(defaultScalingBasis = basis)
    }
    fun setVerbalEncouragementEnabled(enabled: Boolean) = updateVbt { _, current ->
        if (enabled) {
            current.copy(verbalEncouragementEnabled = true)
        } else {
            current.copy(
                verbalEncouragementEnabled = false,
                vulgarModeEnabled = false,
                dominatrixModeActive = false,
            )
        }
    }
    fun setVulgarModeEnabled(enabled: Boolean) = updateVbt { context, current ->
        when {
            enabled && !context.localSafety.adultsOnlyPrompted -> null
            !enabled -> current.copy(vulgarModeEnabled = false, dominatrixModeActive = false)
            else -> current.copy(vulgarModeEnabled = true)
        }
    }
    fun setVulgarTier(tier: VulgarTier) = updateVbt { _, current -> current.copy(vulgarTier = tier) }
    fun setDominatrixModeUnlocked(unlocked: Boolean) = updateVbt { _, current ->
        current.copy(dominatrixModeUnlocked = unlocked)
    }
    fun setDominatrixModeActive(active: Boolean) = updateVbt { context, current ->
        if (
            active &&
            (!current.dominatrixModeUnlocked || !current.vulgarModeEnabled || !context.localSafety.adultsOnlyConfirmed)
        ) {
            null
        } else {
            current.copy(dominatrixModeActive = active)
        }
    }

    fun setSafeWord(word: String?) = updateSafety { _, current -> current.copy(safeWord = word) }
    fun setSafeWordCalibrated(calibrated: Boolean) = updateSafety { _, current ->
        current.copy(safeWordCalibrated = calibrated)
    }
    fun setAdultsOnlyConfirmed(confirmed: Boolean) = updateSafety { _, current ->
        current.copy(adultsOnlyConfirmed = confirmed, adultsOnlyPrompted = true)
    }
    fun isAdultsOnlyPrompted(): Boolean = ready().localSafety.adultsOnlyPrompted
    fun setAdultsOnlyPrompted(prompted: Boolean) = updateSafety { _, current ->
        current.copy(adultsOnlyPrompted = prompted)
    }

    fun confirmAdultsAndEnableVulgar() {
        val expectedId = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
            ?.profile?.id ?: return
        scope.launch {
            try {
                vbtAndSafetyUpdates.withLock {
                    val before = readyFor(expectedId)
                    userProfileRepository.updateLocalSafety(
                        expectedId,
                        before.localSafety.copy(
                            adultsOnlyConfirmed = true,
                            adultsOnlyPrompted = true,
                        ),
                    )
                    val afterSafety = readyFor(expectedId)
                    userProfileRepository.updateVbt(
                        expectedId,
                        afterSafety.preferences.vbt.value.copy(vulgarModeEnabled = true),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextUnavailableException) {
                Logger.w(error) { "Adult consent update stopped while switching" }
            } catch (error: StaleProfileContextException) {
                Logger.w(error) { "Adult consent update stopped after profile switch" }
            }
        }
    }

    fun getSingleExerciseDefaultsDocument(exerciseId: String): SingleExerciseDefaultsDocument? =
        ready().preferences.workout.value.singleExerciseDefaults[exerciseId]

    fun saveSingleExerciseDefaultsDocument(document: SingleExerciseDefaultsDocument) =
        updateWorkout { current ->
            current.copy(
                singleExerciseDefaults = current.singleExerciseDefaults +
                    (document.exerciseId to document),
            )
        }

    fun getJustLiftDefaultsDocument(): JustLiftDefaultsDocument =
        ready().preferences.workout.value.justLiftDefaults

    fun saveJustLiftDefaultsDocument(document: JustLiftDefaultsDocument) =
        updateWorkout { current -> current.copy(justLiftDefaults = document) }

    fun setEnableVideoPlayback(enabled: Boolean) {
        scope.launch { globalPreferences.setEnableVideoPlayback(enabled) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        scope.launch { globalPreferences.setAutoBackupEnabled(enabled) }
    }

    fun setBackupDestination(destination: BackupDestination) {
        scope.launch { globalPreferences.setBackupDestination(destination) }
    }

    fun setLanguage(language: String) {
        scope.launch { globalPreferences.setLanguage(language) }
        com.devil.phoenixproject.util.applyAppLocale(language)
    }

    fun setBleCompatibilityMode(setting: BleCompatibilitySetting) {
        scope.launch { globalPreferences.setBleCompatibilityMode(setting) }
    }

    fun setVelocityOneRepMaxBackfillDone(done: Boolean) {
        scope.launch { globalPreferences.setVelocityOneRepMaxBackfillDone(done) }
    }

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
        val formatted = if (value % 1 == 0f) {
            value.toInt().toString()
        } else {
            value.format(2).trimEnd('0').trimEnd('.')
        }
        return "$formatted ${unit.name.lowercase()}"
    }
}
