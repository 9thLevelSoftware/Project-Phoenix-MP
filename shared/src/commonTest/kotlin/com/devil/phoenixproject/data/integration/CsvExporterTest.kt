package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvExporterTest {

    private val HEADER = "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes"

    // -------------------------------------------------------------------------
    // generateStrongCsv — top-level contract
    // -------------------------------------------------------------------------

    @Test
    fun emptyList_returnsHeaderOnly() {
        val result = CsvExporter.generateStrongCsv(emptyList(), WeightUnit.KG)
        assertEquals(HEADER, result)
    }

    @Test
    fun singleSession_correctColumnsAndWeightMultiplier() {
        // weightPerCableKg = 10 → total = 20 kg
        val session = WorkoutSession(
            id = "session-1",
            timestamp = 1_700_000_000_000L, // some fixed epoch ms
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = 10f,
            duration = 45L,
            totalReps = 10,
            warmupReps = 0,
            workingReps = 10,
            exerciseName = "Bench Press"
        )

        val csv = CsvExporter.generateStrongCsv(listOf(session), WeightUnit.KG)
        val lines = csv.lines()

        assertEquals(HEADER, lines[0], "First line must be header")
        assertEquals(2, lines.size, "Should have header + 1 data row")

        val fields = lines[1].split(",")
        // Column 5 (index 5) = Weight
        assertEquals("20", fields[5], "Weight should be perCable * 2 = 20")
        // Column 6 = Reps
        assertEquals("10", fields[6])
        // Column 3 = Exercise Name
        assertEquals("Bench Press", fields[3])
        // Column 4 = Set Order
        assertEquals("1", fields[4])
    }

    @Test
    fun weightConversion_toLbs() {
        // 10 kg per cable → 20 kg total → 20 * 2.20462 = 44.0924 lbs → "44.09"
        val session = WorkoutSession(
            id = "s1",
            timestamp = 1_700_000_000_000L,
            weightPerCableKg = 10f,
            exerciseName = "Squat"
        )

        val csv = CsvExporter.generateStrongCsv(listOf(session), WeightUnit.LB)
        val fields = csv.lines()[1].split(",")
        // 10 * 2 * 2.20462 = 44.0924, formatted to 2dp trimmed = "44.09"
        assertEquals("44.09", fields[5])
    }

    // -------------------------------------------------------------------------
    // formatDuration
    // -------------------------------------------------------------------------

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("1h 23m", CsvExporter.formatDuration(83 * 60L))
    }

    @Test
    fun formatDuration_minutesOnly() {
        assertEquals("45m", CsvExporter.formatDuration(45 * 60L))
    }

    @Test
    fun formatDuration_zero() {
        assertEquals("0m", CsvExporter.formatDuration(0L))
    }

    @Test
    fun formatDuration_negative_treatsAsZero() {
        assertEquals("0m", CsvExporter.formatDuration(-10L))
    }

    @Test
    fun formatDuration_exactOneHour() {
        assertEquals("1h 0m", CsvExporter.formatDuration(3600L))
    }

    // -------------------------------------------------------------------------
    // formatWeight
    // -------------------------------------------------------------------------

    @Test
    fun formatWeight_kg_multipliesBy2() {
        // 5 kg per cable → 10 kg total
        assertEquals("10", CsvExporter.formatWeight(5f, WeightUnit.KG))
    }

    @Test
    fun formatWeight_lb_convertsCorrectly() {
        // 10 kg per cable → 20 kg → 44.09 lbs
        val result = CsvExporter.formatWeight(10f, WeightUnit.LB)
        assertEquals("44.09", result)
    }

    @Test
    fun formatWeight_trimsTrailingZeros() {
        // 15 kg per cable → 30 kg → "30" (not "30.00")
        assertEquals("30", CsvExporter.formatWeight(15f, WeightUnit.KG))
    }

    @Test
    fun formatWeight_handlesDecimalResult() {
        // 2.5 kg per cable → 5 kg → "5"
        assertEquals("5", CsvExporter.formatWeight(2.5f, WeightUnit.KG))
    }

    // -------------------------------------------------------------------------
    // Routine grouping
    // -------------------------------------------------------------------------

    @Test
    fun routineGrouping_sameRoutineSessionId_groupedTogether() {
        val routineId = "routine-abc"
        val sessions = listOf(
            WorkoutSession(
                id = "s1",
                timestamp = 1_700_000_000_000L,
                weightPerCableKg = 10f,
                exerciseName = "Bench Press",
                routineSessionId = routineId,
                routineName = "Push Day"
            ),
            WorkoutSession(
                id = "s2",
                timestamp = 1_700_000_060_000L,
                weightPerCableKg = 20f,
                exerciseName = "Overhead Press",
                routineSessionId = routineId,
                routineName = "Push Day"
            )
        )

        val csv = CsvExporter.generateStrongCsv(sessions, WeightUnit.KG)
        val lines = csv.lines()

        assertEquals(3, lines.size, "Header + 2 data rows")

        // Both rows should share the same Workout Name = "Push Day"
        val row1Fields = lines[1].split(",")
        val row2Fields = lines[2].split(",")

        assertEquals("Push Day", row1Fields[1], "Workout name should be routine name")
        assertEquals("Push Day", row2Fields[1], "Workout name should be routine name")

        // Different exercises → each gets set order 1
        assertEquals("1", row1Fields[4], "First exercise set order = 1")
        assertEquals("1", row2Fields[4], "Second (different) exercise set order = 1")
    }

    @Test
    fun routineGrouping_differentRoutineIds_separateGroups() {
        val sessions = listOf(
            WorkoutSession(
                id = "s1",
                timestamp = 1_700_000_000_000L,
                weightPerCableKg = 10f,
                exerciseName = "Bench Press",
                routineSessionId = "routine-1",
                routineName = "Push Day"
            ),
            WorkoutSession(
                id = "s2",
                timestamp = 1_700_100_000_000L,
                weightPerCableKg = 20f,
                exerciseName = "Squat",
                routineSessionId = "routine-2",
                routineName = "Leg Day"
            )
        )

        val csv = CsvExporter.generateStrongCsv(sessions, WeightUnit.KG)
        val lines = csv.lines()
        assertEquals(3, lines.size)

        val row1Fields = lines[1].split(",")
        val row2Fields = lines[2].split(",")
        assertEquals("Push Day", row1Fields[1])
        assertEquals("Leg Day", row2Fields[1])
    }

    @Test
    fun routineGrouping_sameExerciseInRoutine_incrementsSetOrder() {
        val routineId = "routine-xyz"
        val sessions = listOf(
            WorkoutSession(
                id = "s1",
                timestamp = 1_700_000_000_000L,
                weightPerCableKg = 10f,
                exerciseName = "Bicep Curl",
                routineSessionId = routineId,
                routineName = "Arms"
            ),
            WorkoutSession(
                id = "s2",
                timestamp = 1_700_000_060_000L,
                weightPerCableKg = 12f,
                exerciseName = "Bicep Curl",
                routineSessionId = routineId,
                routineName = "Arms"
            ),
            WorkoutSession(
                id = "s3",
                timestamp = 1_700_000_120_000L,
                weightPerCableKg = 14f,
                exerciseName = "Bicep Curl",
                routineSessionId = routineId,
                routineName = "Arms"
            )
        )

        val csv = CsvExporter.generateStrongCsv(sessions, WeightUnit.KG)
        val lines = csv.lines()
        assertEquals(4, lines.size)

        val setOrders = lines.drop(1).map { it.split(",")[4] }
        assertEquals(listOf("1", "2", "3"), setOrders, "Same exercise sets numbered 1, 2, 3")
    }

    @Test
    fun standaloneSession_usesExerciseNameAsWorkoutName() {
        val session = WorkoutSession(
            id = "s1",
            timestamp = 1_700_000_000_000L,
            weightPerCableKg = 10f,
            exerciseName = "Deadlift"
        )

        val csv = CsvExporter.generateStrongCsv(listOf(session), WeightUnit.KG)
        val fields = csv.lines()[1].split(",")
        assertEquals("Deadlift", fields[1], "Standalone uses exercise name as workout name")
    }

    // -------------------------------------------------------------------------
    // escapeCsvField
    // -------------------------------------------------------------------------

    @Test
    fun escapeCsvField_plainString_noQuotes() {
        assertEquals("Bench Press", CsvExporter.escapeCsvField("Bench Press"))
    }

    @Test
    fun escapeCsvField_containsComma_wrapsInQuotes() {
        assertEquals("\"Bench Press, Flat\"", CsvExporter.escapeCsvField("Bench Press, Flat"))
    }

    @Test
    fun escapeCsvField_containsQuote_doublesAndWraps() {
        assertEquals("\"He said \"\"hi\"\"\"", CsvExporter.escapeCsvField("He said \"hi\""))
    }

    @Test
    fun escapeCsvField_emptyString_noQuotes() {
        assertEquals("", CsvExporter.escapeCsvField(""))
    }

    // -------------------------------------------------------------------------
    // groupSessions
    // -------------------------------------------------------------------------

    @Test
    fun groupSessions_mixedRoutineAndStandalone() {
        val sessions = listOf(
            WorkoutSession(id = "s1", timestamp = 1000L, routineSessionId = "r1"),
            WorkoutSession(id = "s2", timestamp = 2000L, routineSessionId = "r1"),
            WorkoutSession(id = "s3", timestamp = 3000L, routineSessionId = null),
            WorkoutSession(id = "s4", timestamp = 4000L, routineSessionId = "r2")
        )

        val groups = CsvExporter.groupSessions(sessions)

        // r1 group (2 sessions), s3 standalone, r2 group (1 session)
        assertEquals(3, groups.size, "Should have 3 groups: r1, s3, r2")
        assertEquals(2, groups["r1"]?.size, "r1 group should have 2 sessions")
        assertEquals(1, groups["s3"]?.size, "s3 standalone should have 1 session")
        assertEquals(1, groups["r2"]?.size, "r2 group should have 1 session")
    }

    // -------------------------------------------------------------------------
    // resolveWorkoutName
    // -------------------------------------------------------------------------

    @Test
    fun resolveWorkoutName_routineWithName_usesRoutineName() {
        val session = WorkoutSession(
            id = "s1",
            routineSessionId = "r1",
            routineName = "Push Day"
        )
        assertEquals("Push Day", CsvExporter.resolveWorkoutName(session))
    }

    @Test
    fun resolveWorkoutName_routineWithoutName_fallsBackToRoutine() {
        val session = WorkoutSession(
            id = "s1",
            routineSessionId = "r1",
            routineName = null
        )
        assertEquals("Routine", CsvExporter.resolveWorkoutName(session))
    }

    @Test
    fun resolveWorkoutName_standalone_usesExerciseName() {
        val session = WorkoutSession(
            id = "s1",
            routineSessionId = null,
            exerciseName = "Squat"
        )
        assertEquals("Squat", CsvExporter.resolveWorkoutName(session))
    }

    @Test
    fun resolveWorkoutName_standalone_noExerciseName_fallsBackToWorkout() {
        val session = WorkoutSession(
            id = "s1",
            routineSessionId = null,
            exerciseName = null
        )
        assertEquals("Workout", CsvExporter.resolveWorkoutName(session))
    }
}
