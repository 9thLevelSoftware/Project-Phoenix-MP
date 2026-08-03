package com.devil.phoenixproject.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger

private val log = Logger.withTag("PlatformSystemDark")

/**
 * Android-owned lifecycle-safe system appearance source.
 *
 * Seeds from `Configuration.uiMode` on first composition and refreshes on every
 * `ON_RESUME` event so lock/unlock, display-mode changes, and other configuration
 * transitions are captured.  Logs a mismatch when the Compose
 * `isSystemInDarkTheme()` signal disagrees with the Android-owned value — this
 * telemetry is the first diagnostic that would have caught issue #677.
 */
@Composable
actual fun rememberPlatformSystemDark(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun readUiModeDark(): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    var isDark by remember { mutableStateOf(readUiModeDark()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val refreshed = readUiModeDark()
                if (refreshed != isDark) {
                    log.i { "System dark mode changed on resume: $isDark -> $refreshed" }
                }
                isDark = refreshed

                // Diagnostic: log mismatch between Android-owned value and Compose signal
                val composeSignal = try {
                    // Evaluate the Compose system-dark signal outside of composition
                    // so we can compare it against our authoritative source.
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                } catch (_: Throwable) {
                    null
                }
                if (composeSignal != null && composeSignal != refreshed) {
                    log.w {
                        "MISMATCH: Configuration.uiMode says dark=$refreshed " +
                            "but Compose signal says dark=$composeSignal"
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return isDark
}
