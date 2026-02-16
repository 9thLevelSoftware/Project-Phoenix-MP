package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandleStateDetectorTest {

    private var fakeTime = 0L

    private fun createDetector(): HandleStateDetector =
        HandleStateDetector(timeProvider = { fakeTime })

    /**
     * Helper to build a WorkoutMetric with sensible defaults.
     * All zeros except specified params.
     */
    private fun metric(
        posA: Float = 0f,
        posB: Float = 0f,
        velA: Double = 0.0,
        velB: Double = 0.0
    ) = WorkoutMetric(
        timestamp = fakeTime,
        loadA = 0f,
        loadB = 0f,
        positionA = posA,
        positionB = posB,
        velocityA = velA,
        velocityB = velB
    )

    // ================================================================
    // State Transition Tests
    // ================================================================

    @Test
    fun `initial state is WaitingForRest`() {
        val detector = createDetector()
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)
    }

    @Test
    fun `WaitingForRest to Released when both handles below rest threshold`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Both handles below rest threshold (5mm)
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `WaitingForRest stays when handles above rest threshold`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Both handles above rest threshold
        detector.processMetric(metric(posA = 10.0f, posB = 10.0f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)
    }

    @Test
    fun `WaitingForRest timeout arms after 3 seconds`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        fakeTime = 0L

        // Handles above rest threshold - starts timeout
        detector.processMetric(metric(posA = 6.0f, posB = 6.0f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // 2999ms later - still waiting
        fakeTime = 2999L
        detector.processMetric(metric(posA = 6.0f, posB = 6.0f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // 3001ms - timeout fires, uses real baseline (not grabbed)
        fakeTime = 3001L
        detector.processMetric(metric(posA = 6.0f, posB = 6.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `WaitingForRest timeout with grabbed handles uses virtual baseline`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        fakeTime = 0L

        // Handles above GRABBED threshold (8mm) - starts timeout
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Timeout fires
        fakeTime = 3001L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // After virtual baseline (0.0), position 20mm is well above grab delta threshold (10mm)
        // So next metric with velocity should start the grab dwell
        fakeTime = 3100L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        // Should still be Released (dwell not started or just started)

        // After 200ms dwell
        fakeTime = 3300L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `WaitingForRest timeout with elevated rest uses real baseline`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        fakeTime = 0L

        // Handles above rest (5mm) but below grabbed threshold (8mm) -- elevated rest
        detector.processMetric(metric(posA = 6.0f, posB = 6.0f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Timeout fires - real baseline captured at 6.0
        fakeTime = 3001L
        detector.processMetric(metric(posA = 6.0f, posB = 6.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Position at 17mm: delta from baseline 6.0 = 11mm > GRAB_DELTA_THRESHOLD (10mm)
        // With velocity, should start grab dwell
        fakeTime = 3100L
        detector.processMetric(metric(posA = 17.0f, posB = 17.0f, velA = 100.0, velB = 100.0))

        // After 200ms dwell
        fakeTime = 3300L
        detector.processMetric(metric(posA = 17.0f, posB = 17.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `Released to Grabbed requires position AND velocity AND 200ms dwell`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // First grab signal - starts dwell timer
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value) // Still dwelling

        // 199ms later - not yet
        fakeTime = 1199L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)

        // 200ms - dwell complete
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `Released to Moving when position extended but no velocity`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Position extended but zero velocity -> Moving
        fakeTime = 100L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 0.0, velB = 0.0))
        assertEquals(HandleState.Moving, detector.handleState.value)
    }

    @Test
    fun `Released stays Released when handles at rest`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Handles still at rest (below grab threshold)
        fakeTime = 100L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `Moving to Grabbed when velocity threshold met with 200ms dwell`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Moving
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 100L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 0.0, velB = 0.0))
        assertEquals(HandleState.Moving, detector.handleState.value)

        // Now add velocity - starts grab dwell
        fakeTime = 200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Moving, detector.handleState.value) // Still dwelling

        // 200ms later - grab confirmed
        fakeTime = 400L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `Moving to Released when handles return to rest`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Moving
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 100L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 0.0, velB = 0.0))
        assertEquals(HandleState.Moving, detector.handleState.value)

        // Handles return to rest
        fakeTime = 200L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `Grabbed to Released requires 200ms dwell`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Grabbed
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Start release - handles back at rest
        fakeTime = 2000L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value) // Still dwelling

        // 199ms - not yet
        fakeTime = 2199L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // 200ms - release confirmed
        fakeTime = 2200L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `Grabbed stays Grabbed when handles still extended`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Grabbed
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Handles still extended
        fakeTime = 2000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 50.0, velB = 50.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    // ================================================================
    // Hysteresis Tests
    // ================================================================

    @Test
    fun `grab dwell timer resets when threshold not met`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Start grab dwell
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))

        // 150ms later - still dwelling
        fakeTime = 1150L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Handles drop back to rest - resets dwell
        fakeTime = 1160L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // New grab attempt - should need full 200ms again
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        // Only 0ms into new dwell
        assertEquals(HandleState.Released, detector.handleState.value)

        // 200ms from the NEW dwell start (1200 + 200 = 1400)
        fakeTime = 1400L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `release dwell timer resets when handles move away from rest`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Grabbed
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Start release dwell
        fakeTime = 2000L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // 150ms - still dwelling
        fakeTime = 2150L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Handles move away from rest - resets release dwell
        fakeTime = 2160L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 50.0, velB = 50.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // New release attempt - needs full 200ms again
        fakeTime = 2200L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value) // Just started new dwell

        fakeTime = 2400L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value) // 200ms from 2200
    }

    @Test
    fun `dwell timer requires sustained threshold for exactly STATE_TRANSITION_DWELL_MS`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))

        // Start grab at t=1000
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)

        // At exactly 199ms (< 200ms dwell) -- should NOT transition
        fakeTime = 1199L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)

        // At exactly 200ms (>= 200ms dwell) -- SHOULD transition
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    // ================================================================
    // Single Handle Tests
    // ================================================================

    @Test
    fun `single handle A grab detected correctly`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))

        // Only handle A grabbed and moving
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 1.5f, velA = 100.0, velB = 0.0))

        // 200ms dwell
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 1.5f, velA = 100.0, velB = 0.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `single handle B grab detected correctly`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))

        // Only handle B grabbed and moving
        fakeTime = 1000L
        detector.processMetric(metric(posA = 1.5f, posB = 20.0f, velA = 0.0, velB = 100.0))

        // 200ms dwell
        fakeTime = 1200L
        detector.processMetric(metric(posA = 1.5f, posB = 20.0f, velA = 0.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `single handle release only checks active handle`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Grabbed (only handle A active)
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 1.5f, velA = 100.0, velB = 0.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 1.5f, velA = 100.0, velB = 0.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Handle B at rest (was never active) -- should NOT trigger release
        fakeTime = 2000L
        detector.processMetric(metric(posA = 20.0f, posB = 0.0f))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Handle A returns to rest -- SHOULD start release dwell
        fakeTime = 2100L
        detector.processMetric(metric(posA = 2.0f, posB = 0.0f))
        assertEquals(HandleState.Grabbed, detector.handleState.value) // Dwelling

        // 200ms later -- release confirmed
        fakeTime = 2300L
        detector.processMetric(metric(posA = 2.0f, posB = 0.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `both handles grabbed requires both released`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Go to Released -> Grabbed (both handles active)
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Only A released, B still extended -- should NOT release
        fakeTime = 2000L
        detector.processMetric(metric(posA = 2.0f, posB = 20.0f))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Both released
        fakeTime = 2100L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Grabbed, detector.handleState.value) // Dwelling

        // 200ms later
        fakeTime = 2300L
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    // ================================================================
    // Baseline Tracking Tests (Issue #176)
    // ================================================================

    @Test
    fun `baseline captured on WaitingForRest to Released transition`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Handles at rest with non-zero position -> captures baseline
        detector.processMetric(metric(posA = 3.0f, posB = 4.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Now position must be 3.0 + GRAB_DELTA_THRESHOLD (10mm) = 13mm to trigger grab
        // Position at 12mm -> delta = 9mm < 10mm -> NOT grabbed
        fakeTime = 100L
        detector.processMetric(metric(posA = 12.0f, posB = 12.0f, velA = 100.0, velB = 100.0))
        // Not grabbed because delta from baseline is only 9mm (12-3) and 8mm (12-4)
        // Should be Moving (position extended but doesn't meet grab delta + velocity)
        // Actually let's check: handleAGrabbed = (12 - 3) = 9 < 10 = false for A
        // So neither handle is grabbed, which means back to rest: Released
        assertEquals(HandleState.Released, detector.handleState.value)

        // Position at 14mm -> delta from baseline 3 = 11mm > 10mm -> grabbed
        fakeTime = 200L
        detector.processMetric(metric(posA = 14.0f, posB = 15.0f, velA = 100.0, velB = 100.0))

        // 200ms dwell
        fakeTime = 400L
        detector.processMetric(metric(posA = 14.0f, posB = 15.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `grab detection uses baseline-relative position`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Baseline captured at 3.0, 4.0
        detector.processMetric(metric(posA = 3.0f, posB = 4.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Absolute position 9mm > HANDLE_GRABBED_THRESHOLD (8mm) -- but baseline-relative:
        // delta = 9 - 3 = 6mm which is < GRAB_DELTA_THRESHOLD (10mm) -- NOT grabbed
        fakeTime = 100L
        detector.processMetric(metric(posA = 9.0f, posB = 9.0f, velA = 100.0, velB = 100.0))
        // Since neither is grab-detected via delta, this is "back to rest" => Released
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `release detection uses baseline-relative position`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Rest baseline at 3.0
        detector.processMetric(metric(posA = 3.0f, posB = 3.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Grab with large delta
        fakeTime = 1000L
        detector.processMetric(metric(posA = 30.0f, posB = 30.0f, velA = 100.0, velB = 100.0))
        fakeTime = 1200L
        detector.processMetric(metric(posA = 30.0f, posB = 30.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)

        // Release: position returns to within RELEASE_DELTA_THRESHOLD (5mm) of baseline (3.0)
        // Position 7.0: delta = 7 - 3 = 4mm < 5mm -> released
        fakeTime = 2000L
        detector.processMetric(metric(posA = 7.0f, posB = 7.0f))
        assertEquals(HandleState.Grabbed, detector.handleState.value) // Dwelling

        fakeTime = 2200L
        detector.processMetric(metric(posA = 7.0f, posB = 7.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `baseline reset on enable`() {
        val detector = createDetector()

        // First session - set baseline
        detector.enable(autoStart = true)
        detector.processMetric(metric(posA = 3.0f, posB = 3.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Re-enable - should reset everything including baseline
        detector.enable(autoStart = false)
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // New baseline should be captured fresh
        detector.processMetric(metric(posA = 1.0f, posB = 1.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `baseline reset on disable`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Set baseline
        detector.processMetric(metric(posA = 3.0f, posB = 3.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Disable clears everything
        detector.disable()
        assertFalse(detector.isEnabled)

        // Re-enable
        detector.enable(autoStart = true)

        // New baseline captured
        detector.processMetric(metric(posA = 1.0f, posB = 1.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `baseline reset on reset`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Set baseline
        detector.processMetric(metric(posA = 3.0f, posB = 3.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Reset returns to WaitingForRest
        detector.reset()
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // New baseline captured
        detector.processMetric(metric(posA = 1.0f, posB = 1.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `baseline reset on enableJustLiftWaiting`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Set baseline
        detector.processMetric(metric(posA = 3.0f, posB = 3.0f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // enableJustLiftWaiting resets everything
        detector.enableJustLiftWaiting()
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // New baseline captured
        detector.processMetric(metric(posA = 1.0f, posB = 1.0f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    // ================================================================
    // Auto-Start Mode Tests (Issue #96)
    // ================================================================

    @Test
    fun `auto-start mode uses lower velocity threshold`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        assertTrue(detector.isAutoStartMode)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))

        // Velocity 25 mm/s > AUTO_START_VELOCITY_THRESHOLD (20) -> should detect grab
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 25.0, velB = 25.0))

        // 200ms dwell
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 25.0, velB = 25.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }

    @Test
    fun `normal mode uses standard velocity threshold`() {
        val detector = createDetector()
        detector.enable(autoStart = false)
        assertFalse(detector.isAutoStartMode)

        // Go to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))

        // Velocity 25 mm/s < VELOCITY_THRESHOLD (50) -> should NOT detect grab
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 25.0, velB = 25.0))

        // 200ms later - still no grab because velocity too low
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 25.0, velB = 25.0))
        // With no velocity above threshold, position above threshold -> Moving
        assertEquals(HandleState.Moving, detector.handleState.value)
    }

    // ================================================================
    // Simple Detection Tests
    // ================================================================

    @Test
    fun `handleDetection updates left right booleans`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Initial: both false
        assertEquals(HandleDetection(false, false), detector.handleDetection.value)

        // Left handle extended > 50mm
        detector.processMetric(metric(posA = 60.0f, posB = 10.0f))
        assertEquals(HandleDetection(true, false), detector.handleDetection.value)

        // Both extended
        fakeTime = 100L
        detector.processMetric(metric(posA = 60.0f, posB = 60.0f))
        assertEquals(HandleDetection(true, true), detector.handleDetection.value)

        // Both back
        fakeTime = 200L
        detector.processMetric(metric(posA = 10.0f, posB = 10.0f))
        assertEquals(HandleDetection(false, false), detector.handleDetection.value)
    }

    @Test
    fun `handleDetection not updated when detection disabled`() {
        val detector = createDetector()
        // Not enabled - processMetric should be no-op
        assertFalse(detector.isEnabled)

        detector.processMetric(metric(posA = 60.0f, posB = 60.0f))
        assertEquals(HandleDetection(false, false), detector.handleDetection.value)
    }

    // ================================================================
    // Control Method Tests
    // ================================================================

    @Test
    fun `enable sets isEnabled and autoStartMode`() {
        val detector = createDetector()
        assertFalse(detector.isEnabled)
        assertFalse(detector.isAutoStartMode)

        detector.enable(autoStart = true)
        assertTrue(detector.isEnabled)
        assertTrue(detector.isAutoStartMode)

        detector.enable(autoStart = false)
        assertTrue(detector.isEnabled)
        assertFalse(detector.isAutoStartMode)
    }

    @Test
    fun `disable clears isEnabled and resets baseline`() {
        val detector = createDetector()
        detector.enable(autoStart = true)
        assertTrue(detector.isEnabled)
        assertTrue(detector.isAutoStartMode)

        detector.disable()
        assertFalse(detector.isEnabled)
        assertFalse(detector.isAutoStartMode)
    }

    @Test
    fun `reset returns to WaitingForRest and clears all timers`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        // Advance to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // Reset
        detector.reset()
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)
        // Should still be enabled
        assertTrue(detector.isEnabled)
    }

    @Test
    fun `enableJustLiftWaiting resets state and enables autoStart`() {
        val detector = createDetector()
        detector.enable(autoStart = false)
        assertFalse(detector.isAutoStartMode)

        // Advance to Released
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)

        // enableJustLiftWaiting
        detector.enableJustLiftWaiting()
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)
        assertTrue(detector.isEnabled)
        assertTrue(detector.isAutoStartMode)
    }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Test
    fun `processMetric is no-op when disabled`() {
        val detector = createDetector()
        assertFalse(detector.isEnabled)
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Should not change state
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Should not change handle detection
        detector.processMetric(metric(posA = 60.0f, posB = 60.0f))
        assertEquals(HandleDetection(false, false), detector.handleDetection.value)
    }

    @Test
    fun `position diagnostics track min and max`() {
        val detector = createDetector()
        detector.enable(autoStart = true)

        detector.processMetric(metric(posA = 10.0f, posB = 5.0f))
        assertEquals(5.0, detector.minPositionSeen)
        assertEquals(10.0, detector.maxPositionSeen)

        fakeTime = 100L
        detector.processMetric(metric(posA = 2.0f, posB = 20.0f))
        assertEquals(2.0, detector.minPositionSeen)
        assertEquals(20.0, detector.maxPositionSeen)
    }
}
