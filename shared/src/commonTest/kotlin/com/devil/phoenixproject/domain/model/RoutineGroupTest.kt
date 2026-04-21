package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for RoutineGroup entity and Routine.groupId assignment (Issue #307).
 *
 * Covers:
 * - RoutineGroup creation, rename, ordering
 * - Routine ↔ Group assignment (move to group, remove from group)
 * - Profile scoping for groups
 * - Batch operations
 * - Default (ungrouped) state
 * - Duplicate group names allowed
 */
class RoutineGroupTest {

    // ── Helpers ─────────────────────────────────────────────────────

    private fun group(
        id: String = "group-1",
        name: String = "Push Day",
        profileId: String = "default",
        orderIndex: Int = 0,
    ) = RoutineGroup(
        id = id,
        name = name,
        profileId = profileId,
        orderIndex = orderIndex,
    )

    private fun routine(
        id: String = "r1",
        name: String = "Test Routine",
        groupId: String? = null,
        profileId: String = "default",
    ) = Routine(
        id = id,
        name = name,
        groupId = groupId,
        profileId = profileId,
    )

    // ── Creation ────────────────────────────────────────────────────

    @Test
    fun createRoutineGroup_fieldsPopulatedCorrectly() {
        val g = group(id = "g1", name = "Push Day", profileId = "profile-A", orderIndex = 3)

        assertEquals("g1", g.id)
        assertEquals("Push Day", g.name)
        assertEquals("profile-A", g.profileId)
        assertEquals(3, g.orderIndex)
        assertTrue(g.createdAt > 0, "createdAt should be a positive timestamp")
    }

    // ── Rename ──────────────────────────────────────────────────────

    @Test
    fun renameRoutineGroup_copyWithNewName() {
        val original = group(name = "Push Day")
        val renamed = original.copy(name = "Upper Body")

        assertEquals("Upper Body", renamed.name)
        assertEquals(original.id, renamed.id)
        assertEquals(original.profileId, renamed.profileId)
    }

    // ── Delete group preserves routines (model-level) ───────────────

    @Test
    fun deleteGroupPreservesRoutines_setGroupIdToNull() {
        // Simulate ON DELETE SET NULL: when group is removed, routine's groupId becomes null
        val g = group(id = "g1")
        val r = routine(id = "r1", groupId = g.id)

        assertEquals("g1", r.groupId)

        // Simulate group deletion: null out groupId
        val orphaned = r.copy(groupId = null)
        assertNull(orphaned.groupId)
        assertEquals("r1", orphaned.id) // Routine itself is preserved
        assertEquals("Test Routine", orphaned.name)
    }

    // ── Move routine to group ───────────────────────────────────────

    @Test
    fun moveRoutineToGroup_setsGroupId() {
        val g = group(id = "g-push")
        val r = routine(id = "r1", groupId = null)

        val moved = r.copy(groupId = g.id)
        assertEquals("g-push", moved.groupId)
    }

    // ── Remove routine from group ───────────────────────────────────

    @Test
    fun removeRoutineFromGroup_setsGroupIdNull() {
        val r = routine(id = "r1", groupId = "g1")

        val removed = r.copy(groupId = null)
        assertNull(removed.groupId)
    }

    // ── Profile scoping ─────────────────────────────────────────────

    @Test
    fun groupsScopedToProfile_filterByProfileId() {
        val groups = listOf(
            group(id = "g1", name = "Push A", profileId = "profileA"),
            group(id = "g2", name = "Pull A", profileId = "profileA"),
            group(id = "g3", name = "Push B", profileId = "profileB"),
        )

        val profileAGroups = groups.filter { it.profileId == "profileA" }
        val profileBGroups = groups.filter { it.profileId == "profileB" }

        assertEquals(2, profileAGroups.size)
        assertEquals(1, profileBGroups.size)
        assertTrue(profileAGroups.all { it.profileId == "profileA" })
        assertTrue(profileBGroups.all { it.profileId == "profileB" })
    }

    // ── Batch move ──────────────────────────────────────────────────

    @Test
    fun batchMoveRoutinesToGroup_allHaveCorrectGroupId() {
        val g = group(id = "g-legs")
        val routines = listOf(
            routine(id = "r1"),
            routine(id = "r2"),
            routine(id = "r3"),
        )

        val moved = routines.map { it.copy(groupId = g.id) }

        assertEquals(3, moved.size)
        moved.forEach { r ->
            assertEquals("g-legs", r.groupId, "Routine ${r.id} should be in group g-legs")
        }
    }

    // ── Order index ─────────────────────────────────────────────────

    @Test
    fun groupOrderIndex_sortsByOrderIndex() {
        val groups = listOf(
            group(id = "g3", name = "Third", orderIndex = 2),
            group(id = "g1", name = "First", orderIndex = 0),
            group(id = "g2", name = "Second", orderIndex = 1),
        )

        val sorted = groups.sortedBy { it.orderIndex }

        assertEquals("g1", sorted[0].id)
        assertEquals("g2", sorted[1].id)
        assertEquals("g3", sorted[2].id)
    }

    // ── Duplicate names ─────────────────────────────────────────────

    @Test
    fun duplicateGroupNamesAllowed_bothExist() {
        val g1 = group(id = "g1", name = "Push Day")
        val g2 = group(id = "g2", name = "Push Day")

        assertEquals(g1.name, g2.name)
        assertNotEquals(g1.id, g2.id)
        // Both can coexist in a list (no uniqueness constraint on name)
        val groups = listOf(g1, g2)
        assertEquals(2, groups.size)
        assertEquals(2, groups.filter { it.name == "Push Day" }.size)
    }

    // ── Ungrouped default ───────────────────────────────────────────

    @Test
    fun ungroupedRoutinesHaveNullGroupId() {
        val r = Routine(id = "r1", name = "Default Routine")
        assertNull(r.groupId, "Default Routine should have null groupId")
    }
}
