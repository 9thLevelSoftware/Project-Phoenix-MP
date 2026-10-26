package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalWirePlatformTest {
    @Test
    fun appleTvUsesTheAppleWireFamilyWithoutEnablingIosCapabilities() {
        assertEquals("ios", portalWirePlatform("Apple TV", isIosPlatform = false))
        assertEquals("ios", portalWirePlatform("tvOS 17.0", isIosPlatform = false))
    }

    @Test
    fun phonePlatformValuesAndFallbackRemainUnchanged() {
        assertEquals("android", portalWirePlatform("Android 37", false))
        assertEquals("ios", portalWirePlatform("iOS 27.0", true))
        assertEquals("ios", portalWirePlatform("", true))
        assertEquals("android", portalWirePlatform("", false))
    }
}
