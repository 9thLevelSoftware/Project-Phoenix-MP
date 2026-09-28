package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SettingsPreferencesManagerTest {

    @Test
    fun `raw telemetry is out of backups until the user opts in and a restore re-arms one-shot work`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)
        assertFalse(manager.preferencesFlow.value.includeRawTelemetryInBackups, "F-033: off by default")

        manager.setIncludeRawTelemetryInBackups(true)
        assertTrue(SettingsPreferencesManager(settings).preferencesFlow.value.includeRawTelemetryInBackups, "persists")

        manager.setVelocityOneRepMaxBackfillDone(true)
        manager.resetOneShotWorkAfterRestore()
        assertFalse(manager.preferencesFlow.value.velocityOneRepMaxBackfillDone)
        assertFalse(SettingsPreferencesManager(settings).preferencesFlow.value.velocityOneRepMaxBackfillDone, "cleared on disk")
    }

    @Test
    fun `loadPreferences removes legacy hud preset key`() {
        val settings = MapSettings().apply {
            putString("hud_preset", "biomechanics")
            putInt("summary_countdown_seconds", 15)
        }

        val manager = SettingsPreferencesManager(settings)

        assertNull(settings.getStringOrNull("hud_preset"))
        assertEquals(15, manager.preferencesFlow.value.summaryCountdownSeconds)
        assertTrue(manager.preferencesFlow.value.enableVideoPlayback)
    }

    @Test
    fun `just lift defaults use issue 553 Echo defaults`() {
        val defaults = JustLiftDefaults()

        assertEquals(1, defaults.echoLevelValue)
        assertEquals(EchoLevel.HARDER, defaults.getEchoLevel())
    }

    @Test
    fun `invalid saved Echo level falls back to issue 553 default`() {
        val defaults = singleExerciseDefaults(
            echoLevelValue = 99,
        )

        assertEquals(EchoLevel.HARDER, defaults.getEchoLevel())
    }

    @Test
    fun `weight suggestions default to enabled and persist changes`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)

        assertTrue(manager.preferencesFlow.value.weightSuggestionsEnabled)

        manager.setWeightSuggestionsEnabled(false)
        assertFalse(manager.preferencesFlow.value.weightSuggestionsEnabled)

        val reloaded = SettingsPreferencesManager(settings)
        assertFalse(reloaded.preferencesFlow.value.weightSuggestionsEnabled)
    }

    private fun singleExerciseDefaults(
        workoutModeId: Int = ProgramMode.Echo.modeValue,
        echoLevelValue: Int,
    ): SingleExerciseDefaults = SingleExerciseDefaults(
        exerciseId = "crossover-lateral-raise",
        setReps = listOf(10, 10, 10),
        weightPerCableKg = 20f,
        setWeightsPerCableKg = listOf(20f, 20f, 20f),
        progressionKg = 0f,
        setRestSeconds = listOf(60, 60, 60),
        workoutModeId = workoutModeId,
        eccentricLoadPercentage = 100,
        echoLevelValue = echoLevelValue,
        duration = 0,
        isAMRAP = false,
        perSetRestTime = false,
    )
}
