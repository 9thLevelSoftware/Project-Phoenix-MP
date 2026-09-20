package com.devil.phoenixproject.util

import android.content.Context
import android.content.ContextWrapper
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidCsvExporterTest {

    @Test
    fun exportsQuoteSpecialTextAndKeepNumericColumnsRaw() {
        val cache = File.createTempFile("phoenix-csv", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val exporter = AndroidCsvExporter(TestContext(cache))
            val formattedWeight = { _: Float, _: WeightUnit -> "12,5 kg\n\"display\"" }
            val session = WorkoutSession(
                id = "session-1",
                timestamp = 1_700_000_000_000L,
                mode = "Mode, \"quoted\"\nvariant",
                reps = 7,
                totalReps = 7,
                duration = 12_000L,
                exerciseName = "Bench, \"Press\"\nvariant",
                weightPerCableKg = 10f,
                progressionKg = 1f,
            )
            val record = PersonalRecord(
                exerciseId = "bench",
                exerciseName = "Bench",
                weightPerCableKg = 10f,
                reps = 7,
                oneRepMax = 20f,
                timestamp = 1_700_000_000_000L,
                workoutMode = "Mode, \"quoted\"",
                volume = 70f,
            )

            val history = exporter.exportWorkoutHistory(
                listOf(session),
                emptyMap(),
                WeightUnit.KG,
                formattedWeight,
            ).getOrThrow().let(::File).readText()
            val personalRecords = exporter.exportPersonalRecords(
                listOf(record),
                emptyMap(),
                WeightUnit.KG,
                formattedWeight,
            ).getOrThrow().let(::File).readText()
            val progression = exporter.exportPRProgression(
                listOf(record),
                emptyMap(),
                WeightUnit.KG,
                formattedWeight,
            ).getOrThrow().let(::File).readText()

            val escapedWeight = "\"12,5 kg\n\"\"display\"\"\""
            assertTrue(history.contains("\"Bench, \"\"Press\"\"\nvariant\""))
            assertTrue(history.contains("\"Mode, \"\"quoted\"\"\nvariant\""))
            assertTrue(history.contains(escapedWeight))
            assertTrue(history.contains(",7,"), "reps remain numeric")
            assertTrue(history.contains(",12000,No,"), "duration and Just Lift columns remain separate")
            assertTrue(personalRecords.contains(escapedWeight))
            assertTrue(personalRecords.contains(",7,"), "PR reps remain numeric")
            assertTrue(progression.contains(escapedWeight))
            assertTrue(progression.contains(",7,"), "progression reps remain numeric")
        } finally {
            cache.deleteRecursively()
        }
    }

    private class TestContext(cacheDir: File) : ContextWrapper(null) {
        private val cache = cacheDir

        override fun getCacheDir(): File = cache
    }
}
