package com.devil.phoenixproject.util

/**
 * Platform-specific utility to open the app's system settings page.
 *
 * On Android: Opens Settings.ACTION_APPLICATION_DETAILS_SETTINGS for the app
 * On iOS: Opens UIApplication.openSettingsURLString
 */
expect fun openAppSettings()
