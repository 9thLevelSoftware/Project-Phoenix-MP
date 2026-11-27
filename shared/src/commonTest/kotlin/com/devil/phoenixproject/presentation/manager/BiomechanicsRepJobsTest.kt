package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BiomechanicsRepJobsTest {
    @Test
    fun `earlier rep completion preserves registered lazy final rep until snapshot can include it`() = runTest {
        val jobs = BiomechanicsRepJobs()
        val earlierRep = CompletableDeferred<Unit>()
        jobs.track(earlierRep)
        val finalRepGate = CompletableDeferred<Unit>()
        var finalRepPublished = false
        val finalRep = launch(start = CoroutineStart.LAZY) {
            finalRepGate.await()
            finalRepPublished = true
        }
        jobs.track(finalRep)

        // The previous job can finish between registration and start of the final rep.
        earlierRep.complete(Unit)
        assertFalse(finalRep.isActive)
        assertTrue(jobs.hasUnfinishedJobs())
        var snapshotPublished = false
        val snapshot = launch {
            jobs.awaitCompletion()
            assertTrue(finalRepPublished)
            snapshotPublished = true
        }
        runCurrent()
        assertFalse(snapshotPublished)

        finalRepGate.complete(Unit)
        snapshot.join()
        assertTrue(snapshotPublished)
        assertFalse(jobs.hasUnfinishedJobs())
    }

    @Test
    fun `cancelled lazy rep releases completion wait`() = runTest {
        val jobs = BiomechanicsRepJobs()
        val rep = launch(start = CoroutineStart.LAZY) { error("Cancelled rep must never run") }
        jobs.track(rep)
        rep.cancel()
        jobs.awaitCompletion()
        assertFalse(jobs.hasUnfinishedJobs())
    }
}
