package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing app theme preferences.
 * Uses multiplatform-settings for persistence across Android/iOS.
 */
class ThemeViewModel(
    private val settings: Settings
) : ViewModel() {

    private val log = Logger.withTag("ThemeViewModel")

    private val _themeMode = MutableStateFlow(loadThemePreference())

    val themeMode: StateFlow<ThemeMode> = _themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, _themeMode.value)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            _themeMode.value = mode
            saveThemePreference(mode)
            log.d { "Theme mode changed to: $mode" }
        }
    }

    private fun loadThemePreference(): ThemeMode {
        val savedTheme = settings.getStringOrNull(THEME_MODE_KEY)
        return if (savedTheme != null) {
            try {
                ThemeMode.valueOf(savedTheme)
            } catch (e: IllegalArgumentException) {
                log.w { "Invalid saved theme mode: $savedTheme, defaulting to SYSTEM" }
                ThemeMode.SYSTEM
            }
        } else {
            ThemeMode.SYSTEM
        }
    }

    private fun saveThemePreference(mode: ThemeMode) {
        settings[THEME_MODE_KEY] = mode.name
    }

    companion object {
        private const val THEME_MODE_KEY = "theme_mode"
    }
}
