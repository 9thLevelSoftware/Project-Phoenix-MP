package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineUpdate
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class TrainingCyclesScreenTest {
    @Test
    fun `repeated continue while baseline submission pending performs one batch write`() = runTest {
        val gate = CycleCreationSubmissionGate()
        val repository = FakeProfileExerciseBaselineRepository()
        repository.seed("profile-a", "exercise-a", oneRepMaxPerCableKg = 40f)
        val firstSubmissionStarted = CompletableDeferred<Unit>()
        val secondSubmissionStarted = CompletableDeferred<Unit>()
        val releaseSubmission = CompletableDeferred<Unit>()

        suspend fun submitBaseline(oneRepMaxPerCableKg: Float, started: CompletableDeferred<Unit>) {
            val token = gate.begin()
            started.complete(Unit)
            if (token == null) return
            try {
                releaseSubmission.await()
                if (!gate.isCurrent(token)) return
                repository.setBatch(
                    profileId = "profile-a",
                    updates = listOf(
                        ProfileExerciseBaselineUpdate(
                            exerciseId = "exercise-a",
                            oneRepMaxPerCableKg = oneRepMaxPerCableKg,
                        ),
                    ),
                    updatedAt = 1L,
                )
            } finally {
                gate.finish(token)
            }
        }

        val firstSubmission = launch { submitBaseline(50f, firstSubmissionStarted) }
        firstSubmissionStarted.await()
        val secondSubmission = launch { submitBaseline(60f, secondSubmissionStarted) }
        secondSubmissionStarted.await()

        releaseSubmission.complete(Unit)
        firstSubmission.join()
        secondSubmission.join()

        val savedBaseline = requireNotNull(repository.get("profile-a", "exercise-a"))
        assertEquals(2L, savedBaseline.revision)
        assertEquals(50f, savedBaseline.oneRepMaxPerCableKg)

        val freshSubmissionToken = requireNotNull(gate.begin())
        gate.finish(freshSubmissionToken)
    }

    @Test
    fun `canceling in-flight baseline submission rejects late mode confirmation`() = runTest {
        val gate = CycleCreationSubmissionGate()
        val submissionStarted = CompletableDeferred<Unit>()
        val releaseSubmission = CompletableDeferred<Unit>()
        var showModeConfirmation = false

        val submission = launch {
            val token = requireNotNull(gate.begin())
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
