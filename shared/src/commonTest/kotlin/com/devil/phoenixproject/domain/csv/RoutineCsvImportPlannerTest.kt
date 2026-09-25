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
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WarmupSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #772: matching and planning a parsed routine CSV. */
class RoutineCsvImportPlannerTest {
    private var nextId = 0
    private val planner = RoutineCsvImportPlanner(newId = { "id-${nextId++}" }, newSupersetId = { "ss-${nextId++}" })

    private val bench = Exercise(id = "bench-id", name = "Bench Press", muscleGroup = "Chest")
    private val row = Exercise(id = "row-id", name = "Seated Row", muscleGroup = "Back")
    private val curlA = Exercise(id = "curl-a", name = "Curl", muscleGroup = "Arms")
    private val curlB = Exercise(id = "curl-b", name = "curl", muscleGroup = "Arms")
    private val library = listOf(bench, row, curlA, curlB)

    private fun drafts(vararg rows: String): List<RoutineCsvRoutineDraft> {
        val text = (listOf(RoutineCsvFormat.VERSION_LINE, RoutineCsvFormat.COLUMNS.joinToString(",")) + rows).joinToString("\n")
        return assertIs<RoutineCsvParseResult.Parsed>(RoutineCsvCodec.parse(text)).routines
    }

    private fun plan(
        drafts: List<RoutineCsvRoutineDraft>,
        mode: RoutineCsvImportMode = RoutineCsvImportMode.CREATE_NEW,
        routines: List<Routine> = emptyList(),
        groups: List<RoutineGroup> = emptyList(),
    ) = planner.plan(drafts, mode, "p1", routines, groups, library, nowMs = 1_000L)

    private val existingPush = Routine(
        id = "push-id",
        name = "Push",
        createdAt = 10L,
        lastUsed = 20L,
        useCount = 7,
        profileId = "p1",
        groupId = "g-old",
    )

    @Test
    fun newRoutinesAreBuiltLikeTheEditorBuildsThem() {
        val plan = plan(
            drafts(
                ",Push,Chest day,,,bench-id,Bench Press,0,,,,,8|8|6,40|40|42.5,120,PUMP,false",
                ",Push,Chest day,,,,seated row,1,a,Pair,,20,10|10,30|30,90,,false,2",
                ",Push,Chest day,,,,Bench Press,2,a,Pair,,20,AMRAP|AMRAP,0|0,90,,true,2",
            ),
        )
        assertTrue(plan.canCommit, plan.issues.toString())
        assertFalse(plan.hasMatches)
        val routine = plan.writes.single()
        assertEquals("Push", routine.name)
        assertEquals("Chest day", routine.description)
        assertEquals("p1", routine.profileId)
        assertEquals(0, routine.useCount)
        assertEquals(1_000L, routine.createdAt)

        val (first, second, third) = routine.exercises
        assertEquals(listOf(bench, row, bench), listOf(first.exercise, second.exercise, third.exercise))
        assertEquals(listOf(0, 1, 2), routine.exercises.map { it.orderIndex })
        assertEquals(listOf(8, 8, 6), first.setReps)
        assertEquals(40f, first.weightPerCableKg)
        assertEquals(listOf(40f, 40f, 42.5f), first.setWeightsPerCableKg)
        assertEquals(listOf(120, 120, 120), first.setRestSeconds)
        assertEquals(ProgramMode.Pump, first.programMode)
        assertFalse(first.isAMRAP)
        assertTrue(third.isAMRAP)
        assertNull(first.supersetId)

        val superset = routine.supersets.single()
        assertEquals("Pair", superset.name)
        assertEquals(20, superset.restBetweenSeconds)
        assertEquals(2, superset.colorIndex, "a colour given in the file is kept")
        assertEquals(1, superset.orderIndex)
        assertEquals(routine.id, superset.routineId)
        assertEquals(listOf(superset.id, superset.id), listOf(second.supersetId, third.supersetId))
        assertEquals(listOf(0, 1), listOf(second.orderInSuperset, third.orderInSuperset))
    }

    @Test
    fun exercisesMatchByIdThenUniqueNameAndStaleIdsNeverFallBack() {
        val plan = plan(
            drafts(
                ",A,,,,gone-id,Bench Press,0,,,,,8,40,,,",
                ",B,,,,,Curl,0,,,,,8,10,,,",
                ",C,,,,,Zercher Squat,0,,,,,8,10,,,",
            ),
        )
        assertFalse(plan.canCommit)
        assertTrue(plan.writes.isEmpty())
        val messages = plan.issues.map { it.message }
        assertTrue(messages[0].contains("gone-id"), messages.toString())
        assertTrue(messages[1].contains("2 exercises are named 'Curl'") && messages[1].contains("curl-a"), messages.toString())
        assertTrue(messages[2].contains("Zercher Squat"), messages.toString())
    }

