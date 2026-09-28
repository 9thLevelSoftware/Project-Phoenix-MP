package com.devil.phoenixproject.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the English workout-notification copy that used to be hardcoded in
 * [WorkoutForegroundService], and checks locale folders do not drop those keys.
 */
class WorkoutForegroundNotificationStringsTest {
    @Test
    fun `default strings match the previous English notification copy`() {
        val strings = readStringResources(File(findRepoRoot(), "androidApp/src/main/res/values/strings.xml"))

        assertEquals("Phoenix Workout", strings.getValue("workout_notification_channel_name"))
        assertEquals("Shows ongoing workout status", strings.getValue("workout_notification_channel_description"))
        assertEquals("Phoenix Workout", strings.getValue("workout_notification_title_initializing"))
        assertEquals("Workout Starting", strings.getValue("workout_notification_title_countdown"))
        assertEquals("Workout Active", strings.getValue("workout_notification_title_active"))
        assertEquals("Set Complete", strings.getValue("workout_notification_title_set_complete"))
        assertEquals("Rest Timer", strings.getValue("workout_notification_title_rest"))
        assertEquals("Just Lift Rest", strings.getValue("workout_notification_title_just_lift_rest"))
        assertEquals("Workout Paused", strings.getValue("workout_notification_title_paused"))
        assertEquals("Phoenix workout in progress", strings.getValue("workout_notification_in_progress"))

        assertEquals(
            "Preparing Old School",
            strings.getValue("workout_notification_preparing").format("Old School"),
        )
        assertEquals("Starts in 12s", strings.getValue("workout_notification_starts_in").format(12))
        assertEquals("Next: Bench Press", strings.getValue("workout_notification_next").format("Bench Press"))
        assertEquals("8s remaining", strings.getValue("workout_notification_seconds_remaining").format(8))
        assertEquals("Set 2/4", strings.getValue("workout_notification_set_progress").format(2, 4))
        assertEquals("Reps 6/10", strings.getValue("workout_notification_reps_progress").format(6, 10))
        assertEquals("Reps 6", strings.getValue("workout_notification_reps").format(6))
    }

    @Test
    fun `foreground service resolves notification copy from resources`() {
        val source = File(
            findRepoRoot(),
            "androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt",
        ).readText()

        val resourceIds = listOf(
            "workout_notification_channel_name",
            "workout_notification_channel_description",
            "workout_notification_title_initializing",
            "workout_notification_title_countdown",
            "workout_notification_title_active",
            "workout_notification_title_set_complete",
            "workout_notification_title_rest",
            "workout_notification_title_just_lift_rest",
            "workout_notification_title_paused",
            "workout_notification_preparing",
            "workout_notification_starts_in",
            "workout_notification_next",
            "workout_notification_seconds_remaining",
            "workout_notification_set_progress",
            "workout_notification_reps_progress",
            "workout_notification_reps",
            "workout_notification_in_progress",
        )
        resourceIds.forEach { id ->
            assertTrue("Missing R.string.$id", source.contains("R.string.$id"))
        }

        val removedLiterals = listOf(
            "\"Phoenix Workout\"",
            "\"Shows ongoing workout status\"",
            "\"Workout Starting\"",
            "\"Workout Active\"",
            "\"Set Complete\"",
            "\"Rest Timer\"",
            "\"Just Lift Rest\"",
            "\"Workout Paused\"",
            "\"Phoenix workout in progress\"",
            "\"Starts in ",
            "\"Next: ",
            "s remaining\"",
            "\"Preparing ",
            "\"Set \$",
            "\"Reps ",
        )
        removedLiterals.forEach { literal ->
            assertFalse("Hardcoded notification copy still present: $literal", source.contains(literal))
        }
    }

    @Test
    fun `locale string files include every workout notification key`() {
        val defaultKeys = readStringResources(
            File(findRepoRoot(), "androidApp/src/main/res/values/strings.xml"),
        ).keys.filter { it.startsWith("workout_notification_") }.toSet()
        assertTrue(defaultKeys.isNotEmpty())

        val resDir = File(findRepoRoot(), "androidApp/src/main/res")
        val localeFiles = resDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    dir.name.startsWith("values-") &&
                    !dir.name.startsWith("values-night")
            }
            ?.map { File(it, "strings.xml") }
            ?.filter { it.isFile }
            .orEmpty()

        localeFiles.forEach { file ->
            val keys = readStringResources(file).keys
            val missing = defaultKeys - keys
            assertTrue(
                "${file.parentFile?.name ?: file.path}/strings.xml is missing ${missing.joinToString()}",
                missing.isEmpty(),
            )
        }
    }

    private fun readStringResources(file: File): Map<String, String> {
        assertTrue("Missing $file", file.isFile)
        val pattern = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        return pattern.findAll(file.readText()).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun findRepoRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile ?: error("Could not locate repo root from user.dir")
        }
    }
}
