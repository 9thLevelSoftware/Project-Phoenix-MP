package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepCounterFromMachineTest {

    private lateinit var repCounter: RepCounterFromMachine
    private val capturedEvents = mutableListOf<RepEvent>()

    @BeforeTest
    fun setup() {
        repCounter = RepCounterFromMachine()
        capturedEvents.clear()
        repCounter.onRepEvent = { capturedEvents.add(it) }
    }

    // ========== Configuration Tests ==========

    @Test
    fun `configure sets warmup and working targets`() {
        repCounter.configure(
            warmupTarget = 3,
            workingTarget = 10,
            isJustLift = false,
            stopAtTop = false
        )

        // Initially no reps counted
        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `reset clears all state`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Process some reps
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 0)

        repCounter.reset()

        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== Modern Mode Rep Counting Tests ==========

    @Test
    fun `warmup reps are counted from repsRomCount`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 0)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `warmup complete event fires when warmup target reached`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 2)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertTrue(count.isWarmupComplete)

        // Should have warmup completed events
        assertTrue(capturedEvents.any { it.type == RepType.WARMUP_COMPLETE })
    }

    @Test
    fun `working reps are counted from repsSetCount`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete warmup first
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        // Now working reps
        repCounter.process(repsRomCount = 3, repsSetCount = 1, up = 4, down = 4)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps)
        assertEquals(1, count.totalReps) // totalReps = workingReps only
    }

    @Test
    fun `shouldStopWorkout returns true when working target reached`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false)

        // Simulate 5 working reps
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `shouldStopWorkout returns false for AMRAP mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false, isAMRAP = true)

        // Simulate 5 working reps
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    @Test
    fun `shouldStopWorkout returns false for Just Lift mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = true, stopAtTop = false)

        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== Legacy Mode Rep Counting Tests ==========

    @Test
    fun `legacy mode counts reps from topCounter increments`() {
        repCounter.configure(warmupTarget = 2, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // First call establishes baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, isLegacyFormat = true)

        // Increment top counter = rep
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, isLegacyFormat = true)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps)
    }

    @Test
    fun `legacy mode transitions from warmup to working reps`() {
        repCounter.configure(warmupTarget = 2, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, isLegacyFormat = true)

        // 2 warmup reps
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, isLegacyFormat = true)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 2, down = 1, isLegacyFormat = true)

        // 1 working rep
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 3, down = 2, isLegacyFormat = true)

        val count = repCounter.getRepCount()
        assertEquals(2, count.warmupReps)
        assertEquals(1, count.workingReps)
    }

    // ========== Pending Rep Tests ==========

    @Test
    fun `pending rep is set on up movement after warmup`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Up movement should trigger pending
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0)

        val count = repCounter.getRepCount()
        assertTrue(count.hasPendingRep)
        assertEquals(0f, count.pendingRepProgress)
    }

    @Test
    fun `pending rep is cleared on down movement`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline and go up
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0)

        // Go down
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1)

        val count = repCounter.getRepCount()
        assertFalse(count.hasPendingRep)
    }

    // ========== Position Range Tests ==========

    @Test
    fun `position ranges are tracked from rep positions`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)

        // Go up (records top position)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 800f, posB = 800f)

        // Go down (records bottom position)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        val ranges = repCounter.getRepRanges()
        assertEquals(800f, ranges.maxPosA)
        assertEquals(100f, ranges.minPosA)
    }

    @Test
    fun `hasMeaningfulRange returns true when range exceeds threshold`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges with >50mm difference
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 200f, posB = 200f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        assertTrue(repCounter.hasMeaningfulRange(minRangeThreshold = 50f))
    }

    @Test
    fun `hasMeaningfulRange returns false when range is too small`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges with <50mm difference
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 130f, posB = 130f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        assertFalse(repCounter.hasMeaningfulRange(minRangeThreshold = 50f))
    }

    @Test
    fun `isInDangerZone detects when position is near minimum`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges: min=100, max=1000 (range=900)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 1000f, posB = 1000f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        // Position at 105 is within 5% of min (threshold = 100 + 900*0.05 = 145)
        assertTrue(repCounter.isInDangerZone(posA = 105f, posB = 105f))

        // Position at 200 is above danger zone
        assertFalse(repCounter.isInDangerZone(posA = 200f, posB = 200f))
    }

    // ========== resetCountsOnly Tests ==========

    @Test
    fun `resetCountsOnly preserves position ranges`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Build up position ranges
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 800f, posB = 800f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        // Reset counts only
        repCounter.resetCountsOnly()

        // Counts should be reset
        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)

        // But ranges should be preserved
        assertTrue(repCounter.hasMeaningfulRange())
    }

    // ========== Continuous Position Tracking Tests ==========

    @Test
    fun `updatePositionRangesContinuously tracks min and max`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = true, stopAtTop = false)

        // Simulate continuous position updates during Just Lift
        repCounter.updatePositionRangesContinuously(100f, 100f)
        repCounter.updatePositionRangesContinuously(500f, 500f)
        repCounter.updatePositionRangesContinuously(200f, 200f)

        val ranges = repCounter.getRepRanges()
        assertEquals(100f, ranges.minPosA)
        assertEquals(500f, ranges.maxPosA)
    }
}

class RepRangesTest {

    @Test
    fun `isInDangerZone returns true when position is within 5 percent of min`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 1000f,
            minPosB = 100f,
            maxPosB = 1000f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        // 5% of range (900) = 45, threshold = 145
        assertTrue(ranges.isInDangerZone(posA = 110f, posB = 500f))
    }

    @Test
    fun `isInDangerZone returns false when position is safe`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 1000f,
            minPosB = 100f,
            maxPosB = 1000f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        assertFalse(ranges.isInDangerZone(posA = 500f, posB = 500f))
    }

    @Test
    fun `isInDangerZone returns false when range is too small`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 120f, // Range = 20, less than threshold
            minPosB = 100f,
            maxPosB = 120f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        // Even at min, not in danger zone because range is too small
        assertFalse(ranges.isInDangerZone(posA = 100f, posB = 100f, minRangeThreshold = 50f))
    }
}
