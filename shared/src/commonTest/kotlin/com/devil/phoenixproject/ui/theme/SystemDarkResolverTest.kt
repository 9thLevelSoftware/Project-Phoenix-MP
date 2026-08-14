package com.devil.phoenixproject.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemDarkResolverTest {

    @Test
    fun nightSampleFromMask_mapsAndroidConstants() {
        assertEquals(NightSample.YES, nightSampleFromMask(0x20))
        assertEquals(NightSample.NO, nightSampleFromMask(0x10))
        assertEquals(NightSample.UNDEFINED, nightSampleFromMask(0x00))
        assertEquals(NightSample.UNDEFINED, nightSampleFromMask(0x30 and 0x00))
    }

    @Test
    fun applicationYes_winsOverActivityLightAndComposeLight() {
        val result = resolveSystemDark(
            previous = false,
            applicationNight = NightSample.YES,
            activityNight = NightSample.NO,
            composeNight = false,
        )
        assertTrue(result)
    }

    @Test
    fun applicationNo_winsOverActivityDarkAndComposeDark() {
        val result = resolveSystemDark(
            previous = true,
            applicationNight = NightSample.NO,
            activityNight = NightSample.YES,
            composeNight = true,
        )
        assertFalse(result)
    }

    @Test
    fun undefinedApplicationAndActivity_keepPreviousDark() {
        val result = resolveSystemDark(
            previous = true,
            applicationNight = NightSample.UNDEFINED,
            activityNight = NightSample.UNDEFINED,
            composeNight = false,
        )
        assertTrue(result)
    }

    @Test
    fun activityNo_doesNotForceLightWhenComposeStillDark() {
        val result = resolveSystemDark(
            previous = true,
            applicationNight = NightSample.UNDEFINED,
            activityNight = NightSample.NO,
            composeNight = true,
        )
        assertTrue(result)
    }

    @Test
    fun activityNo_andComposeLight_acceptedAsLightWhenApplicationUndefined() {
        val result = resolveSystemDark(
            previous = true,
            applicationNight = NightSample.UNDEFINED,
            activityNight = NightSample.NO,
            composeNight = false,
        )
        assertFalse(result)
    }

    @Test
    fun activityYes_recoversDarkWhenApplicationUndefined() {
        val result = resolveSystemDark(
            previous = false,
            applicationNight = NightSample.UNDEFINED,
            activityNight = NightSample.YES,
            composeNight = false,
        )
        assertTrue(result)
    }

    @Test
    fun resolveUseDarkColors_darkIgnoresSystemFalse() {
        assertTrue(resolveUseDarkColors(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun resolveUseDarkColors_lightIgnoresSystemTrue() {
        assertFalse(resolveUseDarkColors(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun resolveUseDarkColors_systemForwardsResolver() {
        assertTrue(resolveUseDarkColors(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveUseDarkColors(ThemeMode.SYSTEM, systemDark = false))
    }
}
