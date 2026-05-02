package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Integration tests verifying that v0.9.0 features coexist without interfering.
 *
 * These tests verify CONFIGURATION-LEVEL coexistence: multiple features enabled
 * simultaneously don't cause constructor failures, state corruption, or preference
 * cross-contamination. Full BLE-driven behavioral tests are out of scope here —
 * they require HandleState transitions and metric ingestion that belong in
 * higher-level E2E tests.
 *
 * Uses DWSMTestHarness which wires all 22+ dependencies of ActiveSessionEngine.
 */
class ActiveSessionEngineIntegrationTest {

    @Test
    fun bodyweightAndVbtFeaturesCoexistWithoutConstructorFailure() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Enable both bodyweight volume tracking and VBT auto-end simultaneously
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                bodyWeightKg = 85f,
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 15,
            ),
        )
        advanceUntilIdle()

        // Verify both features' settings propagated through SettingsManager
        val prefs = harness.settingsManager.userPreferences.value
        assertEquals(85f, prefs.bodyWeightKg, "bodyWeightKg should be set")
        assertTrue(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss should be enabled")
        assertEquals(15, prefs.velocityLossThresholdPercent, "VBT threshold should be 15%")

        // Verify coordinator was constructed with VBT config (from DWSM construction)
        // The coordinator's biomechanicsEngine should exist and be operational
        val bioEngine = harness.coordinator.biomechanicsEngine
        // latestRepResult should be null (no reps processed yet) — not crashed
        assertEquals(null, bioEngine.latestRepResult.value, "No biomechanics result before any reps")

        harness.cleanup()
    }

    @Test
    fun autoStartRoutineAndVbtThresholdsDoNotCorruptState() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Set auto-start routine AND VBT thresholds together
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                autoStartRoutine = true,
                autoStartCountdownSeconds = 3,
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 25,
            ),
        )
        advanceUntilIdle()

        val prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine should be enabled")
        assertEquals(3, prefs.autoStartCountdownSeconds, "countdown should be 3s")
        assertTrue(prefs.autoEndOnVelocityLoss, "VBT auto-end should be enabled")
        assertEquals(25, prefs.velocityLossThresholdPercent, "VBT threshold should be 25%")

        // Verify coordinator workout state is still Idle (no spurious transitions)
        val workoutState = harness.coordinator.workoutState.value
        assertFalse(harness.coordinator.isWorkoutActive, "No workout should be active")

        harness.cleanup()
    }

    @Test
    fun preferencesIsolationAcrossFeatures() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Start with defaults
        var prefs = harness.settingsManager.userPreferences.value
        assertFalse(prefs.autoStartRoutine, "autoStartRoutine should default to false")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg should default to 0")
        assertEquals(20, prefs.velocityLossThresholdPercent, "VBT threshold should default to 20")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss should default to false")
        assertFalse(prefs.autoBackupEnabled, "autoBackupEnabled should default to false")

        // Set only autoStartRoutine — other features should remain at defaults
        harness.fakePrefsManager.setAutoStartRoutine(true)
        advanceUntilIdle()

        prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine should now be true")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg unchanged after setting autoStart")
        assertEquals(20, prefs.velocityLossThresholdPercent, "VBT threshold unchanged")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss unchanged")
        assertFalse(prefs.autoBackupEnabled, "autoBackupEnabled unchanged")

        // Set only bodyWeightKg — autoStartRoutine should remain true
        harness.fakePrefsManager.setBodyWeightKg(75f)
        advanceUntilIdle()

        prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine still true")
        assertEquals(75f, prefs.bodyWeightKg, "bodyWeightKg should now be 75")

        harness.cleanup()
    }

    @Test
    fun coordinatorTracksVbtConfigurationUpdates() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        assertFalse(
            harness.coordinator.autoEndOnVelocityLoss,
            "Coordinator should reflect default autoEndOnVelocityLoss = false",
        )
        assertEquals(
            20f,
            harness.coordinator.biomechanicsEngine.currentVelocityLossThresholdPercent,
            "Coordinator should reflect default VBT threshold",
        )

        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 35,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            harness.coordinator.autoEndOnVelocityLoss,
            "Coordinator should update autoEndOnVelocityLoss from preferences",
        )
        assertEquals(
            35f,
            harness.coordinator.biomechanicsEngine.currentVelocityLossThresholdPercent,
            "Coordinator should update VBT threshold from preferences",
        )

        harness.cleanup()
    }
}
