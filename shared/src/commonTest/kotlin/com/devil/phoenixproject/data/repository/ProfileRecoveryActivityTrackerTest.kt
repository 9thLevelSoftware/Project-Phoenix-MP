package com.devil.phoenixproject.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRecoveryActivityTrackerTest {
    @Test
    fun `workout and assessment independently block recovery`() {
        val tracker = ProfileRecoveryActivityTracker()
        assertFalse(tracker.isBusy())

        tracker.setWorkoutActive(true)
        assertTrue(tracker.isBusy())
        tracker.setAssessmentActive(true)
        tracker.setWorkoutActive(false)
        assertTrue(tracker.isBusy())
        tracker.setAssessmentActive(false)
        assertFalse(tracker.isBusy())
    }

    @Test
    fun `concurrent activity starts preserve both flags`() = runTest {
        repeat(1_000) {
            val tracker = ProfileRecoveryActivityTracker()
            val start = CompletableDeferred<Unit>()
            coroutineScope {
                launch(Dispatchers.Default) {
                    start.await()
                    tracker.setWorkoutActive(true)
                }
                launch(Dispatchers.Default) {
                    start.await()
                    tracker.setAssessmentActive(true)
                }
                start.complete(Unit)
            }
            assertTrue(tracker.activity.value.workoutActive)
            assertTrue(tracker.activity.value.assessmentActive)
        }
    }

    @Test
    fun `recovery reservation atomically rejects new activity starts`() {
        val tracker = ProfileRecoveryActivityTracker()

        assertTrue(tracker.tryReserveRecovery())
        assertFalse(tracker.setWorkoutActive(true))
        assertFalse(tracker.setAssessmentActive(true))
        assertFalse(tracker.activity.value.workoutActive)
        assertFalse(tracker.activity.value.assessmentActive)

        tracker.releaseRecoveryReservation()
        assertTrue(tracker.setWorkoutActive(true))
        assertFalse(tracker.tryReserveRecovery())
    }
}
