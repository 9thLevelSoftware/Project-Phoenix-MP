package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ResolveCurrentOneRepMaxUseCaseTest {
    private val velocity = FakeVelocityOneRepMaxRepository()
    private val assessments = FakeAssessmentRepository()
    private val workouts = FakeWorkoutRepository()
    private val resolver = ResolveCurrentOneRepMaxUseCase(velocity, assessments, workouts)

    @Test
    fun `velocity wins over assessment and session`() = runTest {
        seedAssessment(totalKg = 120f)
        seedSession(perCableKg = 50f, reps = 5)
        velocity.latestPassing = VelocityOneRepMaxEntity(
            id = 1L,
            exerciseId = "bench",
            estimatedPerCableKg = 70f,
            mvtUsedMs = 0.3f,
            r2 = 0.95f,
            distinctLoads = 3,
            passedQualityGate = true,
            computedAt = 30L,
            profileId = "athlete-a",
        )

        assertEquals(
            CurrentOneRepMax(70f, CurrentOneRepMaxSource.VELOCITY, 30L),
            resolver("bench", "athlete-a"),
        )
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `assessment override total is normalized to per cable`() = runTest {
        assessments.saveAssessment(
            exerciseId = "bench",
            estimatedOneRepMaxKg = 120f,
            loadVelocityDataJson = "[]",
            sessionId = null,
            userOverrideKg = 140f,
            profileId = "athlete-a",
        )

        assertEquals(70f, resolver("bench", "athlete-a")?.perCableKg)
        assertEquals(CurrentOneRepMaxSource.ASSESSMENT, resolver("bench", "athlete-a")?.source)
    }

    @Test
    fun `invalid assessment override falls through to its valid estimate`() = runTest {
        seedAssessment(totalKg = 120f, overrideKg = Float.NaN)

        assertEquals(
            CurrentOneRepMax(60f, CurrentOneRepMaxSource.ASSESSMENT, 1L),
            resolver("bench", "athlete-a"),
        )
    }

    @Test
    fun `invalid velocity falls through to valid assessment`() = runTest {
        seedAssessment(totalKg = 120f)
        velocity.latestPassing = velocityEstimate(
            perCableKg = Float.POSITIVE_INFINITY,
            profileId = "athlete-a",
            exerciseId = "bench",
        )

        assertEquals(CurrentOneRepMaxSource.ASSESSMENT, resolver("bench", "athlete-a")?.source)
        assertEquals(60f, resolver("bench", "athlete-a")?.perCableKg)
    }

    @Test
    fun `session fallback uses canonical hybrid and never another profile`() = runTest {
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-b", timestamp = 40L)
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-a", timestamp = 20L)

        val result = resolver("bench", "athlete-a")

        assertEquals(112.5f, result?.perCableKg)
        assertEquals(CurrentOneRepMaxSource.SESSION, result?.source)
        assertEquals(20L, result?.measuredAt)
    }

    @Test
    fun `session fallback skips newest invalid estimate and uses newest valid bounded row`() = runTest {
        seedSession(perCableKg = 100f, reps = 5, timestamp = 20L)
        seedSession(perCableKg = Float.NaN, reps = 5, timestamp = 30L)

        val result = resolver("bench", "athlete-a")

        assertEquals(112.5f, result?.perCableKg)
        assertEquals(20L, result?.measuredAt)
        assertEquals(
            FakeWorkoutRepository.RecentCompletedRequest(
                exerciseId = "bench",
                profileId = "athlete-a",
                limit = MAX_RECENT_EXERCISE_SESSIONS,
            ),
            workouts.recentCompletedRequests.single(),
        )
    }

    @Test
    fun `session helper uses total reps fallback and rejects invalid load or reps`() {
        val base = session(perCableKg = 100f, workingReps = 0, totalReps = 5)

        assertEquals(112.5f, base.estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(weightPerCableKg = Float.NaN).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(weightPerCableKg = 0f).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(workingReps = 0, totalReps = 0).estimatedOneRepMaxPerCableOrNull())
        assertNull(base.copy(workingReps = -1, totalReps = -1).estimatedOneRepMaxPerCableOrNull())
    }

    @Test
    fun `wrong profile and exercise at higher sources cannot block current session`() = runTest {
        velocity.latestPassing = velocityEstimate(
            perCableKg = 200f,
            profileId = "athlete-b",
            exerciseId = "squat",
        )
        seedAssessment(
            totalKg = 400f,
            profileId = "athlete-b",
            exerciseId = "squat",
        )
        seedSession(perCableKg = 100f, reps = 5, profileId = "athlete-a", timestamp = 20L)

        assertEquals(CurrentOneRepMaxSource.SESSION, resolver("bench", "athlete-a")?.source)
    }

    @Test
    fun `only five newest sessions are inspected and all invalid sources return null`() = runTest {
        velocity.latestPassing = velocityEstimate(perCableKg = Float.NaN)
        seedAssessment(totalKg = -1f, overrideKg = Float.POSITIVE_INFINITY)
        seedSession(perCableKg = 100f, reps = 5, timestamp = 1L)
        repeat(MAX_RECENT_EXERCISE_SESSIONS) { index ->
            seedSession(perCableKg = Float.NaN, reps = 5, timestamp = 10L + index)
        }

        assertNull(resolver("bench", "athlete-a"))
        assertEquals(MAX_RECENT_EXERCISE_SESSIONS, workouts.recentCompletedRequests.single().limit)
    }

    @Test
    fun `blank IDs fail before reading any source`() = runTest {
        assertFailsWith<IllegalArgumentException> { resolver(" ", "athlete-a") }
        assertFailsWith<IllegalArgumentException> { resolver("bench", " ") }
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `fake bounded read validation mirrors production`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            workouts.getRecentCompletedSessionsForExercise(" ", "athlete-a", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            workouts.getRecentCompletedSessionsForExercise("bench", " ", 1)
        }
        listOf(0, MAX_RECENT_EXERCISE_SESSIONS + 1).forEach { limit ->
            assertFailsWith<IllegalArgumentException> {
                workouts.getRecentCompletedSessionsForExercise("bench", "athlete-a", limit)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            workouts.getMostRecentCompletedExerciseId(" ")
        }
        assertEquals(emptyList(), workouts.recentCompletedRequests)
    }

    @Test
    fun `ordinary higher source failure propagates instead of selecting a lower source`() = runTest {
        seedAssessment(totalKg = 120f)
        velocity.latestPassingFailure = IllegalStateException("velocity unavailable")

        val thrown = assertFailsWith<IllegalStateException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("velocity unavailable", thrown.message)
    }

    @Test
    fun `cancellation from any source propagates`() = runTest {
        assessments.latestAssessmentFailure = CancellationException("profile changed")

        val thrown = assertFailsWith<CancellationException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("profile changed", thrown.message)
    }

    @Test
    fun `session read failure propagates when higher sources are absent`() = runTest {
        workouts.recentCompletedFailure = IllegalStateException("history unavailable")

        val thrown = assertFailsWith<IllegalStateException> {
            resolver("bench", "athlete-a")
        }
        assertEquals("history unavailable", thrown.message)
    }

    private suspend fun seedAssessment(
        totalKg: Float,
        overrideKg: Float? = null,
        exerciseId: String = "bench",
        profileId: String = "athlete-a",
    ) {
        assessments.saveAssessment(
            exerciseId,
            totalKg,
            "[]",
            null,
            overrideKg,
            profileId,
        )
    }

    private fun seedSession(
        perCableKg: Float,
        reps: Int,
        profileId: String = "athlete-a",
        timestamp: Long = 10L,
    ) {
        workouts.addSession(
            session(
                perCableKg = perCableKg,
                workingReps = reps,
                totalReps = reps,
                profileId = profileId,
                timestamp = timestamp,
            ),
        )
    }

    private fun session(
        perCableKg: Float,
        workingReps: Int,
        totalReps: Int,
        profileId: String = "athlete-a",
        exerciseId: String = "bench",
        timestamp: Long = 10L,
    ) = WorkoutSession(
        id = "$profileId-$exerciseId-$timestamp-$perCableKg",
        timestamp = timestamp,
        mode = "OldSchool",
        reps = totalReps,
        weightPerCableKg = perCableKg,
        duration = 1_000L,
        totalReps = totalReps,
        workingReps = workingReps,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        profileId = profileId,
    )

    private fun velocityEstimate(
        perCableKg: Float,
        profileId: String = "athlete-a",
        exerciseId: String = "bench",
    ) = VelocityOneRepMaxEntity(
        id = 1L,
        exerciseId = exerciseId,
        estimatedPerCableKg = perCableKg,
        mvtUsedMs = 0.3f,
        r2 = 0.95f,
        distinctLoads = 3,
        passedQualityGate = true,
        computedAt = 30L,
        profileId = profileId,
    )
}
