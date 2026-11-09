package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
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
    fun `candidate query excludes unsafe origins and preserves the strongest candidate`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val now = currentTimeMillis()
        val mode = ProgramMode.OldSchool

        val routineId = "issue-1383-routine-plan"
        val activeRecoveryExerciseId = "issue-1383-active-recovery-exercise"
        val heavyDeloadExerciseId = "issue-1383-heavy-deload-exercise"
        val unchangedRoutineExerciseId = "issue-1383-unchanged-routine-exercise"
        val routineExercises = listOf(
            RoutineExercise(
                id = activeRecoveryExerciseId,
                exercise = Exercise(name = "Bench Press", muscleGroup = "Chest"),
                orderIndex = 0,
                setReps = listOf(10),
                weightPerCableKg = 500f,
                setWeightsPerCableKg = listOf(500f),
            ),
            RoutineExercise(
                id = heavyDeloadExerciseId,
                exercise = Exercise(name = "Bench Press", muscleGroup = "Chest"),
                orderIndex = 1,
                setReps = listOf(10),
                weightPerCableKg = 600f,
                setWeightsPerCableKg = listOf(600f),
            ),
            RoutineExercise(
                id = unchangedRoutineExerciseId,
                exercise = Exercise(name = "Bench Press", muscleGroup = "Chest"),
                orderIndex = 2,
                setReps = listOf(10),
                weightPerCableKg = 80f,
                setWeightsPerCableKg = listOf(80f),
            ),
        )
        workoutRepository.saveRoutine(
            Routine(
                id = routineId,
                name = "Issue 1383 candidate fixture",
                exercises = routineExercises,
                profileId = PROFILE_ID,
            ),
        )
        listOf(
            PlannedSet.standard(
                id = "issue-1383-active-recovery-plan",
                routineExerciseId = activeRecoveryExerciseId,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 500f,
            ),
            PlannedSet.standard(
                id = "issue-1383-heavy-deload-plan",
                routineExerciseId = heavyDeloadExerciseId,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 600f,
            ),
            PlannedSet.standard(
                id = "issue-1383-unchanged-routine-plan",
                routineExerciseId = unchangedRoutineExerciseId,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 80f,
            ),
        ).forEach { completedSetRepository.savePlannedSet(it) }

        // The old query's LIMIT 200 would hide this oldest, strongest eligible set; this
        // fixture stays below the current 400-candidate cap while preserving that set.
        repeat(201) { index ->
            val sessionId = "issue-1383-eligible-$index"
            val completedAt = now - (201 - index) * 1_000L
            val weightKg = if (index == 0) 100f else 25f
            commitCandidate(workoutRepository, sessionId, completedAt, weightKg)
        }
        commitCandidate(workoutRepository, "issue-1383-too-many-reps", now - 750L, 1_000f, reps = 50)

        commitCandidate(workoutRepository, "issue-1383-portal", now - 800L, 400f)
        database.phoenixDatabaseQueries.restoreSessionSyncMarkers(
            id = "issue-1383-portal",
            portalOrigin = 1L,
            updatedAt = now,
            acknowledged = 1L,
        )
        // Modifier-adjusted routine completions do not match the persisted plan, while an
        // unchanged planned load/reps pair remains eligible.
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-active-recovery",
            now - 700L,
            400f,
            routineId = routineId,
            routineSessionId = "issue-1383-session-active-recovery",
            plannedSetId = "issue-1383-active-recovery-plan",
            routineExerciseId = activeRecoveryExerciseId,
            routineModifierApplied = true,
        )
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-heavy-deload",
            now - 600L,
            600f,
            reps = 5,
            routineId = routineId,
            routineSessionId = "issue-1383-session-heavy-deload",
            plannedSetId = "issue-1383-heavy-deload-plan",
            routineExerciseId = heavyDeloadExerciseId,
            routineModifierApplied = true,
        )
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-unchanged",
            now - 550L,
            80f,
            routineId = routineId,
            routineSessionId = "issue-1383-session-unchanged",
            plannedSetId = "issue-1383-unchanged-routine-plan",
            routineExerciseId = unchangedRoutineExerciseId,
        )
        commitCandidate(
            workoutRepository,
            "issue-1383-routine-future-retry",
            now + 1_000L,
            80f,
            routineId = routineId,
            routineSessionId = "issue-1383-session-unchanged",
            plannedSetId = "issue-1383-unchanged-routine-plan",
            routineExerciseId = unchangedRoutineExerciseId,
            attemptNumber = 2,
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

        assertEquals(202, candidates.size)
        assertTrue(candidates.any { it.id == "issue-1383-eligible-0-set" })
        assertTrue(candidates.any { it.sessionId == "issue-1383-routine-unchanged" })
        assertFalse(candidates.any { it.sessionId == "issue-1383-routine-active-recovery" })
        assertFalse(candidates.any { it.sessionId == "issue-1383-routine-heavy-deload" })
        assertFalse(candidates.any { it.sessionId == "issue-1383-routine-future-retry" })
        assertFalse(candidates.any { it.sessionId == "issue-1383-too-many-reps" })
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

    @Test
    fun `candidate query bounds to the newest 400 results`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val now = currentTimeMillis()

        repeat(401) { index ->
            commitCandidate(
                repository = workoutRepository,
                sessionId = "issue-1383-window-$index",
                completedAt = now - (401 - index) * 1_000L,
                weightKg = 25f,
            )
        }

        val candidates = completedSetRepository.getEligibleWorkingSetsForOneRepMax(
            exerciseId = EXERCISE_ID,
            profileId = PROFILE_ID,
            mode = ProgramMode.OldSchool.displayName,
            completedAtSince = now - VALIDITY_WINDOW_MS,
            completedAtUntil = now,
        )

        assertEquals(400, candidates.size)
        assertEquals("issue-1383-window-400", candidates.first().sessionId)
        assertEquals("issue-1383-window-1", candidates.last().sessionId)
        assertFalse(candidates.any { it.sessionId == "issue-1383-window-0" })
    }

    @Test
    fun `history and 1RM queries exclude older routine attempts`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val now = currentTimeMillis()
        val routineId = "issue-1383-retry-routine"
        val routineSessionId = "issue-1383-retry-routine-session"
        val routineExerciseId = "issue-1383-retry-exercise"
        val plannedSetId = "issue-1383-retry-planned-set"
        workoutRepository.saveRoutine(
            Routine(
                id = routineId,
                name = "Issue 1383 retry fixture",
                exercises = listOf(
                    RoutineExercise(
                        id = routineExerciseId,
                        exercise = Exercise(name = "Bench Press", muscleGroup = "Chest"),
                        orderIndex = 0,
                        setReps = listOf(10),
                        weightPerCableKg = 40f,
                        setWeightsPerCableKg = listOf(40f),
                    ),
                ),
                profileId = PROFILE_ID,
            ),
        )
        completedSetRepository.savePlannedSet(
            PlannedSet.standard(
                id = plannedSetId,
                routineExerciseId = routineExerciseId,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 40f,
            ),
        )

        commitCandidate(
            repository = workoutRepository,
            sessionId = "issue-1383-retry-old",
            completedAt = now - 2_000L,
            weightKg = 40f,
            routineId = routineId,
            routineSessionId = routineSessionId,
            plannedSetId = plannedSetId,
            routineExerciseId = routineExerciseId,
            attemptNumber = 1,
        )
        commitCandidate(
            repository = workoutRepository,
            sessionId = "issue-1383-retry-new",
            completedAt = now - 1_000L,
            weightKg = 40f,
            routineId = routineId,
            routineSessionId = routineSessionId,
            plannedSetId = plannedSetId,
            routineExerciseId = routineExerciseId,
            attemptNumber = 2,
        )

        val eligible = completedSetRepository.getEligibleWorkingSetsForOneRepMax(
            exerciseId = EXERCISE_ID,
            profileId = PROFILE_ID,
            mode = ProgramMode.OldSchool.displayName,
            completedAtSince = now - VALIDITY_WINDOW_MS,
            completedAtUntil = now,
        )
        val recent = completedSetRepository.getRecentCompletedSetsForExercise(
            exerciseId = EXERCISE_ID,
            profileId = PROFILE_ID,
            limit = 10,
        )

        assertEquals(listOf("issue-1383-retry-new-set"), eligible.map { it.id })
        assertEquals(listOf("issue-1383-retry-new-set"), recent.map { it.id })
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
        plannedSetId: String? = null,
        routineExerciseId: String? = null,
        routineModifierApplied: Boolean = false,
        attemptNumber: Int = 1,
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
                routineModifierApplied = routineModifierApplied,
                safetyFlags = safetyFlags,
                profileId = profileId,
            ),
            metrics = emptyList(),
            completedSet = CompletedSet(
                id = "$sessionId-set",
                sessionId = sessionId,
                plannedSetId = plannedSetId,
                routineExerciseId = routineExerciseId,
                setNumber = 0,
                setType = SetType.STANDARD,
                attemptNumber = attemptNumber,
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
