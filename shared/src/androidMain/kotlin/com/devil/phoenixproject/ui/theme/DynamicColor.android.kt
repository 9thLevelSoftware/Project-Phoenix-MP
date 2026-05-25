package com.devil.phoenixproject.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

actual fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
@Suppress("NewApi")
internal actual fun platformDynamicColorScheme(useDarkColors: Boolean): ColorScheme? {
    if (!isDynamicColorAvailable()) return null

    val context = LocalContext.current
    return if (useDarkColors) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}
