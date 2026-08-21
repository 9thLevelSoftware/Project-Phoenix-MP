package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightCompletedSetRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightCompletedSetRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightCompletedSetRepository(database)
        insertRoutine("routine-1")
        insertRoutineExercise("exercise-1", "routine-1", "Bench Press")
        insertWorkoutSession("session-1", "bench")
    }

    @Test
    fun `savePlannedSet and getPlannedSets returns ordered sets`() = runTest {
        repository.savePlannedSet(plannedSet("set-1", "exercise-1", 2))
        repository.savePlannedSet(plannedSet("set-2", "exercise-1", 1))

        val planned = repository.getPlannedSets("exercise-1")

        assertEquals(2, planned.size)
        assertEquals(1, planned.first().setNumber)
    }

    @Test
    fun `updatePlannedSet updates fields`() = runTest {
        val set = plannedSet("set-1", "exercise-1", 1, targetReps = 8)
        repository.savePlannedSet(set)

        repository.updatePlannedSet(set.copy(targetReps = 10, restSeconds = 90))

        val updated = repository.getPlannedSets("exercise-1").first()
        assertEquals(10, updated.targetReps)
        assertEquals(90, updated.restSeconds)
    }

    @Test
    fun `saveCompletedSet updates RPE and PR flags`() = runTest {
        val completed = completedSet("cset-1", "session-1", setNumber = 1)
        repository.saveCompletedSet(completed)

        repository.updateRpe("cset-1", 8)
        repository.markAsPr("cset-1")

        val updated = repository.getCompletedSets("session-1").first()
        assertEquals(8, updated.loggedRpe)
        assertTrue(updated.isPr)
    }

    @Test
    fun `getCompletedSetsForExercise filters by session exercise`() = runTest {
        repository.saveCompletedSet(completedSet("cset-1", "session-1", setNumber = 1))
        repository.saveCompletedSet(completedSet("cset-2", "session-1", setNumber = 2))

        val sets = repository.getCompletedSetsForExercise("bench")

        assertEquals(2, sets.size)
    }

    @Test
    fun `saveCompletedSet round-trips setEndReason STALL_FAILURE`() = runTest {
        val completed = completedSet("cset-stall", "session-1", setNumber = 1, setEndReason = SetEndReason.STALL_FAILURE)
        repository.saveCompletedSet(completed)

        val loaded = repository.getCompletedSets("session-1").first { it.id == "cset-stall" }
        assertEquals(SetEndReason.STALL_FAILURE, loaded.setEndReason)
    }

    @Test
    fun `saveCompletedSet round-trips setEndReason TARGET_REPS_REACHED default`() = runTest {
        val completed = completedSet("cset-default", "session-1", setNumber = 1)
        repository.saveCompletedSet(completed)

        val loaded = repository.getCompletedSets("session-1").first { it.id == "cset-default" }
        assertEquals(SetEndReason.TARGET_REPS_REACHED, loaded.setEndReason)
    }

    @Test
    fun `saveCompletedSet round-trips all SetEndReason values`() = runTest {
        for ((index, reason) in SetEndReason.entries.withIndex()) {
            repository.saveCompletedSet(completedSet("cset-$index", "session-1", setNumber = index + 1, setEndReason = reason))
        }

        val loaded = repository.getCompletedSets("session-1")
        assertEquals(SetEndReason.entries.size, loaded.size)
        for ((index, reason) in SetEndReason.entries.withIndex()) {
            assertEquals(reason, loaded[index].setEndReason, "Mismatch at index $index")
        }
    }

    @Test
    fun `saveCompletedSets preserves distinct non-default end reasons`() = runTest {
        repository.saveCompletedSets(
            listOf(
                completedSet("cset-bulk-stall", "session-1", setNumber = 1, setEndReason = SetEndReason.STALL_FAILURE),
                completedSet("cset-bulk-timer", "session-1", setNumber = 2, setEndReason = SetEndReason.TIMER_EXPIRED),
            ),
        )

        val reasonsById = repository.getCompletedSets("session-1").associate { it.id to it.setEndReason }
        assertEquals(SetEndReason.STALL_FAILURE, reasonsById["cset-bulk-stall"])
        assertEquals(SetEndReason.TIMER_EXPIRED, reasonsById["cset-bulk-timer"])
    }

    @Test
    fun `single and bulk saves round-trip routine occurrence and attempts without changing zero-based set index`() = runTest {
        val key = LogicalSetKey(
            routineSessionId = "routine-session-cross-model",
            routineExerciseId = "exercise-1",
            setIndex = 4,
            setKind = SetType.AMRAP,
        )
        repository.saveCompletedSet(
            completedSet(
                id = "cset-single-attempt-2",
                sessionId = "session-1",
                setNumber = key.setIndex,
                setType = key.setKind,
                routineExerciseId = key.routineExerciseId,
                attemptNumber = 2,
            ),
        )
        repository.saveCompletedSets(
            listOf(
                completedSet("cset-bulk-attempt-1", "session-1", key.setIndex, setType = key.setKind, routineExerciseId = key.routineExerciseId, attemptNumber = 1),
                completedSet("cset-bulk-attempt-3", "session-1", key.setIndex, setType = key.setKind, routineExerciseId = key.routineExerciseId, attemptNumber = 3),
                completedSet("cset-bulk-legacy", "session-1", 7),
            ),
        )

        val byId = repository.getCompletedSets("session-1").associateBy { it.id }
        val attempts = listOf(
            byId.getValue("cset-bulk-attempt-1"),
            byId.getValue("cset-single-attempt-2"),
            byId.getValue("cset-bulk-attempt-3"),
        )
        assertEquals(listOf(1, 2, 3), attempts.map { it.attemptNumber })
        attempts.forEach { completedSet ->
            assertEquals(key.routineExerciseId, completedSet.routineExerciseId)
            assertEquals(key.setIndex, completedSet.setNumber)
            assertEquals(key.setKind, completedSet.setType)
        }
        assertEquals(null, byId.getValue("cset-bulk-legacy").routineExerciseId)
        assertEquals(1, byId.getValue("cset-bulk-legacy").attemptNumber)
    }

    @Test
    fun `ordinary save canonicalizes negative attempt number to one`() = runTest {
        repository.saveCompletedSet(
            completedSet("invalid-ordinary", "session-1", 0, routineExerciseId = "exercise-1", attemptNumber = -4),
        )

        assertEquals(1L, database.vitruvianDatabaseQueries.selectCompletedSetById("invalid-ordinary").executeAsOne().attempt_number)
        assertEquals(1, repository.getCompletedSets("session-1").single().attemptNumber)
    }

    @Test
    fun `bulk save canonicalizes zero attempt number to one`() = runTest {
        repository.saveCompletedSets(
            listOf(completedSet("invalid-bulk", "session-1", 1, routineExerciseId = "exercise-1", attemptNumber = 0)),
        )

        assertEquals(1L, database.vitruvianDatabaseQueries.selectCompletedSetById("invalid-bulk").executeAsOne().attempt_number)
        assertEquals(1, repository.getCompletedSets("session-1").single().attemptNumber)
    }

    @Test
    fun `invalid stored attempt numbers are coerced to one on read`() = runTest {
        listOf(0L, -4L).forEachIndexed { index, invalidAttempt ->
            database.vitruvianDatabaseQueries.insertCompletedSet(
                id = "cset-invalid-attempt-$index",
                session_id = "session-1",
                planned_set_id = null,
                routine_exercise_id = "exercise-1",
                set_number = index.toLong(),
                set_type = "STANDARD",
                attempt_number = invalidAttempt,
                actual_reps = 8L,
                actual_weight_kg = 40.0,
                logged_rpe = null,
                is_pr = 0L,
                completed_at = 1001L + index,
                set_end_reason = "TARGET_REPS_REACHED",
            )
        }

        assertEquals(listOf(1, 1), repository.getCompletedSets("session-1").map { it.attemptNumber })
    }

    @Test
    fun `durable attempt APIs canonicalize negative stored attempt without accepting negative caller`() = runTest {
        assertInvalidStoredAttemptApiPolicy(-4L)
    }

    @Test
    fun `durable attempt APIs canonicalize zero stored attempt without accepting zero caller`() = runTest {
        assertInvalidStoredAttemptApiPolicy(0L)
    }

    private suspend fun assertInvalidStoredAttemptApiPolicy(invalidAttempt: Long) {
        val suffix = if (invalidAttempt < 0) "negative" else "zero"
        val sessionId = "invalid-api-session-$suffix"
        val key = LogicalSetKey(
            routineSessionId = "invalid-api-routine-$suffix",
            routineExerciseId = "invalid-api-occurrence-$suffix",
            setIndex = 2,
            setKind = SetType.STANDARD,
        )
        insertWorkoutSession(sessionId, "bench", routineSessionId = key.routineSessionId)
        database.vitruvianDatabaseQueries.insertCompletedSet(
            id = "invalid-api-attempt-$suffix",
            session_id = sessionId,
            planned_set_id = null,
            routine_exercise_id = key.routineExerciseId,
            set_number = key.setIndex.toLong(),
            set_type = key.setKind.name,
            attempt_number = invalidAttempt,
            actual_reps = 8L,
            actual_weight_kg = 40.0,
            logged_rpe = null,
            is_pr = 0L,
            completed_at = 2000L,
            set_end_reason = "TARGET_REPS_REACHED",
        )

        assertEquals(2, repository.nextAttemptNumber(key))
        assertTrue(repository.isAttemptDurable(sessionId, key, 1))
        assertFalse(repository.isAttemptDurable(sessionId, key, invalidAttempt.toInt()))
    }

    @Test
    fun `nextAttemptNumber isolates every logical key dimension and ignores soft-deleted sessions`() = runTest {
        val key = LogicalSetKey("routine-session-a", "exercise-1", 0, SetType.STANDARD)
        assertEquals(1, repository.nextAttemptNumber(key))

        insertWorkoutSession("attempt-session-1", "bench", routineSessionId = "routine-session-a")
        insertWorkoutSession("attempt-session-2", "bench", routineSessionId = "routine-session-a")
        insertWorkoutSession("other-routine-session", "bench", routineSessionId = "routine-session-b")
        insertWorkoutSession("deleted-attempt-session", "bench", routineSessionId = "routine-session-a")
        repository.saveCompletedSets(
            listOf(
                completedSet("attempt-1", "attempt-session-1", 0, routineExerciseId = "exercise-1", attemptNumber = 1),
                completedSet("attempt-2", "attempt-session-2", 0, routineExerciseId = "exercise-1", attemptNumber = 2),
                completedSet("wrong-occurrence", "attempt-session-1", 0, routineExerciseId = "exercise-other", attemptNumber = 20),
                completedSet("wrong-set", "attempt-session-1", 1, routineExerciseId = "exercise-1", attemptNumber = 21),
                completedSet("wrong-kind", "attempt-session-1", 0, setType = SetType.AMRAP, routineExerciseId = "exercise-1", attemptNumber = 22),
                completedSet("wrong-routine-session", "other-routine-session", 0, routineExerciseId = "exercise-1", attemptNumber = 23),
                completedSet("soft-deleted", "deleted-attempt-session", 0, routineExerciseId = "exercise-1", attemptNumber = 24),
            ),
        )
        database.vitruvianDatabaseQueries.softDeleteSession(123L, 123L, "deleted-attempt-session")

        assertEquals(3, repository.nextAttemptNumber(key))
    }

    @Test
    fun `isAttemptDurable requires exact stable session logical key and attempt on authoritative session`() = runTest {
        insertWorkoutSession("durable-session", "bench", routineSessionId = "routine-session-a")
        insertWorkoutSession("other-stable-session", "bench", routineSessionId = "routine-session-a")
        insertWorkoutSession("soft-deleted-durable", "bench", routineSessionId = "routine-session-a")
        repository.saveCompletedSets(
            listOf(
                completedSet("durable", "durable-session", 0, routineExerciseId = "exercise-1", attemptNumber = 3),
                completedSet("other-stable", "other-stable-session", 0, routineExerciseId = "exercise-1", attemptNumber = 3),
                completedSet("deleted-durable", "soft-deleted-durable", 0, routineExerciseId = "exercise-1", attemptNumber = 3),
            ),
        )
        database.vitruvianDatabaseQueries.softDeleteSession(123L, 123L, "soft-deleted-durable")
        val key = LogicalSetKey("routine-session-a", "exercise-1", 0, SetType.STANDARD)

        assertTrue(repository.isAttemptDurable("durable-session", key, 3))
        assertFalse(repository.isAttemptDurable("missing-session", key, 3))
        assertFalse(repository.isAttemptDurable("other-stable-session", key, 2))
        assertFalse(repository.isAttemptDurable("durable-session", key.copy(routineSessionId = "routine-session-b"), 3))
        assertFalse(repository.isAttemptDurable("durable-session", key.copy(routineExerciseId = "exercise-other"), 3))
        assertFalse(repository.isAttemptDurable("durable-session", key.copy(setIndex = 1), 3))
        assertFalse(repository.isAttemptDurable("durable-session", key.copy(setKind = SetType.AMRAP), 3))
        assertFalse(repository.isAttemptDurable("soft-deleted-durable", key, 3))
    }

    @Test
    fun `unknown persisted end reason reads as UNKNOWN`() = runTest {
        database.vitruvianDatabaseQueries.insertCompletedSet(
            id = "cset-future",
            session_id = "session-1",
            planned_set_id = null,
            routine_exercise_id = null,
            set_number = 1L,
            set_type = "STANDARD",
            attempt_number = 1L,
            actual_reps = 8L,
            actual_weight_kg = 40.0,
            logged_rpe = null,
            is_pr = 0L,
            completed_at = 1001L,
            set_end_reason = "FUTURE_REASON",
        )

        assertEquals(SetEndReason.UNKNOWN, repository.getCompletedSets("session-1").single().setEndReason)
    }

    @Test
    fun `tagged Just Lift updates existing set without overwriting captured end reason`() = runTest {
        insertWorkoutSession(
            id = "captured-untagged-just-lift",
            exerciseId = null,
            totalReps = 7,
            workingReps = 7,
            isJustLift = 1L,
        )
        repository.saveCompletedSet(
            completedSet(
                id = "cset-captured-stall",
                sessionId = "captured-untagged-just-lift",
                setNumber = 1,
                setEndReason = SetEndReason.STALL_FAILURE,
            ),
        )

        repository.ensureCompletedSetForTaggedJustLift(
            justLiftSession("captured-untagged-just-lift", exerciseId = "deadlift"),
            isAmrap = false,
        )

        val persisted = repository.getCompletedSets("captured-untagged-just-lift").single()
        assertEquals("cset-captured-stall", persisted.id)
        assertEquals(SetEndReason.STALL_FAILURE, persisted.setEndReason)
    }

    @Test
    fun `tagged historical Just Lift session without completed set uses UNKNOWN reason`() = runTest {
        insertWorkoutSession(
            id = "historical-tagged-just-lift",
            exerciseId = "deadlift",
            totalReps = 7,
            workingReps = 7,
            isJustLift = 1L,
        )

        repository.ensureCompletedSetForTaggedJustLift(
            justLiftSession("historical-tagged-just-lift", exerciseId = "deadlift"),
            isAmrap = false,
        )

        val persisted = repository.getCompletedSets("historical-tagged-just-lift").single()
        assertEquals(SetEndReason.UNKNOWN, persisted.setEndReason)
        assertEquals(
            "UNKNOWN",
            database.vitruvianDatabaseQueries.selectCompletedSetById(persisted.id).executeAsOne().set_end_reason,
        )
    }

    private fun plannedSet(
        id: String,
        routineExerciseId: String,
        setNumber: Int,
        targetReps: Int = 10,
        targetWeightKg: Float = 40f,
        restSeconds: Int? = 60,
    ) = PlannedSet(
        id = id,
        routineExerciseId = routineExerciseId,
        setNumber = setNumber,
        setType = SetType.STANDARD,
        targetReps = targetReps,
        targetWeightKg = targetWeightKg,
        targetRpe = null,
        restSeconds = restSeconds,
    )

    private fun completedSet(
        id: String,
        sessionId: String,
        setNumber: Int,
        setEndReason: SetEndReason = SetEndReason.TARGET_REPS_REACHED,
        setType: SetType = SetType.STANDARD,
        routineExerciseId: String? = null,
        attemptNumber: Int = 1,
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = setNumber,
        setType = setType,
        actualReps = 8,
        actualWeightKg = 40f,
        loggedRpe = null,
        isPr = false,
        completedAt = 1000L + setNumber,
        setEndReason = setEndReason,
        routineExerciseId = routineExerciseId,
        attemptNumber = attemptNumber,
    )

    private fun justLiftSession(id: String, exerciseId: String) = WorkoutSession(
        id = id,
        timestamp = 1_000L,
        mode = "OldSchool",
        reps = 0,
        weightPerCableKg = 40f,
        duration = 10_000L,
        totalReps = 7,
        workingReps = 7,
        isJustLift = true,
        exerciseId = exerciseId,
    )

    private fun insertRoutine(id: String) {
        database.vitruvianDatabaseQueries.insertRoutine(
            id = id,
            name = "Test Routine",
            description = "",
            createdAt = 0L,
            lastUsed = null,
            useCount = 0L,
            profile_id = "default",
            groupId = null,
        )
    }

    private fun insertRoutineExercise(id: String, routineId: String, name: String) {
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = id,
            routineId = routineId,
            exerciseName = name,
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "BAR",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = "bench",
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 40.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 60L,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0L,
            isAMRAP = 0L,
            supersetId = null,
            orderInSuperset = 0L,
            usePercentOfPR = 0L,
            weightPercentOfPR = 80L,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1L,
            stopAtTop = 0L,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
            defaultRackItemIds = "[]",
            rackBehaviorOverrides = "{}",
            scalingBasis = null,
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
        )
    }

    private fun insertWorkoutSession(
        id: String,
        exerciseId: String?,
        totalReps: Long = 0L,
        workingReps: Long = 0L,
        isJustLift: Long = 0L,
        routineSessionId: String? = null,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 0L,
            mode = "OldSchool",
            targetReps = 10L,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0L,
            workingReps = workingReps,
            isJustLift = isJustLift,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = exerciseId,
            exerciseName = "Bench Press",
            routineSessionId = routineSessionId,
            routineName = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            routineId = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "default",
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }
}
