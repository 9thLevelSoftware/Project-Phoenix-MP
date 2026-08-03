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

    // Capture the Compose system-dark signal during composition so we can compare
    // it against the authoritative Configuration.uiMode value on resume.
    val composeSignal = isSystemInDarkTheme()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val refreshed = readUiModeDark()
                if (refreshed != isDark) {
                    log.i { "System dark mode changed on resume: $isDark -> $refreshed" }
                }
                isDark = refreshed

                // Diagnostic: log mismatch between Android-owned value and Compose signal.
                // composeSignal is captured during composition; refreshed comes from
                // Configuration.uiMode on this resume event.  A drift between them means
                // the Compose ambient and the OS night-mode flag disagree.
                if (composeSignal != refreshed) {
                    log.w {
                        "MISMATCH: Configuration.uiMode says dark=$refreshed " +
                            "but Compose isSystemInDarkTheme() says dark=$composeSignal"
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
