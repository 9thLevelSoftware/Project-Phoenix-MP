package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.SetEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
        explicitNulls = false
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

    @Test
    fun equipmentRackItemsAndRoutineDefaultIdsRoundTrip() {
        val original = BackupData(
            version = 4,
            exportedAt = "2026-06-07T12:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(
                legacyEquipmentRackItems = json.encodeToJsonElement(
                    listOf(
                        RackItem(
                            id = "vest",
                            name = "Weighted vest",
                            category = RackItemCategory.WEIGHTED_VEST,
                            weightKg = 10f,
                            behavior = RackItemBehavior.ADDED_RESISTANCE,
                        ),
                    ),
                ),
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "rex-rack",
                        routineId = "routine-rack",
                        exerciseName = "Pull Up",
                        exerciseMuscleGroup = "Back",
                        exerciseDefaultCableConfig = "DOUBLE",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "8",
                        weightPerCableKg = 0f,
                        defaultRackItemIds = listOf("vest"),
                    ),
                ),
            ),
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        assertEquals(
            "vest",
            json.decodeFromJsonElement<List<RackItem>>(
                requireNotNull(deserialized.data.legacyEquipmentRackItems),
            ).single().id,
        )
        assertEquals(listOf("vest"), deserialized.data.routineExercises.single().defaultRackItemIds)
    }

    @Test
    fun dropSetConfigurationRoundTrip() {
        val original = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-08-20T12:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(
                routineExercises = listOf(
                    RoutineExerciseBackup(
                        id = "rex-drop-on",
                        routineId = "routine-drop",
                        exerciseName = "Bench Press",
                        exerciseMuscleGroup = "Chest",
                        exerciseDefaultCableConfig = "DOUBLE",
                        cableConfig = "DOUBLE",
                        orderIndex = 0,
                        setReps = "8",
                        weightPerCableKg = 20f,
                        dropSetEnabled = true,
                        dropSetMinWeightKg = 7.5f,
                    ),
                    RoutineExerciseBackup(
                        id = "rex-drop-off",
                        routineId = "routine-drop",
                        exerciseName = "Row",
                        exerciseMuscleGroup = "Back",
                        exerciseDefaultCableConfig = "DOUBLE",
                        cableConfig = "DOUBLE",
                        orderIndex = 1,
                        setReps = "10",
                        weightPerCableKg = 15f,
                    ),
                ),
            ),
        )

        val deserialized = json.decodeFromString<BackupData>(json.encodeToString(original))
        val enabled = deserialized.data.routineExercises.first { it.id == "rex-drop-on" }
        val disabled = deserialized.data.routineExercises.first { it.id == "rex-drop-off" }
        assertTrue(enabled.dropSetEnabled)
        assertEquals(7.5f, enabled.dropSetMinWeightKg)
        assertFalse(disabled.dropSetEnabled)
        assertNull(disabled.dropSetMinWeightKg)
    }

    @Test
    fun legacyRoutineExerciseBackupOmittingDropSetDefaultsDisabled() {
        val legacy = """
            {
              "version": 5,
              "exportedAt": "2026-07-01T12:00:00Z",
              "appVersion": "0.8.0",
              "data": {
                "routineExercises": [
                  {
                    "id": "rex-legacy",
                    "routineId": "routine-legacy",
                    "exerciseName": "Squat",
                    "exerciseMuscleGroup": "Legs",
                    "exerciseDefaultCableConfig": "DOUBLE",
                    "cableConfig": "DOUBLE",
                    "orderIndex": 0,
                    "setReps": "5",
                    "weightPerCableKg": 40.0
                  }
                ]
              }
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<BackupData>(legacy)
        val exercise = deserialized.data.routineExercises.single()
        assertFalse(exercise.dropSetEnabled)
        assertNull(exercise.dropSetMinWeightKg)
    }

    @Test
    fun privacyMetadataDefaultsToFullPersonalDataExportWithoutSecrets() {
        val backupData = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-06-04T12:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(),
        )

        assertEquals(BackupDataClassification.FULL_PERSONAL_DATA, backupData.privacy.classification)
        assertTrue(backupData.privacy.containsWorkoutHistory)
        assertTrue(backupData.privacy.containsUserProfiles)
        assertTrue(backupData.privacy.containsSessionNotes)
        assertFalse(backupData.privacy.containsAuthTokens)
        assertFalse(backupData.privacy.containsRuntimeSecrets)
    }

    @Test
    fun legacyBackupWithoutPrivacyMetadataDeserializesWithDefaults() {
        val legacyJson = """{"version":3,"exportedAt":"2026-06-04T12:00:00Z","appVersion":"0.9.0","data":{}}"""

        val deserialized = json.decodeFromString<BackupData>(legacyJson)

        assertEquals(BackupDataClassification.FULL_PERSONAL_DATA, deserialized.privacy.classification)
        assertFalse(deserialized.privacy.containsAuthTokens)
        assertFalse(deserialized.privacy.containsRuntimeSecrets)
    }

    @Test
    fun backupSerializationDoesNotIncludeAuthTokensOrRuntimeSecretFields() {
        val backupData = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-06-04T12:00:00Z",
            appVersion = "0.9.0",
            data = BackupContent(
                userProfiles = listOf(
                    UserProfileBackup(
                        id = "profile-1",
                        name = "Primary",
                        colorIndex = 2,
                        createdAt = 1000L,
                        isActive = true,
                    ),
                ),
                sessionNotes = listOf(
                    SessionNotesBackup(
                        routineSessionId = "routine-session-1",
                        notes = "Personal notes are exported; auth credentials are not.",
                        updatedAt = 2000L,
                    ),
                ),
            ),
        )

        val jsonString = json.encodeToString(backupData)
        val forbidden = listOf(
            "accessToken",
            "refreshToken",
            "access_token",
            "refresh_token",
            "providerAccessToken",
            "providerRefreshToken",
            "SUPABASE_ANON_KEY",
            "supabaseUrl",
            "keystorePassword",
            "local.properties",
        )

        forbidden.forEach { tokenOrSecretField ->
            assertFalse(
                jsonString.contains(tokenOrSecretField),
                "Backup JSON must not contain secret/auth field '$tokenOrSecretField'",
            )
        }
    }

    @Test
    fun v5WireDefaultsExposePreferencesAndOmitLegacyRackAndLocalOnlyState() {
        val backupData = BackupData(
            exportedAt = "2026-07-12T00:00:00Z",
            appVersion = "test",
            data = BackupContent(),
        )

        val jsonString = json.encodeToString(backupData)

        assertEquals(7, CURRENT_BACKUP_VERSION)
        assertTrue(jsonString.contains("\"profilePreferences\""))
        assertFalse(jsonString.contains("equipmentRackItems"))
        listOf(
            "localGeneration",
            "serverRevision",
            "dirty",
            "safeWord",
            "safe_word",
            "calibrated",
            "adultsOnly",
            "adult_confirm",
            "adult_prompt",
        ).forEach { forbidden ->
            assertFalse(jsonString.contains(forbidden, ignoreCase = true), forbidden)
        }
        assertTrue(backupData.privacy.userFacingSummary.contains("profile training preferences"))
        assertTrue(backupData.privacy.userFacingSummary.contains("voice", ignoreCase = true))
        assertTrue(backupData.privacy.userFacingSummary.contains("adult", ignoreCase = true))
    }

    @Test
    fun completedSetEndReasonsRoundTripAndLegacyOrFutureValuesDecodeSafely() {
        SetEndReason.entries.forEach { reason ->
            val original = CompletedSetBackup(
                id = "set-${reason.name}",
                sessionId = "session-1",
                setNumber = 1,
                actualReps = 8,
                actualWeightKg = 40f,
                completedAt = 1000L,
                setEndReason = reason.name,
            )
            assertEquals(reason.name, json.decodeFromString<CompletedSetBackup>(json.encodeToString(original)).setEndReason)
        }

        val legacy = json.decodeFromString<CompletedSetBackup>("""{"id":"legacy","sessionId":"s","setNumber":1,"actualReps":8,"actualWeightKg":40.0,"completedAt":1000}""")
        val future = json.decodeFromString<CompletedSetBackup>("""{"id":"future","sessionId":"s","setNumber":1,"actualReps":8,"actualWeightKg":40.0,"completedAt":1000,"setEndReason":"FUTURE_REASON"}""")

        assertEquals("UNKNOWN", legacy.setEndReason)
        assertEquals("FUTURE_REASON", future.setEndReason)
    }

    @Test
    fun completedSetAttemptIdentityRoundTripsAndLegacyJsonUsesSafeDefaults() {
        val original = CompletedSetBackup(
            id = "attempt-3",
            sessionId = "session-1",
            setNumber = 0,
            actualReps = 8,
            actualWeightKg = 40f,
            completedAt = 1000L,
            routineExerciseId = "routine-exercise-7",
            attemptNumber = 3,
        )

        val restored = json.decodeFromString<CompletedSetBackup>(json.encodeToString(original))
        val legacy = json.decodeFromString<CompletedSetBackup>(
            """{"id":"legacy","sessionId":"s","setNumber":0,"actualReps":8,"actualWeightKg":40.0,"completedAt":1000}""",
        )

        assertEquals("routine-exercise-7", restored.routineExerciseId)
        assertEquals(3, restored.attemptNumber)
        assertEquals(null, legacy.routineExerciseId)
        assertEquals(1, legacy.attemptNumber)
    }

    @Test
    fun polarTelemetryAndSamplesRoundTrip() {
        val original = BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = "2026-10-20T12:00:00Z",
            appVersion = "1.0.4",
            data = BackupContent(
                workoutSessions = listOf(
                    WorkoutSessionBackup(
                        id = "session-polar-1",
                        timestamp = 1700000000000L,
                        mode = "OldSchool",
                        targetReps = 8,
                        weightPerCableKg = 30f,
                        progressionKg = 0f,
                        duration = 60000L,
                        totalReps = 8,
                        warmupReps = 0,
                        workingReps = 8,
                        isJustLift = false,
                        stopAtTop = false,
                        exerciseName = "Bench Press",
                        avgHeartRateBpm = 142,
                        maxHeartRateBpm = 168,
                        minHeartRateBpm = 95,
                        avgRrIntervalMs = 750.5f,
                        rmssdMs = 45.2f,
                        hrr60Bpm = 25,
                        hrr300Bpm = 50,
                        maxTorsoPitchDeg = 32.5f,
                        maxTorsoRollDeg = 15.1f,
                    ),
                ),
                metricSamples = listOf(
                    MetricSampleBackup(
                        id = 1L,
                        sessionId = "session-polar-1",
                        timestamp = 1700000001000L,
                        position = 100f,
                        velocity = 200f,
                        load = 30f,
                        power = 60f,
                        heartRateBpm = 145,
                        rrIntervalMs = 740f,
                        torsoPitchDeg = 30f,
                        torsoRollDeg = 12f,
                        dynamicAccMg = 150,
                        chestAccXMg = 100,
                        chestAccYMg = 200,
                        chestAccZMg = 950,
                    ),
                ),
                polarBeatSamples = listOf(
                    PolarBeatSampleBackup(
                        id = 1L,
                        sessionId = "session-polar-1",
                        timestampMs = 1700000002000L,
                        phase = "WORKING_SET",
                        hrBpm = 145,
                        rrMs = 740.0f,
                        rmssdMs = 45.2f,
                        torsoPitchDeg = 30.0f,
                        torsoRollDeg = 12.0f,
                        dynamicAccMg = 150,
                    ),
                ),
                polarEcgSegments = listOf(
                    PolarEcgSegmentBackup(
                        id = 1L,
                        sessionId = "session-polar-1",
                        sensorTimestampNs = 1000000000L,
                        wallClockTimestampMs = 1700000000000L,
                        phase = "WORKING_SET",
                        samplesUvCsv = "100,105,110",
                    ),
                ),
            ),
        )

        val jsonString = json.encodeToString(original)
        val deserialized = json.decodeFromString<BackupData>(jsonString)

        assertEquals(1, deserialized.data.workoutSessions.size)
        val s = deserialized.data.workoutSessions[0]
        assertEquals(142, s.avgHeartRateBpm)
        assertEquals(168, s.maxHeartRateBpm)
        assertEquals(95, s.minHeartRateBpm)
        assertEquals(750.5f, s.avgRrIntervalMs)
        assertEquals(45.2f, s.rmssdMs)
        assertEquals(25, s.hrr60Bpm)
        assertEquals(50, s.hrr300Bpm)
        assertEquals(32.5f, s.maxTorsoPitchDeg)
        assertEquals(15.1f, s.maxTorsoRollDeg)

        assertEquals(1, deserialized.data.metricSamples.size)
        val m = deserialized.data.metricSamples[0]
        assertEquals(145, m.heartRateBpm)
        assertEquals(740f, m.rrIntervalMs)
        assertEquals(30f, m.torsoPitchDeg)
        assertEquals(12f, m.torsoRollDeg)
        assertEquals(150, m.dynamicAccMg)
        assertEquals(100, m.chestAccXMg)
        assertEquals(200, m.chestAccYMg)
        assertEquals(950, m.chestAccZMg)

        assertEquals(1, deserialized.data.polarBeatSamples.size)
        val b = deserialized.data.polarBeatSamples[0]
        assertEquals(1L, b.id)
        assertEquals(740.0f, b.rrMs)
        assertEquals(145, b.hrBpm)
        assertEquals("WORKING_SET", b.phase)
        assertEquals(45.2f, b.rmssdMs)

        assertEquals(1, deserialized.data.polarEcgSegments.size)
        val e = deserialized.data.polarEcgSegments[0]
        assertEquals(1L, e.id)
        assertEquals("100,105,110", e.samplesUvCsv)
        assertEquals(1000000000L, e.sensorTimestampNs)
    }
}
