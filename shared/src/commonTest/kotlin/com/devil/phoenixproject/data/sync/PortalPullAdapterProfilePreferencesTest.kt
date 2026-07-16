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

    @Test
    fun `pull adapter rejects non contract timestamp spellings and calendar values`() {
        listOf(
            "+002026-07-11T12:00:00Z",
            "+000001-01-01T00:00:00Z",
            "2026-07-11t12:00:00Z",
            "2026-07-11T12:00:00z",
            "2026-07-11T12:00:00+00",
            "2025-02-29T12:00:00Z",
            "2026-07-11T24:00:00Z",
            "2026-07-11T12:00:00+24:00",
            "2026-07-11T12:00:00.1234567890Z",
        ).forEach { timestamp ->
            val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
                PortalPullAdapter.toCanonicalProfilePreferenceSection(
                    coreCanonicalWire(serverUpdatedAt = timestamp),
                ),
            )
            assertEquals(
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP.name,
                invalid.reason,
            )
            assertFalse(timestamp in invalid.reason)
        }
    }

    @Test
    fun `pull adapter accepts canonical Edge timestamp forms`() {
        listOf(
            "0001-01-01T00:00:00Z" to -62_135_596_800_000L,
            "2026-07-11T12:00:00Z" to 1_783_771_200_000L,
            "2026-07-11T12:00:00.1Z" to 1_783_771_200_100L,
            "2026-07-11T14:30:00.123456789+02:30" to 1_783_771_200_123L,
            "2026-07-11T12:00:00+23:59" to 1_783_684_860_000L,
            "2026-07-11T12:00:00-23:59" to 1_783_857_540_000L,
            "9999-12-31T23:59:59.999Z" to 253_402_300_799_999L,
        ).forEach { (timestamp, expectedEpochMs) ->
            val valid = assertIs<ProfilePreferenceCanonicalDecodeResult.Valid>(
                PortalPullAdapter.toCanonicalProfilePreferenceSection(
                    coreCanonicalWire(serverUpdatedAt = timestamp),
                ),
            )
            assertEquals(expectedEpochMs, valid.section.serverUpdatedAtEpochMs)
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
