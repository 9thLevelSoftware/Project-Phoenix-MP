package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.LedFeedbackMode
import com.devil.phoenixproject.domain.model.RepPhase
import com.devil.phoenixproject.domain.model.VelocityZone
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.testutil.FakeBleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for LedFeedbackController covering:
 * - VelocityZone boundary values (simplified 4-zone: 5/30/60 mm/s thresholds)
 * - Hysteresis (3-sample stability requirement)
 * - Mode-specific resolvers (tempo, echo)
 * - Enabled/disabled state
 * - Rest period handling
 * - Workout end color restoration
 * - PR celebration
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedFeedbackControllerTest {

    // ===== VelocityZone.fromVelocity boundary tests (simplified 4-zone: 5/30/60) =====

    @Test
    fun `VelocityZone fromVelocity maps REST below 5`() {
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(0.0))
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(4.0))
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(4.999))
    }

    @Test
    fun `VelocityZone fromVelocity maps CONTROLLED 5 to 29`() {
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(5.0))
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(20.0))
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(29.999))
    }

    @Test
    fun `VelocityZone fromVelocity maps MODERATE 30 to 59`() {
        assertEquals(VelocityZone.MODERATE, VelocityZone.fromVelocity(30.0))
        assertEquals(VelocityZone.MODERATE, VelocityZone.fromVelocity(45.0))
        assertEquals(VelocityZone.MODERATE, VelocityZone.fromVelocity(59.999))
    }

    @Test
    fun `VelocityZone fromVelocity maps FAST at 60 and above`() {
        assertEquals(VelocityZone.FAST, VelocityZone.fromVelocity(60.0))
        assertEquals(VelocityZone.FAST, VelocityZone.fromVelocity(100.0))
        assertEquals(VelocityZone.FAST, VelocityZone.fromVelocity(500.0))
    }

    // ===== Hysteresis tests =====

    @Test
    fun `hysteresis requires 3 consecutive samples before zone switch`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)

        // Establish CONTROLLED zone (20.0 mm/s = 5-30 range)
        repeat(3) {
            controller.updateMetrics(20.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // Now try to switch to FAST (70.0 mm/s = >=60 range) -- only 2 samples should NOT trigger
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()
        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "2 samples in new zone should NOT trigger color change")

        // Third sample in new zone should trigger
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()
        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.FAST.schemeIndex),
            "3rd sample should trigger zone switch to FAST (red)")
    }

    @Test
    fun `hysteresis resets counter when target matches current zone`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)

        // Establish CONTROLLED zone (20.0 mm/s)
        repeat(3) {
            controller.updateMetrics(20.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // 2 samples toward FAST (70.0 mm/s)
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)

        // Back to CONTROLLED -- resets stability counter
        controller.updateMetrics(20.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)

        // 2 more samples toward FAST (counter should have reset)
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "Stability counter should have reset; 2 samples not enough")
    }

    // ===== Tempo guide resolver tests (TUT: 50-70 mm/s, TUT Beast: 30-50 mm/s) =====

    @Test
    fun `resolveTempoZone TUT returns green when in target range`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // TUT target: 50-70 mm/s (calibrated 2026-02-14)
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(60.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(50.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(70.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns yellow when slightly fast`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // Slightly above target (70 < vel <= 70*1.5=105)
        assertEquals(VelocityZone.FAST, controller.resolveTempoZone(80.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.FAST, controller.resolveTempoZone(105.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns red when too fast`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // Well above target (> 105)
        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveTempoZone(120.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns teal when too slow`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // TUT tolerance = (70-50)*0.25 = 5. Below 50-5=45
        assertEquals(VelocityZone.MODERATE, controller.resolveTempoZone(40.0, WorkoutMode.TUT))
    }

    // ===== Echo zone resolver tests =====

    @Test
    fun `resolveEchoZone returns green when ratio near 1_0`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(1.0f))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(0.90f))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(1.10f))
    }

    @Test
    fun `resolveEchoZone returns yellow when slightly off`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.FAST, controller.resolveEchoZone(0.80f))
        assertEquals(VelocityZone.FAST, controller.resolveEchoZone(1.20f))
    }

    @Test
    fun `resolveEchoZone returns red when significant mismatch`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveEchoZone(0.50f))
        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveEchoZone(1.50f))
    }

    // ===== Disabled state =====

    @Test
    fun `updateMetrics does nothing when disabled`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        // Not enabled -- default is false

        repeat(10) {
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No BLE commands should be sent when controller is disabled")
    }

    // ===== Rest period =====

    @Test
    fun `onRestPeriodStart sends REST zone color`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)

        controller.onRestPeriodStart()
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.REST.schemeIndex),
            "Rest period should send REST zone color (index ${VelocityZone.REST.schemeIndex})")
    }

    @Test
    fun `updateMetrics ignored during rest period`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        controller.onRestPeriodStart()
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // Velocity updates during rest should be ignored
        fakeTime = 1000L
        repeat(5) {
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No velocity-driven updates during rest period")
    }

    // ===== Workout end =====

    @Test
    fun `onWorkoutEnd restores user color scheme`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)
        controller.setUserColorScheme(3) // Yellow

        controller.onWorkoutEnd()
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(3),
            "Workout end should restore user's color scheme (index 3)")
    }

    // ===== Disco mode interaction =====

    @Test
    fun `updateMetrics ignored when disco mode active`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)
        fakeBle.setDiscoModeActive(true)

        repeat(5) {
            fakeTime += 600L
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No LED commands when disco mode is active")
    }

    // ===== Auto mode resolution =====

    @Test
    fun `AUTO mode resolves to TEMPO_GUIDE for TUT`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setMode(LedFeedbackMode.AUTO)

        assertEquals(LedFeedbackMode.TEMPO_GUIDE, controller.resolveEffectiveMode(WorkoutMode.TUT))
        assertEquals(LedFeedbackMode.TEMPO_GUIDE, controller.resolveEffectiveMode(WorkoutMode.TUTBeast))
    }

    @Test
    fun `AUTO mode resolves to VELOCITY_ZONE for other modes`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setMode(LedFeedbackMode.AUTO)

        assertEquals(LedFeedbackMode.VELOCITY_ZONE, controller.resolveEffectiveMode(WorkoutMode.OldSchool))
        assertEquals(LedFeedbackMode.VELOCITY_ZONE, controller.resolveEffectiveMode(WorkoutMode.Pump))
    }

    // ===== Disconnect reset =====

    @Test
    fun `onDisconnect resets lastSentSchemeIndex for fresh send on reconnect`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)

        // Establish CONTROLLED zone (20.0 mm/s = 5-30 range)
        repeat(3) {
            controller.updateMetrics(20.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        // Switch to FAST zone (70.0 mm/s = >=60 range)
        repeat(3) {
            controller.updateMetrics(70.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        // Verify FAST was sent
        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.FAST.schemeIndex),
            "FAST zone should have been sent")
        fakeBle.colorSchemeCommands.clear()

        // Disconnect -- resets lastSentSchemeIndex to -1
        controller.onDisconnect()

        // After disconnect, switch back to CONTROLLED (which was previously sent).
        // Without disconnect, this would be deduped. With disconnect, it should re-send.
        repeat(3) {
            controller.updateMetrics(20.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.CONTROLLED.schemeIndex),
            "After disconnect, previously-sent zone should re-send color command")
    }
}
