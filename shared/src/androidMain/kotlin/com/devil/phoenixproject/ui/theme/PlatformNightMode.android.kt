package com.devil.phoenixproject.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.util.ActivityHolder

private object ApplicationContextHolder {
    @Volatile
    var context: Context? = null
}

fun rememberApplicationContext(context: Context) {
    ApplicationContextHolder.context = context.applicationContext ?: context
}

/**
 * Aligns application night mode with the persisted Phoenix theme so
 * `values` vs `values-night` match explicit LIGHT/DARK before the starting window.
 * No-op below API 31 (this app has no AppCompat night-mode delegate).
 */
fun applyPersistedApplicationNightMode(context: Context) {
    rememberApplicationContext(context)
    val prefs = context.getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
    val themeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(ThemeViewModel.THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM")
    }.getOrDefault(ThemeMode.SYSTEM)
    applyApplicationNightMode(context, themeMode)
}

fun applyApplicationNightMode(context: Context, themeMode: ThemeMode) {
    rememberApplicationContext(context)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val appContext = context.applicationContext ?: context
    val uiModeManager = appContext.getSystemService(UiModeManager::class.java) ?: return
    uiModeManager.setApplicationNightMode(applicationNightModeForTheme(themeMode))
}

fun applicationNightModeForTheme(themeMode: ThemeMode): Int = when (themeMode) {
    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
}

actual fun applyPlatformNightMode(themeMode: ThemeMode) {
    val context = ApplicationContextHolder.context
        ?: ActivityHolder.getActivity()?.applicationContext
        ?: return
    applyApplicationNightMode(context, themeMode)
}
