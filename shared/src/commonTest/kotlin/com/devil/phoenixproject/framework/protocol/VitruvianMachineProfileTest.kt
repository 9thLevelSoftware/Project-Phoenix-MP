package com.devil.phoenixproject.framework.protocol

import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class VitruvianMachineProfileTest {

    private val profile = VitruvianMachineProfile
    private val fef3Uuid = Uuid.parse("0000fef3-0000-1000-8000-00805f9b34fb")

    private fun advertisement(
        name: String? = null,
        identifier: String = "id-1",
        serviceUuids: List<Uuid> = emptyList(),
        serviceData: Map<Uuid, ByteArray> = emptyMap()
    ) = AdvertisedMachineData(
        name = name,
        identifier = identifier,
        rssi = -55,
        serviceUuids = serviceUuids,
        serviceData = serviceData
    )

    @Test
    fun `match returns true for Vee prefix`() {
        val match = profile.match(advertisement(name = "Vee_Trainer"))
        assertTrue(match.matches)
        assertEquals("name", match.reason)
    }

    @Test
    fun `match returns true for VIT prefix`() {
        val match = profile.match(advertisement(name = "VIT_001"))
        assertTrue(match.matches)
        assertEquals("name", match.reason)
    }

    @Test
    fun `match returns true for Vitruvian prefix`() {
        val match = profile.match(advertisement(name = "Vitruvian Trainer"))
        assertTrue(match.matches)
        assertEquals("name", match.reason)
    }

    @Test
    fun `match returns true for NUS service UUID`() {
        val match = profile.match(advertisement(serviceUuids = listOf(BleConstants.NUS_SERVICE_UUID)))
        assertTrue(match.matches)
        assertEquals("service_uuid", match.reason)
    }

    @Test
    fun `match returns true for FEF3 service UUID prefix`() {
        val match = profile.match(advertisement(serviceUuids = listOf(fef3Uuid)))
        assertTrue(match.matches)
        assertEquals("service_uuid", match.reason)
    }

    @Test
    fun `match returns true for non-empty FEF3 service data`() {
        val match = profile.match(advertisement(serviceData = mapOf(fef3Uuid to byteArrayOf(0x01))))
        assertTrue(match.matches)
        assertEquals("service_data", match.reason)
    }

    @Test
    fun `match returns false for unrelated advertisement`() {
        val match = profile.match(
            advertisement(
                name = "Headphones",
                serviceUuids = listOf(Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb"))
            )
        )
        assertFalse(match.matches)
    }

    @Test
    fun `hasPreferredName only accepts vee and vit prefixes`() {
        assertTrue(profile.hasPreferredName("Vee_Trainer"))
        assertTrue(profile.hasPreferredName("VIT_001"))
        assertFalse(profile.hasPreferredName("Vitruvian Trainer"))
        assertFalse(profile.hasPreferredName(null))
    }

    @Test
    fun `labelFor uses advertised name when present`() {
        val label = profile.labelFor(advertisement(name = "Vee_Trainer", identifier = "abc"))
        assertEquals("Vee_Trainer", label)
    }

    @Test
    fun `labelFor uses profile display name fallback when name missing`() {
        val label = profile.labelFor(advertisement(name = null, identifier = "abc"))
        assertEquals("Vitruvian (abc)", label)
    }

    @Test
    fun `profile advertises expected contract fields`() {
        assertEquals("vitruvian", profile.key)
        assertEquals("Vitruvian", profile.displayName)
        assertTrue(profile.serviceUuids.contains(BleConstants.NUS_SERVICE_UUID))
        assertTrue(profile.advertisedDataHints.serviceDataUuids.contains(fef3Uuid))
        assertNotNull(profile.capabilities.firstOrNull())
    }
}