    @Test
    fun aMatchingRoutineNeedsAnExplicitChoice() {
        val file = drafts(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,")

        val createNew = plan(file, RoutineCsvImportMode.CREATE_NEW, routines = listOf(existingPush))
        assertTrue(createNew.hasMatches)
        assertFalse(createNew.canCommit)

        val overwrite = plan(file, RoutineCsvImportMode.OVERWRITE_MATCHING, routines = listOf(existingPush))
        assertTrue(overwrite.canCommit, overwrite.issues.toString())
        assertEquals(RoutineCsvImportAction.OVERWRITE, overwrite.routines.single().action)
        assertEquals("push-id", overwrite.routines.single().targetRoutineId)
        assertEquals(setOf("push-id"), overwrite.overwriteRoutineIds)
        val replaced = overwrite.writes.single()
        assertEquals("push-id", replaced.id)
        assertEquals(10L, replaced.createdAt)
        assertEquals(20L, replaced.lastUsed)
        assertEquals(7, replaced.useCount)
        assertEquals("g-old", replaced.groupId, "a blank group keeps the existing one")

        val copies = plan(file, RoutineCsvImportMode.CREATE_COPIES, routines = listOf(existingPush))
        assertTrue(copies.canCommit)
        assertTrue(copies.overwriteRoutineIds.isEmpty())
        assertNull(copies.routines.single().targetRoutineId)
        assertEquals("Push (Copy)", copies.writes.single().name)
        assertEquals(0, copies.writes.single().useCount)
        assertNull(copies.writes.single().groupId)
    }

    @Test
    fun routinesMatchByIdBeforeNameAndOnlyInTheActiveProfile() {
        val renamed = plan(
            drafts("push-id,Push v2,,,,bench-id,Bench Press,0,,,,,8,40,,,"),
            RoutineCsvImportMode.OVERWRITE_MATCHING,
            routines = listOf(existingPush),
        )
        assertEquals("push-id", renamed.writes.single().id)
        assertEquals("Push v2", renamed.writes.single().name)

        val otherProfile = plan(
            drafts("push-id,Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"),
            routines = listOf(existingPush.copy(profileId = "p2")),
        )
        assertFalse(otherProfile.hasMatches)
        assertEquals(RoutineCsvImportAction.CREATE, otherProfile.routines.single().action)
        assertTrue(otherProfile.writes.single().id != "push-id", "another profile's routine is never overwritten")

        val cycleRoutine = plan(
            drafts(",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,"),
            routines = listOf(existingPush.copy(id = "cycle_routine_1")),
        )
        assertFalse(cycleRoutine.hasMatches)
    }

    @Test
    fun aNameSharedBySeveralRoutinesCanOnlyBeCopied() {
        val twoPushes = listOf(existingPush, existingPush.copy(id = "push-2"))
        val file = drafts(",push,,,,bench-id,Bench Press,0,,,,,8,40,,,")

        val overwrite = plan(file, RoutineCsvImportMode.OVERWRITE_MATCHING, routines = twoPushes)
        assertTrue(overwrite.issues.single().message.contains("2 routines are named"))

        val copies = plan(file, RoutineCsvImportMode.CREATE_COPIES, routines = twoPushes)
        assertTrue(copies.canCommit)
    }

    @Test
    fun twoRoutinesInTheFileCannotReplaceTheSameRoutine() {
        val plan = plan(
            drafts(
                "push-id,Other,,,,bench-id,Bench Press,0,,,,,8,40,,,",
                ",Push,,,,bench-id,Bench Press,0,,,,,8,40,,,",
            ),
            RoutineCsvImportMode.OVERWRITE_MATCHING,
            routines = listOf(existingPush),
        )
        assertTrue(plan.issues.single().message.contains("same existing routine"))
    }

    @Test
    fun groupsAreReusedByNameOrCreatedOnce() {
        val existing = RoutineGroup(id = "g-strength", name = "Strength", profileId = "p1", orderIndex = 0)
        val plan = plan(
            drafts(
                ",A,,strength,,bench-id,Bench Press,0,,,,,8,40,,,",
                ",B,,Cardio,5,bench-id,Bench Press,0,,,,,8,40,,,",
                ",C,,cardio,5,bench-id,Bench Press,0,,,,,8,40,,,",
            ),
            groups = listOf(existing),
        )
        assertTrue(plan.canCommit, plan.issues.toString())
        val cardio = plan.newGroups.single()
        assertEquals("Cardio", cardio.name)
        assertEquals(5, cardio.orderIndex)
        assertEquals("p1", cardio.profileId)
        assertEquals(listOf("g-strength", cardio.id, cardio.id), plan.writes.map { it.groupId })
    }

    @Test
    fun copiesOfTwoMatchesInOneFileGetDistinctNames() {
        val plan = plan(
            drafts(
                "push-id,Push,,,,bench-id,Bench Press,0,,,,,8,40,,,",
                ",Push (Copy),,,,bench-id,Bench Press,0,,,,,8,40,,,",
            ),
            RoutineCsvImportMode.CREATE_COPIES,
            routines = listOf(existingPush, existingPush.copy(id = "copy-id", name = "Push (Copy)")),
        )
        assertEquals(listOf("Push (Copy 2)", "Push (Copy 3)"), plan.writes.map { it.name })
    }

    @Test
    fun version2RoundTripRestoresEveryAdvancedField() {
        // Issue #896: the planner writes every field the file carried through the insert, so a
        // routine with advanced settings survives export and import unchanged.
        val source = Routine(
            id = "r-896",
            name = "Friday Lower (Old School)",
            profileId = "p1",
            exercises = listOf(
                RoutineExercise(
                    id = "e1",
                    exercise = bench,
                    orderIndex = 0,
                    setReps = listOf(8, 8, 6),
                    weightPerCableKg = 40f,
                    setWeightsPerCableKg = listOf(40f, 40f, 42.5f),
                    programMode = ProgramMode.Echo,
                    eccentricLoad = EccentricLoad.LOAD_120,
                    echoLevel = EchoLevel.EPIC,
                    progressionKg = -1.25f,
                    setRestSeconds = listOf(60, 90, 120),
                    setEchoLevels = listOf(EchoLevel.HARD, null, EchoLevel.HARDEST),
                    duration = 45,
                    isAMRAP = true,
                    perSetRestTime = true,
                    stallDetectionEnabled = false,
                    dropSetEnabled = true,
                    dropSetMinWeightKg = 20f,
                    repCountTiming = RepCountTiming.BOTTOM,
                    stopAtTop = true,
                    usePercentOfPR = true,
                    weightPercentOfPR = 80,
                    prTypeForScaling = PRType.MAX_VOLUME,
                    setWeightsPercentOfPR = listOf(75, 80, 85),
                    scalingBasis = ScalingBasis.ESTIMATED_1RM,
                    warmupSets = listOf(WarmupSet(5, 50), WarmupSet(3, 70)),
                    defaultRackItemIds = listOf("rack-a", "rack-b"),
                    rackBehaviorOverrides = mapOf("rack-a" to RackItemBehavior.COUNTERWEIGHT),
                ),
            ),
        )

        val exported = assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(source, null, null))
        val drafts = assertIs<RoutineCsvParseResult.Parsed>(RoutineCsvCodec.parse(exported.content)).routines
        val plan = plan(drafts)
        assertTrue(plan.canCommit, plan.issues.toString())

        val original = source.exercises.single()
        val stored = plan.writes.single().exercises.single()
        assertEquals(original.setReps, stored.setReps)
        assertEquals(original.setWeightsPerCableKg, stored.setWeightsPerCableKg)
        assertEquals(original.weightPerCableKg, stored.weightPerCableKg)
        assertEquals(original.programMode, stored.programMode)
        assertEquals(original.eccentricLoad, stored.eccentricLoad)
        assertEquals(original.echoLevel, stored.echoLevel)
        assertEquals(original.progressionKg, stored.progressionKg)
        assertEquals(original.setRestSeconds, stored.setRestSeconds)
        assertEquals(original.setEchoLevels, stored.setEchoLevels)
        assertEquals(original.duration, stored.duration)
        assertEquals(original.isAMRAP, stored.isAMRAP)
        assertEquals(original.perSetRestTime, stored.perSetRestTime)
        assertEquals(original.stallDetectionEnabled, stored.stallDetectionEnabled)
        assertEquals(original.dropSetEnabled, stored.dropSetEnabled)
        assertEquals(original.dropSetMinWeightKg, stored.dropSetMinWeightKg)
        assertEquals(original.repCountTiming, stored.repCountTiming)
        assertEquals(original.stopAtTop, stored.stopAtTop)
        assertEquals(original.usePercentOfPR, stored.usePercentOfPR)
        assertEquals(original.weightPercentOfPR, stored.weightPercentOfPR)
        assertEquals(original.prTypeForScaling, stored.prTypeForScaling)
        assertEquals(original.setWeightsPercentOfPR, stored.setWeightsPercentOfPR)
        assertEquals(original.scalingBasis, stored.scalingBasis)
        assertEquals(original.warmupSets, stored.warmupSets)
        assertEquals(original.defaultRackItemIds, stored.defaultRackItemIds)
        assertEquals(original.rackBehaviorOverrides, stored.rackBehaviorOverrides)
        assertEquals(false, stored.durationSyncKnown, "sync state is never in the file")
        assertEquals(false, stored.isLaunchAdjustedDuration, "launch state is never in the file")
    }
}
