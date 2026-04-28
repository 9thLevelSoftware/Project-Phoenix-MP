package com.devil.phoenixproject.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for backup serialization round-trips with v0.9.0 additions.
 *
 * Phase 42 added RoutineGroup support (migration 27, backup v3):
 * - RoutineGroupBackup entity in BackupContent.routineGroups
 * - RoutineBackup.groupId field linking routines to groups
 *
 * These tests verify that new fields survive serialization round-trips and
 * that backups without the new fields (v1/v2) still deserialize cleanly.
 */
class BackupSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun routineGroupAndGroupIdRoundTrip() {
        val original = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-04-28T12:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(
                routines = listOf(
                    RoutineBackup(
                        id = "routine-1",
                        name = "Push Day",
                        createdAt = 1000L,
                        groupId = "group-A",
                    ),
                ),
                routineGroups = listOf(
                    RoutineGroupBackup(
                        id = "group-A",
                        name = "Upper Body",
                        orderIndex = 0,
                        createdAt = 900L,
                        profileId = "default",
                    ),
                ),
            ),
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        assertEquals(CURRENT_BACKUP_VERSION, deserialized.version, "Version should survive round-trip")
        assertEquals(1, deserialized.data.routines.size, "Should have 1 routine")
        assertEquals("group-A", deserialized.data.routines[0].groupId, "groupId should survive round-trip")
        assertEquals(1, deserialized.data.routineGroups.size, "Should have 1 routine group")
        assertEquals("Upper Body", deserialized.data.routineGroups[0].name, "Group name should survive")
        assertEquals(0, deserialized.data.routineGroups[0].orderIndex, "orderIndex should survive")
    }

    @Test
    fun nullGroupIdBackwardCompatibility() {
        // Simulate a pre-v3 backup: routines without groupId, no routineGroups
        val original = BackupData(
            version = 2,
            exportedAt = "2026-01-01T00:00:00Z",
            appVersion = "0.8.0",
            data = BackupContent(
                routines = listOf(
                    RoutineBackup(
                        id = "routine-old",
                        name = "Legacy Routine",
                        createdAt = 500L,
                        groupId = null,
                    ),
                ),
                // No routineGroups — empty by default
            ),
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        assertEquals(1, deserialized.data.routines.size, "Should have 1 routine")
        assertNull(deserialized.data.routines[0].groupId, "groupId should be null for old backups")
        assertTrue(deserialized.data.routineGroups.isEmpty(), "routineGroups should be empty for old backups")
    }

    @Test
    fun routineGroupEntityFieldsSurviveSerialization() {
        val group = RoutineGroupBackup(
            id = "group-X",
            name = "Leg Day Group",
            orderIndex = 3,
            createdAt = 1714300000000L,
            profileId = "user-42",
        )

        val backupData = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-04-28T15:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(routineGroups = listOf(group)),
        )

        val jsonString = json.encodeToString(backupData)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        val restored = deserialized.data.routineGroups[0]
        assertEquals("group-X", restored.id, "id should survive")
        assertEquals("Leg Day Group", restored.name, "name should survive")
        assertEquals(3, restored.orderIndex, "orderIndex should survive")
        assertEquals(1714300000000L, restored.createdAt, "createdAt should survive")
        assertEquals("user-42", restored.profileId, "profileId should survive")
    }

    @Test
    fun mixedGroupAssignmentsPreserved() {
        // 3 routines: 2 with groupId="group-A", 1 with null
        val original = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-04-28T16:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(
                routines = listOf(
                    RoutineBackup(id = "r1", name = "Push", createdAt = 100L, groupId = "group-A"),
                    RoutineBackup(id = "r2", name = "Pull", createdAt = 200L, groupId = "group-A"),
                    RoutineBackup(id = "r3", name = "Legs", createdAt = 300L, groupId = null),
                ),
                routineGroups = listOf(
                    RoutineGroupBackup(
                        id = "group-A",
                        name = "Upper",
                        orderIndex = 0,
                        createdAt = 50L,
                    ),
                ),
            ),
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        val routines = deserialized.data.routines
        assertEquals(3, routines.size, "Should have 3 routines")
        assertEquals("group-A", routines[0].groupId, "r1 should be in group-A")
        assertEquals("group-A", routines[1].groupId, "r2 should be in group-A")
        assertNull(routines[2].groupId, "r3 should have null groupId")

        // Verify grouped count
        val grouped = routines.filter { it.groupId == "group-A" }
        assertEquals(2, grouped.size, "2 routines should be in group-A")
        val ungrouped = routines.filter { it.groupId == null }
        assertEquals(1, ungrouped.size, "1 routine should be ungrouped")
    }
}
