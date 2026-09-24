package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.WarmupSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Issue #772: the v1 routine CSV contract. */
class RoutineCsvCodecTest {
    private val header = RoutineCsvFormat.COLUMNS.joinToString(",")

    private fun exercise(id: String, name: String) = Exercise(id = id, name = name, muscleGroup = "Chest")

    private fun routineExercise(
        id: String,
        exercise: Exercise,
        order: Int,
        reps: List<Int?> = listOf(8, 8, 6),
        weights: List<Float> = listOf(40f, 40f, 42.5f),
        rest: Int = 120,
        mode: ProgramMode = ProgramMode.OldSchool,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
    ) = RoutineExercise(
        id = id,
        exercise = exercise,
        orderIndex = order,
        setReps = reps,
        weightPerCableKg = weights.first(),
        setWeightsPerCableKg = weights,
        programMode = mode,
        setRestSeconds = List(reps.size) { rest },
        isAMRAP = reps.all { it == null },
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )

    private val bench = exercise("bench-id", "Bench Press")
    private val row = exercise("row-id", "Row")
    private val pulldown = exercise("pulldown-id", "Lat Pulldown")
    private val pushUp = exercise("pushup-id", "Push Up")

    private fun sampleRoutine() = Routine(
        id = "routine-1",
        name = "Upper, heavy",
        description = "Says \"hi\"\nover two lines",
        exercises = listOf(
            routineExercise("e1", bench, 0),
            routineExercise("e2", row, 1, reps = listOf(10, 10), weights = listOf(30f, 30f), rest = 90, mode = ProgramMode.Pump, supersetId = "s1"),
            routineExercise("e3", pulldown, 2, reps = listOf(10, 10), weights = listOf(35f, 35f), rest = 90, supersetId = "s1", orderInSuperset = 1),
            routineExercise("e4", pushUp, 3, reps = listOf(null, null), weights = listOf(0f, 0f), rest = 60, mode = ProgramMode.TUTBeast),
        ),
        supersets = listOf(
            Superset(id = "s1", routineId = "routine-1", name = "Back pair", colorIndex = SupersetColors.AMBER, restBetweenSeconds = 15, orderIndex = 1),
        ),
    )

    private fun parsed(text: String): List<RoutineCsvRoutineDraft> =
        assertIs<RoutineCsvParseResult.Parsed>(RoutineCsvCodec.parse(text), "issues: ${(RoutineCsvCodec.parse(text) as? RoutineCsvParseResult.Invalid)?.issues}").routines

    private fun issues(text: String): List<RoutineCsvIssue> = assertIs<RoutineCsvParseResult.Invalid>(RoutineCsvCodec.parse(text)).issues

    private fun file(vararg rows: String) = (listOf(RoutineCsvFormat.VERSION_LINE, header) + rows).joinToString("\n")

    @Test
    fun exportParsesBackToTheSameRoutine() {
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(sampleRoutine(), "Strength", 2))
        assertEquals("phoenix-routine-upper-heavy.csv", exported.fileName)

        val draft = parsed(exported.content).single()
        assertEquals("routine-1", draft.routineId)
        assertEquals("Upper, heavy", draft.name)
        assertEquals("Says \"hi\"\nover two lines", draft.description)
        assertEquals("Strength", draft.groupName)
        assertEquals(2, draft.groupOrder)
        assertEquals(listOf("bench-id", "row-id", "pulldown-id", "pushup-id"), draft.exercises.map { it.exerciseId })
        assertEquals(listOf(8, 8, 6), draft.exercises[0].setReps)
        assertEquals(listOf(40f, 40f, 42.5f), draft.exercises[0].setWeightsKg)
        assertEquals(120, draft.exercises[0].restSeconds)
        assertEquals(ProgramMode.Pump, draft.exercises[1].mode)
        assertEquals(listOf("ss1", "ss1"), draft.exercises.subList(1, 3).map { it.supersetKey })
        assertEquals("Back pair", draft.exercises[1].supersetName)
        assertEquals(15, draft.exercises[1].supersetRestSeconds)
        assertEquals(SupersetColors.AMBER, draft.exercises[1].supersetColor)
        assertEquals(listOf(null, null), draft.exercises[3].setReps)
        assertEquals(ProgramMode.TUTBeast, draft.exercises[3].mode)

