package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.EchoLevel
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SettingsPreferencesManagerTest {

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
        val defaults = SingleExerciseDefaults(
            exerciseId = "crossover-lateral-raise",
            setReps = listOf(10, 10, 10),
            weightPerCableKg = 20f,
            setWeightsPerCableKg = listOf(20f, 20f, 20f),
            progressionKg = 0f,
            setRestSeconds = listOf(60, 60, 60),
            workoutModeId = 10,
            eccentricLoadPercentage = 100,
            echoLevelValue = 99,
            duration = 0,
            isAMRAP = false,
            perSetRestTime = false,
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
}
