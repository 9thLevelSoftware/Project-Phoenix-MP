package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.EchoLevel
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsPreferencesManagerTest {

    private val legacyDefaultsJson = Json { encodeDefaults = true }

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
    fun `saved Just Lift Hard default migrates once to issue 553 default`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)
        val hardDefaults = JustLiftDefaults(
            workoutModeId = 10,
            echoLevelValue = EchoLevel.HARD.levelValue,
        )

        settings.putString("just_lift_defaults", legacyDefaultsJson.encodeToString(hardDefaults))

        val migrated = manager.getJustLiftDefaults()
        assertEquals(EchoLevel.HARDER.levelValue, migrated.echoLevelValue)
        assertEquals(EchoLevel.HARDER, migrated.getEchoLevel())

        manager.saveJustLiftDefaults(hardDefaults)

        val explicitlySavedHard = manager.getJustLiftDefaults()
        assertEquals(EchoLevel.HARD.levelValue, explicitlySavedHard.echoLevelValue)
        assertEquals(EchoLevel.HARD, explicitlySavedHard.getEchoLevel())
    }

    @Test
    fun `new Just Lift Hard default saved after issue 553 migration is preserved`() = runTest {
        val manager = SettingsPreferencesManager(MapSettings())
        val hardDefaults = JustLiftDefaults(
            workoutModeId = 10,
            echoLevelValue = EchoLevel.HARD.levelValue,
        )

        manager.saveJustLiftDefaults(hardDefaults)

        val saved = manager.getJustLiftDefaults()
        assertEquals(EchoLevel.HARD.levelValue, saved.echoLevelValue)
        assertEquals(EchoLevel.HARD, saved.getEchoLevel())
    }

    @Test
    fun `saved single exercise Hard default migrates once to issue 553 default`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)
        val hardDefaults = singleExerciseDefaults(echoLevelValue = EchoLevel.HARD.levelValue)

        settings.putString(
            "exercise_defaults_${hardDefaults.exerciseId}",
            legacyDefaultsJson.encodeToString(hardDefaults),
        )

        val migrated = manager.getSingleExerciseDefaults(hardDefaults.exerciseId) ?: error("Expected migrated defaults")
        assertEquals(EchoLevel.HARDER.levelValue, migrated.echoLevelValue)
        assertEquals(EchoLevel.HARDER, migrated.getEchoLevel())

        manager.saveSingleExerciseDefaults(hardDefaults)

        val explicitlySavedHard = manager.getSingleExerciseDefaults(hardDefaults.exerciseId) ?: error("Expected saved defaults")
        assertEquals(EchoLevel.HARD.levelValue, explicitlySavedHard.echoLevelValue)
        assertEquals(EchoLevel.HARD, explicitlySavedHard.getEchoLevel())
    }

    @Test
    fun `new single exercise Hard default saved after issue 553 migration is preserved`() = runTest {
        val manager = SettingsPreferencesManager(MapSettings())
        val hardDefaults = singleExerciseDefaults(echoLevelValue = EchoLevel.HARD.levelValue)

        manager.saveSingleExerciseDefaults(hardDefaults)

        val saved = manager.getSingleExerciseDefaults(hardDefaults.exerciseId) ?: error("Expected saved defaults")
        assertEquals(EchoLevel.HARD.levelValue, saved.echoLevelValue)
        assertEquals(EchoLevel.HARD, saved.getEchoLevel())
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

    private fun singleExerciseDefaults(echoLevelValue: Int): SingleExerciseDefaults = SingleExerciseDefaults(
        exerciseId = "crossover-lateral-raise",
        setReps = listOf(10, 10, 10),
        weightPerCableKg = 20f,
        setWeightsPerCableKg = listOf(20f, 20f, 20f),
        progressionKg = 0f,
        setRestSeconds = listOf(60, 60, 60),
        workoutModeId = 10,
        eccentricLoadPercentage = 100,
        echoLevelValue = echoLevelValue,
        duration = 0,
        isAMRAP = false,
        perSetRestTime = false,
    )
}
