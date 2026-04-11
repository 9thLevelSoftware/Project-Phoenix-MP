package com.devil.phoenixproject.util

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

actual fun openAppSettings() {
    val activity = ActivityHolder.getActivity() ?: run {
        Log.w("AppSettingsOpener", "No activity available to open settings")
        return
    }
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    } catch (e: Exception) {
        Log.e("AppSettingsOpener", "Failed to open app settings", e)
    }
}
