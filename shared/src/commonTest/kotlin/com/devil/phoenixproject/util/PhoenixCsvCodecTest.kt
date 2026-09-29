package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

class PhoenixCsvCodecTest {

    @Test
    fun androidShapedAndIosShapedRowsParseToTheSameSession() {
        val expected = WorkoutSession(
            id = "ignored",
            timestamp = localMinute(2026, 3, 10, 14, 30),
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 80f,
            progressionKg = 2.5f,
            duration = 45_000L,
            totalReps = 0,
            warmupReps = 0,
            workingReps = 0,
            isJustLift = false,
            eccentricLoad = 100,
            exerciseName = "Bench Press",
        )
        // Android's columns, with Time so the clock time survives, and duration in seconds.
        // Total/warmup/working are 0 because that is all a legacy iOS row can represent:
        // its single Reps cell was WorkoutSession.reps (the target).
        val androidShaped = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,14:30,Bench Press,OldSchool,10,0,0,0,80 kg,+2.5 kg,45,No,100
        """.trimIndent()
        val iosShaped = """
            Date,Time,Exercise,Mode,Weight (KG),Progression,Reps,Duration (s)
            2026-03-10,14:30,Bench Press,OldSchool,80 kg,+2.5 kg,10,45
        """.trimIndent()

        val androidParsed = parseSingleSession(androidShaped)
        val iosParsed = parseSingleSession(iosShaped)

        assertEquals(androidParsed.copy(id = iosParsed.id), iosParsed)
        assertEquals(expected.copy(id = androidParsed.id), androidParsed)
    }

    @Test
    fun historyRoundTripKeepsPhoenixFieldsAndStoresDurationAsMilliseconds() {
        val original = WorkoutSession(
            id = "session-1",
            timestamp = localMinute(2026, 3, 10, 14, 30),
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 80f,
            progressionKg = -1.5f,
            duration = 90_000L,
            totalReps = 10,
            warmupReps = 2,
            workingReps = 8,
            isJustLift = true,
            eccentricLoad = 125,
            exerciseName = "=Bench, Flat",
        )

        val csv = PhoenixCsvCodec.encodeWorkoutHistory(
            listOf(original),
            emptyMap(),
            WeightUnit.KG,
            ::formatKg,
        )
        assertEquals(PhoenixCsvCodec.HISTORY_HEADER, csv.lineSequence().first())
        assertEquals("90", cell(csv, "Duration (s)"))

        val parsed = parseSingleSession(csv)
        assertEquals(original.copy(id = parsed.id), parsed)
    }

    @Test
    fun durationColumnTruncatesSubSecondRemainder() {
        val session = WorkoutSession(
            timestamp = localMinute(2026, 3, 10, 14, 30),
            duration = 45_500L,
            exerciseName = "Curl",
        )
        val csv = PhoenixCsvCodec.encodeWorkoutHistory(listOf(session), emptyMap(), WeightUnit.KG, ::formatKg)

        assertEquals("45", cell(csv, "Duration (s)"))
        assertEquals(45_000L, parseSingleSession(csv).duration)
    }

    @Test
    fun echoHistoryExportsPeakLoad() {
        val session = WorkoutSession(
            timestamp = localMinute(2026, 3, 10, 14, 30),
            mode = "Echo",
            weightPerCableKg = 10f,
            peakWeightKg = 40f,
            exerciseName = "Row",
        )
        val csv = PhoenixCsvCodec.encodeWorkoutHistory(listOf(session), emptyMap(), WeightUnit.KG, ::formatKg)

        assertEquals("40 kg", cell(csv, "Weight"))
    }

    @Test
    fun historyExportOrdersNewestFirst() {
        val older = WorkoutSession(
            timestamp = localMinute(2026, 3, 10, 9, 0),
            exerciseName = "Squat",
        )
        val newer = WorkoutSession(
            timestamp = localMinute(2026, 3, 11, 9, 0),
            exerciseName = "Press",
        )
        val csv = PhoenixCsvCodec.encodeWorkoutHistory(listOf(older, newer), emptyMap(), WeightUnit.KG, ::formatKg)
        val (parsed, errors) = PhoenixCsvCodec.parseWorkoutHistory(csv)

        assertEquals(emptyList(), errors)
        assertEquals(listOf("Press", "Squat"), parsed.map { it.exerciseName })
    }

    @Test
    fun personalRecordRoundTrip() {
        val timestamp = LocalDate(2026, 3, 10).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val original = PersonalRecord(
            exerciseId = "bench",
            exerciseName = "Bench Press",
            weightPerCableKg = 80f,
            reps = 5,
            oneRepMax = 90f,
            timestamp = timestamp,
            workoutMode = "OldSchool",
            volume = 400f,
            phase = WorkoutPhase.CONCENTRIC,
        )

        val csv = PhoenixCsvCodec.encodePersonalRecords(
            listOf(original),
            emptyMap(),
            WeightUnit.KG,
            ::formatKg,
        )
        assertEquals(PhoenixCsvCodec.PERSONAL_RECORD_HEADER, csv.lineSequence().first())
        val (parsed, errors) = PhoenixCsvCodec.parsePersonalRecords(csv)

        assertEquals(emptyList(), errors)
        val record = parsed.single()
        assertEquals("Bench Press", record.exerciseName)
        assertEquals("", record.exerciseId)
        assertEquals(WorkoutPhase.CONCENTRIC, record.phase)
        assertEquals(80f, record.weightPerCableKg)
        assertEquals(5, record.reps)
        assertEquals(90f, record.oneRepMax)
        assertEquals(timestamp, record.timestamp)
        assertEquals("OldSchool", record.workoutMode)
        assertEquals(400f, record.volume)
    }

    @Test
    fun legacyAndroidPersonalRecordParsesUnitlessOneRepMax() {
        val csv = """
            Exercise,Phase,Weight,Reps,Date,Mode,1RM
            Bench Press,COMBINED,80.0 kg,5,2026-03-10,OldSchool,90.0
        """.trimIndent()

        val (parsed, errors) = PhoenixCsvCodec.parsePersonalRecords(csv)

        assertEquals(emptyList(), errors)
        assertEquals(80f, parsed.single().weightPerCableKg)
        assertEquals(90f, parsed.single().oneRepMax)
        assertEquals("OldSchool", parsed.single().workoutMode)
    }

    @Test
    fun legacyIosPersonalRecordHeaderParsesWithoutMode() {
        val csv = """
            Exercise,Phase,Weight (KG),Reps,1RM,Date
            Bench Press,COMBINED,80 kg,5,90 kg,2026-03-10
        """.trimIndent()

        val (parsed, errors) = PhoenixCsvCodec.parsePersonalRecords(csv)

        assertEquals(emptyList(), errors)
        val record = parsed.single()
        assertEquals("Bench Press", record.exerciseName)
        assertEquals(WorkoutPhase.COMBINED, record.phase)
        assertEquals(80f, record.weightPerCableKg)
        assertEquals(5, record.reps)
        assertEquals(90f, record.oneRepMax)
        assertEquals("", record.workoutMode)
    }

    @Test
    fun prProgressionUsesWeightDelta() {
        val day = LocalDate(2026, 3, 10).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val later = LocalDate(2026, 3, 17).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val first = PersonalRecord(
            exerciseId = "squat",
            exerciseName = "Squat",
            weightPerCableKg = 80f,
            reps = 5,
            oneRepMax = 90f,
            timestamp = day,
            workoutMode = "OldSchool",
            volume = 400f,
        )
        val second = first.copy(weightPerCableKg = 82.5f, timestamp = later, volume = 412.5f)

        val csv = PhoenixCsvCodec.encodePrProgression(listOf(second, first), emptyMap(), WeightUnit.KG, ::formatKg)

        assertEquals(PhoenixCsvCodec.PR_PROGRESSION_HEADER, csv.lineSequence().first())
        assertTrue(csv.contains("'-"), "the first row has no previous load")
        assertTrue(csv.contains("'+2.5 kg"), "later row records the per-cable weight increase")
    }

    private fun parseSingleSession(csv: String): WorkoutSession {
        val (sessions, errors) = PhoenixCsvCodec.parseWorkoutHistory(csv)
        assertEquals(emptyList(), errors, "Expected no errors but got: $errors")
        return sessions.single()
    }

    private fun cell(csv: String, column: String): String {
        val lines = csv.lines().filter { it.isNotBlank() }
        val headers = CsvParser.parseCsvRow(lines.first())
        val fields = CsvParser.parseCsvRow(lines[1])
        val index = headers.indexOf(column)
        assertTrue(index >= 0, "Missing column $column in ${headers.joinToString()}")
        return fields[index]
    }

    private fun localMinute(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = LocalDateTime(year, month, day, hour, minute, 0)
        .toInstant(TimeZone.currentSystemDefault())
        .toEpochMilliseconds()

    private fun formatKg(kg: Float, unit: WeightUnit): String {
        assertEquals(WeightUnit.KG, unit)
        return "${kg.toString().removeSuffix(".0")} kg"
    }
}
