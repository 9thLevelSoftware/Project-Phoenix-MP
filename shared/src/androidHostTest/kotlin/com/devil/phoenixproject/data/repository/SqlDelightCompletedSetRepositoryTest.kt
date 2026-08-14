package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
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
    fun `unknown persisted end reason reads as UNKNOWN`() = runTest {
        database.vitruvianDatabaseQueries.insertCompletedSet(
            id = "cset-future",
            session_id = "session-1",
            planned_set_id = null,
            set_number = 1L,
            set_type = "STANDARD",
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

    private fun completedSet(id: String, sessionId: String, setNumber: Int, setEndReason: SetEndReason = SetEndReason.TARGET_REPS_REACHED) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = setNumber,
        setType = SetType.STANDARD,
        actualReps = 8,
        actualWeightKg = 40f,
        loggedRpe = null,
        isPr = false,
        completedAt = 1000L + setNumber,
        setEndReason = setEndReason,
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
        )
    }

    private fun insertWorkoutSession(
        id: String,
        exerciseId: String?,
        totalReps: Long = 0L,
        workingReps: Long = 0L,
        isJustLift: Long = 0L,
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
            routineSessionId = null,
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
