package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ProfilePreferenceSyncDtosTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `legacy responses decode without falsely acknowledging preferences`() {
        val push = json.decodeFromString<PortalSyncPushResponse>(
            """{"syncTime":"2026-07-11T12:00:00Z"}""",
        )
        val pull = json.decodeFromString<PortalSyncPullResponse>(
            """{"syncTime":1783771200000}""",
        )

        assertNull(push.profilePreferencesAccepted)
        assertTrue(push.canonicalProfilePreferenceSections.isEmpty())
        assertTrue(push.profilePreferenceRejections.isEmpty())
        assertNull(pull.profilePreferenceSections)

        val encodedPush = json.encodeToJsonElement(push).jsonObject
        val encodedPull = json.encodeToJsonElement(pull).jsonObject
        assertFalse("profilePreferencesAccepted" in encodedPush)
        assertEquals(
            JsonArray(emptyList()),
            encodedPush.getValue("canonicalProfilePreferenceSections"),
        )
        assertEquals(
            JsonArray(emptyList()),
            encodedPush.getValue("profilePreferenceRejections"),
        )
        assertFalse("profilePreferenceSections" in encodedPull)
    }

    @Test
    fun `mutation has exact value-only wire keys and JSON number revisions`() {
        val mutation = PortalProfilePreferenceSectionMutationDto(
            localProfileId = "profile-a",
            section = "WORKOUT",
            documentVersion = 1,
            baseRevision = 4,
            clientModifiedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject {
                put("version", 1)
                put("voiceStopEnabled", true)
            },
        )

        val encoded = json.encodeToJsonElement(mutation).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "documentVersion", "baseRevision",
                "clientModifiedAt", "payload",
            ),
            encoded.keys,
        )
        assertEquals("profile-a", encoded.getValue("localProfileId").jsonPrimitive.content)
        assertEquals("WORKOUT", encoded.getValue("section").jsonPrimitive.content)
        assertFalse(encoded.getValue("documentVersion").jsonPrimitive.isString)
        assertEquals(1, encoded.getValue("documentVersion").jsonPrimitive.int)
        assertFalse(encoded.getValue("baseRevision").jsonPrimitive.isString)
        assertEquals(4L, encoded.getValue("baseRevision").jsonPrimitive.long)
        assertEquals(
            "2026-07-11T12:00:00Z",
            encoded.getValue("clientModifiedAt").jsonPrimitive.content,
        )
        val payload = encoded.getValue("payload")
        assertTrue(payload is JsonObject)
        assertEquals(setOf("version", "voiceStopEnabled"), payload.jsonObject.keys)
        assertFalse(payload.jsonObject.getValue("version").jsonPrimitive.isString)
        assertTrue(payload.jsonObject.getValue("voiceStopEnabled").jsonPrimitive.boolean)
    }

    @Test
    fun `recursive normalized local-only names cannot enter mutation or canonical DTOs`() {
        listOf(
            "safeWord",
            "safeWord\u0130",
            "SAFE_WORD",
            "safe-word-calibrated",
            "adults_only_confirmed",
            "adultsOnlyPrompted",
            "local_generation",
            "DIRTY",
            "legacy-migration-version",
        ).forEach { forbidden ->
            val adversarial = buildJsonObject {
                put("version", 1)
                putJsonArray("nested") {
                    add(buildJsonObject { put(forbidden, "must-not-enter-wire-dto") })
                }
            }
            assertFailsWith<IllegalArgumentException>(forbidden) {
                PortalProfilePreferenceSectionMutationDto(
                    localProfileId = "profile-a",
                    section = "WORKOUT",
                    documentVersion = 1,
                    baseRevision = 4,
                    clientModifiedAt = "2026-07-11T12:00:00Z",
                    payload = adversarial,
                )
            }
            assertFailsWith<IllegalArgumentException>(forbidden) {
                PortalProfilePreferenceSectionCanonicalDto(
                    localProfileId = "profile-a",
                    section = "WORKOUT",
                    documentVersion = 1,
                    serverRevision = 7,
                    serverUpdatedAt = "2026-07-11T12:01:00Z",
                    payload = adversarial,
                )
            }
        }
    }

    @Test
    fun `canonical and rejection encode exact keys types and revision identity`() {
        val canonical = PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "CORE",
            documentVersion = 1,
            serverRevision = 7,
            serverUpdatedAt = "2026-07-11T12:01:00Z",
            payload = buildJsonObject { put("weightUnit", "KG") },
        )
        val rejection = ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 7,
            reason = "REVISION_CONFLICT",
            canonicalSection = canonical,
        )

        val encodedCanonical = json.encodeToJsonElement(canonical).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "documentVersion", "serverRevision",
                "serverUpdatedAt", "payload",
            ),
            encodedCanonical.keys,
        )
        assertFalse(encodedCanonical.getValue("serverRevision").jsonPrimitive.isString)
        assertEquals(7L, encodedCanonical.getValue("serverRevision").jsonPrimitive.long)
        assertTrue(encodedCanonical.getValue("payload") is JsonObject)

        val encodedRejection = json.encodeToJsonElement(rejection).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "serverRevision", "reason", "canonicalSection",
            ),
            encodedRejection.keys,
        )
        assertFalse(encodedRejection.getValue("serverRevision").jsonPrimitive.isString)
        assertEquals(7L, encodedRejection.getValue("serverRevision").jsonPrimitive.long)
        assertEquals("REVISION_CONFLICT", encodedRejection.getValue("reason").jsonPrimitive.content)
        assertEquals(encodedCanonical, encodedRejection.getValue("canonicalSection"))
    }
}
