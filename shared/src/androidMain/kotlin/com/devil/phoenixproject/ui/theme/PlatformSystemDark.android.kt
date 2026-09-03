package com.devil.phoenixproject.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger

private val log = Logger.withTag("PlatformSystemDark")

@Composable
actual fun rememberPlatformSystemDark(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val composeNight = isSystemInDarkTheme()
    val currentComposeNight by rememberUpdatedState(composeNight)

    fun applicationNight(): NightSample = nightSampleFromMask(
        context.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK,
    )

    fun activityNight(): NightSample = nightSampleFromMask(
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
    )

    var previous by remember {
        mutableStateOf(
            resolveSystemDark(
                previous = true,
                applicationNight = applicationNight(),
                activityNight = activityNight(),
                composeNight = composeNight,
            ),
        )
    }

    val resolved = resolveSystemDark(
        previous = previous,
        applicationNight = applicationNight(),
        activityNight = activityNight(),
        composeNight = composeNight,
    )
    SideEffect { previous = resolved }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val next = resolveSystemDark(
                    previous = previous,
                    applicationNight = applicationNight(),
                    activityNight = activityNight(),
                    composeNight = currentComposeNight,
                )
                if (next != previous) {
                    log.i {
                        "System dark reconciled on resume: $previous -> $next " +
                            "app=${applicationNight()} activity=${activityNight()} " +
                            "compose=$currentComposeNight"
                    }
                }
                previous = next
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return resolved
}
