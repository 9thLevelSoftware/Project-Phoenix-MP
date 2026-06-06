package com.devil.phoenixproject.presentation.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberIsTvRemoteInputMode(): Boolean {
    val configuration = LocalContext.current.resources.configuration
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return remember(uiModeType, configuration.touchscreen, configuration.navigation) {
        isTvRemoteInputMode(
            uiModeType = uiModeType,
            touchscreen = configuration.touchscreen,
            navigation = configuration.navigation,
        )
    }
}

internal fun isTvRemoteInputMode(
    uiModeType: Int,
    touchscreen: Int,
    navigation: Int,
): Boolean {
    val isTelevision = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val isNoTouchDpad = touchscreen == Configuration.TOUCHSCREEN_NOTOUCH &&
        navigation == Configuration.NAVIGATION_DPAD
    return isTelevision || isNoTouchDpad
}
