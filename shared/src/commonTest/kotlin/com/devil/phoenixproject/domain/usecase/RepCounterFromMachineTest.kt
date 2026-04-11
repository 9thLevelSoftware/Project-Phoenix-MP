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
            stopAtTop = false,
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

        // Establish baseline and process a rep
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        repCounter.reset()

        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== Modern Mode Rep Counting Tests (Issue #210: down counter based) ==========

    @Test
    fun `warmup reps are counted from down counter`() {
        // Issue #210: Warmup reps are now counted from down counter increments
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // First warmup rep - down counter increments from 0 to 1
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps) // From down counter
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `warmup complete event fires when warmup target reached`() {
        // Issue #210: Warmup complete fires when down counter reaches warmupTarget
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Complete 3 warmup reps via down counter
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)
        repCounter.process(repsRomCount = 2, repsSetCount = 0, up = 2, down = 2)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertTrue(count.isWarmupComplete)

        // Should have warmup complete event
        assertTrue(capturedEvents.any { it.type == RepType.WARMUP_COMPLETE })
    }

    @Test
    fun `working reps are counted from down counter after warmup`() {
        // Issue #210: Working reps = totalDownReps - warmupTarget
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Complete warmup (down = 3)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        // First working rep (down = 4, working = 4 - 3 = 1)
        repCounter.process(repsRomCount = 3, repsSetCount = 1, up = 4, down = 4)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps) // From down counter: 4 - 3 = 1
        assertEquals(1, count.totalReps) // totalReps = workingReps only
    }

    @Test
    fun `working reps come directly from repsSetCount`() {
        // Issue #210 v2: Trust the machine - working reps come from repsSetCount directly
        repCounter.configure(
            warmupTarget = 1,
            workingTarget = 5,
            isJustLift = false,
            stopAtTop = false,
        )

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Warmup complete (repsRomCount = 1)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // First working rep - repsSetCount = 1 (trust machine)
        repCounter.process(repsRomCount = 1, repsSetCount = 1, up = 2, down = 2)

        val count = repCounter.getRepCount()
        assertEquals(1, count.workingReps) // Directly from repsSetCount
    }

    @Test
    fun `down counter triggers workout completion`() {
        // Issue #210: Workout completes when down counter shows target reached
        repCounter.configure(
            warmupTarget = 0,
            workingTarget = 2,
            isJustLift = false,
            stopAtTop = false,
        )

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Two reps via down counter
        repCounter.process(repsRomCount = 0, repsSetCount = 1, up = 1, down = 1)
        repCounter.process(repsRomCount = 0, repsSetCount = 2, up = 2, down = 2)

        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `shouldStopWorkout returns true when working target reached via down counter`() {
        // Issue #210: Completion based on down counter calculation
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate 5 working reps via down counter
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `shouldStopWorkout returns false for AMRAP mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false, isAMRAP = true)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate 5 working reps
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    @Test
    fun `shouldStopWorkout returns false for Just Lift mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = true, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== stopAtTop Mode Tests ==========

    @Test
    fun `stopAtTop true counts working reps from down counter`() {
        // Issue #210: With stopAtTop=true, working reps still come from down counter
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = true)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Complete 3 warmup reps
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        // First working rep - down = 4
        repCounter.process(repsRomCount = 3, repsSetCount = 1, up = 4, down = 4)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps) // From down counter: 4 - 3 = 1
    }

    @Test
    fun `stopAtTop true completes workout when repsSetCount reaches target`() {
        // Issue #210 v2: Trust repsSetCount - workout completes when machine reports target reached
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = true)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate 4 reps via repsSetCount
        repCounter.process(repsRomCount = 0, repsSetCount = 1, up = 1, down = 1)
        repCounter.process(repsRomCount = 0, repsSetCount = 2, up = 2, down = 2)
        repCounter.process(repsRomCount = 0, repsSetCount = 3, up = 3, down = 3)
        repCounter.process(repsRomCount = 0, repsSetCount = 4, up = 4, down = 4)

        val countBefore = repCounter.getRepCount()
        assertEquals(4, countBefore.workingReps)
        assertFalse(repCounter.shouldStopWorkout())

        // Rep 5: Machine reports repsSetCount = 5
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        val count = repCounter.getRepCount()
        assertEquals(5, count.workingReps) // From repsSetCount
        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `stopAtTop true completes final rep at TOP before repsSetCount increments`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 3, isJustLift = false, stopAtTop = true)

        // Baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Rep 1 confirmed at bottom
        repCounter.process(repsRomCount = 0, repsSetCount = 1, up = 1, down = 1)
        // Rep 2 confirmed at bottom
        repCounter.process(repsRomCount = 0, repsSetCount = 2, up = 2, down = 2)

        assertFalse(repCounter.shouldStopWorkout())
        assertEquals(2, repCounter.getRepCount().workingReps)

        // Final rep reaches TOP: up increments, repsSetCount still 2 (not yet bottom-confirmed)
        repCounter.process(repsRomCount = 0, repsSetCount = 2, up = 3, down = 2)

        val count = repCounter.getRepCount()
        assertEquals(3, count.workingReps, "Final rep should be counted at TOP in stopAtTop mode")
        assertTrue(repCounter.shouldStopWorkout(), "Workout should complete at TOP when target rep is reached")
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `stopAtTop false counts reps from down counter`() {
        // Issue #210: With stopAtTop=false, working reps come from down counter
        repCounter.configure(warmupTarget = 0, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // At TOP - down hasn't incremented yet
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0)

        val countAfterTop = repCounter.getRepCount()
        assertEquals(0, countAfterTop.workingReps) // Down counter still 0

        // Rep confirmed at BOTTOM (down = 1)
        repCounter.process(repsRomCount = 0, repsSetCount = 1, up = 1, down = 1)

        val countAfterBottom = repCounter.getRepCount()
        assertEquals(1, countAfterBottom.workingReps) // From down counter
    }

    @Test
    fun `pending state shown when at TOP after warmup complete`() {
        // Issue #210 v2: Pending state shows for working reps after warmup complete
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline and complete warmup
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - should show pending for working reps
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val count = repCounter.getRepCount()
        assertTrue(count.hasPendingRep, "Should show pending state at TOP")
        // Working reps don't increment until repsSetCount does
        assertEquals(0, count.workingReps)
    }

    // ========== Issue #210: First Rep Not Registering ==========

    @Test
    fun `Issue 210 - first warmup rep registers immediately in legacy mode`() {
        // Issue #210: V-Form users reported first warmup rep doesn't register
        // ROOT CAUSE: lastTopCounter was null on first notification, so baseline was
        // established instead of counting the rep.
        // FIX: Initialize lastTopCounter to 0, so first notification with up=1 gives delta=1
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // CRITICAL: NO baseline call - machine starts workout and sends first rep immediately
        // This mimics V-Form behavior where first notification arrives with up=1
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, isLegacyFormat = true)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps, "First warmup rep should be counted immediately without baseline")
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)

        // Verify the WARMUP_COMPLETED event was fired
        assertTrue(
            capturedEvents.any { it.type == RepType.WARMUP_COMPLETED && it.warmupCount == 1 },
            "WARMUP_COMPLETED event should fire for first rep",
        )
    }

    @Test
    fun `Issue 210 - first warmup rep registers immediately in modern mode`() {
        // Same issue in modern mode - repsRomCount=1 on first notification should count
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // CRITICAL: NO baseline call - machine sends first rep data immediately
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps, "First warmup rep should be counted from repsRomCount=1")
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `Issue 210 - warmup completes at correct time without baseline`() {
        // Ensure warmup transitions to working at the right time even without explicit baseline
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Simulate V-Form sequence: 3 warmup reps without baseline call
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)
        assertEquals(1, repCounter.getRepCount().warmupReps)

        repCounter.process(repsRomCount = 2, repsSetCount = 0, up = 2, down = 2)
        assertEquals(2, repCounter.getRepCount().warmupReps)

        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)
        assertEquals(3, repCounter.getRepCount().warmupReps)
        assertTrue(repCounter.getRepCount().isWarmupComplete, "Warmup should complete after 3 reps")

        // WARMUP_COMPLETE event should have fired
        assertTrue(capturedEvents.any { it.type == RepType.WARMUP_COMPLETE })

        // First working rep
        repCounter.process(repsRomCount = 3, repsSetCount = 1, up = 4, down = 4)
        assertEquals(1, repCounter.getRepCount().workingReps, "First working rep should register")
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
    fun `pending rep is set on up movement after warmup complete`() {
        // Issue #210: Warmup complete is determined by down counter
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline and complete warmup via down counter
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Up movement should trigger pending (warmup complete via down counter)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val count = repCounter.getRepCount()
        assertTrue(count.hasPendingRep)
    }

    @Test
    fun `pending rep persists until down counter confirms rep`() {
        // Issue #210: Pending rep is cleared when down counter increments
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline and complete warmup
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - triggers pending
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val countBeforeConfirm = repCounter.getRepCount()
        assertTrue(countBeforeConfirm.hasPendingRep, "Pending rep should be set on up movement")
        assertEquals(0, countBeforeConfirm.workingReps) // Down counter still at 1
    }

    @Test
    fun `pending rep is cleared when down counter confirms rep`() {
        // Issue #210: Pending clears when down counter increments
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline and complete warmup
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - triggers pending
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        // Down counter increments, confirming the working rep
        repCounter.process(repsRomCount = 1, repsSetCount = 1, up = 2, down = 2)

        val countAfterConfirm = repCounter.getRepCount()
        assertFalse(countAfterConfirm.hasPendingRep, "Pending rep should be cleared when down counter confirms")
        assertEquals(1, countAfterConfirm.workingReps) // From down counter: 2 - 1 = 1
    }

    // ========== Issue #210: Trust repsSetCount from Machine ==========

    @Test
    fun `working reps match repsSetCount from machine`() {
        // Issue #210 v2: Trust repsSetCount directly - it's the source of truth
        repCounter.configure(warmupTarget = 3, workingTarget = 5, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Machine reports 4 working reps complete
        repCounter.process(repsRomCount = 3, repsSetCount = 4, up = 8, down = 8)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(4, count.workingReps) // Directly from repsSetCount
        assertFalse(repCounter.shouldStopWorkout()) // Not at target yet

        // Machine reports 5th rep (target reached)
        repCounter.process(repsRomCount = 3, repsSetCount = 5, up = 9, down = 9)

        val countFinal = repCounter.getRepCount()
        assertEquals(5, countFinal.workingReps)
        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `safety net triggers workout completion when repsSetCount reaches target`() {
        // Issue #210: When down counter lags but repsSetCount reaches target,
        // the safety net should trigger WORKOUT_COMPLETE, not just WORKING_COMPLETED
        repCounter.configure(warmupTarget = 3, workingTarget = 5, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate: Down counter is at 7 (showing 4 working reps: 7 - 3 = 4)
        // but repsSetCount shows 5 (final rep detected)
        // This should trigger safety net AND completion
        repCounter.process(repsRomCount = 3, repsSetCount = 5, up = 8, down = 7)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(5, count.workingReps) // Safety net syncs to repsSetCount
        assertTrue(repCounter.shouldStopWorkout(), "Safety net should trigger completion when target reached")
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE }, "WORKOUT_COMPLETE event should fire")
    }

    @Test
    fun `safety net does not trigger completion when target not reached`() {
        // Issue #210: Safety net should NOT trigger completion if synced count is still below target
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate: Down counter is at 5 (showing 2 working reps: 5 - 3 = 2)
        // repsSetCount shows 3 (safety net syncs to 3, still below target of 10)
        repCounter.process(repsRomCount = 3, repsSetCount = 3, up = 6, down = 5)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(3, count.workingReps) // Safety net syncs to repsSetCount
        assertFalse(repCounter.shouldStopWorkout(), "Should NOT stop - target not yet reached")
        assertFalse(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE }, "WORKOUT_COMPLETE should NOT fire")
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
            maxRangeB = null,
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
            maxRangeB = null,
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
            maxRangeB = null,
        )

        // Even at min, not in danger zone because range is too small
        assertFalse(ranges.isInDangerZone(posA = 100f, posB = 100f, minRangeThreshold = 50f))
    }

    @Test
    fun `isInDangerZone triggers when position goes to zero after meaningful range`() {
        // This tests the Just Lift autostop scenario where handles are placed down
        // Position goes to ~0 after user has established a meaningful ROM range
        val ranges = RepRanges(
            minPosA = 30f, // Typical overhead pulley rest position
            maxPosA = 230f, // Extended position after reps
            minPosB = 30f,
            maxPosB = 230f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null,
        )

        // Range = 200mm, threshold = 30 + (200 * 0.05) = 40mm
        // Position at 0 or very low should trigger danger zone for autostop
        assertTrue(ranges.isInDangerZone(posA = 0f, posB = 0f))
        assertTrue(ranges.isInDangerZone(posA = 5f, posB = 5f))
        assertTrue(ranges.isInDangerZone(posA = 35f, posB = 35f)) // Just above min but below threshold

        // Position at 50 should NOT trigger (above threshold of 40)
        assertFalse(ranges.isInDangerZone(posA = 50f, posB = 50f))
    }
}
