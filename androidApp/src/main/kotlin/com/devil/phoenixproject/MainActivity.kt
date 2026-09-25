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

        val systemBarStyle = applyPersistedThemeWindow()
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        setContent {
            AndroidAppHost()
        }
    }

    private fun applyPersistedThemeWindow(): SystemBarStyle {
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
        val windowColor = if (useDark) WINDOW_BACKGROUND_DARK else WINDOW_BACKGROUND_LIGHT
        window.setBackgroundDrawable(ColorDrawable(windowColor))
        return if (useDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
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
            val prefs = getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
            val langCode = prefs.getString("language", null)
            if (!langCode.isNullOrBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val localeManager = getSystemService(android.app.LocaleManager::class.java)
                    localeManager.applicationLocales = LocaleList.forLanguageTags(langCode)
                } else {
                    val locale = Locale.forLanguageTag(langCode)
                    val config = resources.configuration
                    config.setLocale(locale)
                    config.setLocales(LocaleList(locale))
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(config, resources.displayMetrics)
                }
                Logger.d(tag = "MainActivity") { "Applied locale '$langCode' before composition" }
            }
        } catch (e: Exception) {
            Logger.w(tag = "MainActivity") { "Failed to apply locale before composition: ${e.message}" }
        }
    }

    private companion object {
        const val WINDOW_BACKGROUND_DARK = 0xFF0F172A.toInt()
        const val WINDOW_BACKGROUND_LIGHT = 0xFFF8FAFC.toInt()
    }
}
