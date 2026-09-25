package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.csv.RoutineCsvCodec
import com.devil.phoenixproject.domain.csv.RoutineCsvExportResult
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlanner
import com.devil.phoenixproject.domain.csv.RoutineCsvParseResult
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Issue #772: the CSV import commit against real SQLite (foreign keys on). */
class RoutineCsvImportRepositoryTest {
    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightWorkoutRepository
    private val exercises = FakeExerciseRepository()
    private val bench = Exercise(id = "bench-id", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR")
    private val row = Exercise(id = "row-id", name = "Seated Row", muscleGroup = "Back", equipment = "BAR")

    @BeforeTest
    fun setUp() {
        database = createTestDatabase()
        repository = SqlDelightWorkoutRepository(database, exercises)
        listOf(bench, row).forEach { exercise ->
            seedExercise(exercise.id!!, exercise.name)
            exercises.addExercise(exercise)
        }
        database.phoenixDatabaseQueries.insertProfile("p1", "Lifter", 0L, 1L, 1L)
    }

    private fun seedExercise(id: String, name: String) {
        database.phoenixDatabaseQueries.insertExercise(
            id = id, name = name, displayName = null, description = null, created = 0L,
            muscleGroup = "Chest", muscleGroups = "Chest", muscles = null, equipment = "BAR", movement = null,
            sidedness = null, grip = null, gripWidth = null, minRepRange = null, popularity = 0.0, archived = 0L,
            isFavorite = 0L, isCustom = 0L, timesPerformed = 0L, lastPerformed = null, aliases = null,
            defaultCableConfig = "DOUBLE", one_rep_max_kg = null, mvtOverrideMs = null, isBodyweight = null,
        )
    }

    private suspend fun planFor(text: String, mode: RoutineCsvImportMode) = RoutineCsvImportPlanner().plan(
        drafts = assertIs<RoutineCsvParseResult.Parsed>(RoutineCsvCodec.parse(text)).routines,
        mode = mode,
        profileId = "p1",
        existingRoutines = repository.getRoutineHeaders("p1"),
        existingGroups = repository.getRoutineGroupsSnapshot("p1"),
        exerciseLibrary = listOf(bench, row),
        nowMs = 5_000L,
    )

    private fun csv(vararg rows: String) = (listOf("# phoenix_routine_csv_version=1", HEADER) + rows).joinToString("\n")

    @Test
    fun descriptionsSurviveSaveAndReload() = runTest {
        repository.saveRoutine(routine("r1", "Push", description = "Heavy day"))

        assertEquals("Heavy day", repository.getRoutineById("r1")?.description)
        assertEquals("Heavy day", repository.getAllRoutines("p1").first().single().description)
        assertEquals("Heavy day", repository.getRoutineHeaders("p1").single().description)

        repository.updateRoutine(routine("r1", "Push", description = "Lighter day"))
        assertEquals("Lighter day", repository.getRoutineById("r1")?.description)
    }

    @Test
    fun anImportWithGroupsAndSupersetsIsStoredAndExportsTheSameContent() = runTest {
        val text = csv(
            ",Upper,Two pairs,Strength,1,bench-id,Bench Press,0,a,Pair,,15,8|8,40|42.5,120,OLD_SCHOOL,false",
            ",Upper,Two pairs,Strength,1,row-id,Seated Row,1,a,Pair,,15,10|10,30|30,120,PUMP,false",
        )
        val plan = planFor(text, RoutineCsvImportMode.CREATE_NEW)
        assertTrue(plan.canCommit, plan.issues.toString())

        repository.commitRoutineCsvImport("p1", plan.newGroups, plan.writes, plan.overwriteRoutineIds)

        val stored = repository.getAllRoutines("p1").first().single()
        val group = repository.getRoutineGroupsSnapshot("p1").single()
        assertEquals("Strength", group.name)
        assertEquals(group.id, stored.groupId)
        assertEquals("Two pairs", stored.description)
        assertEquals(listOf(bench.id, row.id), stored.exercises.map { it.exercise.id })
        assertEquals(listOf(40f, 42.5f), stored.exercises.first().setWeightsPerCableKg)
        assertEquals(stored.supersets.single().id, stored.exercises.last().supersetId)

        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(stored, group.name, group.orderIndex))
        assertTrue(exported.content.startsWith("# phoenix_routine_csv_version=2"), "stored routines export as version 2")
        val reparsed = assertIs<RoutineCsvParseResult.Parsed>(RoutineCsvCodec.parse(exported.content)).routines.single()
        assertEquals(stored.id, reparsed.routineId)
        assertEquals(listOf(listOf(8, 8), listOf(10, 10)), reparsed.exercises.map { it.setReps })
        assertEquals("Pair", reparsed.exercises.first().supersetName)
    }

