package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleCompatibilityModeTest {

    @BeforeTest
    fun reset() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.AUTO
    }

    @AfterTest
    fun restore() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.AUTO
    }

    @Test
    fun `auto enables the compatibility path only on affected devices`() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.AUTO

        assertTrue(BleCompatibilityMode.isActive(isAffectedDevice = true))
        assertFalse(BleCompatibilityMode.isActive(isAffectedDevice = false))
    }

    @Test
    fun `explicit on wins over device detection`() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.ON

        assertTrue(BleCompatibilityMode.isActive(isAffectedDevice = true))
        assertTrue(BleCompatibilityMode.isActive(isAffectedDevice = false))
    }

    @Test
    fun `explicit off wins over device detection`() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.OFF

        assertFalse(BleCompatibilityMode.isActive(isAffectedDevice = true))
        assertFalse(BleCompatibilityMode.isActive(isAffectedDevice = false))
    }

    @Test
    fun `fromStorage parses stored names and defaults to auto`() {
        assertEquals(BleCompatibilitySetting.ON, BleCompatibilitySetting.fromStorage("ON"))
        assertEquals(BleCompatibilitySetting.OFF, BleCompatibilitySetting.fromStorage("OFF"))
        assertEquals(BleCompatibilitySetting.AUTO, BleCompatibilitySetting.fromStorage("AUTO"))
        assertEquals(BleCompatibilitySetting.AUTO, BleCompatibilitySetting.fromStorage(null))
        assertEquals(BleCompatibilitySetting.AUTO, BleCompatibilitySetting.fromStorage("garbage"))
    }

    @Test
    fun `summary reports setting and resolved state`() {
        BleCompatibilityMode.setting = BleCompatibilitySetting.AUTO

        val summary = BleCompatibilityMode.summary(isAffectedDevice = true)

        assertTrue(summary.contains("setting=AUTO"))
        assertTrue(summary.contains("active=true"))
    }
}
