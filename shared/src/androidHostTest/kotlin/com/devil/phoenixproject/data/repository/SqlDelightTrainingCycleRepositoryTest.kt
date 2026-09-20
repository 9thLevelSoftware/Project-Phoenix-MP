package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightTrainingCycleRepositoryTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightTrainingCycleRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightTrainingCycleRepository(database)
    }

    @Test
    fun `saveCycle and getCycleById returns days`() = runTest {
        val cycleId = "cycle-1"
        val days = listOf(
            CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
            CycleDay.restDay(cycleId = cycleId, dayNumber = 2),
        )
        val cycle = TrainingCycle.create(id = cycleId, name = "Test", days = days)

        repository.saveCycle(cycle)

        val loaded = repository.getCycleById(cycleId)
        assertNotNull(loaded)
        assertEquals(2, loaded.days.size)
    }

    @Test
    fun `saveCycle persists templateId and weekNumber`() = runTest {
        val cycleId = "cycle-template-week"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "5/3/1",
            templateId = "template_531",
            weekNumber = 3,
            days = listOf(CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Press")),
        )

        repository.saveCycle(cycle)

        val loaded = repository.getCycleById(cycleId)
        assertNotNull(loaded)
        assertEquals("template_531", loaded.templateId)
        assertEquals(3, loaded.weekNumber)
    }

    @Test
    fun `setActiveCycle updates active flow`() = runTest {
        val cycle = TrainingCycle.create(id = "cycle-2", name = "Active Cycle")
        repository.saveCycle(cycle)

        repository.setActiveCycle("cycle-2", profileId = "default")

        repository.getActiveCycle(profileId = "default").test {
            val active = awaitItem()
            assertNotNull(active)
            assertEquals("cycle-2", active.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reselecting active cycle preserves progress and generation`() = runTest {
        val cycleId = "cycle-active-no-op"
        repository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Active Cycle",
                days = listOf(CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day")),
            ),
        )
        repository.setActiveCycle(cycleId, profileId = "default")
        val progress = assertNotNull(repository.getCycleProgress(cycleId))
        repository.updateCycleProgress(
            progress.copy(
                currentDayNumber = 2,
                completedDays = setOf(1),
                rotationCount = 3,
            ),
        )
        val beforeRepeat = assertNotNull(repository.getCycleProgress(cycleId))
        val generationBeforeRepeat = assertNotNull(repository.getCycleSyncState(cycleId)).dirtyGeneration

        repository.setActiveCycle(cycleId, profileId = "default")

        assertEquals(beforeRepeat, repository.getCycleProgress(cycleId))
        assertEquals(
            generationBeforeRepeat,
            assertNotNull(repository.getCycleSyncState(cycleId)).dirtyGeneration,
        )
    }

    @Test
    fun `push rejection draft preserves sent snapshot across concurrent edit`() = runTest {
        val cycleId = "cycle-rejected-snapshot"
        repository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Sent version",
                days = listOf(
                    CycleDay.create(
                        id = "sent-day",
                        cycleId = cycleId,
                        dayNumber = 1,
                        name = "Sent day",
                    ),
                ),
            ),
        )
        val sentCycle = assertNotNull(repository.getCycleById(cycleId))
        val sentGeneration = assertNotNull(repository.getCycleSyncState(cycleId)).dirtyGeneration
        val sentSnapshot = CycleComponentSnapshot(
            context = com.devil.phoenixproject.data.sync.PortalSyncAdapter.CycleWithContext(
                cycle = sentCycle,
                progress = repository.getCycleProgress(cycleId),
                progression = repository.getCycleProgression(cycleId),
            ),
            localSyncGeneration = sentGeneration,
        )

        repository.updateCycle(sentCycle.copy(name = "Later local edit"))
        repository.saveRejectedCycleDraft(sentSnapshot, rejectedUpdatedAt = 9_000L)

        val draft = repository.getCycleConflictDrafts("default").single()
        assertEquals("Sent version", draft.cycle.name)
        assertEquals("Sent day", draft.cycle.days.single().name)
        assertEquals("${cycleId}:${sentCycle.updatedAt ?: sentCycle.createdAt}", draft.id)
    }

    @Test
    fun `advanceToNextDay wraps based on cycle size`() = runTest {
        val cycleId = "cycle-3"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2"),
            ),
        )
        repository.saveCycle(cycle)
        repository.initializeProgress(cycleId)

        val nextDay = repository.advanceToNextDay(cycleId)
        assertEquals(2, nextDay)

        val progress = repository.getCycleProgress(cycleId)
        assertNotNull(progress?.lastAdvancedAt)
    }

    @Test
    fun `checkAndAutoAdvance advances when overdue`() = runTest {
        val cycleId = "cycle-4"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2"),
            ),
        )
        repository.saveCycle(cycle)
        val progress = repository.initializeProgress(cycleId)

        // Use midnight-of-yesterday to guarantee exactly 1 calendar day difference.
        // pendingAutoAdvanceDays() compares calendar dates in the system timezone,
        // so raw "25 hours ago" millis can still be the same calendar day near midnight
        // depending on timezone. Using yesterday's midnight is deterministic.
        val yesterdayMidnight = java.time.LocalDate.now().minusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        repository.updateCycleProgress(
            progress.copy(lastAdvancedAt = yesterdayMidnight),
        )

        val updated = repository.checkAndAutoAdvance(cycleId)
        assertEquals(2, updated?.currentDayNumber)
    }

    @Test
    fun `checkAndAutoAdvance uses cycle start date when never manually advanced`() = runTest {
        val cycleId = "cycle-4b"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2"),
                CycleDay.create(cycleId = cycleId, dayNumber = 3, name = "Day 3"),
                CycleDay.create(cycleId = cycleId, dayNumber = 4, name = "Day 4"),
            ),
        )
        repository.saveCycle(cycle)
        val progress = repository.initializeProgress(cycleId)

        // Use midnight-of-2-days-ago for timezone-safe day comparison.
        // pendingAutoAdvanceDays() compares calendar dates, not raw millis.
        val twoDaysAgoMidnight = java.time.LocalDate.now().minusDays(2)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        repository.updateCycleProgress(
            progress.copy(
                cycleStartDate = twoDaysAgoMidnight,
                lastAdvancedAt = null,
            ),
        )

        val updated = repository.checkAndAutoAdvance(cycleId)
        assertEquals(3, updated?.currentDayNumber)
        assertNotNull(updated?.lastAdvancedAt)
    }

    @Test
    fun `saveCycleProgression persists progression`() = runTest {
        val cycleId = "cycle-5"
        repository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Cycle",
                days = listOf(CycleDay.restDay(cycleId = cycleId, dayNumber = 1)),
            ),
        )
        val progression = CycleProgression(
            cycleId = cycleId,
            frequencyCycles = 3,
            weightIncreasePercent = 2.5f,
            echoLevelIncrease = true,
        )

        repository.saveCycleProgression(progression)

        val loaded = repository.getCycleProgression(cycleId)
        assertEquals(3, loaded?.frequencyCycles)
        assertTrue(loaded?.echoLevelIncrease == true)
    }

    @Test
    fun `updateWeekNumber persists new week without touching templateId`() = runTest {
        val cycleId = "cycle-update-week"
        repository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Cycle",
                templateId = "template_531",
                weekNumber = 1,
                days = listOf(CycleDay.restDay(cycleId = cycleId, dayNumber = 1)),
            ),
        )

        repository.updateWeekNumber(cycleId, 4)

        val loaded = repository.getCycleById(cycleId)
        assertNotNull(loaded)
        assertEquals("template_531", loaded.templateId)
        assertEquals(4, loaded.weekNumber)
    }

    @Test
    fun `new and nested cycle edits keep an unacknowledged dirty generation`() = runTest {
        val cycleId = "cycle-dirty-generation"
        repository.saveCycle(TrainingCycle.create(id = cycleId, name = "Cycle"))
        val beforeNestedEdit = repository.getCycleSyncState(cycleId)
        assertNotNull(beforeNestedEdit)
        assertTrue(beforeNestedEdit.dirtyGeneration > beforeNestedEdit.acknowledgedGeneration)

        repository.addCycleDay(CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day"))
        val afterNestedEdit = repository.getCycleSyncState(cycleId)
        assertNotNull(afterNestedEdit)
        assertTrue(afterNestedEdit.dirtyGeneration > beforeNestedEdit.dirtyGeneration)

        repository.acknowledgeCycleGeneration(cycleId, beforeNestedEdit.dirtyGeneration)
        val afterStaleAck = repository.getCycleSyncState(cycleId)
        assertNotNull(afterStaleAck)
        assertTrue(afterStaleAck.dirtyGeneration > afterStaleAck.acknowledgedGeneration)
    }

    @Test
    fun `deletion stays pending until exact owner clock and generation acknowledgement`() = runTest {
        val cycleId = "cycle-pending-delete"
        database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
        database.phoenixDatabaseQueries.linkProfileToSupabase("owner-a", 1L, "default")
        repository.saveCycle(TrainingCycle.create(id = cycleId, name = "Cycle", profileId = "default"))

        repository.deleteCycle(cycleId)
        val pending = repository.getPendingCycleDeletions(ownerUserId = "owner-a", profileId = "default")
        val deletion = pending.single()
        assertEquals(cycleId, deletion.id)
        assertNull(repository.getCycleById(cycleId), "soft-deleted rows must leave the visible repository")

        repository.acknowledgeCycleDeletions(
            ownerUserId = "owner-b",
            sentGenerationsById = mapOf(cycleId to deletion.generation),
            acknowledgedIds = setOf(cycleId),
            at = deletion.updatedAt,
        )
        assertEquals(1, repository.getPendingCycleDeletions("owner-a", "default").size)

        repository.acknowledgeCycleDeletions(
            ownerUserId = "owner-a",
            sentGenerationsById = mapOf(cycleId to deletion.generation),
            acknowledgedIds = setOf(cycleId),
            at = deletion.updatedAt,
        )
        assertTrue(repository.getPendingCycleDeletions("owner-a", "default").isEmpty())
    }

    @Test
    fun `getCycleItems includes routine info`() = runTest {
        val cycleId = "cycle-6"
        val routineId = "routine-1"
        insertRoutine(routineId)
        insertRoutineExercise(generateUUID(), routineId, "Bench Press")

        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(
                    cycleId = cycleId,
                    dayNumber = 1,
                    name = "Day 1",
                    routineId = routineId,
                ),
            ),
        )
        repository.saveCycle(cycle)

        val items = repository.getCycleItems(cycleId)
        assertEquals(1, items.size)
        val item = items.first()
        assertTrue(item is com.devil.phoenixproject.domain.model.CycleItem.Workout)
    }

    private fun insertRoutine(id: String) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = id,
            name = "Routine",
            description = "",
            createdAt = 0L,
            lastUsed = null,
            useCount = 0L,
            profile_id = "default",
            groupId = null,
            deletedAt = null,
        )
    }

    private fun insertRoutineExercise(id: String, routineId: String, name: String) {
        database.seedExercise("bench", "Bench Press")
        database.phoenixDatabaseQueries.insertRoutineExercise(
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
}
