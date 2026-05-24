package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class IntegrationDtosTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun decodesOldActivityOnlyResponse() {
        val decoded = json.decodeFromString(
            IntegrationSyncResponse.serializer(),
            """
            {
              "status": "ok",
              "activities": [
                {
                  "externalId": "a1",
                  "provider": "hevy",
                  "name": "Push",
                  "startedAt": "2026-03-20T10:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("ok", decoded.status)
        assertEquals(1, decoded.activities.size)
        assertTrue(decoded.routines.isEmpty())
        assertTrue(decoded.programs.isEmpty())
        assertFalse(decoded.requiresUpgrade)
        assertNull(decoded.nextCursor)
    }

    @Test
    fun decodesExpandedResponseWithEntitlementAndUnknownFields() {
        val decoded = json.decodeFromString(
            IntegrationSyncResponse.serializer(),
            """
            {
              "status": "ok",
              "unknownField": true,
              "routines": [
                {
                  "externalId": "r1",
                  "provider": "hevy",
                  "title": "Upper",
                  "exercises": [
                    {
                      "title": "Bench",
                      "primaryMuscleGroups": ["chest"],
                      "sets": [{"index": 0, "reps": 8, "weightKg": 80.0}]
                    }
                  ]
                }
              ],
              "routineFolders": [{"externalId": "f1", "provider": "hevy", "title": "Main"}],
              "exerciseTemplates": [{"externalId": "t1", "provider": "hevy", "title": "Bench"}],
              "bodyMeasurements": [{"externalId": "m1", "provider": "hevy", "measurementType": "weight", "value": 82.5, "unit": "kg", "measuredAt": "2026-03-21T10:00:00Z"}],
              "programs": [{"externalId": "p1", "provider": "liftosaur", "name": "LP", "isCurrent": true}],
              "programStats": [{"externalProgramId": "p1", "days": 4, "setCount": 20}],
              "requiresUpgrade": true,
              "upgradeReason": "Program stats require Premium",
              "providerPlanName": "Free",
              "entitlementStatus": "requires_upgrade",
              "partial": true,
              "hasMore": true,
              "nextCursor": "cursor-2",
              "providerSyncCursor": "provider-cursor",
              "deletedExternalIds": ["a1"],
              "updatedExternalIds": ["a2"],
              "errors": [{"entityType": "measurements", "message": "Rate limited", "code": "rate_limited", "retryAfterSeconds": 60}]
            }
            """.trimIndent(),
        )

        assertEquals(1, decoded.routines.size)
        assertEquals(1, decoded.routines.single().exercises.single().sets.size)
        assertEquals(1, decoded.routineFolders.size)
        assertEquals(1, decoded.exerciseTemplates.size)
        assertEquals(1, decoded.bodyMeasurements.size)
        assertEquals(1, decoded.programs.size)
        assertEquals(1, decoded.programStats.size)
        assertTrue(decoded.requiresUpgrade)
        assertTrue(decoded.partial)
        assertEquals("cursor-2", decoded.nextCursor)
        assertEquals("provider-cursor", decoded.providerSyncCursor)
        assertEquals("a1", decoded.deletedExternalIds.single())
        assertEquals("rate_limited", decoded.errors.single().code)
    }

    @Test
    fun decodesPlaygroundSimulationResponse() {
        val decoded = json.decodeFromString(
            IntegrationPlaygroundSimulationResponse.serializer(),
            """
            {
              "status": "ok",
              "preview": {
                "programExternalId": "p1",
                "currentWorkoutText": "A",
                "nextWorkoutText": "B",
                "updatedProgramText": "C",
                "commandsApplied": ["advance"]
              }
            }
            """.trimIndent(),
        )

        assertEquals("ok", decoded.status)
        assertEquals("p1", decoded.preview?.programExternalId)
        assertEquals("C", decoded.preview?.updatedProgramText)
    }
}
