package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakePreferencesManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests verifying that v0.9.0 preference fields are properly isolated from each other.
 *
 * Multiple phases added new preference keys (autoStartRoutine, bodyWeightKg,
 * velocityLossThresholdPercent, autoEndOnVelocityLoss, autoBackupEnabled,
 * autoStartCountdownSeconds, weightIncrement). This test suite verifies that
 * setting one feature's preferences does not corrupt another's values.
 *
 * Uses FakePreferencesManager (in-memory StateFlow-backed implementation)
 * to verify the same contract the real SettingsPreferencesManager fulfills.
 */
class PreferencesIsolationTest {

    @Test
    fun freshDefaultsReturnCorrectValues() {
        val prefs = UserPreferences()

        // Verify all 7 v0.9.0 preference fields have correct defaults
        assertFalse(prefs.autoStartRoutine, "autoStartRoutine should default to false")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg should default to 0")
        assertEquals(20, prefs.velocityLossThresholdPercent, "velocityLossThresholdPercent should default to 20")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss should default to false")
        assertFalse(prefs.autoBackupEnabled, "autoBackupEnabled should default to false")
        assertEquals(5, prefs.autoStartCountdownSeconds, "autoStartCountdownSeconds should default to 5")
        assertEquals(-1f, prefs.weightIncrement, "weightIncrement should default to -1 (use unit default)")
    }

    @Test
    fun crossFeatureIsolationWhenSettingIndividualPrefs() = runTest {
        val manager = FakePreferencesManager()

        // Set autoStartRoutine — verify VBT prefs unchanged
        manager.setAutoStartRoutine(true)
        var prefs = manager.preferencesFlow.value

        assertTrue(prefs.autoStartRoutine, "autoStartRoutine should be true")
        assertEquals(20, prefs.velocityLossThresholdPercent, "VBT threshold unchanged after setting autoStart")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss unchanged after setting autoStart")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg unchanged after setting autoStart")

        // Set autoBackupEnabled — verify bodyWeightKg unchanged
        manager.setAutoBackupEnabled(true)
        prefs = manager.preferencesFlow.value

        assertTrue(prefs.autoBackupEnabled, "autoBackupEnabled should be true")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg unchanged after setting autoBackup")
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine still true after setting autoBackup")

        // Set VBT threshold — verify auto-start prefs unchanged
        manager.setVelocityLossThreshold(30)
        prefs = manager.preferencesFlow.value

        assertEquals(30, prefs.velocityLossThresholdPercent, "VBT threshold should be 30")
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine still true after setting VBT threshold")
        assertTrue(prefs.autoBackupEnabled, "autoBackupEnabled still true after setting VBT threshold")
        assertEquals(5, prefs.autoStartCountdownSeconds, "countdown unchanged after setting VBT threshold")
    }

    @Test
    fun boundaryValuesAccepted() = runTest {
        val manager = FakePreferencesManager()

        // VBT threshold boundary: min 10, max 50 (clamped by FakePreferencesManager)
        manager.setVelocityLossThreshold(10)
        assertEquals(10, manager.preferencesFlow.value.velocityLossThresholdPercent, "Min VBT threshold = 10")

        manager.setVelocityLossThreshold(50)
        assertEquals(50, manager.preferencesFlow.value.velocityLossThresholdPercent, "Max VBT threshold = 50")

        // Body weight boundaries
        manager.setBodyWeightKg(0f)
        assertEquals(0f, manager.preferencesFlow.value.bodyWeightKg, "bodyWeightKg = 0 (not set)")

        manager.setBodyWeightKg(300f)
        assertEquals(300f, manager.preferencesFlow.value.bodyWeightKg, "bodyWeightKg = 300 (heavyweight)")

        // Auto-start countdown boundaries
        manager.setAutoStartCountdownSeconds(1)
        assertEquals(1, manager.preferencesFlow.value.autoStartCountdownSeconds, "Countdown min = 1")

        manager.setAutoStartCountdownSeconds(30)
        assertEquals(30, manager.preferencesFlow.value.autoStartCountdownSeconds, "Countdown max = 30")
    }

    @Test
    fun allPrefsAtOnceSetAndReadBack() = runTest {
        val manager = FakePreferencesManager()

        // Set all 7 v0.9.0 fields to non-default values in a single batch
        manager.setPreferences(
            UserPreferences(
                autoStartRoutine = true,
                bodyWeightKg = 92.5f,
                velocityLossThresholdPercent = 15,
                autoEndOnVelocityLoss = true,
                autoBackupEnabled = true,
                autoStartCountdownSeconds = 8,
                weightIncrement = 2.5f,
                // Also set a pre-existing field to verify no interference
                weightUnit = WeightUnit.KG,
            ),
        )

        val prefs = manager.preferencesFlow.value

        assertTrue(prefs.autoStartRoutine, "autoStartRoutine = true")
        assertEquals(92.5f, prefs.bodyWeightKg, "bodyWeightKg = 92.5")
        assertEquals(15, prefs.velocityLossThresholdPercent, "velocityLossThresholdPercent = 15")
        assertTrue(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss = true")
        assertTrue(prefs.autoBackupEnabled, "autoBackupEnabled = true")
        assertEquals(8, prefs.autoStartCountdownSeconds, "autoStartCountdownSeconds = 8")
        assertEquals(2.5f, prefs.weightIncrement, "weightIncrement = 2.5")
        assertEquals(WeightUnit.KG, prefs.weightUnit, "weightUnit unchanged = KG")
    }
}
