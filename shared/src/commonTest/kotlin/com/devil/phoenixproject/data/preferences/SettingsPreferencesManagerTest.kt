package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WeightTextScale
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SettingsPreferencesManagerTest {

    @Test
    fun `language preference key is the shared public name`() {
        assertEquals("language", SettingsPreferencesManager.KEY_LANGUAGE)
    }

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

    // ===== Issue #1367 (T2/T3/T13): weight_text_scale persistence =====

    @Test
    fun `weight text size stores the enum name and round trips`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)

        assertEquals(WeightTextScale.STANDARD, manager.preferencesFlow.value.weightTextScale, "absent key -> STANDARD")

        manager.setWeightTextScale(WeightTextScale.EXTRA_LARGE)
        assertEquals(WeightTextScale.EXTRA_LARGE, manager.preferencesFlow.value.weightTextScale)
        assertEquals("EXTRA_LARGE", settings.getString("weight_text_scale", ""), "enum NAME stored")

        val reloaded = SettingsPreferencesManager(settings)
        assertEquals(WeightTextScale.EXTRA_LARGE, reloaded.preferencesFlow.value.weightTextScale)
    }

    @Test
    fun `weight text size emits on preferencesFlow and rapid changes end at the last value`() = runTest {
        val settings = MapSettings()
        val manager = SettingsPreferencesManager(settings)
        val emissions = mutableListOf<WeightTextScale>()
        val job = backgroundScope.launch {
            manager.preferencesFlow.collect { emissions.add(it.weightTextScale) }
        }

        manager.setWeightTextScale(WeightTextScale.LARGE)
        yield()
        manager.setWeightTextScale(WeightTextScale.STANDARD)
        yield()
        manager.setWeightTextScale(WeightTextScale.EXTRA_LARGE)
        yield()

        assertEquals(WeightTextScale.EXTRA_LARGE, manager.preferencesFlow.value.weightTextScale, "last choice wins")
        assertEquals("EXTRA_LARGE", settings.getString("weight_text_scale", ""))
        assertTrue(emissions.contains(WeightTextScale.LARGE), "preferencesFlow emitted intermediate choices")
        job.cancel()
    }

    @Test
    fun `failed weight text size write applies the session choice without corrupting storage`() = runTest {
        val backing = MapSettings()
        val failing = FailingWeightTextScaleSettings(backing)
        val manager = SettingsPreferencesManager(failing)

        manager.setWeightTextScale(WeightTextScale.LARGE)
        assertEquals(WeightTextScale.LARGE, manager.preferencesFlow.value.weightTextScale, "session choice applied")
        assertEquals(
            WeightTextScale.STANDARD,
            SettingsPreferencesManager(backing).preferencesFlow.value.weightTextScale,
            "failed write left stored state uncorrupted",
        )

        failing.failWrites = false
        manager.setWeightTextScale(WeightTextScale.EXTRA_LARGE)
        assertEquals(
            WeightTextScale.EXTRA_LARGE,
            SettingsPreferencesManager(backing).preferencesFlow.value.weightTextScale,
        )
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

/**
 * Issue #1367 T13: a [Settings] wrapper whose `weight_text_scale` writes fail on demand,
 * to prove the write-error contract (session choice applied, stored state uncorrupted).
 */
private class FailingWeightTextScaleSettings(
    private val delegate: MapSettings,
    var failWrites: Boolean = true,
) : Settings by delegate {
    override fun putString(key: String, value: String) {
        if (failWrites && key == "weight_text_scale") error("simulated disk failure")
        delegate.putString(key, value)
    }
}
