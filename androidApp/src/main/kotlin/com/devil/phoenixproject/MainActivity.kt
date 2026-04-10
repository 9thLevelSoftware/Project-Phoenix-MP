package com.devil.phoenixproject

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.presentation.components.RequireBlePermissions
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply stored locale BEFORE composition to prevent first-frame flicker.
        // This reads directly from SharedPreferences instead of waiting for the
        // Compose ViewModel pipeline (which fires after the first frame).
        applyStoredLocaleBeforeComposition()

        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = koinViewModel()

            // Require BLE permissions before showing the app
            // Permission screens have their own theme, App provides its own theme
            RequireBlePermissions {
                App(mainViewModel = mainViewModel)
            }
        }
    }

    /**
     * Reads the persisted language preference and applies it to the platform locale
     * before setContent{} is called. This prevents the brief English flash on non-EN
     * locales during cold start.
     */
    private fun applyStoredLocaleBeforeComposition() {
        try {
            val prefs = getSharedPreferences("vitruvian_preferences", Context.MODE_PRIVATE)
            val langCode = prefs.getString("language", null)
            if (!langCode.isNullOrBlank() && langCode != "en") {
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
                Logger.d("MainActivity") { "Applied locale '$langCode' before composition" }
            }
        } catch (e: Exception) {
            Logger.w("MainActivity") { "Failed to apply locale before composition: ${e.message}" }
        }
    }
}
