package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SqlDelightWorkoutRepositoryVelocityPointsTest {

    private fun createInMemoryTestDatabase(): VitruvianDatabase = createTestDatabase()

    private fun seedExercise(db: VitruvianDatabase, id: String) {
        db.vitruvianDatabaseQueries.insertExercise(
            id = id,
            name = id,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = null,
            mvtOverrideMs = null,
        )
    }

    private var sessionCounter = 0

    private fun seedSession(
        db: VitruvianDatabase,
        exerciseId: String,
        weightPerCableKg: Float,
        workingAvgWeightKg: Float?,
        avgMcvMmS: Float?,
        timestamp: Long,
        workingReps: Int,
        profileId: String,
    ) {
        db.vitruvianDatabaseQueries.insertSession(
            id = "session-${++sessionCounter}",
            timestamp = timestamp,
            mode = "Program:OldSchool",
            targetReps = 5L,
            weightPerCableKg = weightPerCableKg.toDouble(),
            progressionKg = 0.0,
            duration = 30_000L,
            totalReps = workingReps.toLong(),
            warmupReps = 0L,
            workingReps = workingReps.toLong(),
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = exerciseId,
            exerciseName = exerciseId,
            routineSessionId = null,
            routineName = null,
            routineId = null,
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
            workingAvgWeightKg = workingAvgWeightKg?.toDouble(),
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = avgMcvMmS?.toDouble(),
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }

    @Test
    fun `returns one point per qualifying session using working avg weight`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val exerciseRepo = SqlDelightExerciseRepository(db, ExerciseImporter(db))
        val repo = SqlDelightWorkoutRepository(db, exerciseRepo)

        // session A: inside window — workingAvgWeightKg=40 must win over weightPerCableKg=42
        seedSession(
            db,
            exerciseId = "ex1",
            weightPerCableKg = 42f,
            workingAvgWeightKg = 40f,
            avgMcvMmS = 1200f,
            timestamp = 2000L,
            workingReps = 5,
            profileId = "default",
        )
        // session B: before sinceTimestamp — must be excluded
        seedSession(
            db,
            exerciseId = "ex1",
            weightPerCableKg = 80f,
            workingAvgWeightKg = 80f,
            avgMcvMmS = 600f,
            timestamp = 100L,
            workingReps = 5,
            profileId = "default",
        )

        val points = repo.getVelocityPointsForExercise("ex1", "default", sinceTimestampMs = 1000L)
        assertEquals(1, points.size, "Only the in-window session should be returned")
        assertEquals(40f, points.first().loadPerCableKg, "workingAvgWeightKg must be preferred over weightPerCableKg")
        assertEquals(1200f, points.first().mcvMmS, "MCV must be preserved as mm/s")
    }

    @Test
    fun `excludes null-MCV and zero-rep sessions within the window`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val exerciseRepo = SqlDelightExerciseRepository(db, ExerciseImporter(db))
        val repo = SqlDelightWorkoutRepository(db, exerciseRepo)

        // (a) qualifying in-window session
        seedSession(
            db,
            exerciseId = "ex1",
            weightPerCableKg = 50f,
            workingAvgWeightKg = 48f,
            avgMcvMmS = 900f,
            timestamp = 3000L,
            workingReps = 5,
            profileId = "default",
        )
        // (b) in-window but null MCV — excluded by WHERE avgMcvMmS IS NOT NULL
        seedSession(
            db,
            exerciseId = "ex1",
            weightPerCableKg = 60f,
            workingAvgWeightKg = 58f,
            avgMcvMmS = null,
            timestamp = 3500L,
            workingReps = 5,
            profileId = "default",
        )
        // (c) in-window but zero working reps — excluded by WHERE workingReps > 0
        seedSession(
            db,
            exerciseId = "ex1",
            weightPerCableKg = 70f,
            workingAvgWeightKg = 68f,
            avgMcvMmS = 800f,
            timestamp = 4000L,
            workingReps = 0,
            profileId = "default",
        )

        val points = repo.getVelocityPointsForExercise("ex1", "default", sinceTimestampMs = 1000L)
        assertEquals(1, points.size, "Only the session with non-null MCV and working reps should qualify")
        assertEquals(48f, points.first().loadPerCableKg)
        assertEquals(900f, points.first().mcvMmS)
    }

    // Issue #517 Phase 5 T1
    @Test
    fun `getExerciseIdsWithVelocityData returns distinct mcv-bearing exercises`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "exA"); seedExercise(db, id = "exB")
        val exerciseRepo = SqlDelightExerciseRepository(db, ExerciseImporter(db))
        val repo = SqlDelightWorkoutRepository(db, exerciseRepo)

        // two MCV-bearing sets for exA — must appear only once (DISTINCT)
        seedSession(db, exerciseId = "exA", weightPerCableKg = 40f, workingAvgWeightKg = 40f, avgMcvMmS = 800f, timestamp = 1L, workingReps = 5, profileId = "default")
        seedSession(db, exerciseId = "exA", weightPerCableKg = 60f, workingAvgWeightKg = 60f, avgMcvMmS = 500f, timestamp = 2L, workingReps = 5, profileId = "default")
        // exB has null MCV — must be excluded
        seedSession(db, exerciseId = "exB", weightPerCableKg = 50f, workingAvgWeightKg = 50f, avgMcvMmS = null, timestamp = 1L, workingReps = 5, profileId = "default")

        val ids = repo.getExerciseIdsWithVelocityData("default")
        assertEquals(listOf("exA"), ids)
        assertTrue(ids.none { it == "exB" }, "exB (null MCV) must be excluded")
    }
}
