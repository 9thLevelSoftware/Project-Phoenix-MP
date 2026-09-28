package com.devil.phoenixproject.presentation.components

import android.Manifest
import android.os.Build
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * POST_NOTIFICATIONS is optional. A user who granted Bluetooth and denied
 * notifications must still pass the BLE gate, and the denial screen must name
 * the permission that is actually missing.
 */
class BlePermissionGateTest {

    @Test
    fun `api 33 required permissions are bluetooth only`() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.TIRAMISU),
        )
        assertFalse(
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.TIRAMISU)
                .contains(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun `api 31 required permissions are bluetooth scan and connect`() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.S),
        )
        assertEquals(emptyList(), BlePermissionPolicy.optionalPermissions(Build.VERSION_CODES.S))
    }

    @Test
    fun `pre api 31 required permission is fine location`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredPermissions(Build.VERSION_CODES.R),
        )
        assertEquals(emptyList(), BlePermissionPolicy.optionalPermissions(Build.VERSION_CODES.R))
    }

    @Test
    fun `api 33 still requests notifications but does not require them for the gate`() {
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            BlePermissionPolicy.optionalPermissions(Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            BlePermissionPolicy.permissionsToRequest(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun `bluetooth granted and notifications denied still satisfies the ble gate`() {
        val granted = setOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        assertTrue(BlePermissionPolicy.gateSatisfied(granted, Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun `missing bluetooth connect does not satisfy the ble gate`() {
        val granted = setOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertFalse(BlePermissionPolicy.gateSatisfied(granted, Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun `legacy location grant satisfies the ble gate without bluetooth runtime permissions`() {
        assertTrue(
            BlePermissionPolicy.gateSatisfied(
                setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                Build.VERSION_CODES.R,
            ),
        )
        assertFalse(BlePermissionPolicy.gateSatisfied(emptySet(), Build.VERSION_CODES.R))
    }

    @Test
    fun `denial copy names bluetooth when bluetooth is missing`() {
        val copy = BlePermissionPolicy.denialCopy(
            missingPermissions = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            canRetry = true,
        )
        assertEquals(
            "Bluetooth permission is required to connect to your Phoenix Trainer. Please grant the permission to continue.",
            copy.body,
        )
        assertEquals(
            "If the permission dialog doesn't appear, you may need to enable Bluetooth permissions in your device's Settings > Apps > Project Phoenix > Permissions.",
            copy.hint,
        )
        assertFalse(copy.body.contains("Notification"))
        assertFalse(copy.hint.contains("Notification"))
    }

    @Test
    fun `permanent bluetooth denial names bluetooth`() {
        val copy = BlePermissionPolicy.denialCopy(
            missingPermissions = listOf(Manifest.permission.BLUETOOTH_CONNECT),
            canRetry = false,
        )
        assertEquals(
            "Bluetooth permission has been permanently denied. Please enable it in your device's Settings to use Project Phoenix.",
            copy.body,
        )
        assertEquals(
            "Navigate to Permissions and enable Bluetooth access, then return here. The app will detect the change automatically.",
            copy.hint,
        )
    }

    @Test
    fun `denial copy names location when fine location is missing`() {
        val copy = BlePermissionPolicy.denialCopy(
            missingPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            canRetry = false,
        )
        assertEquals(
            "Location permission has been permanently denied. Please enable it in your device's Settings to use Project Phoenix.",
            copy.body,
        )
        assertEquals(
            "Navigate to Permissions and enable Location access, then return here. The app will detect the change automatically.",
            copy.hint,
        )
        assertFalse(copy.body.contains("Bluetooth"))
        assertFalse(copy.hint.contains("Bluetooth"))
    }

    @Test
    fun `denial copy names every missing permission group`() {
        val copy = BlePermissionPolicy.denialCopy(
            missingPermissions = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            canRetry = true,
        )
        assertEquals(
            "Bluetooth and Location permissions are required to connect to your Phoenix Trainer. Please grant them to continue.",
            copy.body,
        )
        assertEquals(
            "If the permission dialog doesn't appear, you may need to enable Bluetooth and Location permissions in your device's Settings > Apps > Project Phoenix > Permissions.",
            copy.hint,
        )
        assertFalse(copy.body.contains("Notification"))
    }

    @Test
    fun `denial copy names notifications when that is the missing permission`() {
        val copy = BlePermissionPolicy.denialCopy(
            missingPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            canRetry = true,
        )
        assertTrue(copy.body.contains("Notifications"))
        assertTrue(copy.hint.contains("Notifications"))
        assertFalse(copy.body.contains("Bluetooth"))
        assertFalse(copy.hint.contains("Bluetooth"))
    }

    @Test
    fun `denied screen uses the missing permission copy`() {
        val handler = source(
            "src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.android.kt",
        )
        assertTrue(
            "BlePermissionPolicy.denialCopy(" in handler,
            "BlePermissionDeniedScreen must render copy for the permissions that are actually missing.",
        )
        assertTrue(
            "missingRequiredPermissions(" in handler,
            "The denial screen must be told which required permissions are missing.",
        )
        assertTrue(
            "getPermissionsToRequest()" in handler,
            "The prompt may still ask for optional notifications.",
        )
        assertFalse(
            "Bluetooth permission is required" in handler,
            "Denial copy must not hardcode Bluetooth.",
        )
        assertFalse(
            "Bluetooth permission has been permanently denied" in handler,
            "Permanent denial copy must not hardcode Bluetooth.",
        )
        assertFalse(
            "enable Bluetooth" in handler,
            "Settings hints must not hardcode Bluetooth.",
        )
    }

    @Test
    fun `workout notification posting stays best effort when notifications are denied`() {
        val service = source(
            "androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt",
        )
        val start = service.substringAfter("private fun startWorkoutForeground()")
        assertTrue(start.contains("startForeground("))
        assertTrue(start.contains("catch (e: Exception)"))
        assertFalse(
            "POST_NOTIFICATIONS" in start,
            "The workout foreground notification must still be posted when the optional permission is denied.",
        )
        assertFalse(
            "checkSelfPermission" in start,
            "Notification posting must not be gated on a runtime permission check.",
        )
    }

    private fun source(relativePath: String): String = assertNotNull(
        readProjectFile(relativePath),
        "$relativePath must be readable for this contract test",
    )
}
