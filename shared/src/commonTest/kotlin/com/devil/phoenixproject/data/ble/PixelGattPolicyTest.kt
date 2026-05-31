package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelGattPolicyTest {

    @Test
    fun `official small MTU path is inactive by default`() {
        resetFlags()

        assertFalse(PixelGattPolicy.isOfficialSmallMtuPathActive(isPixel = true))
        assertFalse(PixelGattPolicy.isOfficialSmallMtuPathActive(isPixel = false))
        assertTrue(PixelGattPolicy.includeHeartbeat(isPixel = true))
    }

    @Test
    fun `official small MTU path is active only on Pixel devices`() {
        resetFlags()
        PixelGattFlags.setOfficialSmallMtuPathEnabled(enabled = true)

        try {
            assertTrue(PixelGattPolicy.isOfficialSmallMtuPathActive(isPixel = true))
            assertFalse(PixelGattPolicy.isOfficialSmallMtuPathActive(isPixel = false))
            assertFalse(PixelGattPolicy.includeHeartbeat(isPixel = true))
            assertTrue(PixelGattPolicy.includeHeartbeat(isPixel = false))
        } finally {
            resetFlags()
        }
    }

    @Test
    fun `enabling official small MTU path clears legacy D through H experiments`() {
        resetFlags()
        PixelGattFlags.refreshGattCache = true
        PixelGattFlags.forceImmediateClose = true
        PixelGattFlags.quiescePolling = true
        PixelGattFlags.skipPhantomStart = true
        PixelGattFlags.rawGattWrite = true

        PixelGattFlags.setOfficialSmallMtuPathEnabled(enabled = true)

        try {
            assertTrue(PixelGattFlags.officialSmallMtuPath)
            assertFalse(PixelGattFlags.refreshGattCache)
            assertFalse(PixelGattFlags.forceImmediateClose)
            assertFalse(PixelGattFlags.quiescePolling)
            assertFalse(PixelGattFlags.skipPhantomStart)
            assertFalse(PixelGattFlags.rawGattWrite)
            assertTrue(PixelGattFlags.activeFlagsSummary().contains("I:SmallMtu"))
        } finally {
            resetFlags()
        }
    }

    @Test
    fun `disabling official small MTU path leaves legacy experiments disabled`() {
        resetFlags()
        PixelGattFlags.setOfficialSmallMtuPathEnabled(enabled = true)
        PixelGattFlags.setOfficialSmallMtuPathEnabled(enabled = false)

        assertFalse(PixelGattFlags.officialSmallMtuPath)
        assertFalse(PixelGattFlags.refreshGattCache)
        assertFalse(PixelGattFlags.forceImmediateClose)
        assertFalse(PixelGattFlags.quiescePolling)
        assertFalse(PixelGattFlags.skipPhantomStart)
        assertFalse(PixelGattFlags.rawGattWrite)
        assertTrue(PixelGattFlags.activeFlagsSummary() == "None")
    }

    private fun resetFlags() {
        PixelGattFlags.refreshGattCache = false
        PixelGattFlags.forceImmediateClose = false
        PixelGattFlags.quiescePolling = false
        PixelGattFlags.skipPhantomStart = false
        PixelGattFlags.rawGattWrite = false
        PixelGattFlags.officialSmallMtuPath = false
    }
}
