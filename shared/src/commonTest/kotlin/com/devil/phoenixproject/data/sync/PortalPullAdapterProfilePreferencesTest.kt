package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class PortalPullAdapterProfilePreferencesTest {
    @Test
    fun `pull adapter rejects document version mismatch without fallback`() {
        val wire = PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "VBT",
            documentVersion = 2,
            serverRevision = 3,
            serverUpdatedAt = "2026-07-11T12:00:00.000Z",
            payload = buildJsonObject {
                put("vbtEnabled", true)
                putJsonObject("preferences") { put("version", 1) }
            },
        )

        val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
            PortalPullAdapter.toCanonicalProfilePreferenceSection(wire),
        )
        assertEquals(ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION.name, invalid.reason)
    }

    @Test
    fun `pull adapter enforces exact canonical revision interval`() {
        assertIs<ProfilePreferenceCanonicalDecodeResult.Valid>(
            PortalPullAdapter.toCanonicalProfilePreferenceSection(
                coreCanonicalWire(revision = 9_007_199_254_740_991L),
            ),
        )

        listOf(-1L, 9_007_199_254_740_992L).forEach { revision ->
            val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
                PortalPullAdapter.toCanonicalProfilePreferenceSection(
                    coreCanonicalWire(revision = revision),
                ),
            )
            assertEquals(
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION.name,
                invalid.reason,
            )
        }
    }

    @Test
    fun `pull adapter rejects wrapper identity timestamp and section with category only`() {
        val sentinel = "SECRET_REMOTE_SENTINEL"
        val cases = listOf(
            coreCanonicalWire(localProfileId = " ") to
                ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
            coreCanonicalWire(localProfileId = "$sentinel\u0000") to
                ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
            coreCanonicalWire(serverUpdatedAt = "+10000-01-01T00:00:00.000Z") to
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP,
            coreCanonicalWire().copy(section = sentinel) to
                ProfilePreferenceSyncIssueReason.UNSUPPORTED_SECTION,
        )

        cases.forEach { (wire, expectedReason) ->
            val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
                PortalPullAdapter.toCanonicalProfilePreferenceSection(wire),
            )
            assertEquals(expectedReason.name, invalid.reason)
            assertFalse(sentinel in invalid.reason)
        }
    }

    private fun coreCanonicalWire(
        revision: Long = 3,
        localProfileId: String = "profile-a",
        serverUpdatedAt: String = "2026-07-11T12:00:00.000Z",
    ) = PortalProfilePreferenceSectionCanonicalDto(
        localProfileId = localProfileId,
        section = "CORE",
        documentVersion = 1,
        serverRevision = revision,
        serverUpdatedAt = serverUpdatedAt,
        payload = buildJsonObject {
            put("bodyWeightKg", 82.5)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )
}
