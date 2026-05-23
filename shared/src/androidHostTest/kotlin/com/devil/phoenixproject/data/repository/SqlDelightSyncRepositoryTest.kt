package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullRoutineExerciseDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqlDelightSyncRepositoryTest {

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var repository: SqlDelightSyncRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository()
        userProfileRepository.setActiveProfileForTest(id = "active-profile")
        repository = SqlDelightSyncRepository(database, userProfileRepository)
    }

    @Test
    fun `mergeSessions uses active profile id`() = runTest {
        repository.mergeSessions(
            sessions = listOf(
                WorkoutSessionSyncDto(
                    clientId = "session-profile-b",
                    serverId = "server-session-profile-b",
                    timestamp = 1_700_000_000_000,
                    mode = "Old School",
                    targetReps = 8,
                    weightPerCableKg = 42.5f,
                    duration = 120,
                    totalReps = 24,
                    exerciseId = "bench",
                    exerciseName = "Bench Press",
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val session = database.vitruvianDatabaseQueries
            .selectSessionById("session-profile-b")
            .executeAsOneOrNull()

        assertNotNull(session)
        assertEquals("active-profile", session.profile_id)
    }

    @Test
    fun `mergeSessions preserves existing display multiplier when replacing synced session`() = runTest {
        insertWorkoutSession(
            id = "session-display-existing",
            displayMultiplier = 1L,
        )
        database.vitruvianDatabaseQueries.updateSessionServerId(
            serverId = "server-session-display-existing",
            id = "session-display-existing",
        )

        repository.mergeSessions(
            sessions = listOf(
                WorkoutSessionSyncDto(
                    clientId = "different-client-id",
                    serverId = "server-session-display-existing",
                    timestamp = 1_700_000_000_100,
                    mode = "Old School",
                    targetReps = 8,
                    weightPerCableKg = 42.5f,
                    duration = 120,
                    totalReps = 24,
                    exerciseId = "bench",
                    exerciseName = "Bench Press",
                    createdAt = 1_700_000_000_100,
                    updatedAt = 1_700_000_000_200,
                ),
            ),
        )

        val session = database.vitruvianDatabaseQueries
            .selectSessionById("session-display-existing")
            .executeAsOneOrNull()

        assertNotNull(session)
        assertEquals(1L, session.display_multiplier)
    }

    @Test
    fun `mergePRs uses active profile id`() = runTest {
        repository.mergePRs(
            records = listOf(
                PersonalRecordSyncDto(
                    clientId = "pr-profile-b",
                    serverId = "server-pr-profile-b",
                    exerciseId = "deadlift",
                    exerciseName = "Deadlift",
                    weight = 85f,
                    reps = 5,
                    oneRepMax = 99.17f,
                    achievedAt = 1_700_000_000_000,
                    workoutMode = "Old School",
                    prType = PRType.MAX_WEIGHT.name,
                    volume = 425f,
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val profileRecord = database.vitruvianDatabaseQueries.selectPR(
            exerciseId = "deadlift",
            workoutMode = "Old School",
            prType = PRType.MAX_WEIGHT.name,
            phase = "COMBINED",
            profileId = "active-profile",
        ).executeAsOneOrNull()

        assertNotNull(profileRecord)
        assertEquals("active-profile", profileRecord.profile_id)
    }

    @Test
    fun `mergeRoutines uses active profile id`() = runTest {
        repository.mergeRoutines(
            routines = listOf(
                RoutineSyncDto(
                    clientId = "routine-profile-b",
                    serverId = "server-routine-profile-b",
                    name = "Pull Day",
                    description = "Synced routine",
                    createdAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_100,
                ),
            ),
        )

        val routine = database.vitruvianDatabaseQueries
            .selectRoutineById("routine-profile-b")
            .executeAsOneOrNull()

        assertNotNull(routine)
        assertEquals("active-profile", routine.profile_id)
    }

    @Test
    fun `mergePortalRoutines neutralizes bodyweight hidden cable behavior before writing`() = runTest {
        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "portal-bodyweight-routine",
                    name = "Bodyweight Routine",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "portal-bodyweight-exercise",
                            name = "Plank",
                            muscleGroup = "Core",
                            isBodyweight = true,
                            stallDetection = true,
                            repCountTiming = "BOTTOM",
                            stopAtPosition = "TOP",
                        ),
                    ),
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )

        val row = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine("portal-bodyweight-routine")
            .executeAsOne()

        assertEquals(0L, row.stallDetectionEnabled)
        assertEquals("TOP", row.repCountTiming)
        assertEquals(0L, row.stopAtTop)
    }

    @Test
    fun `mergePortalRoutines accepts only TOP or BOTTOM rep count timing for cable exercises`() = runTest {
        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = "portal-cable-routine",
                    name = "Cable Routine",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "portal-cable-bottom",
                            name = "Cable Curl",
                            muscleGroup = "Biceps",
                            isBodyweight = false,
                            repCountTiming = "BOTTOM",
                        ),
                        PullRoutineExerciseDto(
                            id = "portal-cable-invalid",
                            name = "Cable Row",
                            muscleGroup = "Back",
                            isBodyweight = false,
                            repCountTiming = "MIDDLE",
                        ),
                        PullRoutineExerciseDto(
                            id = "portal-cable-null",
                            name = "Cable Press",
                            muscleGroup = "Chest",
                            isBodyweight = false,
                            repCountTiming = null,
                        ),
                    ),
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )

        val rows = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine("portal-cable-routine")
            .executeAsList()
            .associateBy { it.id }

        assertEquals("BOTTOM", rows.getValue("portal-cable-bottom").repCountTiming)
        assertEquals("TOP", rows.getValue("portal-cable-invalid").repCountTiming)
        assertEquals("TOP", rows.getValue("portal-cable-null").repCountTiming)
    }

    @Test
    fun `mergePortalRoutines preserves local cable count override when portal omits override`() = runTest {
        val routineId = "portal-routine-preserve-cable-override"
        val routineExerciseId = "portal-exercise-preserve-cable-override"

        database.vitruvianDatabaseQueries.insertRoutine(
            id = routineId,
            name = "Local Cable Routine",
            description = "",
            createdAt = 1_700_000_000_000L,
            lastUsed = null,
            useCount = 0L,
            profile_id = "active-profile",
            groupId = null,
        )
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = routineExerciseId,
            routineId = routineId,
            exerciseName = "Cable Row",
            exerciseMuscleGroup = "Back",
            exerciseEquipment = "HANDLES",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 30.0,
            setWeights = "",
            mode = "OLD_SCHOOL",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 90L,
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
            cableCountOverride = 2L,
            stallDetectionEnabled = 1L,
            stopAtTop = 0L,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = routineId,
                    name = "Portal Cable Routine",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = routineExerciseId,
                            name = "Cable Row",
                            muscleGroup = "Back",
                            exerciseEquipment = "HANDLES",
                            sets = 3,
                            reps = 10,
                            weight = 35f,
                        ),
                    ),
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )

        val row = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine(routineId)
            .executeAsOne()

        assertEquals(2L, row.cableCountOverride)
    }

    @Test
    fun `mergePortalRoutines applies portal cable count override when provided`() = runTest {
        val routineId = "portal-routine-apply-cable-override"
        val routineExerciseId = "portal-exercise-apply-cable-override"

        database.vitruvianDatabaseQueries.insertRoutine(
            id = routineId,
            name = "Local Cable Routine",
            description = "",
            createdAt = 1_700_000_000_000L,
            lastUsed = null,
            useCount = 0L,
            profile_id = "active-profile",
            groupId = null,
        )
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = routineExerciseId,
            routineId = routineId,
            exerciseName = "Cable Row",
            exerciseMuscleGroup = "Back",
            exerciseEquipment = "HANDLES",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 30.0,
            setWeights = "",
            mode = "OLD_SCHOOL",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 90L,
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
            cableCountOverride = 2L,
            stallDetectionEnabled = 1L,
            stopAtTop = 0L,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = routineId,
                    name = "Portal Cable Routine",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = routineExerciseId,
                            name = "Cable Row",
                            muscleGroup = "Back",
                            exerciseEquipment = "HANDLES",
                            sets = 3,
                            reps = 10,
                            weight = 35f,
                            cableCountOverride = 1,
                        ),
                    ),
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )

        val row = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine(routineId)
            .executeAsOne()

        assertEquals(1L, row.cableCountOverride)
    }

    @Test
    fun `mergePortalRoutines preserves cable count override by exercise name when ids change`() = runTest {
        val routineId = "portal-routine-preserve-cable-override-by-name"

        database.vitruvianDatabaseQueries.insertRoutine(
            id = routineId,
            name = "Local Cable Routine",
            description = "",
            createdAt = 1_700_000_000_000L,
            lastUsed = null,
            useCount = 0L,
            profile_id = "active-profile",
            groupId = null,
        )
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = "local-exercise-id",
            routineId = routineId,
            exerciseName = "Cable Row",
            exerciseMuscleGroup = "Back",
            exerciseEquipment = "HANDLES",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 30.0,
            setWeights = "",
            mode = "OLD_SCHOOL",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 90L,
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
            cableCountOverride = 1L,
            stallDetectionEnabled = 1L,
            stopAtTop = 0L,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
        )

        repository.mergePortalRoutines(
            routines = listOf(
                PullRoutineDto(
                    id = routineId,
                    name = "Portal Cable Routine",
                    exercises = listOf(
                        PullRoutineExerciseDto(
                            id = "portal-exercise-id",
                            name = "Cable Row",
                            muscleGroup = "Back",
                            exerciseEquipment = "HANDLES",
                            sets = 3,
                            reps = 10,
                            weight = 35f,
                        ),
                    ),
                ),
            ),
            lastSync = 0L,
            profileId = "active-profile",
        )

        val row = database.vitruvianDatabaseQueries
            .selectExercisesByRoutine(routineId)
            .executeAsOne()

        assertEquals(1L, row.cableCountOverride)
    }

    private fun insertWorkoutSession(
        id: String,
        displayMultiplier: Long? = null,
    ) {
        database.vitruvianDatabaseQueries.insertSession(
            id = id,
            timestamp = 1_700_000_000_000,
            mode = "Old School",
            targetReps = 10,
            weightPerCableKg = 80.0,
            progressionKg = 0.0,
            duration = 60_000L,
            totalReps = 10,
            warmupReps = 0,
            workingReps = 10,
            isJustLift = 0,
            stopAtTop = 0,
            eccentricLoad = 100,
            echoLevel = 0,
            exerciseId = "exercise-display",
            exerciseName = "Display Exercise",
            routineSessionId = null,
            routineName = null,
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
            cableCount = 2L,
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
            profile_id = "active-profile",
            display_multiplier = displayMultiplier,
        )
    }
}
