package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Issue #634 (reopened): repository-backed identity guarantees behind the push
 * payload reconciliation (RCA acceptance criteria 1 and 2, SQLDelight side).
 *
 *  - `idx_pr_unique (exerciseId, workoutMode, prType, phase, profile_id)` keeps
 *    same-timestamp/type/phase PR rows with distinct workoutMode AND uuid
 *    distinct all the way through gather: both persist, both are returned, never
 *    merged or deleted.
 *  - `TrainingCycle.id` is a TEXT PRIMARY KEY, so two persisted rows with the
 *    identical cycle id CANNOT coexist — a duplicate-UUID payload pair can only
 *    come from projection/planner/request duplication (or case-variant ids,
 *    which the push reconciler repairs). Both facts are pinned here.
 *  - `clearPersonalRecordUpdatedAt` is the re-arm path for rows the push held
 *    back as conflicting: clearing updatedAt makes the ordinary delta pick them
 *    up again, so a held-back row stays retryable instead of stalling below the
 *    advanced push watermark.
 */
class Issue634RepositoryIdentityTest {

    private lateinit var database: com.devil.phoenixproject.database.PhoenixDatabase
    private lateinit var repository: SqlDelightSyncRepository
    private val profileId = "test-profile"

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        val userProfileRepository = FakeUserProfileRepository()
        userProfileRepository.setActiveProfileForTest(id = profileId)
        repository = SqlDelightSyncRepository(database, userProfileRepository)
    }

    private fun insertPr(
        exerciseId: String = "Standing_Calf_Raises",
        workoutMode: String,
        uuid: String?,
        achievedAt: Long = 1_770_569_713_326L,
    ) {
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = exerciseId,
            exerciseName = "Standing Calf Raises",
            weight = 60.0,
            reps = 12L,
            oneRepMax = 60.0,
            achievedAt = achievedAt,
            workoutMode = workoutMode,
            prType = "MAX_VOLUME",
            volume = 720.0,
            phase = "COMBINED",
            profile_id = profileId,
            cable_count = 2L,
            uuid = uuid,
        )
    }

    private fun insertCycle(id: String, name: String) {
        database.phoenixDatabaseQueries.insertTrainingCycle(
            id = id,
            name = name,
            description = null,
            created_at = 1_770_000_000_000L,
            is_active = 0L,
            profile_id = profileId,
            template_id = null,
            week_number = 1L,
            updatedAt = 1_770_000_000_000L,
        )
    }

    @Test
    fun `same-timestamp PR rows with distinct workoutMode and UUID both persist and gather`() = runTest {
        insertPr(workoutMode = "OLD_SCHOOL", uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        insertPr(workoutMode = "TUT_BEAST", uuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val gathered = repository.getFullPRsModifiedSince(0L, profileId)

        assertEquals(2, gathered.size, "Both rows survive the unique index and the delta gather")
        assertEquals(
            setOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            gathered.map { it.uuid }.toSet(),
            "Distinct UUIDs are never merged or deleted",
        )
        assertEquals(2, gathered.map { it.workoutMode }.distinct().size, "Distinct workoutModes are preserved")
    }

    @Test
    fun `identical TrainingCycle primary keys cannot coexist`() = runTest {
        val cycleId = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe"
        insertCycle(id = cycleId, name = "Cycle copy A")

        val duplicateInsertFailed = runCatching {
            insertCycle(id = cycleId, name = "Cycle copy B")
        }.isFailure
        assertTrue(
            duplicateInsertFailed,
            "TrainingCycle.id is a PRIMARY KEY: a second row with the same id is rejected",
        )
        val cycles = repository.getFullCyclesForSync(profileId)
        assertEquals(
            listOf(cycleId),
            cycles.map { it.cycle.id },
            "Exactly one persisted row per cycle id — a duplicate-UUID payload pair " +
                "cannot come from two identical persisted rows",
        )
    }

    @Test
    fun `case-variant cycle ids coexist locally and are the near-duplicate shape the reconciler repairs`() = runTest {
        // TEXT primary keys do not fold case, so these two rows CAN coexist locally
        // while the portal's uuid primary key treats them as ONE server row. The
        // push reconciler collapses such case-variant twins when their content is
        // identical (Issue634PushIdentityReconcileTest).
        insertCycle(id = "3C2C452C-6B1A-45DE-BA25-FDAC4AFF8CBE", name = "Volume block")
        insertCycle(id = "3c2c452c-6b1a-45de-ba25-fdac4aff8cbe", name = "Volume block")

        val cycles = repository.getFullCyclesForSync(profileId)
        assertEquals(2, cycles.size, "Both case-variant rows persist locally")
    }

    @Test
    fun `clearPersonalRecordUpdatedAt re-arms a held-back row for the ordinary delta`() = runTest {
        insertPr(workoutMode = "OLD_SCHOOL", uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val row = repository.getFullPRsModifiedSince(0L, profileId).single()

        // Stamp it as synced (what an acked push would do), then prove it drops
        // out of the delta…
        repository.updatePersonalRecordTimestamp(listOf(row.id), timestamp = 1_000L, gatherStartedAt = 0L)
        assertTrue(
            repository.getFullPRsModifiedSince(1_100L, profileId).isEmpty(),
            "A stamped row is not re-selected by the delta",
        )

        // …and that the Issue #634 re-arm path puts it back (held-back rows are
        // never stamped; this covers the recovery path for rows that were).
        repository.clearPersonalRecordUpdatedAt(listOf(row.id))
        assertEquals(
            1,
            repository.getFullPRsModifiedSince(1_100L, profileId).size,
            "updatedAt IS NULL rows are retried on the next sync",
        )
    }
}
