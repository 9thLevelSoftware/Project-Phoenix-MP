package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ProgressionReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeProgressionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionUseCaseTest {

    private lateinit var completedSetRepository: FakeCompletedSetRepository
    private lateinit var progressionRepository: FakeProgressionRepository
    private lateinit var useCase: ProgressionUseCase

    @BeforeTest
    fun setup() {
        completedSetRepository = FakeCompletedSetRepository()
        progressionRepository = FakeProgressionRepository()
        useCase = ProgressionUseCase(completedSetRepository, progressionRepository)
    }

    @Test
    fun `returns null when pending progression already exists`() = runTest {
        val pending = com.devil.phoenixproject.domain.model.ProgressionEvent.create(
            exerciseId = "bench",
            previousWeightKg = 50f,
            reason = ProgressionReason.REPS_ACHIEVED
        )
        progressionRepository.createProgressionSuggestion(pending)

        val result = useCase.checkForProgression(exerciseId = "bench", targetReps = 10)

        assertNull(result)
    }

    @Test
    fun `returns null when not enough history`() = runTest {
        val now = 1_000_000_000L
        completedSetRepository.setSessionExercise("session-1", "bench")
        completedSetRepository.saveCompletedSet(
            completedSet(
                id = "set-1",
                sessionId = "session-1",
                reps = 10,
                weight = 50f,
                rpe = 8,
                completedAt = now
            )
        )

        val result = useCase.checkForProgression(exerciseId = "bench", targetReps = 10)

        assertNull(result)
    }

    @Test
    fun `suggests progression for low RPE`() = runTest {
        val now = 1_000_000_000L
        completedSetRepository.setSessionExercise("session-1", "bench")
        completedSetRepository.saveCompletedSets(
            listOf(
                completedSet("set-1", "session-1", reps = 10, weight = 50f, rpe = 5, completedAt = now),
                completedSet("set-2", "session-1", reps = 10, weight = 50f, rpe = 6, completedAt = now - 1000),
                completedSet("set-3", "session-1", reps = 10, weight = 50f, rpe = 6, completedAt = now - 2000)
            )
        )

        val result = useCase.checkForProgression(exerciseId = "bench", targetReps = 10)

        assertEquals(ProgressionReason.LOW_RPE, result?.reason)
    }

    @Test
    fun `suggests progression when target reps achieved across sessions`() = runTest {
        val now = 1_000_000_000L
        completedSetRepository.setSessionExercise("session-1", "bench")
        completedSetRepository.setSessionExercise("session-2", "bench")

        // Use null RPE to skip RPE-based progression check and test rep-based only
        completedSetRepository.saveCompletedSets(
            listOf(
                completedSet("set-1", "session-1", reps = 10, weight = 40f, rpe = null, completedAt = now),
                completedSet("set-2", "session-1", reps = 10, weight = 40f, rpe = null, completedAt = now - 500),
                completedSet(
                    id = "set-3",
                    sessionId = "session-2",
                    reps = 10,
                    weight = 40f,
                    rpe = null,
                    completedAt = now - (3 * 60 * 60 * 1000L)
                )
            )
        )

        val result = useCase.checkForProgression(exerciseId = "bench", targetReps = 10)

        assertEquals(ProgressionReason.REPS_ACHIEVED, result?.reason)
        assertTrue(result?.isPending() == true)
    }

    private fun completedSet(
        id: String,
        sessionId: String,
        reps: Int,
        weight: Float,
        rpe: Int?,
        completedAt: Long
    ): CompletedSet {
        return CompletedSet(
            id = id,
            sessionId = sessionId,
            plannedSetId = null,
            setNumber = 1,
            setType = SetType.STANDARD,
            actualReps = reps,
            actualWeightKg = weight,
            loggedRpe = rpe,
            isPr = false,
            completedAt = completedAt
        )
    }
}
