package com.devil.phoenixproject.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectSettingsAttemptTest {

    @Test
    fun belowApi34_failedSettingsIntent_doesNotRetryTheSameAction() {
        val attempts = healthConnectSettingsAttempts("com.devil.phoenixproject", Build.VERSION_CODES.TIRAMISU)
        val started = mutableListOf<String?>()

        val opened = launchHealthConnectSettingsChain(attempts) { intent ->
            started += intent.action
            if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                throw ActivityNotFoundException(intent.action)
            }
        }

        assertEquals(
            listOf(
                "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            started,
        )
        assertEquals(1, opened)
        assertEquals("package:com.devil.phoenixproject", attempts.last().data?.toString())
    }

    @Test
    fun api34_permissionIntentCarriesPackageThenDistinctFallbacks() {
        val attempts = healthConnectSettingsAttempts(
            "com.devil.phoenixproject",
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )

        assertEquals(
            listOf(
                "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS",
                "android.health.connect.action.HEALTH_HOME_SETTINGS",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            attempts.map { it.action },
        )
        assertEquals(attempts.map { it.action }.distinct(), attempts.map { it.action })
        assertEquals(
            "com.devil.phoenixproject",
            attempts.first().getStringExtra(Intent.EXTRA_PACKAGE_NAME),
        )
        assertNull(attempts[1].getStringExtra(Intent.EXTRA_PACKAGE_NAME))
        assertEquals("package:com.devil.phoenixproject", attempts.last().data?.toString())
    }
}
