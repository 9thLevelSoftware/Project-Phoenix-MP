package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.WarmupSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #772: the v1 routine CSV contract. Issue #896: version 2, which carries every advanced
 * setting so export is only refused when no CSV row can hold the routine at all.
 */
class RoutineCsvCodecTest {
    private val header = RoutineCsvFormat.COLUMNS.joinToString(",")
    private val headerV2 = RoutineCsvFormat.COLUMNS_V2.joinToString(",")

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
    private val squat = exercise("squat-id", "Barbell Squat")
    private val calfRaise = exercise("calf-id", "Standing Calf Raises")
    private val deadlift = exercise("deadlift-id", "Stiff Leg Deadlift")

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
    private fun fileV2(vararg rows: String) = (listOf(RoutineCsvFormat.VERSION_V2_LINE, headerV2) + rows).joinToString("\n")

    /** A hand-written version 2 row: the fixed v1 cells, then [values] by column name. */
    private fun rowV2(vararg values: Pair<String, String>, name: String = "Barbell Squat"): String {
        val byName = values.toMap()
        val cells = listOf("", name, "", "", "", "", name, "0", "", "", "", "", "8|8|6", "40|40|42.5", "120", "", "false", "") +
            RoutineCsvFormat.COLUMNS_V2_ONLY.map { byName[it].orEmpty() }
        return cells.joinToString(",")
    }

    @Test
    fun exportParsesBackToTheSameRoutine() {
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(sampleRoutine(), "Strength", 2))
        assertEquals("phoenix-routine-upper-heavy.csv", exported.fileName)
        assertTrue(exported.content.startsWith(RoutineCsvFormat.VERSION_V2_LINE), "exports are version 2")

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
    fun theFirstV1HeaderWithoutSupersetColourIsStillRead() {
        val header17 = RoutineCsvFormat.COLUMNS.dropLast(1).joinToString(",")
        val text = listOf(
            RoutineCsvFormat.VERSION_LINE,
            header17,
            ",Pull,,,,,Row,0,a,Pair,,15,10,30,90,,false",
            ",Pull,,,,,Curl,1,a,Pair,,15,10,20,90,,false",
        ).joinToString("\n")
        val exercises = parsed(text).single().exercises
        assertEquals(listOf("a", "a"), exercises.map { it.supersetKey })
        assertEquals(listOf(null, null), exercises.map { it.supersetColor })
    }

    @Test
    fun version1KeepsReadingExactlyAsReleased() {
        // v1 never carried advanced settings; a v1 row leaves them at their defaults and Echo
        // and Eccentric Only are still refused.
        val exercise = parsed(file(",Push,,,,,Bench Press,0,,,,,8,40,,Old School,false,")).single().exercises.single()
        assertEquals(ProgramMode.OldSchool, exercise.mode)
        assertEquals(false, exercise.usePercentOfPR)
        assertEquals(emptyList(), exercise.warmupSets)
        assertEquals(emptyList(), exercise.defaultRackItemIds)
        assertEquals(emptyMap(), exercise.rackBehaviorOverrides)
        assertEquals(false, exercise.dropSetEnabled)
        assertNull(exercise.dropSetMinWeightKg)
        assertNull(exercise.durationSeconds)
        assertEquals(0f, exercise.progressionKg)
        assertEquals(true, exercise.stallDetectionEnabled)
        assertEquals(false, exercise.stopAtTop)
        assertEquals(RepCountTiming.TOP, exercise.repCountTiming)
        assertEquals(emptyList(), exercise.restSecondsPerSet)
        assertEquals(EchoLevel.HARDER, exercise.echoLevel)
        assertEquals(EccentricLoad.LOAD_100, exercise.eccentricLoad)
        assertNull(exercise.isAmrapFlag)

        val found = issues(file(",Push,,,,,Bench Press,0,,,,,8,40,,Echo,false,"))
        assertTrue(found.single().message.contains("Echo"))
    }