        // Canonical output: exporting what the file describes gives the same file.
        val again = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(sampleRoutine(), "Strength", 2))
        assertEquals(exported.content, again.content)
    }

    @Test
    fun textKeepsItsSpacesAndLineBreaksThroughARoundTrip() {
        val base = sampleRoutine()
        val routine = base.copy(
            name = " Upper\r\nheavy ",
            description = "Line one\n\nLine three\r\n# not a comment, still the description\n",
            supersets = base.supersets.map { it.copy(name = "  Back pair ") },
        )
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, " Strength ", 2))

        val draft = parsed(exported.content).single()
        assertEquals(routine.name, draft.name)
        assertEquals(routine.description, draft.description)
        assertEquals(" Strength ", draft.groupName)
        assertEquals("  Back pair ", draft.exercises[1].supersetName)
        // Every row repeats the name and description, so each record spans six lines and is
        // reported by the line it starts on.
        assertEquals(listOf(3, 9, 15, 21), draft.exercises.map { it.line })
    }

    @Test
    fun blankLinesAreSkippedOneAtATime() {
        // A file of mostly line breaks: read line by line, nothing kept per blank line.
        val text = RoutineCsvFormat.VERSION_LINE + "\n" + header + "\n".repeat(1_000_000) + ",R,,,,,Squat,0,,,,,5,60,,,\n"

        assertEquals(1_000_002, parsed(text).single().exercises.single().line)
    }

    @Test
    fun formulaLikeNamesAreGuardedOnExportAndRestoredOnImport() {
        val routine = sampleRoutine().copy(name = "=SUM(A1)")
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null))
        assertTrue(exported.content.contains(",'=SUM(A1),"))
        assertEquals("=SUM(A1)", parsed(exported.content).single().name)
    }

    @Test
    fun exportIsBlockedWhenASettingWouldBeLost() {
        val base = sampleRoutine()
        fun blocked(change: (RoutineExercise) -> RoutineExercise): List<String> {
            val routine = base.copy(exercises = listOf(change(base.exercises.first())) + base.exercises.drop(1))
            return assertIs<RoutineCsvExportResult.Blocked>(RoutineCsvCodec.encode(routine, null, null)).reasons
        }
        assertTrue(blocked { it.copy(programMode = ProgramMode.Echo) }.single().contains("Echo"))
        assertTrue(blocked { it.copy(usePercentOfPR = true) }.single().contains("% of PR"))
        assertTrue(blocked { it.copy(warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50))) }.single().contains("warm-up"))
        assertTrue(blocked { it.copy(defaultRackItemIds = listOf("rack-1")) }.single().contains("rack"))
        assertTrue(blocked { it.copy(dropSetEnabled = true, dropSetMinWeightKg = 10f) }.single().contains("drop sets"))
        assertTrue(blocked { it.copy(duration = 30) }.single().contains("timed"))
        assertTrue(blocked { it.copy(progressionKg = 2.5f) }.single().contains("progression"))
        assertTrue(blocked { it.copy(stallDetectionEnabled = false) }.single().contains("stall"))
        assertTrue(blocked { it.copy(stopAtTop = true) }.single().contains("top"))
        assertTrue(blocked { it.copy(repCountTiming = RepCountTiming.BOTTOM) }.single().contains("bottom"))
        assertTrue(blocked { it.copy(setRestSeconds = listOf(60, 90, 120), perSetRestTime = true) }.single().contains("rest"))
        // Missing entries rest 60 s at runtime (getRestForSet), so [90] over three sets is 90, 60, 60.
        assertTrue(blocked { it.copy(isAMRAP = true) }.single().contains("AMRAP"))
        assertTrue(blocked { it.copy(setRestSeconds = listOf(90)) }.single().contains("rest"))

        val empty = assertIs<RoutineCsvExportResult.Blocked>(RoutineCsvCodec.encode(base.copy(exercises = emptyList()), null, null))
        assertEquals(1, empty.reasons.size)
    }

    @Test
    fun byteOrderMarkCrlfCommentsBlankLinesAndTrailingEmptyCellsAreAccepted() {
        val text = "﻿" + RoutineCsvFormat.VERSION_LINE + "\r\n# written by hand\r\n\r\n" + header + ",,\r\n" +
            ",Legs,,,,,Squat,0,,,,,5|5|5,60|60|60,180,Old School,false,,\r\n\r\n"
        val draft = parsed(text).single()
        assertEquals("Legs", draft.name)
        assertEquals(ProgramMode.OldSchool, draft.exercises.single().mode)
        assertEquals(listOf(5, 5, 5), draft.exercises.single().setReps)
    }

    @Test
    fun rowsGroupIntoRoutinesByIdThenName() {
        val routines = parsed(
            file(
                ",Push,,,,,Bench Press,0,,,,,8,40,,,",
                "r-2,Pull,,,,,Row,0,,,,,8,30,,,",
                ",Push,,,,,Dip,1,,,,,8,0,,,",
                "r-2,Pull,,,,,Curl,1,,,,,8,10,,,",
            ),
        )
        assertEquals(listOf("Push", "Pull"), routines.map { it.name })
        assertEquals(listOf(2, 2), routines.map { it.exercises.size })
    }

    @Test
    fun blanksTakeDefaults() {
        val exercise = parsed(file(",Push,,,,,Bench Press,0,,,,,8|8,40|40,,,")).single().exercises.single()
        assertEquals(RoutineCsvFormat.DEFAULT_REST_SECONDS, exercise.restSeconds)
        assertEquals(ProgramMode.OldSchool, exercise.mode)
    }

    @Test
    fun fileLevelProblemsAreReported() {
        assertTrue(issues("").single().message.contains("empty"))
        assertTrue(issues("routine_id\n").single().message.contains(RoutineCsvFormat.VERSION_LINE))
        assertTrue(issues("# phoenix_routine_csv_version=2\n$header").single().message.contains("version"))
        assertTrue(issues(RoutineCsvFormat.VERSION_LINE + "\nroutine_id;routine_name").single().message.contains("semicolons"))
        assertTrue(issues(RoutineCsvFormat.VERSION_LINE + "\nroutine_id,routine_name").single().message.contains("header"))
        assertTrue(issues(file()).single().message.contains("no routine rows"))
    }

    @Test
    fun everyRowProblemIsReportedWithItsLine() {
        val found = issues(
            file(
                ",Push,,,,,Bench Press,0,,,,,8|8,40,,,", // line 3: list lengths differ
                ",Push,,,,,Dip,x,,,,,8,0,,,", // line 4: bad order
                ",Pull,,,,,Row,0,,,,,0,30,,,", // line 5: reps must be positive
                ",Pull,,,,,Curl,1,,,,,8,300,,,", // line 6: weight over the per-cable limit
                ",Legs,,,,,Squat,0,,,,,8,60,,Echo,", // line 7: unsupported mode
                ",Legs,,,,,Lunge,1,,,,,8,20,,Zumba,", // line 8: unknown mode
                ",Arms,,,,,Curl,0,,,,,AMRAP,10,,,false", // line 9: is_amrap contradicts
                ",Core,,,,,Crunch,0,,,,,\"8|10,", // line 10: unclosed quote
            ),
        )
        val byLine = found.sortedBy { it.line }
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9, 10), byLine.map { it.line })
        assertTrue(byLine[4].message.contains("Echo"))
        assertTrue(byLine[5].message.contains("Zumba"))
    }

    @Test
    fun routineAndSupersetRowsMustAgree() {
        val found = issues(
            file(
                ",Push,first,,,,Bench Press,0,,,,,8,40,,,",
                ",Push,second,,,,Dip,1,,,,,8,0,,,",
                ",Pull,,,,,Row,0,a,Pair,,15,8,30,,,",
                ",Pull,,,,,Curl,1,a,Other,,15,8,10,,,",
                ",Legs,,,,,Squat,0,solo,,,,8,60,,,",
                ",Arms,,,,,Curl,0,,,,,8,10,,,",
                ",Arms,,,,,Dip,0,,,,,8,0,,,",
            ),
        )
        assertTrue(found.any { it.line == 4 && it.message.contains("routine_description") })
        assertTrue(found.any { it.line == 6 && it.message.contains("Superset 'a'") })
        assertTrue(found.any { it.line == 7 && it.message.contains("at least two") })
        assertTrue(found.any { it.line == 9 && it.message.contains("exercise_order 0") })
    }

    @Test
    fun limitsAreEnforced() {
        val tooManyRoutines = file(*Array(RoutineCsvFormat.MAX_ROUTINES + 1) { ",R$it,,,,,Squat,0,,,,,5,60,,," })
        assertTrue(issues(tooManyRoutines).any { it.message.contains("${RoutineCsvFormat.MAX_ROUTINES} routines") })

        val tooManyRows = file(*Array(RoutineCsvFormat.MAX_ROWS + 1) { ",Big,,,,,Squat,$it,,,,,5,60,,," })
        assertTrue(issues(tooManyRows).single().message.contains("${RoutineCsvFormat.MAX_ROWS} rows"))

        // Rows rejected before validation count toward the cap as well.
        val tooManyBadRows = file(*Array(RoutineCsvFormat.MAX_ROWS + 1) { ",Big,,,,,\"Squat,$it" })
        assertTrue(issues(tooManyBadRows).single().message.contains("${RoutineCsvFormat.MAX_ROWS} rows"))

        val tooManySets = List(RoutineCsvFormat.MAX_SETS_PER_EXERCISE + 1) { "5" }.joinToString("|")
        assertTrue(issues(file(",R,,,,,Squat,0,,,,,5,$tooManySets,,,")).single().message.contains("set_weights_kg has more than"))
        assertTrue(issues(file(",R,,,,,Squat,0,,,,,$tooManySets,60,,,")).any { it.message.contains("set_reps has more than") })

        val huge = RoutineCsvFormat.VERSION_LINE + "\n" + "x".repeat(RoutineCsvFormat.MAX_BYTES)
        assertTrue(issues(huge).single().message.contains("2 MB"))
    }
}
