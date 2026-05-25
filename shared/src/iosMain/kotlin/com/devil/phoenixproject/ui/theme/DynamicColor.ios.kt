package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

actual fun isDynamicColorAvailable(): Boolean = false

@Composable
internal actual fun platformDynamicColorScheme(useDarkColors: Boolean): ColorScheme? = null
