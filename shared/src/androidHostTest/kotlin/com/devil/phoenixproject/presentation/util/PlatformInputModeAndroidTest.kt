package com.devil.phoenixproject.presentation.util

import android.content.res.Configuration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformInputModeAndroidTest {

    @Test
    fun televisionUiModeUsesTvRemoteInputMode() {
        assertTrue(
            isTvRemoteInputMode(
                uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
                touchscreen = Configuration.TOUCHSCREEN_FINGER,
                navigation = Configuration.NAVIGATION_NONAV,
            ),
        )
    }

    @Test
    fun noTouchDpadUsesTvRemoteInputMode() {
        assertTrue(
            isTvRemoteInputMode(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                touchscreen = Configuration.TOUCHSCREEN_NOTOUCH,
                navigation = Configuration.NAVIGATION_DPAD,
            ),
        )
    }

    @Test
    fun touchscreenPhoneDoesNotUseTvRemoteInputMode() {
        assertFalse(
            isTvRemoteInputMode(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                touchscreen = Configuration.TOUCHSCREEN_FINGER,
                navigation = Configuration.NAVIGATION_NONAV,
            ),
        )
    }
}