    @Test
    fun overwriteKeepsTheRoutinesIdentityUsageAndGroup() = runTest {
        repository.saveRoutineGroup(RoutineGroup(id = "g1", name = "Old group", profileId = "p1"))
        repository.saveRoutine(
            routine("r1", "Push", description = "old").copy(createdAt = 11L, lastUsed = 22L, useCount = 3, groupId = "g1"),
        )
        val plan = planFor(csv("r1,Push,new,,,row-id,Seated Row,0,,,,,5,50,90,,"), RoutineCsvImportMode.OVERWRITE_MATCHING)

        repository.commitRoutineCsvImport("p1", plan.newGroups, plan.writes, plan.overwriteRoutineIds)

        val stored = repository.getRoutineById("r1")!!
        assertEquals("new", stored.description)
        assertEquals(11L, stored.createdAt)
        assertEquals(22L, stored.lastUsed)
        assertEquals(3, stored.useCount)
        assertEquals("g1", stored.groupId)
        assertEquals(listOf(row.id), stored.exercises.map { it.exercise.id })
        assertEquals(1, repository.getRoutineHeaders("p1").size)
    }

    @Test
    fun aTargetDeletedAfterThePreviewRollsBackTheWholeImport() = runTest {
        repository.saveRoutine(routine("r1", "Push"))
        val plan = planFor(
            csv(
                ",Brand new,,New group,,bench-id,Bench Press,0,,,,,5,50,,,",
                "r1,Push,,,,row-id,Seated Row,0,,,,,5,50,,,",
            ),
            RoutineCsvImportMode.OVERWRITE_MATCHING,
        )
        assertTrue(plan.canCommit, plan.issues.toString())
        repository.deleteRoutine("r1")

        assertFailsWith<RoutineCsvImportConflictException> {
            repository.commitRoutineCsvImport("p1", plan.newGroups, plan.writes, plan.overwriteRoutineIds)
        }

        assertTrue(repository.getRoutineHeaders("p1").isEmpty(), "the new routine must not be written either")
        assertTrue(repository.getRoutineGroupsSnapshot("p1").isEmpty(), "nor the new group")
        assertEquals(bench.id, repository.getRoutineById("r1")?.exercises?.single()?.exercise?.id, "the deleted target is untouched")
    }

    @Test
    fun aTargetMovedToAnotherProfileIsNotOverwritten() = runTest {
        database.phoenixDatabaseQueries.insertProfile("p2", "Partner", 1L, 1L, 0L)
        repository.saveRoutine(routine("r1", "Push"))
        val plan = planFor(csv("r1,Push,,,,row-id,Seated Row,0,,,,,5,50,,,"), RoutineCsvImportMode.OVERWRITE_MATCHING)
        repository.moveRoutineToProfile("r1", "p2")

        assertFailsWith<RoutineCsvImportConflictException> {
            repository.commitRoutineCsvImport("p1", plan.newGroups, plan.writes, plan.overwriteRoutineIds)
        }
        assertEquals("p2", repository.getRoutineById("r1")?.profileId)
    }

    private fun routine(id: String, name: String, description: String = "") = Routine(
        id = id,
        name = name,
        description = description,
        profileId = "p1",
        exercises = listOf(
            RoutineExercise(
                id = "$id-e1",
                exercise = bench,
                orderIndex = 0,
                setReps = listOf(5),
                weightPerCableKg = 50f,
                setWeightsPerCableKg = listOf(50f),
                setRestSeconds = listOf(90),
            ),
        ),
    )

    private companion object {
        const val HEADER = "routine_id,routine_name,routine_description,group_name,group_order,exercise_id,exercise_name," +
            "exercise_order,superset_key,superset_name,superset_order,superset_rest_seconds,set_reps,set_weights_kg," +
            "rest_seconds,mode,is_amrap,superset_color"
    }
}
