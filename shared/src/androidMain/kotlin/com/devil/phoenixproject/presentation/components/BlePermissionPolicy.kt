package com.devil.phoenixproject.presentation.components

import android.Manifest
import android.os.Build

internal data class BlePermissionDenialCopy(
    val body: String,
    val hint: String,
)

/**
 * Bluetooth (API 31+) or legacy location is required to scan and connect.
 * [Manifest.permission.POST_NOTIFICATIONS] is requested on API 33+ but is not
 * part of the BLE gate: a denial must not block the app, and workout
 * notifications are posted best-effort.
 */
internal object BlePermissionPolicy {
    fun requiredPermissions(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun optionalPermissions(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun permissionsToRequest(sdkInt: Int): List<String> =
        requiredPermissions(sdkInt) + optionalPermissions(sdkInt)

    fun gateSatisfied(grantedPermissions: Set<String>, sdkInt: Int): Boolean =
        requiredPermissions(sdkInt).all { it in grantedPermissions }

    fun denialCopy(missingPermissions: List<String>, canRetry: Boolean): BlePermissionDenialCopy {
        val labels = missingPermissions.map(::permissionLabel).distinct()
        val name = when (labels.size) {
            0 -> "Required"
            1 -> labels.single()
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
        val multiple = labels.size > 1
        val body = if (canRetry) {
            if (multiple) {
                "$name permissions are required to connect to your Phoenix Trainer. Please grant them to continue."
            } else {
                "$name permission is required to connect to your Phoenix Trainer. Please grant the permission to continue."
            }
        } else if (multiple) {
            "$name permissions have been permanently denied. Please enable them in your device's Settings to use Project Phoenix."
        } else {
            "$name permission has been permanently denied. Please enable it in your device's Settings to use Project Phoenix."
        }
        val hint = if (canRetry) {
            "If the permission dialog doesn't appear, you may need to enable $name permissions in your device's Settings > Apps > Project Phoenix > Permissions."
        } else {
            "Navigate to Permissions and enable $name access, then return here. The app will detect the change automatically."
        }
        return BlePermissionDenialCopy(body, hint)
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        -> "Bluetooth"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> "Location"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> "Required"
    }
}
