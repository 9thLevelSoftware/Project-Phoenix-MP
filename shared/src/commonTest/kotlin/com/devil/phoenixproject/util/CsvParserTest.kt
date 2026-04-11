package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvParserTest {

    @Test
    fun parseCsvRow_simpleFields() {
        val result = CsvParser.parseCsvRow("a,b,c")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun parseCsvRow_quotedFieldWithComma() {
        val result = CsvParser.parseCsvRow("\"Bench Press, Flat\",OldSchool,10")
        assertEquals(listOf("Bench Press, Flat", "OldSchool", "10"), result)
    }

    @Test
    fun parseCsvRow_escapedQuotes() {
        val result = CsvParser.parseCsvRow("\"He said \"\"hello\"\"\",b")
        assertEquals(listOf("He said \"hello\"", "b"), result)
    }

    @Test
    fun parseCsvRow_emptyFields() {
        val result = CsvParser.parseCsvRow("a,,c,")
        assertEquals(listOf("a", "", "c", ""), result)
    }

    @Test
    fun parseWeight_plainNumber() {
        assertEquals(80.0f, CsvParser.parseWeight("80.0"))
    }

    @Test
    fun parseWeight_withKgSuffix() {
        assertEquals(80.0f, CsvParser.parseWeight("80.0 kg"))
    }

    @Test
    fun parseWeight_withLbSuffix() {
        assertEquals(176.4f, CsvParser.parseWeight("176.4 lb"))
    }

    @Test
    fun parseWeight_positiveProgression() {
        assertEquals(2.5f, CsvParser.parseWeight("+2.5 kg"))
    }

    @Test
    fun parseWeight_negativeProgression() {
        assertEquals(-1.0f, CsvParser.parseWeight("-1.0"))
    }

    @Test
    fun parseWeight_zero() {
        assertEquals(0f, CsvParser.parseWeight("0"))
    }

    @Test
    fun parseWeight_null() {
        assertEquals(0f, CsvParser.parseWeight(null))
    }

    @Test
    fun parseWorkoutHistory_validCsv() {
        val csv = """
            Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,Bench Press,OldSchool,10,0,10,10,80.0 kg,+2.5 kg,45,No,100
            2026-03-11,Squat,Echo,8,3,5,8,100.0 kg,0,60,No,125
        """.trimIndent()

        val (sessions, errors) = CsvParser.parseWorkoutHistory(csv)

        assertEquals(0, errors.size, "Expected no errors but got: $errors")
        assertEquals(2, sessions.size)

        val bench = sessions[0]
        assertEquals("Bench Press", bench.exerciseName)
        assertEquals("OldSchool", bench.mode)
        assertEquals(10, bench.reps)
        assertEquals(0, bench.warmupReps)
        assertEquals(10, bench.workingReps)
        assertEquals(10, bench.totalReps)
        assertEquals(80.0f, bench.weightPerCableKg)
        assertEquals(2.5f, bench.progressionKg)
        assertEquals(45L, bench.duration)
        assertEquals(false, bench.isJustLift)
        assertEquals(100, bench.eccentricLoad)

        val squat = sessions[1]
        assertEquals("Squat", squat.exerciseName)
        assertEquals("Echo", squat.mode)
        assertEquals(8, squat.reps)
        assertEquals(3, squat.warmupReps)
        assertEquals(5, squat.workingReps)
        assertEquals(125, squat.eccentricLoad)
    }

    @Test
    fun parseWorkoutHistory_quotedExerciseName() {
        val csv = """
            Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,"Bench Press, Flat",OldSchool,10,0,10,10,80.0,0,45,No,100
        """.trimIndent()

        val (sessions, errors) = CsvParser.parseWorkoutHistory(csv)

        assertEquals(0, errors.size)
        assertEquals(1, sessions.size)
        assertEquals("Bench Press, Flat", sessions[0].exerciseName)
    }

    @Test
    fun parseWorkoutHistory_justLiftYes() {
        val csv = """
            Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,Curl,OldSchool,0,0,0,5,30.0,0,30,Yes,100
        """.trimIndent()

        val (sessions, _) = CsvParser.parseWorkoutHistory(csv)
        assertEquals(true, sessions[0].isJustLift)
    }

    @Test
    fun parseWorkoutHistory_emptyCsv() {
        val (sessions, errors) = CsvParser.parseWorkoutHistory("")
        assertEquals(0, sessions.size)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("empty"))
    }

    @Test
    fun parseWorkoutHistory_unrecognizedHeaders() {
        val csv = """
            Foo,Bar,Baz
            1,2,3
        """.trimIndent()

        val (sessions, errors) = CsvParser.parseWorkoutHistory(csv)
        assertEquals(0, sessions.size)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Unrecognized"))
    }

    @Test
    fun parseWorkoutHistory_invalidDateRow() {
        val csv = """
            Date,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            not-a-date,Bench,OldSchool,10,0,10,10,80.0,0,45,No,100
        """.trimIndent()

        val (sessions, errors) = CsvParser.parseWorkoutHistory(csv)
        assertEquals(0, sessions.size)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Row 2"))
    }

    @Test
    fun parseWorkoutHistory_missingOptionalColumns() {
        // CSV with only required + a few optional columns
        val csv = """
            Date,Exercise,Mode,Weight,Total Reps
            2026-03-10,Bench Press,OldSchool,80.0,10
        """.trimIndent()

        val (sessions, errors) = CsvParser.parseWorkoutHistory(csv)
        assertEquals(0, errors.size)
        assertEquals(1, sessions.size)
        assertEquals("Bench Press", sessions[0].exerciseName)
        assertEquals(80.0f, sessions[0].weightPerCableKg)
        assertEquals(10, sessions[0].totalReps)
        // Defaults for missing fields
        assertEquals(0, sessions[0].reps) // target_reps not present
        assertEquals(0, sessions[0].warmupReps)
        assertEquals(0L, sessions[0].duration)
    }
}
