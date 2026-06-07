package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeIntegrationSyncCursorRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HealthBackfillManagerTest {

    @Test
    fun chunksCompletedSetLookupsAndDoesNotFailWhenOneWorkoutFails() = runTest {
        val workoutRepository = FakeWorkoutRepository()
        val completedSetRepository = CountingCompletedSetRepository()
        val cursorRepository = FakeIntegrationSyncCursorRepository()
        val writer = FakeHealthWorkoutWriter(failExternalIds = setOf("phoenix:session:session-2"))

        repeat(1_001) { index ->
            val session = workoutSession(id = "session-$index", timestamp = index * 10_000L)
            workoutRepository.addSession(session)
            completedSetRepository.saveCompletedSet(
                completedSet(
                    sessionId = session.id,
                    completedAt = session.timestamp + session.duration,
                ),
            )
        }

        val result = HealthBackfillManager(
            workoutRepository = workoutRepository,
            completedSetRepository = completedSetRepository,
            cursorRepository = cursorRepository,
            healthWriter = writer,
        ).syncPreviousWorkouts(
            provider = IntegrationProvider.GOOGLE_HEALTH,
            profileId = "default",
        )

        assertTrue(result.isSuccess)
        assertEquals(1_001, result.getOrThrow().eligibleWorkouts)
        assertEquals(1_000, result.getOrThrow().writtenWorkouts)
        assertEquals(1, result.getOrThrow().skippedWorkouts)
        assertEquals(listOf(500, 500, 1), completedSetRepository.requestedBatchSizes)
    }

    @Test
    fun skipsWorkoutsAlreadyMarkedAsExported() = runTest {
        val workoutRepository = FakeWorkoutRepository()
        val completedSetRepository = CountingCompletedSetRepository()
        val cursorRepository = FakeIntegrationSyncCursorRepository()
        val writer = FakeHealthWorkoutWriter()
        val session = workoutSession(id = "already-exported")
        workoutRepository.addSession(session)
        completedSetRepository.saveCompletedSet(
            completedSet(
                sessionId = session.id,
                completedAt = session.timestamp + session.duration,
            ),
        )

        HealthExportMarkers.markExported(
            cursorRepository = cursorRepository,
            provider = IntegrationProvider.APPLE_HEALTH,
            profileId = "default",
            externalId = "phoenix:session:${session.id}",
        )

        val result = HealthBackfillManager(
            workoutRepository = workoutRepository,
            completedSetRepository = completedSetRepository,
            cursorRepository = cursorRepository,
            healthWriter = writer,
        ).syncPreviousWorkouts(
            provider = IntegrationProvider.APPLE_HEALTH,
            profileId = "default",
        )

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().eligibleWorkouts)
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun failsBackfillOnlyWhenAllWritesFail() = runTest {
        val workoutRepository = FakeWorkoutRepository()
        val completedSetRepository = CountingCompletedSetRepository()
        val cursorRepository = FakeIntegrationSyncCursorRepository()
        val writer = FakeHealthWorkoutWriter(failAll = true)
        val session = workoutSession(id = "failed")
        workoutRepository.addSession(session)
        completedSetRepository.saveCompletedSet(
            completedSet(
                sessionId = session.id,
                completedAt = session.timestamp + session.duration,
            ),
        )

        val result = HealthBackfillManager(
            workoutRepository = workoutRepository,
            completedSetRepository = completedSetRepository,
            cursorRepository = cursorRepository,
            healthWriter = writer,
        ).syncPreviousWorkouts(
            provider = IntegrationProvider.GOOGLE_HEALTH,
            profileId = "default",
        )

        assertFalse(result.isSuccess)
    }

    private class CountingCompletedSetRepository : FakeCompletedSetRepository() {
        val requestedBatchSizes = mutableListOf<Int>()

        override suspend fun getCompletedSetsForSessions(sessionIds: List<String>): List<CompletedSet> {
            requestedBatchSizes += sessionIds.size
            return super.getCompletedSetsForSessions(sessionIds)
        }
    }

    private class FakeHealthWorkoutWriter(
        private val failExternalIds: Set<String> = emptySet(),
        private val failAll: Boolean = false,
    ) : HealthWorkoutWriter {
        val writes = mutableListOf<HealthWorkoutData>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun hasPermissions(): Boolean = true

        override suspend fun writeHealthWorkout(data: HealthWorkoutData): Result<Unit> {
            writes += data
            return if (failAll || data.externalId in failExternalIds) {
                Result.failure(IllegalStateException("write failed for ${data.externalId}"))
            } else {
                Result.success(Unit)
            }
        }
    }

    private fun workoutSession(id: String, timestamp: Long = 10_000L) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        duration = 5_000L,
        exerciseId = "exercise",
        exerciseName = "Cable Row",
        workingReps = 8,
        weightPerCableKg = 20f,
    )

    private fun completedSet(sessionId: String, completedAt: Long) = CompletedSet(
        id = "set-$sessionId",
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = 0,
        setType = SetType.STANDARD,
        actualReps = 8,
        actualWeightKg = 20f,
        loggedRpe = null,
        isPr = false,
        completedAt = completedAt,
    )
}
