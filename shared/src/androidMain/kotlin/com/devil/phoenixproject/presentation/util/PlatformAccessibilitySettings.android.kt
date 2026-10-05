package com.devil.phoenixproject.presentation.util

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberPlatformAccessibilitySettings(): PlatformAccessibilitySettings {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val contextState = rememberUpdatedState(context)

    val boldTextEnabled = remember(config) {
        if (Build.VERSION.SDK_INT >= 31) {
            context.resources.configuration.fontWeightAdjustment >= 300
        } else {
            false
        }
    }

    // ANIMATOR_DURATION_SCALE is a global setting, not a Configuration field.
    // Re-read it on configuration changes, on resume, and from a settings observer
    // so a reduce-motion toggle is picked up without waiting for a density or orientation change.
    var reduceMotion by remember { mutableStateOf(readReduceMotion(context)) }

    LaunchedEffect(config) {
        reduceMotion = readReduceMotion(contextState.value)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduceMotion = readReduceMotion(contextState.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val onScaleChanged = {
            reduceMotion = readReduceMotion(contextState.value)
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onScaleChanged()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return PlatformAccessibilitySettings(
        boldTextEnabled = boldTextEnabled,
        reduceMotion = reduceMotion,
    )
}

private fun readReduceMotion(context: Context): Boolean {
    val animScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return reduceMotionFromAnimatorDurationScale(animScale)
}

/** True when animator duration scale is off (0), which is Android's remove-animations signal. */
internal fun reduceMotionFromAnimatorDurationScale(scale: Float): Boolean = scale == 0f
