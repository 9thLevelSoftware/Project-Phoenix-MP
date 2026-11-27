@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

class Utf8TempFileAndCsvExporterIosTest {

    @Test
    fun writeUtf8TempFileOverwritesExistingFileAndPreservesUtf8() = runTest {
        val fileName = "utf8_temp_test_${NSUUID().UUIDString}.txt"
        val firstPath = assertNotNull(writeUtf8TempFile(fileName, "old-content"))
        try {
            assertEquals("old-content", readUriContent(firstPath))
            val secondPath = assertNotNull(writeUtf8TempFile(fileName, "new-\uD83D\uDCAA-utf8"))
            assertEquals(firstPath, secondPath)
            assertEquals("new-\uD83D\uDCAA-utf8", readUriContent(secondPath))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(firstPath, null)
        }
    }

    @Test
    fun iosCsvExporterWritesHistoryRecordsAndProgressionToDisk() = runTest {
        val exporter = IosCsvExporter()
        val session = WorkoutSession(
            id = "s-1",
            timestamp = 1_700_000_000_000L,
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 40f,
            workingReps = 10,
            totalReps = 10,
            duration = 45_000L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
        )
        val record = PersonalRecord(
            id = 1L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            weightPerCableKg = 50f,
            reps = 5,
            oneRepMax = OneRepMaxCalculator.estimate(50f, 5),
            timestamp = 1_700_000_000_000L,
            workoutMode = "OldSchool",
            prType = PRType.MAX_WEIGHT,
            volume = 250f,
        )
        val formatWeight: (Float, WeightUnit) -> String = { w, u -> "$w ${u.name.lowercase()}" }
        val names = mapOf("bench" to "Bench Press")

        val historyPath = exporter.exportWorkoutHistory(listOf(session), names, WeightUnit.KG, formatWeight).getOrThrow()
        val prPath = exporter.exportPersonalRecords(listOf(record), names, WeightUnit.KG, formatWeight).getOrThrow()
        val progPath = exporter.exportPRProgression(listOf(record), names, WeightUnit.KG, formatWeight).getOrThrow()
        try {
            assertTrue(readUriContent(historyPath)?.contains("Bench Press") == true)
            assertTrue(readUriContent(prPath)?.contains("Bench Press") == true)
            assertTrue(readUriContent(progPath)?.contains("Bench Press") == true)
        } finally {
            listOf(historyPath, prPath, progPath).forEach {
                NSFileManager.defaultManager.removeItemAtPath(it, null)
            }
        }
    }
}
