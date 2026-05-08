package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityBoldTextStatusDidChangeNotification
import platform.UIKit.UIAccessibilityIsBoldTextEnabled

@Composable
actual fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings {
    var boldTextEnabled by remember {
        mutableStateOf(UIAccessibilityIsBoldTextEnabled())
    }

    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityBoldTextStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = {
                boldTextEnabled = UIAccessibilityIsBoldTextEnabled()
            },
        )

        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }

    return PlatformAccessibilitySettings(boldTextEnabled = boldTextEnabled)
}
