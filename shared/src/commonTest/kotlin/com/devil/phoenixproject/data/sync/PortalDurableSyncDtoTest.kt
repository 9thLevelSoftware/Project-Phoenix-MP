package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.WorkoutDeletionScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalDurableSyncDtoTest {
    @Test
    fun `push payload uses canonical durable operation wire fields`() {
        val payload = PortalSyncPayload(
            deviceId = "device-a",
            lastSync = 0,
            workoutDeletions = listOf(
                PortalWorkoutDeletionDto(
                    mutationId = "00000000-0000-4000-8000-000000000001",
                    scope = WorkoutDeletionScope.WORKOUT,
                    portalSessionId = "00000000-0000-4000-8000-000000000002",
                    deletedAt = "2026-09-20T12:00:00Z",
                ),
            ),
            ownershipTransfers = listOf(
                PortalOwnershipTransferDto(
                    mutationId = "00000000-0000-4000-8000-000000000003",
                    sourceProfileId = null,
                    targetProfileId = "profile-b",
                    workoutSessionIds = listOf("00000000-0000-4000-8000-000000000004"),
                ),
            ),
        )

        val encoded = PortalWireJson.encodeToJsonElement(
            PortalSyncPayload.serializer(),
            payload,
        ).jsonObject
        val deletion = encoded.getValue("workoutDeletions").jsonArray.single().jsonObject
        val transfer = encoded.getValue("ownershipTransfers").jsonArray.single().jsonObject

        assertEquals("WORKOUT", deletion.getValue("scope").jsonPrimitive.content)
        assertEquals(
            "00000000-0000-4000-8000-000000000002",
            deletion.getValue("portalSessionId").jsonPrimitive.content,
        )
        assertFalse("componentSessionId" in deletion)
        assertFalse("sourceProfileId" in transfer)
        assertEquals("profile-b", transfer.getValue("targetProfileId").jsonPrimitive.content)
    }

    @Test
    fun `legacy responses default durable arrays to empty`() {
        val push = PortalWireJson.decodeFromString<PortalSyncPushResponse>(
            """{"syncTime":"2026-09-20T12:00:00Z"}""",
        )
        val pull = PortalWireJson.decodeFromString<PortalSyncPullResponse>(
            """{"syncTime":1}""",
        )

        assertEquals(emptyList(), push.acknowledgedWorkoutDeletionIds)
        assertEquals(emptyList(), push.acknowledgedOwnershipTransferIds)
        assertEquals(emptyList(), push.acknowledgedWorkoutSessionIds)
        assertEquals(emptyList(), push.acknowledgedCycleIds)
        assertEquals(emptyList(), pull.workoutDeletions)
        assertEquals(emptyList(), pull.ownershipEvents)
    }

    @Test
    fun `pull decodes account deletion and ownership metadata`() {
        val response = PortalWireJson.decodeFromString<PortalSyncPullResponse>(
            """
            {
              "syncTime":1,
              "workoutDeletions":[{
                "mutationId":"00000000-0000-4000-8000-000000000001",
                "profileId":"profile-a",
                "scope":"COMPONENT",
                "portalSessionId":"00000000-0000-4000-8000-000000000002",
                "componentSessionId":"00000000-0000-4000-8000-000000000003",
                "deletedAt":"2026-09-20T12:00:00Z"
              }],
              "ownershipEvents":[{
                "mutationId":"00000000-0000-4000-8000-000000000004",
                "sourceProfileId":"profile-a",
                "targetProfileId":"profile-b",
                "targetProfileName":"Athlete",
                "targetProfileColorIndex":3,
                "workoutSessionIds":["00000000-0000-4000-8000-000000000003"],
                "routineIds":[],
                "cycleIds":[],
                "personalRecordIds":[],
                "transferredAt":"2026-09-20T12:01:00Z"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(WorkoutDeletionScope.COMPONENT, response.workoutDeletions.single().scope)
        assertEquals("profile-a", response.workoutDeletions.single().profileId)
        assertEquals("Athlete", response.ownershipEvents.single().targetProfileName)
        assertEquals(3, response.ownershipEvents.single().targetProfileColorIndex)
    }

    @Test
    fun `cycle presence flags distinguish legacy omission from an explicit clear`() {
        val legacy = PortalWireJson.decodeFromString<PullTrainingCycleDto>(
            """{"id":"cycle-a","name":"Legacy","days":[{"id":"day-a"}]}""",
        )
        assertNull(legacy.progressStatePresent)
        assertNull(legacy.progressState)
        assertNull(legacy.days.single().echoLevelPresent)
        assertNull(legacy.days.single().eccentricLoadPercentPresent)

        val clear = PortalWireJson.decodeFromString<PullTrainingCycleDto>(
            """
            {
              "id":"cycle-a",
              "name":"Clear",
              "progressStatePresent":true,
              "days":[{
                "id":"day-a",
                "echoLevelPresent":true,
                "eccentricLoadPercentPresent":true
              }]
            }
            """.trimIndent(),
        )
        assertTrue(clear.progressStatePresent == true)
        assertNull(clear.progressState)
        assertTrue(clear.days.single().echoLevelPresent == true)
        assertNull(clear.days.single().echoLevel)
        assertTrue(clear.days.single().eccentricLoadPercentPresent == true)
        assertNull(clear.days.single().eccentricLoadPercent)

        val encodedClear = PortalWireJson.encodeToJsonElement(
            PortalTrainingCycleSyncDto.serializer(),
            PortalTrainingCycleSyncDto(
                id = "cycle-a",
                userId = "owner-a",
                name = "Clear",
                progressStatePresent = true,
                progressState = null,
                days = listOf(
                    PortalCycleDaySyncDto(
                        id = "day-a",
                        cycleId = "cycle-a",
                        dayNumber = 1,
                        echoLevelPresent = true,
                        echoLevel = null,
                        eccentricLoadPercentPresent = true,
                        eccentricLoadPercent = null,
                    ),
                ),
            ),
        ).jsonObject
        assertTrue(encodedClear.getValue("progressStatePresent").jsonPrimitive.content.toBoolean())
        assertFalse("progressState" in encodedClear)
        val encodedDay = encodedClear.getValue("days").jsonArray.single().jsonObject
        assertTrue(encodedDay.getValue("echoLevelPresent").jsonPrimitive.content.toBoolean())
        assertFalse("echoLevel" in encodedDay)
        assertTrue(encodedDay.getValue("eccentricLoadPercentPresent").jsonPrimitive.content.toBoolean())
        assertFalse("eccentricLoadPercent" in encodedDay)
    }
}
