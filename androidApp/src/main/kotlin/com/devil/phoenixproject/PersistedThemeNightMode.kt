package com.devil.phoenixproject

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode

/**
 * Aligns the application's night mode with the persisted Phoenix theme so the
 * OS starting window picks `values` vs `values-night` before MainActivity.onCreate.
 *
 * LIGHT/DARK force the matching qualifier. SYSTEM follows the device.
 * No-op below API 31 (no AppCompat night-mode delegate in this app).
 */
internal fun applyPersistedApplicationNightMode(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val appContext = context.applicationContext ?: context
    val prefs = appContext.getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
    val themeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(ThemeViewModel.THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM")
    }.getOrDefault(ThemeMode.SYSTEM)
    val uiModeManager = appContext.getSystemService(UiModeManager::class.java) ?: return
    uiModeManager.setApplicationNightMode(applicationNightModeForTheme(themeMode))
}

internal fun applicationNightModeForTheme(themeMode: ThemeMode): Int = when (themeMode) {
    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
}
