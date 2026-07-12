package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfilePreferenceSyncPlannerTest {
    @Test
    fun `planner isolates oversized section and keeps valid siblings`() {
        val valid = preparedMutation(
            "profile-a",
            ProfilePreferenceSectionName.CORE,
            payloadChars = 64,
        )
        val oversized = preparedMutation(
            "profile-a",
            ProfilePreferenceSectionName.RACK,
            payloadChars = MAX_PROFILE_PREFERENCE_SECTION_BYTES + 1,
        )

        val plan = planProfilePreferencePushChunks(basePayload(), listOf(oversized, valid))

        assertEquals(1, plan.unsyncable.size)
        assertEquals(ProfilePreferenceSectionName.RACK, plan.unsyncable.single().key.section)
        assertEquals(1L, plan.unsyncable.single().localGeneration)
        assertEquals(
            ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE.name,
            plan.unsyncable.single().reason,
        )
        assertEquals(
            listOf(ProfilePreferenceSectionName.CORE),
            plan.chunks.single().ledger.keys.map { it.section },
        )
    }

    @Test
    fun `every planned request stays within preference request cap`() {
        val mutations = (0 until 12).map { index ->
            preparedMutation(
                "profile-$index",
                ProfilePreferenceSectionName.WORKOUT,
                payloadChars = 90_000,
            )
        }

        val plan = planProfilePreferencePushChunks(basePayload(), mutations)

        assertTrue(plan.chunks.size > 1)
        plan.chunks.forEach { chunk ->
            val bytes = encodePortalSyncPayload(chunk.payload).rawBytes.size
            assertTrue(bytes <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES)
        }
    }

    @Test
    fun `shared raw byte goldens pin scanner spans and inclusive boundaries`() {
        val recipes = byteGoldenRecipes()
        assertEquals(1, recipes.version)

        recipes.sectionTargetBytes.forEach { target ->
            val sectionRaw = fillAsciiPadding(
                recipes.sectionRawTemplate,
                recipes.paddingMarker,
                target,
            )
            val completeRaw = "{\"profilePreferenceSections\":[$sectionRaw]}"
            val span = scanProfilePreferenceElementSpans(completeRaw).single()
            assertEquals(sectionRaw, completeRaw.substring(span.start, span.endExclusive))
            assertEquals(
                target,
                completeRaw.substring(span.start, span.endExclusive).encodeToByteArray().size,
            )
            assertEquals(
                target == 262_145,
                completeRaw.substring(span.start, span.endExclusive).encodeToByteArray().size >
                    MAX_PROFILE_PREFERENCE_SECTION_BYTES,
            )
            assertTrue("\"weightKg\":20.0" in sectionRaw)
            assertTrue("\"createdAt\":-1e3" in sectionRaw)
            assertTrue("π界🙂" in sectionRaw)
            assertTrue("\\\"" in sectionRaw)
            assertTrue("\\\\" in sectionRaw)
            val decodedMutation = PortalWireJson.decodeFromString(
                PortalProfilePreferenceSectionMutationDto.serializer(),
                sectionRaw,
            )
            assertEquals("profile-a", decodedMutation.localProfileId)
            assertEquals("RACK", decodedMutation.section)
            assertEquals(0L, decodedMutation.baseRevision)
            assertTrue(decodedMutation.payload.getValue("items") is JsonArray)
            PortalWireJson.parseToJsonElement(completeRaw)
        }

        recipes.requestTargetBytes.forEach { target ->
            val sectionRaw = recipes.sectionRawTemplate.replace(recipes.paddingMarker, "x")
            val requestTemplate = recipes.requestRawTemplate.replace(
                recipes.sectionMarker,
                sectionRaw,
            )
            val completeRaw = fillAsciiPadding(
                requestTemplate,
                recipes.paddingMarker,
                target,
            )
            assertEquals(target, completeRaw.encodeToByteArray().size)
            assertEquals(
                target == 524_289,
                completeRaw.encodeToByteArray().size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
            )
            val span = scanProfilePreferenceElementSpans(completeRaw).single()
            assertEquals(sectionRaw, completeRaw.substring(span.start, span.endExclusive))
            val decodedRequest = PortalWireJson.decodeFromString(
                PortalSyncPayload.serializer(),
                completeRaw,
            )
            assertEquals("golden-device", decodedRequest.deviceId)
            assertEquals("profile-a", decodedRequest.profileId)
            assertEquals(1, decodedRequest.profilePreferenceSections?.size)
            assertEquals("RACK", decodedRequest.profilePreferenceSections?.single()?.section)
        }
    }

    @Test
    fun `planner enforces actual reencoded section boundaries`() {
        val recipes = byteGoldenRecipes()
        recipes.sectionTargetBytes.forEach { target ->
            fun preparedAtSourceBytes(sourceBytes: Int): PreparedProfilePreferenceMutation {
                val raw = fillAsciiPadding(
                    recipes.sectionRawTemplate,
                    recipes.paddingMarker,
                    sourceBytes,
                )
                val wire = PortalWireJson.decodeFromString(
                    PortalProfilePreferenceSectionMutationDto.serializer(),
                    raw,
                )
                val section = ProfilePreferenceSectionName.valueOf(wire.section)
                return PreparedProfilePreferenceMutation(
                    wire = wire,
                    key = ProfilePreferenceSectionKey(wire.localProfileId, section),
                    sentLocalGeneration = 1,
                )
            }

            var prepared = preparedAtSourceBytes(target)
            var reencoded = encodePortalSyncPayload(minimalPayload(listOf(prepared)))
            // The JSON decoder normalizes the golden's -1e3 numeric lexeme to -1000.0.
            // Calibrate only its ASCII padding so the real encoder lands on the byte cap.
            val initialBytes = reencoded.preferenceElementByteCount(
                reencoded.preferenceElementSpans.single(),
            )
            prepared = preparedAtSourceBytes(target - (initialBytes - target))
            reencoded = encodePortalSyncPayload(minimalPayload(listOf(prepared)))
            val actualBytes = reencoded.preferenceElementByteCount(
                reencoded.preferenceElementSpans.single(),
            )
            assertEquals(target, actualBytes)

            val plan = planProfilePreferencePushChunks(basePayload(), listOf(prepared))
            if (target <= MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
                assertTrue(plan.unsyncable.isEmpty())
                assertEquals(listOf(prepared.key), plan.chunks.single().ledger.keys.toList())
            } else {
                assertTrue(plan.chunks.isEmpty())
                assertEquals(
                    ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE.name,
                    plan.unsyncable.single().reason,
                )
            }
        }
    }

    @Test
    fun `actual encoder request boundary is inclusive and overflow is split`() {
        listOf(MAX_PROFILE_PREFERENCE_REQUEST_BYTES, MAX_PROFILE_PREFERENCE_REQUEST_BYTES + 1)
            .forEach { target ->
                val mutations = requestSizedMutations(target)
                val encoded = encodePortalSyncPayload(minimalPayload(mutations))
                assertEquals(target, encoded.rawBytes.size)
                assertTrue(
                    encoded.preferenceElementSpans.all { span ->
                        encoded.preferenceElementByteCount(span) <=
                            MAX_PROFILE_PREFERENCE_SECTION_BYTES
                    },
                )

                val plan = planProfilePreferencePushChunks(basePayload(), mutations)
                assertTrue(plan.unsyncable.isEmpty())
                if (target == MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
                    assertEquals(1, plan.chunks.size)
                    assertEquals(
                        target,
                        encodePortalSyncPayload(plan.chunks.single().payload).rawBytes.size,
                    )
                } else {
                    assertTrue(plan.chunks.size > 1)
                }
                plan.chunks.forEach { chunk ->
                    assertTrue(
                        encodePortalSyncPayload(chunk.payload).rawBytes.size <=
                            MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
                    )
                    assertEquals(
                        chunk.payload.profilePreferenceSections.orEmpty().size,
                        chunk.ledger.size,
                    )
                }
            }
    }

    @Test
    fun `one item request overflow is diagnosed and never emitted`() {
        val base = PortalSyncPayload(
            deviceId = "x".repeat(MAX_PROFILE_PREFERENCE_REQUEST_BYTES),
            lastSync = 0,
        )
        val mutation = preparedMutation("profile-a", ProfilePreferenceSectionName.CORE, 32)

        val plan = planProfilePreferencePushChunks(base, listOf(mutation))

        assertTrue(plan.chunks.isEmpty())
        assertEquals(
            ProfilePreferenceSyncIssueReason.REQUEST_TOO_LARGE.name,
            plan.unsyncable.single().reason,
        )
    }

    @Test
    fun `planner excludes every duplicate key and preserves unique sibling`() {
        val first = preparedMutation("profile-a", ProfilePreferenceSectionName.CORE, 32)
        val second = first.copy(sentLocalGeneration = 2)
        val sibling = preparedMutation("profile-b", ProfilePreferenceSectionName.RACK, 32)

        val plan = planProfilePreferencePushChunks(basePayload(), listOf(second, sibling, first))

        val duplicateIssues = plan.unsyncable.filter { it.key == first.key }
        assertEquals(listOf(1L, 2L), duplicateIssues.map { it.localGeneration })
        assertTrue(
            duplicateIssues.all {
                it.reason == ProfilePreferenceSyncIssueReason.DUPLICATE_SECTION.name
            },
        )
        assertEquals(listOf(sibling.key), plan.chunks.single().ledger.keys.toList())
    }

    @Test
    fun `planner is permutation deterministic and strips profile metadata`() {
        val sentinel = "SECRET_PROFILE_SENTINEL"
        val base = basePayload().copy(
            profileId = "$sentinel\u0000",
            profileName = sentinel,
            allProfiles = listOf(LocalProfileDto(sentinel, sentinel, 0)),
        )
        val mutations = List(6) { index ->
            preparedMutation("profile-$index", ProfilePreferenceSectionName.RACK, 100_000)
        }

        val forward = planProfilePreferencePushChunks(base, mutations)
        val reversed = planProfilePreferencePushChunks(base, mutations.reversed())

        assertEquals(
            forward.chunks.map { encodePortalSyncPayload(it.payload).raw },
            reversed.chunks.map { encodePortalSyncPayload(it.payload).raw },
        )
        assertEquals(
            forward.chunks.map { it.ledger.entries.toList() },
            reversed.chunks.map { it.ledger.entries.toList() },
        )
        forward.chunks.forEach { chunk ->
            assertNull(chunk.payload.profileId)
            assertNull(chunk.payload.profileName)
            assertNull(chunk.payload.allProfiles)
            assertFalse(sentinel in encodePortalSyncPayload(chunk.payload).raw)
            assertEquals(chunk.payload.profilePreferenceSections.orEmpty().size, chunk.ledger.size)
        }
    }

    @Test
    fun `scanner decodes escaped top level key and ignores nested decoy`() {
        val escapedKey = "\\u0070rofilePreferenceSections"
        val element = """{"text":"brackets ] } , [ { and quote \" and slash \\"}"""
        val raw = """{"nested":{"profilePreferenceSections":[0]},"$escapedKey":[$element]}"""

        val span = scanProfilePreferenceElementSpans(raw).single()

        assertEquals(element, raw.substring(span.start, span.endExclusive))
    }

    @Test
    fun `scanner rejects escaped duplicate and malformed structure with fixed messages`() {
        val escapedKey = "\\u0070rofilePreferenceSections"
        val duplicate = """{"profilePreferenceSections":[],"$escapedKey":[]}"""
        assertEquals(
            "DUPLICATE_PROFILE_PREFERENCE_SECTIONS",
            assertFailsWith<IllegalArgumentException> {
                scanProfilePreferenceElementSpans(duplicate)
            }.message,
        )

        val sentinel = "SECRET_SCAN_SENTINEL"
        mapOf(
            "[]" to "INVALID_JSON_ROOT",
            """{"profilePreferenceSections":[}""" to "INVALID_JSON_STRUCTURE",
            """{"profilePreferenceSections":[]} $sentinel""" to "TRAILING_JSON_DATA",
        ).forEach { (raw, expected) ->
            val error = assertFailsWith<IllegalArgumentException> {
                scanProfilePreferenceElementSpans(raw)
            }
            assertEquals(expected, error.message)
            assertFalse(sentinel in error.message.orEmpty())
        }
        assertTrue(scanProfilePreferenceElementSpans("""{"other":[]}""").isEmpty())
        assertTrue(
            scanProfilePreferenceElementSpans(
                """{"profilePreferenceSections":[]}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `scanner rejects non array preference field with fixed message`() {
        val raw = """{"profilePreferenceSections":{}}"""

        val error = assertFailsWith<IllegalArgumentException> {
            scanProfilePreferenceElementSpans(raw)
        }

        assertEquals("INVALID_PROFILE_PREFERENCE_ARRAY", error.message)
        assertFalse(raw in error.message.orEmpty())
    }

    @Serializable
    private data class ProfilePreferenceByteGoldenRecipes(
        val version: Int,
        val paddingMarker: String,
        val sectionMarker: String,
        val sectionRawTemplate: String,
        val requestRawTemplate: String,
        val sectionTargetBytes: List<Int>,
        val requestTargetBytes: List<Int>,
    )

    private fun preparedMutation(
        profileId: String,
        section: ProfilePreferenceSectionName,
        payloadChars: Int,
    ): PreparedProfilePreferenceMutation {
        val key = ProfilePreferenceSectionKey(profileId, section)
        return PreparedProfilePreferenceMutation(
            wire = PortalProfilePreferenceSectionMutationDto(
                localProfileId = profileId,
                section = section.name,
                documentVersion = 1,
                baseRevision = 0,
                clientModifiedAt = "2026-07-11T12:00:00Z",
                payload = buildJsonObject { put("padding", "x".repeat(payloadChars)) },
            ),
            key = key,
            sentLocalGeneration = 1,
        )
    }

    private fun byteGoldenRecipes() =
        PortalWireJson.decodeFromString<ProfilePreferenceByteGoldenRecipes>(
            assertNotNull(
                readProjectFile("docs/backend-handoff/profile-preference-byte-goldens.json"),
            ),
        )

    private fun minimalPayload(
        mutations: List<PreparedProfilePreferenceMutation>,
    ) = PortalSyncPayload(
        deviceId = "device",
        platform = "android",
        lastSync = 0,
        profilePreferenceSections = mutations.map { it.wire },
    )

    private fun requestSizedMutations(targetBytes: Int): List<PreparedProfilePreferenceMutation> {
        val seeds = List(3) { index ->
            preparedMutation(
                "profile-$index",
                ProfilePreferenceSectionName.RACK,
                payloadChars = 0,
            )
        }
        val fixedBytes = encodePortalSyncPayload(minimalPayload(seeds)).rawBytes.size
        val paddingBytes = targetBytes - fixedBytes
        require(paddingBytes >= 0)
        return seeds.mapIndexed { index, seed ->
            val padding = paddingBytes / seeds.size +
                if (index < paddingBytes % seeds.size) 1 else 0
            seed.copy(
                wire = seed.wire.copy(
                    payload = buildJsonObject { put("padding", "x".repeat(padding)) },
                ),
            )
        }
    }

    private fun basePayload() = PortalSyncPayload(
        deviceId = "device",
        lastSync = 0,
        profileId = "profile-a",
    )

    private fun fillAsciiPadding(
        template: String,
        marker: String,
        targetBytes: Int,
    ): String {
        require(marker.isNotEmpty())
        val markerStart = template.indexOf(marker)
        require(markerStart >= 0)
        require(template.indexOf(marker, markerStart + marker.length) == -1)
        val withoutMarker = template.removeRange(markerStart, markerStart + marker.length)
        val paddingBytes = targetBytes - withoutMarker.encodeToByteArray().size
        require(paddingBytes >= 0)
        val result = withoutMarker.substring(0, markerStart) +
            "x".repeat(paddingBytes) +
            withoutMarker.substring(markerStart)
        assertEquals(targetBytes, result.encodeToByteArray().size)
        return result
    }
}
