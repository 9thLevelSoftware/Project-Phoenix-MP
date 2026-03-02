package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.util.BleConstants
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorDataProcessorTest {

    private var fakeTime = 0L
    private var deloadCallCount = 0
    private var romViolations = mutableListOf<MonitorDataProcessor.RomViolationType>()

    private fun createProcessor(): MonitorDataProcessor = MonitorDataProcessor(
        onDeloadOccurred = { deloadCallCount++ },
        onRomViolation = { romViolations.add(it) },
        timeProvider = { fakeTime }
    )

    /**
     * Helper to build a MonitorPacket with sensible defaults.
     * Positions at 10mm (well within valid range), loads at 5kg.
     */
    private fun packet(
        posA: Float = 10.0f,
        posB: Float = 10.0f,
        loadA: Float = 5.0f,
        loadB: Float = 5.0f,
        ticks: Int = 0,
        status: Int = 0,
        firmwareVelA: Int = 0,
        firmwareVelB: Int = 0
    ) = MonitorPacket(ticks, posA, posB, loadA, loadB, status, firmwareVelA, firmwareVelB)

    /**
     * Reset test state between tests (JUnit creates new instance per test,
     * but reset explicitly for clarity).
     */
    private fun resetTestState() {
        fakeTime = 0L
        deloadCallCount = 0
        romViolations.clear()
    }

    // ================================================================
    // Position Validation Tests (6 tests)
    // ================================================================

    @Test
    fun `valid position passes through unchanged`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(packet(posA = 100.0f, posB = 200.0f))
        assertNotNull(result)
        assertEquals(100.0f, result.positionA)
        assertEquals(200.0f, result.positionB)
    }

    @Test
    fun `position A out of range uses last-good fallback`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample: establish last-good values
        val m1 = processor.process(packet(posA = 50.0f, posB = 60.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Second sample: posA out of range (>1000), should use last-good (50.0)
        val m2 = processor.process(packet(posA = 1500.0f, posB = 65.0f))
        assertNotNull(m2)
        assertEquals(50.0f, m2.positionA, "posA should fall back to last-good value")
        assertEquals(65.0f, m2.positionB, "posB should pass through unchanged")
    }

    @Test
    fun `position B out of range uses last-good fallback`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Establish last-good
        val m1 = processor.process(packet(posA = 50.0f, posB = 60.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // posB out of range
        val m2 = processor.process(packet(posA = 55.0f, posB = -1500.0f))
        assertNotNull(m2)
        assertEquals(55.0f, m2.positionA)
        assertEquals(60.0f, m2.positionB, "posB should fall back to last-good value")
    }

    @Test
    fun `both positions out of range use last-good fallback`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Establish last-good
        val m1 = processor.process(packet(posA = 50.0f, posB = 60.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Both out of range
        val m2 = processor.process(packet(posA = 2000.0f, posB = -2000.0f))
        assertNotNull(m2)
        assertEquals(50.0f, m2.positionA, "posA should fall back to last-good")
        assertEquals(60.0f, m2.positionB, "posB should fall back to last-good")
    }

    @Test
    fun `position at MIN_POSITION boundary is valid`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(
            packet(posA = BleConstants.Thresholds.MIN_POSITION.toFloat(), posB = 10.0f)
        )
        assertNotNull(result)
        assertEquals(BleConstants.Thresholds.MIN_POSITION.toFloat(), result.positionA)
    }

    @Test
    fun `position at MAX_POSITION boundary is valid`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(
            packet(posA = BleConstants.Thresholds.MAX_POSITION.toFloat(), posB = 10.0f)
        )
        assertNotNull(result)
        assertEquals(BleConstants.Thresholds.MAX_POSITION.toFloat(), result.positionA)
    }

    // ================================================================
    // Load Validation Tests (3 tests)
    // ================================================================

    @Test
    fun `valid load passes through`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(packet(loadA = 100.0f, loadB = 50.0f))
        assertNotNull(result)
        assertEquals(100.0f, result.loadA)
        assertEquals(50.0f, result.loadB)
    }

    @Test
    fun `negative load rejects sample`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(packet(loadA = -1.0f, loadB = 5.0f))
        assertNull(result, "Negative load should reject sample")
    }

    @Test
    fun `load exceeding MAX_WEIGHT_KG rejects sample`() {
        val processor = createProcessor()
        fakeTime = 1000L

        val result = processor.process(packet(loadA = 5.0f, loadB = 250.0f))
        assertNull(result, "Load exceeding MAX_WEIGHT_KG (220) should reject sample")
    }

    // ================================================================
    // Jump Filter / Issue #210 Tests (6 tests)
    // ================================================================

    @Test
    fun `position jump over threshold rejects sample`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample establishes baseline
        val m1 = processor.process(packet(posA = 100.0f, posB = 100.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Jump of 25mm > POSITION_JUMP_THRESHOLD (20mm)
        val m2 = processor.process(packet(posA = 125.0f, posB = 100.0f))
        assertNull(m2, "Position jump >20mm should reject sample")
    }

    @Test
    fun `position jump under threshold passes`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample establishes baseline
        val m1 = processor.process(packet(posA = 100.0f, posB = 100.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Jump of 15mm < POSITION_JUMP_THRESHOLD (20mm)
        val m2 = processor.process(packet(posA = 115.0f, posB = 110.0f))
        assertNotNull(m2, "Position jump <20mm should pass")
    }

    @Test
    fun `Issue 210 - spike does not cause infinite cascade`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Normal sample establishes baseline at 100mm
        val m1 = processor.process(packet(posA = 100.0f, posB = 100.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Spike: +50mm jump (> 20mm threshold) -> filtered
        val m2 = processor.process(packet(posA = 150.0f, posB = 100.0f))
        assertNull(m2, "Spike should be filtered")

        fakeTime = 1040L
        // Next sample at 150mm: delta from spike (150) = 0mm -> PASSES
        // This proves the fix: lastPositionA was updated to 150 (the spike value)
        // so delta from the spike to the same value is 0
        val m3 = processor.process(packet(posA = 150.0f, posB = 100.0f))
        assertNotNull(m3, "Issue #210: Same position as spike should pass (delta=0, no cascade)")
    }

    @Test
    fun `Issue 210 - tracking updates even when sample filtered`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Establish baseline at 100mm
        val m1 = processor.process(packet(posA = 100.0f, posB = 100.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Spike to 150mm -> filtered (delta = 50 > 20)
        val m2 = processor.process(packet(posA = 150.0f, posB = 100.0f))
        assertNull(m2)

        fakeTime = 1040L
        // Normal at 102mm: delta from SPIKE value (150) = 48mm -> also filtered
        // This is correct: the tracking position is the spike value, not pre-spike
        val m3 = processor.process(packet(posA = 102.0f, posB = 100.0f))
        assertNull(m3, "Delta from spike (150 -> 102 = 48mm) should still be filtered")

        fakeTime = 1060L
        // Same position again: delta from 102 = 0 -> passes
        val m4 = processor.process(packet(posA = 102.0f, posB = 100.0f))
        assertNotNull(m4, "Issue #210: Second attempt at same position should pass (delta=0)")
    }

    @Test
    fun `jump filter skips first sample - no previous reference`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample ever: no lastTimestamp (==0), so jump filter should NOT apply
        // Even with a "big" position, it should pass because there's no previous reference
        val result = processor.process(packet(posA = 500.0f, posB = 500.0f))
        assertNotNull(result, "First sample should always pass jump filter (no previous reference)")
    }

    @Test
    fun `jump filter respects strictValidationEnabled flag`() {
        val processor = createProcessor()
        processor.strictValidationEnabled = false
        fakeTime = 1000L

        // First sample
        val m1 = processor.process(packet(posA = 100.0f, posB = 100.0f))
        assertNotNull(m1)

        fakeTime = 1020L
        // Huge jump, but strict validation disabled -> should pass
        val m2 = processor.process(packet(posA = 500.0f, posB = 500.0f))
        assertNotNull(m2, "Jump should pass when strictValidationEnabled is false")
    }

    // ================================================================
    // Velocity EMA Tests (5 tests)
    // ================================================================

    @Test
    fun `first velocity sample seeds EMA directly`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First process() call passes validation (no jump filter when lastTimestamp==0)
        // and seeds EMA directly with firmware velocity. With firmware velocity the very
        // first call is the seed point (unlike client-side delta which required 2 samples).
        val result = processor.process(
            packet(posA = 100.0f, posB = 100.0f, firmwareVelA = 100)
        )
        assertNotNull(result)

        // Velocity should be exactly the raw firmware value (seeded, not smoothed toward 0)
        val expectedVelocity = 10.0  // firmwareVelA=100 / 10.0 = 10 mm/s
        assertEquals(expectedVelocity, result.velocityA, 0.01,
            "First velocity should seed EMA directly (no cold start lag)")
    }

    @Test
    fun `EMA converges toward stable velocity`() {
        val processor = createProcessor()
        fakeTime = 0L

        // Establish initial position
        processor.process(packet(posA = 0.0f, posB = 0.0f))

        // Feed constant firmware velocity: 1000 raw units = 100 mm/s
        // Position moves 10mm every 100ms (within jump threshold) to pass validation
        var lastVelocity = 0.0
        for (i in 1..20) {
            fakeTime = (i * 100).toLong()
            val posA = (i * 10).toFloat()
            val result = processor.process(packet(posA = posA, posB = 0.0f, firmwareVelA = 1000))
            if (result != null) {
                lastVelocity = result.velocityA
            }
        }

        // After 20 samples at constant firmware velocity, EMA should be very close to 100 mm/s
        val expectedVelocity = 100.0
        assertTrue(abs(lastVelocity - expectedVelocity) < 5.0,
            "After 20 samples at constant velocity, EMA should converge. Got $lastVelocity, expected ~$expectedVelocity")
    }

    @Test
    fun `velocity skipped after filtered sample`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Establish baseline
        processor.process(packet(posA = 100.0f, posB = 100.0f))

        fakeTime = 2000L
        // Normal movement: 110mm, velocity = 10 mm/s
        val m2 = processor.process(packet(posA = 110.0f, posB = 100.0f))
        assertNotNull(m2)
        val velocityBeforeFilter = m2.velocityA

        fakeTime = 3000L
        // Spike to 200mm -> filtered
        val m3 = processor.process(packet(posA = 200.0f, posB = 100.0f))
        assertNull(m3)

        fakeTime = 4000L
        // Same position as spike (200mm) -> passes
        // But velocity should NOT be updated from the spike's bad reference
        val m4 = processor.process(packet(posA = 200.0f, posB = 100.0f))
        assertNotNull(m4)
        // Velocity should equal the value before filter (skip happened)
        assertEquals(velocityBeforeFilter, m4.velocityA, 0.01,
            "Velocity should be preserved (skipped) after filtered sample")
    }

    @Test
    fun `velocity calculation uses firmware velocity`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample seeds EMA with firmware velocity 200 raw = 20.0 mm/s
        val result = processor.process(
            packet(posA = 100.0f, posB = 100.0f, firmwareVelA = 200)
        )
        assertNotNull(result)

        // Firmware velocity is converted: raw / 10.0 = mm/s
        assertEquals(20.0, result.velocityA, 0.01,
            "Velocity should come from firmware: 200 raw / 10.0 = 20 mm/s")

        fakeTime = 1500L
        // Second sample with different firmware velocity: EMA smooths toward it
        val result2 = processor.process(
            packet(posA = 110.0f, posB = 100.0f, firmwareVelA = 400)
        )
        assertNotNull(result2)

        // EMA: 0.3 * 40.0 + 0.7 * 20.0 = 12.0 + 14.0 = 26.0
        assertEquals(26.0, result2.velocityA, 0.01,
            "Second sample should EMA-smooth toward new firmware velocity")
    }

    @Test
    fun `zero time delta produces zero velocity`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // First sample
        processor.process(packet(posA = 100.0f, posB = 100.0f))

        // Same timestamp, small position change (within jump threshold)
        val result = processor.process(packet(posA = 110.0f, posB = 100.0f))
        assertNotNull(result)

        assertEquals(0.0, result.velocityA, 0.01,
            "Zero time delta should produce zero velocity")
    }

    // ================================================================
    // Status Flag Tests (6 tests)
    // ================================================================

    @Test
    fun `status 0 triggers no callbacks`() {
        val processor = createProcessor()
        fakeTime = 1000L

        processor.process(packet(status = 0))
        assertEquals(0, deloadCallCount, "Status 0 should not trigger deload")
        assertTrue(romViolations.isEmpty(), "Status 0 should not trigger ROM violation")
    }

    @Test
    fun `DELOAD_OCCURRED triggers onDeloadOccurred callback`() {
        val processor = createProcessor()
        // fakeTime must be > DELOAD_EVENT_DEBOUNCE_MS (2000) from initial lastDeloadEventTime (0)
        fakeTime = 5000L

        processor.process(packet(status = SampleStatus.DELOAD_OCCURRED))
        assertEquals(1, deloadCallCount, "DELOAD_OCCURRED should trigger callback")
    }

    @Test
    fun `deload event debounced within 2 seconds`() {
        val processor = createProcessor()
        fakeTime = 5000L

        // First deload
        processor.process(packet(status = SampleStatus.DELOAD_OCCURRED))
        assertEquals(1, deloadCallCount)

        // Second deload within 2 seconds
        fakeTime = 6500L  // 1500ms later (< 2000ms debounce)
        processor.process(packet(status = SampleStatus.DELOAD_OCCURRED))
        assertEquals(1, deloadCallCount, "Second deload within 2s should be debounced")
    }

    @Test
    fun `deload event fires again after 2 second cooldown`() {
        val processor = createProcessor()
        fakeTime = 5000L

        // First deload
        processor.process(packet(status = SampleStatus.DELOAD_OCCURRED))
        assertEquals(1, deloadCallCount)

        // After 2-second cooldown
        fakeTime = 7001L  // 2001ms later (> 2000ms debounce)
        processor.process(packet(status = SampleStatus.DELOAD_OCCURRED))
        assertEquals(2, deloadCallCount, "Deload should fire again after 2s cooldown")
    }

    @Test
    fun `ROM_OUTSIDE_HIGH triggers onRomViolation callback`() {
        val processor = createProcessor()
        fakeTime = 1000L

        processor.process(packet(status = SampleStatus.ROM_OUTSIDE_HIGH))
        assertEquals(1, romViolations.size)
        assertEquals(MonitorDataProcessor.RomViolationType.OUTSIDE_HIGH, romViolations[0])
    }

    @Test
    fun `ROM_OUTSIDE_LOW triggers onRomViolation callback`() {
        val processor = createProcessor()
        fakeTime = 1000L

        processor.process(packet(status = SampleStatus.ROM_OUTSIDE_LOW))
        assertEquals(1, romViolations.size)
        assertEquals(MonitorDataProcessor.RomViolationType.OUTSIDE_LOW, romViolations[0])
    }

    // ================================================================
    // Session Reset Tests (4 tests)
    // ================================================================

    @Test
    fun `resetForNewSession clears tracking state`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Process some samples to establish state
        processor.process(packet(posA = 100.0f, posB = 100.0f))
        fakeTime = 1020L
        processor.process(packet(posA = 110.0f, posB = 110.0f))

        // Reset
        processor.resetForNewSession()

        // After reset, next process should act as first sample (no jump filter, no velocity)
        fakeTime = 5000L
        val result = processor.process(packet(posA = 500.0f, posB = 500.0f))
        assertNotNull(result, "After reset, first sample should pass (no jump filter reference)")
    }

    @Test
    fun `resetForNewSession resets EMA to initial state`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Build up velocity EMA with high firmware velocity (500 mm/s)
        val preReset = processor.process(
            packet(posA = 0.0f, posB = 0.0f, firmwareVelA = 5000)
        )
        assertNotNull(preReset)
        assertEquals(500.0, preReset.velocityA, 0.01, "Pre-reset EMA should be seeded at 500")

        // Reset clears EMA and sets isFirstVelocitySample = true
        processor.resetForNewSession()

        // After reset, first sample re-seeds EMA fresh with firmware velocity
        fakeTime = 3000L
        val result = processor.process(
            packet(posA = 200.0f, posB = 0.0f, firmwareVelA = 100)
        )
        assertNotNull(result)
        // Velocity = firmwareVelA=100 / 10.0 = 10 mm/s (seeded fresh, not contaminated by pre-reset 500)
        assertEquals(10.0, result.velocityA, 0.01,
            "After reset, EMA should be re-seeded with first raw firmware velocity")
    }

    @Test
    fun `resetForNewSession resets poll rate diagnostics`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Process samples to build up poll rate stats
        processor.process(packet())
        fakeTime = 1020L
        processor.process(packet())
        fakeTime = 1040L
        processor.process(packet())

        // Verify stats exist before reset
        val statsBefore = processor.getPollRateStats()
        assertTrue(statsBefore.isNotEmpty(), "Stats should be non-empty before reset")

        // Reset
        processor.resetForNewSession()

        // Notification counter should be zero after reset
        assertEquals(0L, processor.notificationCount, "Notification count should be 0 after reset")
    }

    @Test
    fun `resetForNewSession does NOT reset lastGoodPosA and B`() {
        val processor = createProcessor()
        fakeTime = 1000L

        // Establish last-good at 50, 60
        processor.process(packet(posA = 50.0f, posB = 60.0f))

        // Reset
        processor.resetForNewSession()

        fakeTime = 5000L
        // Process with out-of-range position -> should use preserved last-good (50, 60)
        val result = processor.process(packet(posA = 2000.0f, posB = -2000.0f))
        assertNotNull(result)
        assertEquals(50.0f, result.positionA, "lastGoodPosA should be preserved across reset")
        assertEquals(60.0f, result.positionB, "lastGoodPosB should be preserved across reset")
    }
}
