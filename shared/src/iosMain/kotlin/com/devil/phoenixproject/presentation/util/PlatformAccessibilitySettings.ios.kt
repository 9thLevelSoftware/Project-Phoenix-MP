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
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

@Composable
actual fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings {
    var boldTextEnabled by remember {
        mutableStateOf(UIAccessibilityIsBoldTextEnabled())
    }
    var reduceMotion by remember {
        mutableStateOf(UIAccessibilityIsReduceMotionEnabled())
    }

    DisposableEffect(Unit) {
        val boldObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityBoldTextStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = {
                boldTextEnabled = UIAccessibilityIsBoldTextEnabled()
            },
        )
        val reduceMotionObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue(),
            usingBlock = {
                reduceMotion = UIAccessibilityIsReduceMotionEnabled()
            },
        )

        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(boldObserver)
            NSNotificationCenter.defaultCenter.removeObserver(reduceMotionObserver)
        }
    }

    return PlatformAccessibilitySettings(
        boldTextEnabled = boldTextEnabled,
        reduceMotion = reduceMotion,
    )
}