    @Test
    fun numberCellsOfOneRoutineCompareTrimmed() {
        val draft = parsed(
            file(
                ",Push,,Strength,2,,Bench Press,0,,,,,8,40,,,",
                ",Push,,Strength, 2 ,,Dip,1,,,,,8,0,,,",
            ),
        ).single()
        assertEquals(2, draft.groupOrder)
        assertEquals(2, draft.exercises.size)
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
    fun negativeProgressionStaysANumberAndIsNotFormulaGuarded() {
        val routine = sampleRoutine().copy(
            exercises = listOf(sampleRoutine().exercises.first().copy(progressionKg = -1.25f)),
        )
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null))
        assertTrue(exported.content.contains(",-1.25,"), exported.content)
        assertTrue(!exported.content.contains(",'-1.25"), "a numeric cell must not take the text formula guard")
        assertEquals(-1.25f, parsed(exported.content).single().exercises.single().progressionKg)
    }

    @Test
    fun structuredTextCellsAreGuardedAndUnguarded() {
        val routine = sampleRoutine().copy(exercises = listOf(
            sampleRoutine().exercises.first().copy(
                defaultRackItemIds = listOf("-rack,a"),
                rackBehaviorOverrides = mapOf("-rack,a" to RackItemBehavior.DISPLAY_ONLY),
                warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50)),
            ),
        ))
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null))
        assertTrue(exported.content.contains("\"'-rack,a\""), exported.content)
        val draft = parsed(exported.content).single().exercises.single()
        assertEquals(listOf("-rack,a"), draft.defaultRackItemIds)
        assertEquals(mapOf("-rack,a" to RackItemBehavior.DISPLAY_ONLY), draft.rackBehaviorOverrides)
    }

    @Test
    fun advancedSettingsRoundTripThroughVersion2() {
        val base = sampleRoutine().exercises.first()
        val fridayLower = Routine(
            id = "routine-896",
            name = "Friday Lower (Old School)",
            exercises = listOf(
                base.copy(
                    id = "squat",
                    exercise = squat,
                    usePercentOfPR = true,
                    weightPercentOfPR = 80,
                    prTypeForScaling = PRType.MAX_VOLUME,
                    setWeightsPercentOfPR = listOf(75, 80, 85),
                    scalingBasis = ScalingBasis.ESTIMATED_1RM,
                    warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50), WarmupSet(reps = 3, percentOfWorking = 70)),
                    defaultRackItemIds = listOf("rack-a", "rack-b"),
                    rackBehaviorOverrides = mapOf("rack-a" to RackItemBehavior.COUNTERWEIGHT),
                    stopAtTop = true,
                ),
                base.copy(
                    id = "calf",
                    exercise = calfRaise,
                    programMode = ProgramMode.Echo,
                    echoLevel = EchoLevel.EPIC,
                    eccentricLoad = EccentricLoad.LOAD_120,
                    setEchoLevels = listOf(EchoLevel.HARD, null, EchoLevel.HARDEST),
                    progressionKg = -1.25f,
                    isAMRAP = true,
                ),
                base.copy(
                    id = "deadlift",
                    exercise = deadlift,
                    usePercentOfPR = true,
                    warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50)),
                    dropSetEnabled = true,
                    dropSetMinWeightKg = 20f,
                    duration = 45,
                    stallDetectionEnabled = false,
                    repCountTiming = RepCountTiming.BOTTOM,
                    perSetRestTime = true,
                    setRestSeconds = listOf(60, 90, 120),
                    programMode = ProgramMode.EccentricOnly,
                ),
            ),
        )

        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(fridayLower, null, null))
        val exercises = parsed(exported.content).single().exercises

        val squatRow = exercises[0]
        assertEquals(true, squatRow.usePercentOfPR)
        assertEquals(80, squatRow.weightPercentOfPR)
        assertEquals(PRType.MAX_VOLUME, squatRow.prTypeForScaling)
        assertEquals(listOf(75, 80, 85), squatRow.setWeightsPercentOfPR)
        assertEquals(ScalingBasis.ESTIMATED_1RM, squatRow.scalingBasis)
        assertEquals(listOf(WarmupSet(5, 50), WarmupSet(3, 70)), squatRow.warmupSets)
        assertEquals(listOf("rack-a", "rack-b"), squatRow.defaultRackItemIds)
        assertEquals(mapOf("rack-a" to RackItemBehavior.COUNTERWEIGHT), squatRow.rackBehaviorOverrides)
        assertEquals(true, squatRow.stopAtTop)

        val calfRow = exercises[1]
        assertEquals(ProgramMode.Echo, calfRow.mode)
        assertEquals(EchoLevel.EPIC, calfRow.echoLevel)
        assertEquals(EccentricLoad.LOAD_120, calfRow.eccentricLoad)
        assertEquals(listOf(EchoLevel.HARD, null, EchoLevel.HARDEST), calfRow.setEchoLevels)
        assertEquals(-1.25f, calfRow.progressionKg)
        assertEquals(true, calfRow.isAmrapFlag, "the legacy AMRAP flag keeps its own column")

        val deadliftRow = exercises[2]
        assertEquals(true, deadliftRow.usePercentOfPR)
        assertEquals(listOf(WarmupSet(5, 50)), deadliftRow.warmupSets)
        assertEquals(true, deadliftRow.dropSetEnabled)
        assertEquals(20f, deadliftRow.dropSetMinWeightKg)
        assertEquals(45, deadliftRow.durationSeconds)
        assertEquals(false, deadliftRow.stallDetectionEnabled)
        assertEquals(RepCountTiming.BOTTOM, deadliftRow.repCountTiming)
        assertEquals(true, deadliftRow.perSetRestTime)
        assertEquals(listOf(60, 90, 120), deadliftRow.restSecondsPerSet)
        assertEquals(ProgramMode.EccentricOnly, deadliftRow.mode)
    }

    @Test
    fun theLegacyAmrapFlagNeverOverloadsIsAmrap() {
        // Older routines mark only the last set AMRAP while set_reps keeps numeric reps.
        val routine = sampleRoutine().copy(
            exercises = listOf(sampleRoutine().exercises.first().copy(isAMRAP = true)),
        )
        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null))
        val exercise = parsed(exported.content).single().exercises.single()
        assertEquals(listOf(8, 8, 6), exercise.setReps)
        assertEquals(true, exercise.isAmrapFlag)
    }

    @Test
    fun everySettingV1RefusedNowExports() {
        val base = sampleRoutine()
        fun exports(change: (RoutineExercise) -> RoutineExercise) {
            val routine = base.copy(exercises = listOf(change(base.exercises.first())) + base.exercises.drop(1))
            assertEquals(emptyList(), RoutineCsvCodec.exportBlockers(routine))
            assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null))
        }
        exports { it.copy(programMode = ProgramMode.Echo) }
        exports { it.copy(usePercentOfPR = true) }
        exports { it.copy(warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50))) }
        exports { it.copy(defaultRackItemIds = listOf("rack-1")) }
        exports { it.copy(dropSetEnabled = true, dropSetMinWeightKg = 10f) }
        exports { it.copy(duration = 30) }
        exports { it.copy(progressionKg = 2.5f) }
        exports { it.copy(stallDetectionEnabled = false) }
        exports { it.copy(stopAtTop = true) }
        exports { it.copy(repCountTiming = RepCountTiming.BOTTOM) }
        exports { it.copy(setRestSeconds = listOf(60, 90, 120), perSetRestTime = true) }
        exports { it.copy(isAMRAP = true) }
        exports { it.copy(setRestSeconds = listOf(90)) }
    }

    @Test
    fun exportIsOnlyBlockedWhenARowCannotHoldTheRoutine() {
        val base = sampleRoutine()

        val empty = assertIs<RoutineCsvExportResult.Blocked>(RoutineCsvCodec.encode(base.copy(exercises = emptyList()), null, null))
        assertEquals(listOf("The routine has no exercises."), empty.reasons)

        val noSets = base.copy(exercises = listOf(base.exercises.first().copy(setReps = emptyList(), setWeightsPerCableKg = emptyList())))
        val blocked = assertIs<RoutineCsvExportResult.Blocked>(RoutineCsvCodec.encode(noSets, null, null))
        assertTrue(blocked.reasons.single().contains("no sets"))
    }

    @Test
    fun byteOrderMarkCrlfCommentsBlankLinesAndTrailingEmptyCellsAreAccepted() {
        val text = "\ufeff" + RoutineCsvFormat.VERSION_LINE + "\r\n# written by hand\r\n\r\n" + header + ",,\r\n" +
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
        assertTrue(issues("# phoenix_routine_csv_version=3\n$headerV2").single().message.contains("version"))
        assertTrue(issues("# phoenix_routine_csv_version=2\n$header").single().message.contains("header"))
        assertTrue(issues(RoutineCsvFormat.VERSION_LINE + "\nroutine_id;routine_name").single().message.contains("semicolons"))
        assertTrue(issues(RoutineCsvFormat.VERSION_LINE + "\nroutine_id,routine_name").single().message.contains("header"))
        assertTrue(issues(file()).single().message.contains("no routine rows"))
    }

    @Test
    fun version2CellsAreValidated() {
        fun oneCellProblem(value: Pair<String, String>): String =
            issues(fileV2(rowV2(value))).single().message

        assertTrue(oneCellProblem("use_percent_of_pr" to "maybe").contains("use_percent_of_pr"))
        assertTrue(oneCellProblem("weight_percent_of_pr" to "500").contains("weight_percent_of_pr"))
        assertTrue(oneCellProblem("pr_type_for_scaling" to "BIGGEST").contains("pr_type_for_scaling"))
        assertTrue(oneCellProblem("set_weights_percent_of_pr" to "80|900").contains("set_weights_percent_of_pr"))
        assertTrue(oneCellProblem("scaling_basis" to "VIBE").contains("scaling_basis"))
        assertTrue(oneCellProblem("warmup_sets" to "5-50").contains("warmup_sets"))
        assertTrue(oneCellProblem("rack_behavior_overrides" to "rack-a=LOUD").contains("rack_behavior_overrides"))
        assertTrue(oneCellProblem("drop_set_min_weight_kg" to "999").contains("drop_set_min_weight_kg"))
        assertTrue(oneCellProblem("duration_seconds" to "500").contains("duration_seconds"))
        assertTrue(oneCellProblem("progression_kg" to "-9").contains("progression_kg"))
        assertTrue(oneCellProblem("rep_count_timing" to "MIDDLE").contains("rep_count_timing"))
        assertTrue(oneCellProblem("rest_seconds_per_set" to "60|999").contains("rest_seconds_per_set"))
        assertTrue(oneCellProblem("echo_level" to "MEGA").contains("echo_level"))
        assertTrue(oneCellProblem("eccentric_load" to "LOAD_9000").contains("eccentric_load"))
        assertTrue(oneCellProblem("set_echo_levels" to "HARD|MEGA").contains("set_echo_levels"))
        assertTrue(oneCellProblem("is_amrap_flag" to "nah").contains("is_amrap_flag"))
    }

    @Test
    fun everyRowProblemIsReportedWithItsLine() {
        val found = issues(
            file(
                ",Push,,,,,Bench Press,0,,,,,8|8,40,,,", // line 3: list lengths differ
                ",Push,,,,,Dip,x,,,,,8,0,,,", // line 4: bad order
                ",Pull,,,,,Row,0,,,,,0,30,,,", // line 5: reps must be positive
                ",Pull,,,,,Curl,1,,,,,8,300,,,", // line 6: weight over the per-cable limit
                ",Legs,,,,,Squat,0,,,,,8,60,,Echo,", // line 7: unsupported mode in v1
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
        assertTrue(issues(fileV2(rowV2("rest_seconds_per_set" to tooManySets))).single().message.contains("rest_seconds_per_set has more than"))

        val huge = RoutineCsvFormat.VERSION_LINE + "\n" + "x".repeat(RoutineCsvFormat.MAX_BYTES)
        assertTrue(issues(huge).single().message.contains("2 MB"))
    }
}
