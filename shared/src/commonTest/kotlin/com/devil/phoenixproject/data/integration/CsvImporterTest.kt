package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvImporterTest {

    // -------------------------------------------------------------------------
    // Test data helpers
    // -------------------------------------------------------------------------

    private fun strongCsv(dataRows: String) = buildString {
        appendLine("Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes")
        append(dataRows)
    }

    private fun hevyCsv(dataRows: String) = buildString {
        appendLine("title,start_time,end_time,description,exercise_title,superset_id,notes,set_index,weight_kg,reps,distance_km,duration_seconds,rpe")
        append(dataRows)
    }

    // -------------------------------------------------------------------------
    // detectFormat
    // -------------------------------------------------------------------------

    @Test
    fun detectFormat_strongHeaders_returnsStrong() {
        val csv = strongCsv("2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, ")
        assertEquals(CsvFormat.STRONG, CsvImporter.detectFormat(csv))
    }

    @Test
    fun detectFormat_hevyHeaders_returnsHevy() {
        val csv = hevyCsv("Push Day,2023-10-15T09:30:00Z,2023-10-15T10:30:00Z,,Bench Press,,,,80,10,,,")
        assertEquals(CsvFormat.HEVY, CsvImporter.detectFormat(csv))
    }

    @Test
    fun detectFormat_unknownHeaders_returnsUnknown() {
        val csv = "foo,bar,baz\n1,2,3"
        assertEquals(CsvFormat.UNKNOWN, CsvImporter.detectFormat(csv))
    }

    @Test
    fun detectFormat_emptyContent_returnsUnknown() {
        assertEquals(CsvFormat.UNKNOWN, CsvImporter.detectFormat(""))
    }

    // -------------------------------------------------------------------------
    // parse — unknown format
    // -------------------------------------------------------------------------

    @Test
    fun parse_unknownFormat_returnsPreviewWithError() {
        val preview = CsvImporter.parse("col1,col2\n1,2", WeightUnit.KG)
        assertEquals(CsvFormat.UNKNOWN, preview.format)
        assertTrue(preview.activities.isEmpty())
        assertTrue(preview.errors.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Strong CSV — single workout
    // -------------------------------------------------------------------------

    @Test
    fun strongCsv_singleWorkout_createsOneActivity() {
        val csv = strongCsv(
            "2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, \n" +
            "2023-10-15 09:30:00,Push Day,1h 0m,Overhead Press,2,60,10,,,, "
        )
        val preview = CsvImporter.parse(csv, WeightUnit.KG, "user1", isPaidUser = true)
        assertEquals(CsvFormat.STRONG, preview.format)
        assertEquals(1, preview.activities.size, "Two rows with same workout key → one activity")
        assertEquals("Push Day", preview.activities[0].name)
        assertEquals(IntegrationProvider.STRONG, preview.activities[0].provider)
        assertEquals(3600, preview.activities[0].durationSeconds)
        assertEquals(true, preview.activities[0].needsSync)
        assertEquals("user1", preview.activities[0].profileId)
        assertTrue(preview.errors.isEmpty())
    }

    @Test
    fun strongCsv_multipleWorkouts_createsOneActivityEach() {
        val csv = strongCsv(
            "2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, \n" +
            "2023-10-16 07:00:00,Leg Day,45m,Squat,1,100,8,,,, "
        )
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        assertEquals(2, preview.activities.size)
        assertEquals("Push Day", preview.activities[0].name)
        assertEquals("Leg Day", preview.activities[1].name)
    }

    @Test
    fun strongCsv_externalId_isDeterministic() {
        val csv = strongCsv(
            "2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, "
        )
        val preview1 = CsvImporter.parse(csv, WeightUnit.KG)
        val preview2 = CsvImporter.parse(csv, WeightUnit.KG)

        // External IDs must be identical across two parses of the same content
        assertEquals(
            preview1.activities[0].externalId,
            preview2.activities[0].externalId,
            "externalId must be deterministic"
        )
        // Format: strong-{slug}-{epochSec}
        assertTrue(
            preview1.activities[0].externalId.startsWith("strong-push_day-"),
            "externalId should start with 'strong-push_day-'"
        )
    }

    @Test
    fun strongCsv_needsSync_falseForFreeUser() {
        val csv = strongCsv("2023-10-15 09:30:00,Leg Day,30m,Squat,1,100,5,,,, ")
        val preview = CsvImporter.parse(csv, WeightUnit.KG, isPaidUser = false)
        assertEquals(false, preview.activities[0].needsSync)
    }

    @Test
    fun strongCsv_headerOnly_returnsEmptyActivities() {
        val csv = "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes"
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        assertEquals(CsvFormat.STRONG, preview.format)
        assertTrue(preview.activities.isEmpty())
        assertNull(preview.dateRange)
    }

    @Test
    fun strongCsv_emptyContent_returnsError() {
        // Blank content → UNKNOWN format (no headers to detect)
        val preview = CsvImporter.parse("", WeightUnit.KG)
        assertEquals(CsvFormat.UNKNOWN, preview.format)
        assertTrue(preview.errors.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Strong CSV — date range and duration
    // -------------------------------------------------------------------------

    @Test
    fun strongCsv_dateRange_coversAllActivities() {
        val csv = strongCsv(
            "2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, \n" +
            "2023-10-20 07:00:00,Leg Day,30m,Squat,1,100,8,,,, "
        )
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        assertNotNull(preview.dateRange)
        val (earliest, latest) = preview.dateRange!!
        assertTrue(earliest < latest, "Earliest should be before latest")
    }

    @Test
    fun strongCsv_totalDuration_sumsAllActivities() {
        val csv = strongCsv(
            "2023-10-15 09:30:00,Push Day,1h 0m,Bench Press,1,80,10,,,, \n" +
            "2023-10-20 07:00:00,Leg Day,30m,Squat,1,100,8,,,, "
        )
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        // 1h = 3600s, 30m = 1800s, total = 5400s
        assertEquals(5400L, preview.totalDurationSeconds)
    }

    // -------------------------------------------------------------------------
    // Hevy CSV — single workout with duration from start/end
    // -------------------------------------------------------------------------

    @Test
    fun hevyCsv_singleWorkout_createsOneActivity() {
        val csv = hevyCsv(
            "Push Day,2023-10-15T09:30:00Z,2023-10-15T10:30:00Z,,Bench Press,,,,80,10,,,\n" +
            "Push Day,2023-10-15T09:30:00Z,2023-10-15T10:30:00Z,,Overhead Press,,,,60,10,,,"
        )
        val preview = CsvImporter.parse(csv, WeightUnit.KG, "user2", isPaidUser = true)
        assertEquals(CsvFormat.HEVY, preview.format)
        assertEquals(1, preview.activities.size, "Two rows same key → one activity")
        assertEquals("Push Day", preview.activities[0].name)
        assertEquals(IntegrationProvider.HEVY, preview.activities[0].provider)
        // end - start = 1 hour = 3600s
        assertEquals(3600, preview.activities[0].durationSeconds)
        assertEquals(true, preview.activities[0].needsSync)
    }

    @Test
    fun hevyCsv_externalId_isDeterministic() {
        val csv = hevyCsv("Pull Day,2023-11-01T06:00:00Z,2023-11-01T07:00:00Z,,Deadlift,,,,120,5,,,")
        val p1 = CsvImporter.parse(csv, WeightUnit.KG)
        val p2 = CsvImporter.parse(csv, WeightUnit.KG)
        assertEquals(p1.activities[0].externalId, p2.activities[0].externalId)
        assertTrue(p1.activities[0].externalId.startsWith("hevy-pull_day-"))
    }

    @Test
    fun hevyCsv_localDateFallback_parsedAsLocalTime() {
        // Hevy sometimes exports without timezone — should still parse
        val csv = hevyCsv("Morning Lift,2023-10-15 09:30:00,2023-10-15 10:00:00,,Squat,,,,100,8,,,")
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        assertEquals(1, preview.activities.size)
        assertTrue(preview.activities[0].startedAt > 0L)
        // 30-minute session
        assertEquals(1800, preview.activities[0].durationSeconds)
    }

    @Test
    fun hevyCsv_headerOnly_returnsEmptyActivities() {
        val csv = "title,start_time,end_time,description,exercise_title,superset_id,notes,set_index,weight_kg,reps,distance_km,duration_seconds,rpe"
        val preview = CsvImporter.parse(csv, WeightUnit.KG)
        assertEquals(CsvFormat.HEVY, preview.format)
        assertTrue(preview.activities.isEmpty())
        assertNull(preview.dateRange)
    }

    // -------------------------------------------------------------------------
    // parseStrongDuration
    // -------------------------------------------------------------------------

    @Test
    fun parseStrongDuration_hoursAndMinutes() {
        assertEquals(3600 + 23 * 60, CsvImporter.parseStrongDuration("1h 23m"))
    }

    @Test
    fun parseStrongDuration_minutesOnly() {
        assertEquals(45 * 60, CsvImporter.parseStrongDuration("45m"))
    }

    @Test
    fun parseStrongDuration_hoursOnly() {
        assertEquals(2 * 3600, CsvImporter.parseStrongDuration("2h"))
    }

    @Test
    fun parseStrongDuration_zero() {
        assertEquals(0, CsvImporter.parseStrongDuration("0m"))
    }

    @Test
    fun parseStrongDuration_blank_returnsZero() {
        assertEquals(0, CsvImporter.parseStrongDuration(""))
        assertEquals(0, CsvImporter.parseStrongDuration("  "))
    }

    // -------------------------------------------------------------------------
    // parseStrongDate
    // -------------------------------------------------------------------------

    @Test
    fun parseStrongDate_validFormat_returnsNonZeroEpoch() {
        val result = CsvImporter.parseStrongDate("2023-10-15 09:30:00")
        assertTrue(result > 0L, "Valid date should produce positive epoch ms")
    }

    @Test
    fun parseStrongDate_invalidFormat_returnsZero() {
        assertEquals(0L, CsvImporter.parseStrongDate("not-a-date"))
        assertEquals(0L, CsvImporter.parseStrongDate(""))
    }

    // -------------------------------------------------------------------------
    // parseHevyDate
    // -------------------------------------------------------------------------

    @Test
    fun parseHevyDate_iso8601WithZ_returnsCorrectEpoch() {
        // 2023-10-15T09:30:00Z = specific UTC instant
        val result = CsvImporter.parseHevyDate("2023-10-15T09:30:00Z")
        assertTrue(result > 0L)
    }

    @Test
    fun parseHevyDate_iso8601WithOffset_returnsNonZero() {
        val result = CsvImporter.parseHevyDate("2023-10-15T09:30:00+05:30")
        assertTrue(result > 0L)
    }

    @Test
    fun parseHevyDate_localDatetime_returnsNonZero() {
        val result = CsvImporter.parseHevyDate("2023-10-15 09:30:00")
        assertTrue(result > 0L)
    }

    @Test
    fun parseHevyDate_invalidFormat_returnsZero() {
        assertEquals(0L, CsvImporter.parseHevyDate("garbage"))
        assertEquals(0L, CsvImporter.parseHevyDate(""))
    }

    // -------------------------------------------------------------------------
    // buildStrongExternalId / buildHevyExternalId
    // -------------------------------------------------------------------------

    @Test
    fun buildStrongExternalId_spacesConvertedToUnderscores() {
        val id = CsvImporter.buildStrongExternalId("Push Day", 1_697_359_800_000L)
        assertEquals("strong-push_day-1697359800", id)
    }

    @Test
    fun buildHevyExternalId_titleLowercasedAndSlugified() {
        val id = CsvImporter.buildHevyExternalId("Pull Day", 1_697_359_800_000L)
        assertEquals("hevy-pull_day-1697359800", id)
    }
}
