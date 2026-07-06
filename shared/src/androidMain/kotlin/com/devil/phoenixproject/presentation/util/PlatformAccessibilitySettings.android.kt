package com.devil.phoenixproject.presentation.util

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(config) {
        val boldText = if (Build.VERSION.SDK_INT >= 31) {
            context.resources.configuration.fontWeightAdjustment >= 300
        } else false
        val animScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        PlatformAccessibilitySettings(
            boldTextEnabled = boldText,
            reduceMotion = animScale == 0f,
        )
    }
}
