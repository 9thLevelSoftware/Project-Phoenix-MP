package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ungrouped history cards (Just Lift and single-exercise) are one saved set each.
 * The card must not derive the set count by dividing working reps by the target.
 */
class SingleSessionHistorySetCountTest {

    @Test
    fun `partial set still counts as one`() {
        assertEquals(1, singleSessionHistorySetCount(reps = 10, workingReps = 5))
    }

    @Test
    fun `just lift past the default target of 10 still counts as one`() {
        assertEquals(1, singleSessionHistorySetCount(reps = 10, workingReps = 21))
    }

    @Test
    fun `exact target counts as one`() {
        assertEquals(1, singleSessionHistorySetCount(reps = 10, workingReps = 10))
    }

    @Test
    fun `amrap with working reps counts as one`() {
        assertEquals(1, singleSessionHistorySetCount(reps = 0, workingReps = 15))
    }

    @Test
    fun `amrap with no working reps still counts as one set`() {
        assertEquals(1, singleSessionHistorySetCount(reps = 0, workingReps = 0))
    }

    @Test
    fun `no working reps on a targeted set counts as zero`() {
        assertEquals(0, singleSessionHistorySetCount(reps = 10, workingReps = 0))
    }
}
