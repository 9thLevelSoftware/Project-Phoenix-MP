package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

expect fun isDynamicColorAvailable(): Boolean

@Composable
internal expect fun platformDynamicColorScheme(useDarkColors: Boolean): ColorScheme?
