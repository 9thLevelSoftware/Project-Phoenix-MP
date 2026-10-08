package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.sync.AI_ROUTINE_DRAFT_DISCLAIMER
import com.devil.phoenixproject.data.sync.GeneratedRoutineDraft
import com.devil.phoenixproject.data.sync.GeneratedRoutineExercise
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineItem
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.meetsAiRoutineTier
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GeneratedDraftMapperTest {

    private fun cableExercise(id: String, name: String = id) = Exercise(
        name = name,
        muscleGroup = "Chest",
        muscleGroups = "Chest",
        equipment = "CABLE",
        id = id,
        isBodyweightOverride = false,
    )

    private fun bodyweightExercise(id: String, name: String = id) = Exercise(
        name = name,
        muscleGroup = "Core",
        muscleGroups = "Core",
        equipment = "",
        id = id,
        isBodyweightOverride = true,
    )

    private fun entry(
        exerciseId: String,
        name: String = exerciseId,
        sets: Int = 3,
        reps: Int = 8,
        restSeconds: Int = 60,
        percentOfOneRm: Int = 70,
        mode: String = "OLD_SCHOOL",
        echoLevel: String? = null,
        eccentricLoad: String? = null,
        supersetGroup: String? = null,
    ) = GeneratedRoutineExercise(
        exerciseId = exerciseId,
        name = name,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds,
        percentOfOneRm = percentOfOneRm,
        mode = mode,
        echoLevel = echoLevel,
        eccentricLoad = eccentricLoad,
        supersetGroup = supersetGroup,
    )

    private fun draft(
        name: String = "AI workout",
        exercises: List<GeneratedRoutineExercise>,
    ) = GeneratedRoutineDraft(name = name, exercises = exercises)

    private suspend fun mapped(result: GeneratedDraftResult): GeneratedDraftResult.Mapped =
        assertIs<GeneratedDraftResult.Mapped>(result)

    // === Supersets ===

    @Test
    fun `supersets group members and flatten singleton groups in one shared item order`() = runTest {
        val repository = FakeExerciseRepository().apply {
            listOf("a", "b", "c", "d", "e").forEach { addExercise(cableExercise(it)) }
        }
        val mapper = GeneratedDraftMapper(repository, FakeProfileExerciseBaselineRepository())

        val result = mapped(
            mapper.map(
                draft(
                    exercises = listOf(
                        entry("a"),
                        entry("b", supersetGroup = "g1"),
                        entry("c", supersetGroup = "g1"),
                        entry("d"),
                        entry("e", supersetGroup = "g2"), // singleton group -> flattened
                    ),
                ),
                activeProfileId = "profile-1",
            ),
        )
        val routine = result.routine

        // One Superset row for g1 only; the singleton group is flattened to standalone.
        assertEquals(1, routine.supersets.size)
        val superset = routine.supersets.single()
        assertEquals(routine.id, superset.routineId)
        assertEquals("Superset", superset.name)
        assertEquals(10, superset.restBetweenSeconds)

        // One shared orderIndex sequence across standalone exercises and superset
        // containers, matching draft order.
        val items = routine.getItems()
        assertEquals(4, items.size)
        assertEquals(listOf(0, 1, 2, 3), items.map { it.orderIndex })
        assertIs<RoutineItem.Single>(items[0])
        assertEquals("a", (items[0] as RoutineItem.Single).exercise.exercise.id)
        val supersetItem = assertIs<RoutineItem.SupersetItem>(items[1])
        assertEquals(superset.id, supersetItem.superset.id)
        assertIs<RoutineItem.Single>(items[2])
        assertEquals("d", (items[2] as RoutineItem.Single).exercise.exercise.id)
        assertIs<RoutineItem.Single>(items[3])
        assertEquals("e", (items[3] as RoutineItem.Single).exercise.exercise.id)
        assertEquals(null, (items[3] as RoutineItem.Single).exercise.supersetId)

        // Members carry the container's routine id, orderInSuperset, and the shared slot.
        val members = routine.exercises.filter { it.supersetId == superset.id }
        assertEquals(listOf("b", "c"), members.sortedBy { it.orderInSuperset }.map { it.exercise.id })
        assertEquals(listOf(0, 1), members.map { it.orderInSuperset })
        assertTrue(members.all { it.orderIndex == superset.orderIndex })
        assertTrue(routine.supersets.all { it.routineId == routine.id })
    }

    // === Drop / empty-draft behavior ===

    @Test
    fun `unknown ids are dropped with warnings and never substituted`() = runTest {
        val repository = FakeExerciseRepository().apply { addExercise(cableExercise("known")) }
        val mapper = GeneratedDraftMapper(repository, FakeProfileExerciseBaselineRepository())

        val result = mapped(
            mapper.map(
                draft(exercises = listOf(entry("known"), entry("missing", name = "Mystery Curl"))),
                activeProfileId = "profile-1",
            ),
        )

        assertEquals(listOf("known"), result.routine.exercises.map { it.exercise.id })
        assertEquals(listOf("Mystery Curl"), result.warnings)
    }

    @Test
    fun `a draft where nothing resolves is an empty draft`() = runTest {
        val mapper = GeneratedDraftMapper(
            FakeExerciseRepository(),
            FakeProfileExerciseBaselineRepository(),
        )

        val result = mapper.map(
            draft(exercises = listOf(entry("gone-1"), entry("gone-2"))),
            activeProfileId = "profile-1",
        )

        assertEquals(GeneratedDraftResult.EmptyDraft, result)
    }

    // === Bodyweight ===

    @Test
    fun `bodyweight exercises drop Echo and percent loading`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(bodyweightExercise("plank", name = "Plank"))
        }
        val mapper = GeneratedDraftMapper(repository, FakeProfileExerciseBaselineRepository())

        val result = mapped(
            mapper.map(
                draft(
                    exercises = listOf(
                        entry(
                            "plank",
                            sets = 2,
                            reps = 12,
                            restSeconds = 45,
                            percentOfOneRm = 75,
                            mode = "ECHO",
                            echoLevel = "EPIC",
                            eccentricLoad = "LOAD_150",
                        ),
                    ),
                ),
                activeProfileId = "profile-1",
            ),
        )

        val exercise = result.routine.exercises.single()
        assertEquals(ProgramMode.OldSchool, exercise.programMode)
        assertEquals(EchoLevel.HARDER, exercise.echoLevel)
        assertEquals(EccentricLoad.LOAD_100, exercise.eccentricLoad)
        assertFalse(exercise.usePercentOfPR)
        assertEquals(TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG, exercise.weightPerCableKg)
        assertFalse(exercise.dropSetEnabled)
        assertEquals(List(2) { 12 }, exercise.setReps)
        assertEquals(List(2) { 45 }, exercise.setRestSeconds)
    }

    // === Cable ===

    @Test
    fun `cable exercises keep percent loading and the baseline snapshot`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(cableExercise("bench-001", name = "Bench Press"))
        }
        val baselines = FakeProfileExerciseBaselineRepository().apply {
            seed("profile-7", "bench-001", 123.5f)
        }
        val mapper = GeneratedDraftMapper(repository, baselines)

        val result = mapped(
            mapper.map(
                draft(
                    exercises = listOf(
                        entry(
                            "bench-001",
                            percentOfOneRm = 72,
                            mode = "ECHO",
                            echoLevel = "EPIC",
                            eccentricLoad = "LOAD_150",
                        ),
                    ),
                ),
                activeProfileId = "profile-7",
            ),
        )

        val exercise = result.routine.exercises.single()
        assertTrue(exercise.usePercentOfPR)
        assertEquals(ScalingBasis.ESTIMATED_1RM, exercise.scalingBasis)
        assertEquals(72, exercise.weightPercentOfPR)
        assertEquals(123.5f, exercise.weightPerCableKg)
        // Echo on a cable exercise keeps the draft's Echo settings.
        assertEquals(ProgramMode.Echo, exercise.programMode)
        assertEquals(EchoLevel.EPIC, exercise.echoLevel)
        assertEquals(EccentricLoad.LOAD_150, exercise.eccentricLoad)
        assertFalse(exercise.dropSetEnabled)
    }

    @Test
    fun `missing baseline falls back to 10kg and never zero`() = runTest {
        val repository = FakeExerciseRepository().apply {
            addExercise(cableExercise("row-001"))
            addExercise(cableExercise("curl-001"))
        }
        val baselines = FakeProfileExerciseBaselineRepository().apply {
            // Row exists but carries no value: still the 10kg fallback, never 0.
            seed("profile-1", "row-001", null)
        }
        val mapper = GeneratedDraftMapper(repository, baselines)

        val result = mapped(
            mapper.map(
                draft(exercises = listOf(entry("row-001"), entry("curl-001"))),
                activeProfileId = "profile-1",
            ),
        )

        val weights = result.routine.exercises.associate { it.exercise.id to it.weightPerCableKg }
        assertEquals(TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG, weights["row-001"])
        assertEquals(TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG, weights["curl-001"])
        assertEquals(10f, TemplateConverter.DEFAULT_FALLBACK_WEIGHT_KG)
    }

    // === Identity and description ===

    @Test
    fun `routine belongs to the active profile with a fresh non-new id`() = runTest {
        val repository = FakeExerciseRepository().apply { addExercise(cableExercise("bench-001")) }
        val mapper = GeneratedDraftMapper(repository, FakeProfileExerciseBaselineRepository())

        val result = mapped(
            mapper.map(
                draft(exercises = listOf(entry("bench-001"))),
                activeProfileId = "profile-42",
            ),
        )

        assertEquals("profile-42", result.routine.profileId)
        assertTrue(result.routine.id.isNotBlank())
        assertTrue(result.routine.id != "new")
        assertFalse(result.routine.id.startsWith("cycle_routine_"))
    }

    @Test
    fun `description is the disclaimer and never the prompt`() = runTest {
        val repository = FakeExerciseRepository().apply { addExercise(cableExercise("bench-001")) }
        val mapper = GeneratedDraftMapper(repository, FakeProfileExerciseBaselineRepository())

        val result = mapped(
            mapper.map(
                draft(
                    name = "Upper body, 35 min — avoid shoulders, they are sore",
                    exercises = listOf(entry("bench-001")),
                ),
                activeProfileId = "profile-1",
            ),
        )

        assertEquals(AI_ROUTINE_DRAFT_DISCLAIMER, result.routine.description)
        assertFalse(result.routine.description.contains("avoid shoulders", ignoreCase = true))
        assertFalse(result.routine.description.contains("prompt", ignoreCase = true))
    }

    // === Tier ladder (AiRoutineTier) ===

    @Test
    fun `meetsAiRoutineTier honors the portal ladder`() {
        assertTrue(meetsAiRoutineTier("FLAME"))
        assertTrue(meetsAiRoutineTier("INFERNO"))
        assertTrue(meetsAiRoutineTier("flame"))
        assertFalse(meetsAiRoutineTier("EMBER"))
        assertFalse(meetsAiRoutineTier("FREE"))
        assertFalse(meetsAiRoutineTier(null))
        assertFalse(meetsAiRoutineTier(""))
        assertFalse(meetsAiRoutineTier("CINDER"))
    }
}
