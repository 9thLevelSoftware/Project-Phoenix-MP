package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.ResolveRoutineScalingBaselineUseCase
import com.devil.phoenixproject.domain.usecase.RoutineScalingBaselineSource
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Real SQL coverage for issue #1383's rep-derived baseline candidate contract. */
class Issue1383EligibleRepDerivedOneRepMaxSqlIntegrationTest {
    @Test
    fun `candidate query excludes unsafe origins and returns the full window for highest estimate`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val now = currentTimeMillis()
        val mode = ProgramMode.OldSchool

        // The old query's LIMIT 200 would hide this oldest, strongest eligible set; the
        // bounded LIMIT 400 window keeps 203 realistic candidates intact (no truncation).
        repeat(201) { index ->
            val sessionId = "issue-1383-eligible-$index"
            val completedAt = now - (201 - index) * 1_000L
            val weightKg = if (index == 0) 100f else 25f
            commitCandidate(workoutRepository, sessionId, completedAt, weightKg)
        }

        commitCandidate(workoutRepository, "issue-1383-portal", now - 800L, 400f)
        database.phoenixDatabaseQueries.restoreSessionSyncMarkers(
            id = "issue-1383-portal",
            portalOrigin = 1L,
            updatedAt = now,
            acknowledged = 1L,
        )
        // Routine-session constant-load completed working sets ARE eligible: one-shot
        // Active Recovery/Heavy Deload modifiers adjust the prescription, not the actual
        // completed load recorded on the set. Kept below the strongest open-session set so
        // the full-window winner assertion stays focused.
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-id",
            now - 700L,
            30f,
            routineId = "routine-active-recovery",
        )
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-session",
            now - 600L,
            40f,
            routineSessionId = "routine-session-heavy-deload",
        )
        commitCandidate(workoutRepository, "issue-1383-safety", now - 500L, 700f, safetyFlags = 1)
        commitCandidate(workoutRepository, "issue-1383-other-profile", now - 400L, 800f, profileId = "other-profile")
        commitCandidate(workoutRepository, "issue-1383-other-exercise", now - 300L, 900f, exerciseId = "other-exercise")
        commitCandidate(workoutRepository, "issue-1383-other-mode", now - 200L, 950f, mode = ProgramMode.Pump.displayName)

        val candidates = completedSetRepository.getEligibleWorkingSetsForOneRepMax(
            exerciseId = EXERCISE_ID,
            profileId = PROFILE_ID,
            mode = mode.displayName,
            completedAtSince = now - VALIDITY_WINDOW_MS,
            completedAtUntil = now,
        )

        // 201 open-session + 2 routine-session constant-load rows; well under the LIMIT 400
        // bound, so realistic candidate sets are never truncated.
        assertEquals(203, candidates.size)
        assertTrue(candidates.any { it.id == "issue-1383-eligible-0-set" })
        assertTrue(candidates.any { it.sessionId == "issue-1383-routine-id" })
        assertTrue(candidates.any { it.sessionId == "issue-1383-routine-session" })
        assertFalse(candidates.any { it.sessionId.startsWith("issue-1383-portal") })
        assertFalse(candidates.any { it.sessionId.startsWith("issue-1383-safety") })
        assertFalse(candidates.any { it.sessionId.startsWith("issue-1383-other-") })

        val resolver = ResolveRoutineScalingBaselineUseCase(
            prRepository = FakePersonalRecordRepository(),
            baselineRepository = FakeProfileExerciseBaselineRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            completedSetRepository = completedSetRepository,
            nowMillis = { now },
        )
        val resolved = resolver(
            exerciseId = EXERCISE_ID,
            mode = mode,
            profileId = PROFILE_ID,
            basis = ScalingBasis.ESTIMATED_1RM,
        )

        assertNotNull(resolved)
        assertEquals(RoutineScalingBaselineSource.REP_DERIVED_ESTIMATE, resolved.source)
        assertEquals(100f * 36f / 27f, resolved.weightPerCableKg, 0.0001f)
        assertEquals(now - 201_000L, resolved.sourceTimestamp)
    }

    private suspend fun commitCandidate(
        repository: SqlDelightWorkoutRepository,
        sessionId: String,
        completedAt: Long,
        weightKg: Float,
        reps: Int = 10,
        profileId: String = PROFILE_ID,
        exerciseId: String = EXERCISE_ID,
        mode: String = ProgramMode.OldSchool.displayName,
        routineId: String? = null,
        routineSessionId: String? = null,
        safetyFlags: Int = 0,
    ) {
        repository.commitCompletedSet(
            session = WorkoutSession(
                id = sessionId,
                timestamp = completedAt,
                mode = mode,
                reps = reps,
                weightPerCableKg = weightKg,
                totalReps = reps,
                workingReps = reps,
                exerciseId = exerciseId,
                routineId = routineId,
                routineSessionId = routineSessionId,
                safetyFlags = safetyFlags,
                profileId = profileId,
            ),
            metrics = emptyList(),
            completedSet = CompletedSet(
                id = "$sessionId-set",
                sessionId = sessionId,
                plannedSetId = null,
                setNumber = 0,
                setType = SetType.STANDARD,
                actualReps = reps,
                actualWeightKg = weightKg,
                loggedRpe = null,
                isPr = false,
                completedAt = completedAt,
                setEndReason = SetEndReason.TARGET_REPS_REACHED,
            ),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
        )
    }

    private companion object {
        const val PROFILE_ID = "issue-1383-profile"
        const val EXERCISE_ID = "issue-1383-exercise"
        const val VALIDITY_WINDOW_MS = 28L * 24L * 60L * 60L * 1_000L
    }
}
