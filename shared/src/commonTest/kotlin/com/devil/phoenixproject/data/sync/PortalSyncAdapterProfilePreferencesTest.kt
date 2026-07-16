package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class PortalSyncAdapterProfilePreferencesTest {
    @Test
    fun `push adapter maps audit timestamp but keeps generation off wire`() {
        val source = ProfilePreferenceSectionSyncDto(
            key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
            documentVersion = 1,
            baseRevision = 9_007_199_254_740_991L,
            clientModifiedAtEpochMs = 1_783_771_200_000L,
            localGeneration = 9,
            payload = buildJsonObject {
                put("bodyWeightKg", 82.5)
                put("weightUnit", "KG")
                put("weightIncrement", 0.5)
            },
        )

        val prepared = PortalSyncAdapter.toPortalProfilePreferenceMutation(source)

        assertEquals(9L, prepared.sentLocalGeneration)
        assertEquals(9_007_199_254_740_991L, prepared.wire.baseRevision)
        assertEquals("CORE", prepared.wire.section)
        assertEquals("2026-07-11T12:00:00Z", prepared.wire.clientModifiedAt)
        val encoded = PortalWireJson.encodeToJsonElement(
            PortalProfilePreferenceSectionMutationDto.serializer(),
            prepared.wire,
        ).jsonObject
        assertFalse(encoded.getValue("baseRevision").jsonPrimitive.isString)
        assertEquals(
            9_007_199_254_740_991L,
            encoded.getValue("baseRevision").jsonPrimitive.long,
        )
        assertFalse("localGeneration" in encoded)
    }
}
