package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.testutil.createTestDatabase
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MigrationManagerTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var migrationManager: MigrationManager

    @Before
    fun setup() {
        database = createTestDatabase()
        migrationManager = MigrationManager(database)
    }

    // -- cleanupFabricatedRoutineSessionIds tests --

    @Test
    fun `cleanup strips exact-match fabricated legacy_session routineSessionId`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalSession(
            id = "session-1",
            routineSessionId = "legacy_session_session-1",
            routineName = "Upper Day"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500) // migrations run async

        val session = queries.selectSessionById("session-1").executeAsOneOrNull()
        assertNotNull(session)
        assertNull(session.routineSessionId, "Exact-match legacy_session_<id> should be stripped")
        assertEquals("Upper Day", session.routineName, "Routine name should be preserved")
    }

    @Test
    fun `cleanup preserves legitimate routineSessionId that is not fabricated`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalSession(
            id = "session-2",
            routineSessionId = "real-routine-session-uuid-123",
            routineName = "Leg Day"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-2").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("real-routine-session-uuid-123", session.routineSessionId, "Legitimate routineSessionId should be preserved")
    }

    @Test
    fun `cleanup does not strip legacy_session prefix that does not match session id`() {
        val queries = database.vitruvianDatabaseQueries
        // This has legacy_session_ prefix but the suffix doesn't match the session ID
        insertMinimalSession(
            id = "session-3",
            routineSessionId = "legacy_session_some-other-session",
            routineName = "Push Day"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-3").executeAsOneOrNull()
        assertNotNull(session)
        // Migration uses exact match: legacy_session_${session.id}
        // "legacy_session_some-other-session" != "legacy_session_session-3"
        // So this ID should NOT be stripped by migration (it doesn't match the fabrication pattern)
        // However, the sanitizeRoutineSessionId used in backfill still uses prefix match
        // The migration cleanup only strips exact matches for safety
        assertEquals("legacy_session_some-other-session", session.routineSessionId,
            "Non-matching legacy_session_ prefix should not be stripped by migration")
    }

    // -- backfillLegacyWorkoutRoutineNames tests --

    @Test
    fun `backfill clears garbage routine name when no inference possible`() {
        val queries = database.vitruvianDatabaseQueries
        // No routines defined — inference will fail
        insertMinimalSession(
            id = "session-garbage",
            routineSessionId = null,
            routineName = "Imported Strength Training Session"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-garbage").executeAsOneOrNull()
        assertNotNull(session)
        assertNull(session.routineName, "Garbage routine name should be cleared to null when inference fails")
    }

    @Test
    fun `backfill replaces garbage routine name with inferred name when routine exists`() {
        val queries = database.vitruvianDatabaseQueries
        // Create a routine with exercise mapping
        queries.insertRoutine(
            id = "routine-upper",
            name = "Upper Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0
        )
        insertMinimalRoutineExercise(
            id = "re-bench",
            routineId = "routine-upper",
            exerciseName = "Bench Press",
            exerciseId = "exercise-bench"
        )
        insertMinimalSession(
            id = "session-garbage-infer",
            routineSessionId = null,
            routineName = "Imported Strength Training Session",
            exerciseId = "exercise-bench",
            exerciseName = "Bench Press"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-garbage-infer").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("Upper Day", session.routineName, "Garbage name should be replaced with inferred routine name")
    }

    @Test
    fun `backfill replaces exercise-placeholder routine name with inferred name`() {
        val queries = database.vitruvianDatabaseQueries
        queries.insertRoutine(
            id = "routine-upper",
            name = "Upper Day",
            description = "",
            createdAt = 1_700_000_000_000,
            lastUsed = null,
            useCount = 0
        )
        insertMinimalRoutineExercise(
            id = "re-bench",
            routineId = "routine-upper",
            exerciseName = "Bench Press",
            exerciseId = "exercise-bench"
        )
        // Session with routineName == exerciseName (placeholder pattern)
        insertMinimalSession(
            id = "session-placeholder",
            routineSessionId = null,
            routineName = "Bench Press",
            exerciseId = "exercise-bench",
            exerciseName = "Bench Press"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-placeholder").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("Upper Day", session.routineName, "Exercise-placeholder name should be replaced with actual routine name")
    }

    @Test
    fun `backfill preserves legitimate routine name`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalSession(
            id = "session-legit",
            routineSessionId = null,
            routineName = "My Custom Routine"
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-legit").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("My Custom Routine", session.routineName, "Legitimate routine name should be preserved")
    }

    // -- Helper --

    private fun insertMinimalRoutineExercise(
        id: String,
        routineId: String,
        exerciseName: String,
        exerciseId: String
    ) {
        database.vitruvianDatabaseQueries.insertRoutineExerciseIgnore(
            id = id,
            routineId = routineId,
            exerciseName = exerciseName,
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = exerciseId,
            cableConfig = "DOUBLE",
            orderIndex = 0,
            setReps = "10,10,10",
            weightPerCableKg = 10.0,
            setWeights = "",
            mode = "Old School",
            eccentricLoad = 100,
            echoLevel = 0,
            progressionKg = 0.0,
            restSeconds = 60,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0,
            isAMRAP = 0,
            supersetId = null,
            orderInSuperset = 0,
            usePercentOfPR = 0,
            weightPercentOfPR = 100,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1,
            stopAtTop = 0,
            repCountTiming = "TOP"
        )
    }

    private fun insertMinimalSession(
        id: String,
        routineSessionId: String?,
        routineName: String?,
        exerciseId: String = "exercise-default",
        exerciseName: String = "Default Exercise"
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 1_700_000_000_000,
            mode = "Old School",
            targetReps = 10,
            weightPerCableKg = 10.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 10,
            warmupReps = 0,
            workingReps = 10,
            isJustLift = 0,
            stopAtTop = 0,
            eccentricLoad = 100,
            echoLevel = 0,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineSessionId = routineSessionId,
            routineName = routineName,
            routineId = null,
            safetyFlags = 0,
            deloadWarningCount = 0,
            romViolationCount = 0,
            spotterActivations = 0,
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
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
        )
    }
}
