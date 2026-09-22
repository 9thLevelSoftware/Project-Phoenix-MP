package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class TrainingCyclesScreenTest {
    @Test
    fun `canceling in-flight baseline submission rejects late mode confirmation`() = runTest {
        val gate = CycleCreationSubmissionGate()
        val submissionStarted = CompletableDeferred<Unit>()
        val releaseSubmission = CompletableDeferred<Unit>()
        var showModeConfirmation = false

        val submission = launch {
            val token = gate.begin()
            submissionStarted.complete(Unit)
            withContext(NonCancellable) {
                releaseSubmission.await()
            }
            if (gate.isCurrent(token)) {
                showModeConfirmation = true
            }
        }

        submissionStarted.await()
        gate.cancel()
        submission.cancel()
        releaseSubmission.complete(Unit)
        submission.join()

        assertFalse(showModeConfirmation)
    }
}
