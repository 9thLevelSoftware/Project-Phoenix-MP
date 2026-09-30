package com.devil.phoenixproject.presentation.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the workout foreground-service start contract.
 *
 * The controller must not remember a local "already running" flag. That flag
 * stays true after the workout service calls stopSelf(), and the next update
 * then uses startService, which cannot promote a connected-device foreground
 * service from the background.
 */
class AndroidWorkoutServiceControllerContractTest {

    @Test
    fun controllerAlwaysStartsTheForegroundServiceAndDoesNotTrackIsRunning() {
        val source = readRepoFile(
            "shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/manager/AndroidWorkoutServiceController.android.kt",
        )

        assertFalse(source.contains("isRunning"), "stale isRunning gate must stay deleted")
        assertFalse(
            source.contains("startService("),
            "updates must use startForegroundService, not startService",
        )
        assertTrue(source.contains("ContextCompat.startForegroundService"))
        assertTrue(
            source.contains("stopService("),
            "stop must no-op when the service is already gone",
        )
        assertFalse(source.contains("bindService"))
        assertFalse(source.contains("linkToDeath"))
        assertFalse(source.contains("DeathRecipient"))
    }

    @Test
    fun alreadyForegroundServiceUpdatesTheNotificationWithoutBinding() {
        val source = readRepoFile(
            "androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt",
        )
        val syncBranch = source.substringAfter("ACTION_SYNC ->").substringBefore("ACTION_STOP ->")

        assertTrue(syncBranch.contains("isForegroundActive"))
        assertTrue(syncBranch.contains("startWorkoutForeground()"))
        assertTrue(syncBranch.contains("updateNotification()"))
        assertFalse(source.contains("bindService"))
        assertFalse(source.contains("linkToDeath"))
        assertFalse(source.contains("DeathRecipient"))
    }

    private fun readRepoFile(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue(file.isFile, "Missing $file")
        return file.readText()
    }

    private fun repoRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile ?: error("Could not locate repo root from user.dir")
        }
    }
}
