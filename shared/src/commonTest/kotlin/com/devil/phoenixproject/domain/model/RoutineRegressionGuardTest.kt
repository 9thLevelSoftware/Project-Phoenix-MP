package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.util.BackupContent
import com.devil.phoenixproject.util.BackupData
import com.devil.phoenixproject.util.RoutineBackup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guards for Phase 39 features.
 *
 * Ensures that superset reorder (Issue #365) and routine grouping (Issue #307)
 * do not break existing routine operations: round-trip fidelity, superset membership,
 * top-level reorder, normalizeRoutine behavior, and groupId preservation.
 */
class RoutineRegressionGuardTest {

    // ── Helpers ─────────────────────────────────────────────────────

    private fun exercise(
        id: String,
        orderIndex: Int,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
        weightPerCableKg: Float = 25f,
        programMode: ProgramMode = ProgramMode.OldSchool,
    ) = RoutineExercise(
        id = id,
        exercise = Exercise(name = "Exercise $id", muscleGroup = "Chest", id = id),
        orderIndex = orderIndex,
        weightPerCableKg = weightPerCableKg,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
        programMode = programMode,
    )

    // ── Existing routine round-trip ─────────────────────────────────

    @Test
    fun existingRoutine_noGroupId_roundTripPreservesAllFields() {
        val original = Routine(
            id = "r1",
            name = "Full Body",
            description = "Complete workout",
            exercises = listOf(
                exercise("e1", orderIndex = 0, weightPerCableKg = 40f),
                exercise("e2", orderIndex = 1, weightPerCableKg = 50f),
            ),
            supersets = emptyList(),
            useCount = 5,
            profileId = "profile-main",
            groupId = null,
        )

        // Simulate round-trip: copy with no changes
        val roundTrip = original.copy()

        assertEquals(original.id, roundTrip.id)
        assertEquals(original.name, roundTrip.name)
        assertEquals(original.description, roundTrip.description)
        assertEquals(original.exercises.size, roundTrip.exercises.size)
        assertEquals(original.supersets.size, roundTrip.supersets.size)
        assertEquals(original.useCount, roundTrip.useCount)
        assertEquals(original.profileId, roundTrip.profileId)
        assertNull(roundTrip.groupId, "groupId should remain null after round-trip")

        // Verify each exercise field
        original.exercises.forEachIndexed { i, expected ->
            val actual = roundTrip.exercises[i]
            assertEquals(expected.id, actual.id)
            assertEquals(expected.orderIndex, actual.orderIndex)
            assertEquals(expected.weightPerCableKg, actual.weightPerCableKg)
            assertEquals(expected.programMode, actual.programMode)
        }
    }

    // ── Existing routine with supersets unchanged ────────────────────

    @Test
    fun existingRoutine_withSupersets_orderingAndMembershipUnchanged() {
        val ssId = "ss-alpha"
        val exercises = listOf(
            exercise("e1", orderIndex = 0, supersetId = ssId, orderInSuperset = 0),
            exercise("e2", orderIndex = 1, supersetId = ssId, orderInSuperset = 1),
            exercise("e3", orderIndex = 2), // standalone
            exercise("e4", orderIndex = 3, supersetId = ssId, orderInSuperset = 2),
        )
        val supersets = listOf(
            Superset(id = ssId, routineId = "r1", name = "SS Alpha", orderIndex = 0),
        )
        val routine = Routine(
            id = "r1",
            name = "Superset Routine",
            exercises = exercises,
            supersets = supersets,
        )

        // getItems should produce correct structure
        val items = routine.getItems()

        // Should have 2 items: 1 superset + 1 standalone
        assertEquals(2, items.size)

        val ssItem = items.filterIsInstance<RoutineItem.SupersetItem>().single()
        assertEquals(3, ssItem.superset.exercises.size)
        assertEquals("e1", ssItem.superset.exercises[0].id)
        assertEquals("e2", ssItem.superset.exercises[1].id)
        assertEquals("e4", ssItem.superset.exercises[2].id)

        val single = items.filterIsInstance<RoutineItem.Single>().single()
        assertEquals("e3", single.exercise.id)
    }

    // ── Top-level reorder via normalizeRoutine ──────────────────────

    @Test
    fun routineEditorReorder_topLevel_normalizeProducesCorrectOutput() {
        // Simulate user dragging a standalone exercise above a superset
        // Before: SS[e1,e2](order=0), e3(order=2)
        // After drag: e3(order=0), SS[e1,e2](order=1)
        val ssId = "ss1"
        val exercises = listOf(
            exercise("e1", orderIndex = 1, supersetId = ssId, orderInSuperset = 0),
            exercise("e2", orderIndex = 2, supersetId = ssId, orderInSuperset = 1),
            exercise("e3", orderIndex = 0), // moved above superset
        )
        val supersets = listOf(
            Superset(id = ssId, routineId = "r1", name = "SS1", orderIndex = 1),
        )
        val routine = Routine(
            id = "r1",
            name = "Test",
            exercises = exercises,
            supersets = supersets,
        )

        val normalized = normalizeRoutine(routine, preserveIntraSupersetOrder = false)

        // e3 should be first (orderIndex 0), then ss exercises contiguous
        val items = normalized.getItems()
        assertTrue(items[0] is RoutineItem.Single, "First item should be standalone e3")
        assertEquals("e3", (items[0] as RoutineItem.Single).exercise.id)

        assertTrue(items[1] is RoutineItem.SupersetItem, "Second item should be superset")
        val ssExercises = (items[1] as RoutineItem.SupersetItem).superset.exercises
        assertEquals(2, ssExercises.size)
    }

    // ── normalizeRoutine preserves groupId ───────────────────────────

    @Test
    fun normalizeRoutine_preservesGroupId() {
        val routine = Routine(
            id = "r1",
            name = "Grouped Routine",
            exercises = listOf(
                exercise("e1", orderIndex = 0),
                exercise("e2", orderIndex = 1),
            ),
            groupId = "g-push",
        )

        val normalized = normalizeRoutine(routine)

        assertEquals("g-push", normalized.groupId, "normalizeRoutine must not strip groupId")
    }

    // ── Backup v2 backward compatibility ──────────────────────────────

    @Test
    fun backupV2_withoutRoutineGroupFields_producesValidDefaults() {
        // Simulate a v2 backup that has no routineGroups and no groupId on routines.
        // BackupContent.routineGroups defaults to emptyList(), RoutineBackup.groupId defaults to null.
        val v2Routine = RoutineBackup(
            id = "r1",
            name = "Legacy Routine",
            createdAt = 1000L,
            // groupId intentionally omitted — relies on default null
        )
        val v2Content = BackupContent(
            routines = listOf(v2Routine),
            // routineGroups intentionally omitted — relies on default emptyList()
        )
        val v2Backup = BackupData(
            version = 2,
            exportedAt = "2026-01-01T00:00:00Z",
            appVersion = "0.8.0",
            data = v2Content,
        )

        // Verify v3 fields default correctly on a v2-shaped backup
        assertNull(v2Backup.data.routines.first().groupId, "v2 routines should have null groupId by default")
        assertTrue(v2Backup.data.routineGroups.isEmpty(), "v2 backups should have empty routineGroups by default")
        assertEquals(2, v2Backup.version, "Version should reflect v2 origin")
    }

    // ── normalizeRoutine produces contiguous indices ─────────────────

    @Test
    fun normalizeRoutine_contiguousIndices_afterGapIntroduction() {
        // Exercises with gaps in orderIndex (e.g., after deletion)
        val ssId = "ss1"
        val exercises = listOf(
            exercise("e1", orderIndex = 0, supersetId = ssId, orderInSuperset = 0),
            exercise("e2", orderIndex = 5, supersetId = ssId, orderInSuperset = 1),
            exercise("e3", orderIndex = 10), // standalone with large gap
        )
        val supersets = listOf(
            Superset(id = ssId, routineId = "r1", name = "SS1", orderIndex = 0),
        )
        val routine = Routine(
            id = "r1",
            name = "Gappy",
            exercises = exercises,
            supersets = supersets,
        )

        val normalized = normalizeRoutine(routine, preserveIntraSupersetOrder = false)

        // orderIndex should be contiguous: 0, 1, 2
        val indices = normalized.exercises.map { it.orderIndex }.sorted()
        assertEquals(listOf(0, 1, 2), indices, "orderIndex values must be contiguous after normalization")

        // orderInSuperset should be contiguous within superset: 0, 1
        val ssOrders = normalized.exercises
            .filter { it.supersetId == ssId }
            .map { it.orderInSuperset }
            .sorted()
        assertEquals(listOf(0, 1), ssOrders, "orderInSuperset values must be contiguous within superset")
    }
}
