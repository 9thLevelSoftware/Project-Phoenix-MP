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
}
