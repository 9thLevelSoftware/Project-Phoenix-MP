package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Production predicate for scan/connect fail-closed (D-12 / FP-2).
 * No hardware; [FakeBleRepository.simulateConnect] is not the subject.
 */
class BleAdvertisementFilterTest {

    @Test
    fun `accepts Vee_ and VIT trainer names`() {
        assertTrue(BleAdvertisementFilter.isConnectableName("Vee_foo"))
        assertTrue(BleAdvertisementFilter.isConnectableName("vee_FOO"))
        assertTrue(BleAdvertisementFilter.isConnectableName("VITBAR"))
        assertTrue(BleAdvertisementFilter.isConnectableName("vitbar"))
        assertTrue(BleAdvertisementFilter.isConnectableName("VIT-200"))
        assertTrue(BleAdvertisementFilter.isConnectableName("VIT"))
        assertTrue(BleAdvertisementFilter.mayConnect("Vee_foo"))
        assertTrue(BleAdvertisementFilter.mayConnect("VITBAR"))
    }

    @Test
    fun `rejects placeholder empty Vitruvian Phoenix and Vee without underscore`() {
        assertFalse(BleAdvertisementFilter.isConnectableName("Trainer (AA:BB:CC:DD:EE:FF)"))
        assertFalse(BleAdvertisementFilter.isConnectableName(""))
        assertFalse(BleAdvertisementFilter.isConnectableName("   "))
        assertFalse(BleAdvertisementFilter.isConnectableName(null))
        assertFalse(BleAdvertisementFilter.isConnectableName("Vitruvian"))
        assertFalse(BleAdvertisementFilter.isConnectableName("Vitruvian Form"))
        assertFalse(BleAdvertisementFilter.isConnectableName("Phoenix"))
        assertFalse(BleAdvertisementFilter.isConnectableName("Phoenix_Gym"))
        assertFalse(BleAdvertisementFilter.isConnectableName("Vee"))
        assertFalse(BleAdvertisementFilter.isConnectableName("VeeFoo"))
        assertFalse(BleAdvertisementFilter.mayConnect("Trainer (AA:BB:CC:DD:EE:FF)"))
        assertFalse(BleAdvertisementFilter.mayConnect(""))
        assertFalse(BleAdvertisementFilter.mayConnect("Vitruvian"))
    }

    @Test
    fun `rejects nameless NUS and FEF3-only as connectable`() {
        val nus = listOf(BleConstants.NUS_SERVICE_UUID_STRING)
        val fef3 = listOf(BleAdvertisementFilter.FEF3_UUID_STRING)

        assertFalse(BleAdvertisementFilter.isConnectableName(null))
        assertFalse(BleAdvertisementFilter.mayConnect(name = null, identifier = "AA:BB"))
        assertFalse(
            BleAdvertisementFilter.mayConnect(
                name = null,
                identifier = "AA:BB",
                lastSuccessfulIdentifier = null,
            ),
        )
        assertTrue(BleAdvertisementFilter.hasTrainerServiceUuid(nus))
        assertTrue(BleAdvertisementFilter.hasTrainerServiceUuid(fef3))
        assertTrue(
            BleAdvertisementFilter.isVisibleOnlyCandidate(
                name = null,
                serviceUuidStrings = nus,
                hasFef3ServiceData = false,
            ),
        )
        assertTrue(
            BleAdvertisementFilter.isVisibleOnlyCandidate(
                name = null,
                serviceUuidStrings = emptyList(),
                hasFef3ServiceData = true,
            ),
        )
        assertFalse(
            BleAdvertisementFilter.isVisibleOnlyCandidate(
                name = "Vitruvian",
                serviceUuidStrings = nus,
                hasFef3ServiceData = true,
            ),
        )
    }

    @Test
    fun `scan lists connectable names and unnamed NUS FEF3 but not Vitruvian`() {
        val nus = listOf(BleConstants.NUS_SERVICE_UUID_STRING)
        assertTrue(
            BleAdvertisementFilter.shouldListDuringScan("Vee_foo", emptyList(), false),
        )
        assertTrue(
            BleAdvertisementFilter.shouldListDuringScan("VITBAR", emptyList(), false),
        )
        assertFalse(
            BleAdvertisementFilter.shouldListDuringScan("Vitruvian", nus, true),
        )
        assertFalse(
            BleAdvertisementFilter.shouldListDuringScan("Phoenix", nus, false),
        )
        assertFalse(
            BleAdvertisementFilter.shouldListDuringScan("Trainer (addr)", nus, false),
        )
        assertTrue(
            BleAdvertisementFilter.shouldListDuringScan(null, nus, false),
        )
        assertTrue(
            BleAdvertisementFilter.shouldListDuringScan(
                name = null,
                serviceUuidStrings = emptyList(),
                hasFef3ServiceData = true,
            ),
        )
        assertFalse(
            BleAdvertisementFilter.shouldListDuringScan(null, emptyList(), false),
        )
    }

    @Test
    fun `last-successful identifier is opt-in connect for placeholder names`() {
        assertTrue(
            BleAdvertisementFilter.mayConnect(
                name = "Trainer (AA:BB)",
                identifier = "AA:BB",
                lastSuccessfulIdentifier = "AA:BB",
            ),
        )
        assertFalse(
            BleAdvertisementFilter.mayConnect(
                name = "Trainer (AA:BB)",
                identifier = "AA:BB",
                lastSuccessfulIdentifier = "CC:DD",
            ),
        )
        assertFalse(
            BleAdvertisementFilter.mayConnect(
                name = "Trainer (AA:BB)",
                identifier = "AA:BB",
                lastSuccessfulIdentifier = null,
            ),
        )
    }
}
