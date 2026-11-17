package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * SD5 discovery-contract tests (spec §6 T1–T7): standard 0x180D acceptance,
 * bounded name allow-list, and rejection of non-HR advertisements.
 */
@OptIn(ExperimentalUuidApi::class)
class HrmAdvertisementClassifierTest {

    private val hrService = BleConstants.Hrm.HR_SERVICE_UUID
    private val nusService = BleConstants.NUS_SERVICE_UUID

    // T1: unnamed 0x180D advertiser is accepted with the generic display name.
    @Test
    fun `T1 unnamed 0x180D advertiser is accepted as generic heart rate sensor`() {
        val result = classifyHrmAdvertisement(CompositionAdvertisement(name = null, uuids = listOf(hrService)))
        assertNotNull(result)
        assertEquals(GENERIC_HRM_DISPLAY_NAME, result.displayName)
        assertEquals(HrmFamily.GENERIC_HRM, result.family)
        assertEquals(HrmMatchReason.SERVICE_UUID, result.matchReason)
    }

    // T2: named 0x180D advertiser keeps its advertised name; family falls back to GENERIC_HRM.
    @Test
    fun `T2 named 0x180D advertiser keeps advertised name and generic family`() {
        val result = classifyHrmAdvertisement(
            CompositionAdvertisement(name = "Garmin Forerunner 245", uuids = listOf(hrService)),
        )
        assertNotNull(result)
        assertEquals("Garmin Forerunner 245", result.displayName)
        assertEquals(HrmFamily.GENERIC_HRM, result.family)
        assertEquals(HrmMatchReason.SERVICE_UUID, result.matchReason)
    }

    // T2b: Garmin strap families map to GARMIN when matched by bounded name allow-list.
    @Test
    fun `T2b Garmin strap name in bounded allow-list maps to GARMIN family`() {
        val result = classifyHrmAdvertisement(
            CompositionAdvertisement(name = "HRM-Pro 41234", uuids = emptyList()),
        )
        assertNotNull(result)
        assertEquals(HrmFamily.GARMIN, result.family)
        assertEquals(HrmMatchReason.NAME_ALLOWLIST, result.matchReason)
    }

    // T3: existing Polar H10 name matching preserved (no UUID needed).
    @Test
    fun `T3 Polar H10 names accepted via allow-list without service UUID`() {
        for (name in listOf("Polar H10", "Polar H10 12345678", "polar h10 9999", "POLAR H10")) {
            val result = classifyHrmAdvertisement(CompositionAdvertisement(name = name, uuids = emptyList()))
            assertNotNull(result, "expected accept for $name")
            assertEquals(HrmFamily.POLAR, result.family)
            assertEquals(HrmMatchReason.NAME_ALLOWLIST, result.matchReason)
            assertEquals(name, result.displayName)
        }
    }

    // T4: NUS-only machine trainer advertisements are rejected.
    @Test
    fun `T4 NUS-only trainer advertisement is rejected`() {
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "Vee_Trainer", uuids = listOf(nusService))))
    }

    // T5: named non-HRM devices are rejected.
    @Test
    fun `T5 named non-HRM advertisement is rejected`() {
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "VIT-200", uuids = emptyList())))
    }

    // T6: no name and no service UUID is rejected.
    @Test
    fun `T6 null name without service UUID is rejected`() {
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = null, uuids = emptyList())))
    }

    // T7: the allow-list is bounded — no Garmin*/Polar* wildcard acceptance.
    @Test
    fun `T7 unlisted Garmin name without 0x180D is rejected - no wildcard`() {
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "Garmin xyz", uuids = emptyList())))
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "Garmin Forerunner 245", uuids = emptyList())))
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "Polar H9", uuids = emptyList())))
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "Polar H100", uuids = emptyList())))
    }

    // Allow-list entries accept documented device-id suffixes only (bounded, no wildcards).
    @Test
    fun `allow-list entries accept device-id suffix forms`() {
        assertNotNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "HRM-Dual 41234", uuids = emptyList())))
        assertNotNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "HRM 200-12345", uuids = emptyList())))
        assertNotNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "hrm-pro plus 987", uuids = emptyList())))
        // Suffix separator must be space/hyphen/underscore — glued names are not matched.
        assertNull(classifyHrmAdvertisement(CompositionAdvertisement(name = "HRM-ProX", uuids = emptyList())))
    }
}
