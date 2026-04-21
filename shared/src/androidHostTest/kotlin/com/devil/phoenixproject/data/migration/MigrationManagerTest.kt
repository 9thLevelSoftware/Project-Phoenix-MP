package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test

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
            routineName = "Upper Day",
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
            routineName = "Leg Day",
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
            routineName = "Push Day",
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
        assertEquals(
            "legacy_session_some-other-session",
            session.routineSessionId,
            "Non-matching legacy_session_ prefix should not be stripped by migration",
        )
    }

    // -- backfillLegacyWorkoutRoutineNames tests --

    @Test
    fun `backfill clears garbage routine name when no inference possible`() {
        val queries = database.vitruvianDatabaseQueries
        // No routines defined — inference will fail
        insertMinimalSession(
            id = "session-garbage",
            routineSessionId = null,
            routineName = "Imported Strength Training Session",
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
            useCount = 0,
            profile_id = "default",
            groupId = null,
        )
        insertMinimalRoutineExercise(
            id = "re-bench",
            routineId = "routine-upper",
            exerciseName = "Bench Press",
            exerciseId = "exercise-bench",
        )
        insertMinimalSession(
            id = "session-garbage-infer",
            routineSessionId = null,
            routineName = "Imported Strength Training Session",
            exerciseId = "exercise-bench",
            exerciseName = "Bench Press",
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
            useCount = 0,
            profile_id = "default",
            groupId = null,
        )
        insertMinimalRoutineExercise(
            id = "re-bench",
            routineId = "routine-upper",
            exerciseName = "Bench Press",
            exerciseId = "exercise-bench",
        )
        // Session with routineName == exerciseName (placeholder pattern)
        insertMinimalSession(
            id = "session-placeholder",
            routineSessionId = null,
            routineName = "Bench Press",
            exerciseId = "exercise-bench",
            exerciseName = "Bench Press",
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
            routineName = "My Custom Routine",
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-legit").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("My Custom Routine", session.routineName, "Legitimate routine name should be preserved")
    }

    @Test
    fun `normalize legacy workout session modes to canonical keys`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalSession(
            id = "session-mode",
            routineSessionId = null,
            routineName = "Pull Day",
            mode = "OldSchool",
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-mode").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("Old School", session.mode)
    }

    @Test
    fun `normalize legacy personal record modes merges duplicates into canonical bucket`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalExercise(id = "deadlift", name = "Conventional Deadlift")
        queries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            weight = 60.0,
            reps = 10,
            oneRepMax = OneRepMaxCalculator.epley(60f, 10).toDouble(),
            achievedAt = 1_700_000_000_000,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT.name,
            volume = 500.0,
            phase = "COMBINED",
            profile_id = "default",
        )
        queries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            weight = 55.0,
            reps = 10,
            oneRepMax = OneRepMaxCalculator.epley(55f, 10).toDouble(),
            achievedAt = 1_700_000_100_000,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 500.0,
            phase = "COMBINED",
            profile_id = "default",
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val canonical = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "default",
        ).executeAsOneOrNull()
        val records = queries.selectAllRecords(profileId = "default").executeAsList()
            .filter { it.exerciseId == "deadlift" && it.prType == PRType.MAX_WEIGHT.name }

        assertNotNull(canonical)
        assertEquals(1, records.size)
        assertEquals("Old School", canonical.workoutMode)
        assertEquals(60.0, canonical.weight)
        assertNull(
            queries.selectPR(
                "deadlift",
                "OldSchool",
                PRType.MAX_WEIGHT.name,
                phase = "COMBINED",
                profileId = "default",
            ).executeAsOneOrNull(),
        )
    }

    @Test
    fun `normalize legacy personal record modes stays within each profile`() {
        val queries = database.vitruvianDatabaseQueries
        insertProfile(id = "profile-b", name = "Profile B", isActive = false)
        insertMinimalExercise(id = "deadlift", name = "Conventional Deadlift")

        queries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            weight = 70.0,
            reps = 8,
            oneRepMax = OneRepMaxCalculator.epley(70f, 8).toDouble(),
            achievedAt = 1_700_000_000_000,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT.name,
            volume = 560.0,
            phase = "COMBINED",
            profile_id = "profile-b",
        )
        queries.insertRecord(
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            weight = 65.0,
            reps = 8,
            oneRepMax = OneRepMaxCalculator.epley(65f, 8).toDouble(),
            achievedAt = 1_700_000_100_000,
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            volume = 520.0,
            phase = "COMBINED",
            profile_id = "profile-b",
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val canonicalProfileB = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "profile-b",
        ).executeAsOneOrNull()
        val recordsProfileB = queries.selectAllRecords(profileId = "profile-b").executeAsList()
            .filter { it.exerciseId == "deadlift" && it.prType == PRType.MAX_WEIGHT.name }

        assertNotNull(canonicalProfileB)
        assertEquals(1, recordsProfileB.size)
        assertEquals("Old School", canonicalProfileB.workoutMode)
        assertEquals(70.0, canonicalProfileB.weight)
        assertNull(
            queries.selectPR(
                "deadlift",
                "OldSchool",
                PRType.MAX_WEIGHT.name,
                phase = "COMBINED",
                profileId = "profile-b",
            ).executeAsOneOrNull(),
        )
        assertNull(
            queries.selectPR(
                "deadlift",
                "Old School",
                PRType.MAX_WEIGHT.name,
                phase = "COMBINED",
                profileId = "default",
            ).executeAsOneOrNull(),
        )
    }

    @Test
    fun `repair personal records from workout history backfills achieved load and stays idempotent`() {
        val queries = database.vitruvianDatabaseQueries
        insertMinimalExercise(id = "deadlift", name = "Conventional Deadlift")
        insertMinimalSession(
            id = "session-pr-repair",
            routineSessionId = null,
            routineName = "Pull 2",
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            mode = "OldSchool",
            weightPerCableKg = 50.0,
            totalReps = 10,
            workingReps = 10,
            heaviestLiftKg = 60.0,
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)
        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val session = queries.selectSessionById("session-pr-repair").executeAsOneOrNull()
        val weightPr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "default",
        ).executeAsOneOrNull()
        val volumePr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_VOLUME.name,
            phase = "COMBINED",
            profileId = "default",
        ).executeAsOneOrNull()
        val exercise = queries.selectExerciseById("deadlift").executeAsOneOrNull()
        val repairedRecords = queries.selectAllRecords(profileId = "default").executeAsList()
            .filter { it.exerciseId == "deadlift" }

        assertNotNull(session)
        assertNotNull(weightPr)
        assertNotNull(volumePr)
        assertEquals("Old School", session.mode)
        assertEquals(60.0, weightPr.weight)
        assertEquals(50.0, volumePr.weight)
        assertEquals(500.0, volumePr.volume)
        assertEquals(2, repairedRecords.size)
        assertEquals(
            OneRepMaxCalculator.epley(60f, 10).toDouble(),
            exercise?.one_rep_max_kg,
        )
    }

    @Test
    fun `repair personal records from workout history respects session profile ids`() {
        val queries = database.vitruvianDatabaseQueries
        insertProfile(id = "profile-b", name = "Profile B", isActive = false)
        insertMinimalExercise(id = "deadlift", name = "Conventional Deadlift")

        insertMinimalSession(
            id = "session-default",
            routineSessionId = null,
            routineName = "Pull A",
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            mode = "OldSchool",
            weightPerCableKg = 50.0,
            totalReps = 10,
            workingReps = 10,
            heaviestLiftKg = 60.0,
            timestamp = 1_700_000_000_000,
            profileId = "default",
        )
        insertMinimalSession(
            id = "session-profile-b",
            routineSessionId = null,
            routineName = "Pull B",
            exerciseId = "deadlift",
            exerciseName = "Conventional Deadlift",
            mode = "OldSchool",
            weightPerCableKg = 70.0,
            totalReps = 8,
            workingReps = 8,
            heaviestLiftKg = 80.0,
            timestamp = 1_700_000_100_000,
            profileId = "profile-b",
        )

        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)
        migrationManager.checkAndRunMigrations()
        Thread.sleep(500)

        val defaultWeightPr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "default",
        ).executeAsOneOrNull()
        val defaultVolumePr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_VOLUME.name,
            phase = "COMBINED",
            profileId = "default",
        ).executeAsOneOrNull()
        val profileBWeightPr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "profile-b",
        ).executeAsOneOrNull()
        val profileBVolumePr = queries.selectPR(
            "deadlift",
            "Old School",
            PRType.MAX_VOLUME.name,
            phase = "COMBINED",
            profileId = "profile-b",
        ).executeAsOneOrNull()

        assertNotNull(defaultWeightPr)
        assertNotNull(defaultVolumePr)
        assertNotNull(profileBWeightPr)
        assertNotNull(profileBVolumePr)
        assertEquals(60.0, defaultWeightPr.weight)
        assertEquals(50.0, defaultVolumePr.weight)
        assertEquals(80.0, profileBWeightPr.weight)
        assertEquals(70.0, profileBVolumePr.weight)
        assertEquals(
            2,
            queries.selectAllRecords(profileId = "default").executeAsList().count { it.exerciseId == "deadlift" },
        )
        assertEquals(
            2,
            queries.selectAllRecords(profileId = "profile-b").executeAsList().count { it.exerciseId == "deadlift" },
        )
    }

    // -- Helper --

    private fun insertProfile(id: String, name: String, isActive: Boolean) {
        database.vitruvianDatabaseQueries.insertProfile(
            id = id,
            name = name,
            colorIndex = 0L,
            createdAt = 1_700_000_000_000,
            isActive = if (isActive) 1L else 0L,
        )
    }

    private fun insertMinimalExercise(id: String, name: String, oneRepMaxKg: Double? = null) {
        database.vitruvianDatabaseQueries.insertExercise(
            id = id,
            name = name,
            description = null,
            created = 0L,
            muscleGroup = "Back",
            muscleGroups = "Back",
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
            one_rep_max_kg = oneRepMaxKg,
        )
    }

    private fun insertMinimalRoutineExercise(id: String, routineId: String, exerciseName: String, exerciseId: String) {
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
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
        )
    }

    private fun insertMinimalSession(
        id: String,
        routineSessionId: String?,
        routineName: String?,
        exerciseId: String = "exercise-default",
        exerciseName: String = "Default Exercise",
        mode: String = "Old School",
        weightPerCableKg: Double = 10.0,
        totalReps: Long = 10L,
        workingReps: Long = 10L,
        heaviestLiftKg: Double? = null,
        timestamp: Long = 1_700_000_000_000,
        profileId: String = "default",
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = mode,
            targetReps = 10,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0,
            workingReps = workingReps,
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
            heaviestLiftKg = heaviestLiftKg,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
        )
    }
}
