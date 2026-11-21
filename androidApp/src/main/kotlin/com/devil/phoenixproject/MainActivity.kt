package com.devil.phoenixproject

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.NightSample
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.nightSampleFromMask
import com.devil.phoenixproject.ui.theme.resolveSystemDark
import com.devil.phoenixproject.ui.theme.resolveUseDarkColors
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply stored locale BEFORE composition to prevent first-frame flicker.
        // This reads directly from SharedPreferences instead of waiting for the
        // Compose ViewModel pipeline (which fires after the first frame).
        applyStoredLocaleBeforeComposition()

        volumeControlStream = AudioManager.STREAM_MUSIC

        val (statusBarStyle, navigationBarStyle) = applyPersistedThemeWindow()
        enableEdgeToEdge(
            statusBarStyle = statusBarStyle,
            navigationBarStyle = navigationBarStyle,
        )
        setContent {
            AndroidAppHost()
        }
    }

    private fun applyPersistedThemeWindow(): Pair<SystemBarStyle, SystemBarStyle> {
        val prefs = getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
        val themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(ThemeViewModel.THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(ThemeMode.SYSTEM)
        val applicationNight = nightSampleFromMask(
            applicationContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK,
        )
        // Cold-start seed prefers dark so a missing/undefined night sample cannot flash light.
        val systemDark = resolveSystemDark(
            previous = true,
            applicationNight = applicationNight,
            activityNight = NightSample.UNDEFINED,
            composeNight = applicationNight == NightSample.YES,
        )
        val useDark = resolveUseDarkColors(themeMode, systemDark)
        val windowColor = getColor(
            if (useDark) R.color.phoenix_window_background_dark else R.color.phoenix_window_background_light,
        )
        window.setBackgroundDrawable(ColorDrawable(windowColor))
        return if (useDark) {
            val darkStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            darkStyle to darkStyle
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) to SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb(0x66, 0x0F, 0x17, 0x2A),
            )
        }
    }

    /**
     * Reads the persisted language preference and applies it to the platform locale
     * before setContent{} is called. This prevents the brief English flash on non-EN
     * locales during cold start.
     */
    private fun applyStoredLocaleBeforeComposition() {
        try {
            com.devil.phoenixproject.util.captureInitialSystemLocale()
            val prefs = getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
            val langCode = prefs.getString(SettingsPreferencesManager.KEY_LANGUAGE, null)
            if (!langCode.isNullOrBlank()) {
                val trimmed = langCode.trim()
                val locale = Locale.forLanguageTag(trimmed)
                Locale.setDefault(locale)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val localeManager = getSystemService(android.app.LocaleManager::class.java)
                    val targetLocales = LocaleList.forLanguageTags(trimmed)
                    if (localeManager.applicationLocales != targetLocales) {
                        localeManager.applicationLocales = targetLocales
                    }
                } else {
                    val config = resources.configuration
                    config.setLocale(locale)
                    config.setLocales(LocaleList(locale))
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(config, resources.displayMetrics)
                }
                Logger.d(tag = "MainActivity") { "Applied locale '$trimmed' before composition" }
            } else {
                val systemLocale = com.devil.phoenixproject.util.resolveAndroidSystemLocale()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val localeManager = getSystemService(android.app.LocaleManager::class.java)
                    if (langCode != null && !localeManager.applicationLocales.isEmpty) {
                        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                    }
                    val activeLocale = localeManager.applicationLocales
                        .takeIf { !it.isEmpty }
                        ?.get(0)
                        ?: systemLocale
                    Locale.setDefault(activeLocale)
                } else {
                    Locale.setDefault(systemLocale)
                    val config = resources.configuration
                    config.setLocale(systemLocale)
                    config.setLocales(LocaleList(systemLocale))
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(config, resources.displayMetrics)
                }
            }
        } catch (e: Exception) {
            Logger.w(tag = "MainActivity") { "Failed to apply locale before composition: ${e.message}" }
        }
    }
}
