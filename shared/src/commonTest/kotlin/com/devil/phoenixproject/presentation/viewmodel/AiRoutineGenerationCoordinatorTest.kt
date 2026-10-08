package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.sync.AI_ROUTINE_DRAFT_DISCLAIMER
import com.devil.phoenixproject.data.sync.GenerateRoutineErrorKind
import com.devil.phoenixproject.data.sync.GenerateRoutineException
import com.devil.phoenixproject.data.sync.GenerateRoutineRequest
import com.devil.phoenixproject.data.sync.GenerateRoutineResponse
import com.devil.phoenixproject.data.sync.GeneratedRoutineDraft
import com.devil.phoenixproject.data.sync.GeneratedRoutineExercise
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.usecase.GeneratedDraftMapper
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Issue #1223 FE-C behavioral tests for the non-composable generation
 * coordinator: request identity capture on Generate, response acceptance only
 * when the account/profile/token still match, and the failure taxonomy mapping —
 * with an injected fake repository proving that no error, cancel, or switch path
 * ever reaches a save.
 */
class AiRoutineGenerationCoordinatorTest {

    /** Scripted stand-in for PortalApiClient.generateRoutine (open API, FE-B). */
    private class ScriptedGenerateClient : FakePortalApiClient() {
        var result: Result<GenerateRoutineResponse> = Result.failure(
            GenerateRoutineException(
                kind = GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE,
                message = "unset",
            ),
        )
        var onCall: (() -> Unit)? = null
        val requests = mutableListOf<GenerateRoutineRequest>()

        override suspend fun generateRoutine(request: GenerateRoutineRequest): Result<GenerateRoutineResponse> {
            requests += request
            onCall?.invoke()
            return result
        }
    }

    private fun entry(
        exerciseId: String,
        name: String = exerciseId,
        sets: Int = 3,
        reps: Int = 8,
        restSeconds: Int = 60,
        percentOfOneRm: Int = 70,
        mode: String = "OLD_SCHOOL",
    ) = GeneratedRoutineExercise(
        exerciseId = exerciseId,
        name = name,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds,
        percentOfOneRm = percentOfOneRm,
        mode = mode,
    )

    private fun successResponse(
        exercises: List<GeneratedRoutineExercise> = listOf(entry("a")),
        unmetConstraints: List<String> = emptyList(),
        remainingToday: Int = 19,
    ) = GenerateRoutineResponse(
        kind = "routine",
        remainingToday = remainingToday,
        disclaimer = AI_ROUTINE_DRAFT_DISCLAIMER,
        draft = GeneratedRoutineDraft(
            name = "AI workout",
            unmetConstraints = unmetConstraints,
            exercises = exercises,
        ),
    )

    private class Fixture(
        val client: ScriptedGenerateClient = ScriptedGenerateClient(),
        val exercises: FakeExerciseRepository = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    name = "a",
                    muscleGroup = "Chest",
                    muscleGroups = "Chest",
                    equipment = "CABLE",
                    id = "a",
                    isBodyweightOverride = false,
                ),
            )
        },
        val baselines: FakeProfileExerciseBaselineRepository = FakeProfileExerciseBaselineRepository(),
        val holder: AiRoutineDraftHolder = AiRoutineDraftHolder(),
        val repository: WorkoutRepositoryWriteCounter = WorkoutRepositoryWriteCounter(),
    ) {
        var accountId: String? = "account-1"
        var profileId: String = "profile-a"

        val coordinator = AiRoutineGenerationCoordinator(
            client = client,
            mapper = GeneratedDraftMapper(exercises, baselines),
            baselineRepository = baselines,
            holder = holder,
            accountIdProvider = { accountId },
            profileIdProvider = { profileId },
            newRequestToken = { "request-token" },
        )
    }

    @Test
    fun `successful generation accepts and stages the mapped draft`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(
            successResponse(unmetConstraints = listOf("no barbell available")),
        )

        val outcome = fixture.coordinator.generate("  35-min upper body  ", targetMinutes = 35, includeLoadContext = false)

        val draft = assertIs<AiRoutineGenerateOutcome.Draft>(outcome)
        assertEquals(listOf("no barbell available"), draft.unmetConstraints)
        assertEquals(35, draft.requestedMinutes)
        assertEquals(19, draft.remainingToday)
        assertEquals("profile-a", draft.routine.profileId)
        assertTrue(draft.warnings.isEmpty())

        // Accepted responses are staged for the editor's one-shot consume.
        assertEquals(draft.routine.id, fixture.holder.consumeGeneratedRoutine(draft.routine.id)?.id)

        // The request wire shape: trimmed prompt, kind = routine, no load context.
        val request = fixture.client.requests.single()
        assertEquals("35-min upper body", request.prompt)
        assertEquals("routine", request.kind)
        assertEquals(35, request.targetMinutes)
        assertEquals(false, request.includeLoadContext)
        assertTrue(request.loadContext.isEmpty())

        // The generation flow never writes; only the editor's explicit Save does.
        assertEquals(0, fixture.repository.saveCalls)
    }

    @Test
    fun `dropped exercises surface as warnings and an empty draft never opens the editor`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(
            successResponse(exercises = listOf(entry("a"), entry("ghost", name = "Ghost Fly"))),
        )
        val withWarning = fixture.coordinator.generate("prompt", null, includeLoadContext = false)
        val draft = assertIs<AiRoutineGenerateOutcome.Draft>(withWarning)
        assertEquals(listOf("Ghost Fly"), draft.warnings)

        // Every draft id unknown to the local library -> EmptyDraft (the 422-style
        // message), nothing staged, no editor.
        fixture.client.result = Result.success(successResponse(exercises = listOf(entry("ghost"))))
        val empty = fixture.coordinator.generate("prompt", null, includeLoadContext = false)
        assertEquals(AiRoutineGenerateOutcome.EmptyDraft, empty)
        assertNull(fixture.holder.consumeGeneratedRoutine("ghost"))
    }

    @Test
    fun `every failure kind maps to its copy bucket and saves nothing`() = runTest {
        val cases = listOf(
            GenerateRoutineErrorKind.NOT_SUBSCRIBED to 402,
            GenerateRoutineErrorKind.NOT_AVAILABLE to 403,
            GenerateRoutineErrorKind.INVALID_DRAFT to 422,
            GenerateRoutineErrorKind.RATE_LIMITED to 429,
            GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE to 503,
        )
        for ((kind, status) in cases) {
            val fixture = Fixture()
            fixture.client.result = Result.failure(
                GenerateRoutineException(
                    kind = kind,
                    message = "server message $status",
                    statusCode = status,
                    retryAfterSeconds = if (kind == GenerateRoutineErrorKind.RATE_LIMITED) 300 else null,
                ),
            )

            val outcome = fixture.coordinator.generate("prompt", null, includeLoadContext = false)

            val failure = assertIs<AiRoutineGenerateOutcome.Failed>(outcome, "HTTP $status")
            assertEquals(kind, failure.kind, "HTTP $status")
            if (kind == GenerateRoutineErrorKind.RATE_LIMITED) {
                assertEquals(300, failure.retryAfterSeconds, "429 carries retry timing")
            }

            assertNull(fixture.holder.consumeGeneratedRoutine("any"), "error paths stage nothing")
            assertEquals(0, fixture.repository.saveCalls, "error paths call no save")
        }
    }

    @Test
    fun `cancel mid-flight invalidates the late response and stages nothing`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(successResponse())
        fixture.client.onCall = {
            // The user pressed Cancel while the request was in flight.
            fixture.holder.invalidate()
        }

        val outcome = fixture.coordinator.generate("prompt", null, includeLoadContext = false)

        val rejected = assertIs<AiRoutineGenerateOutcome.Rejected>(outcome)
        assertEquals(AiRoutineDraftAcceptance.STALE_REQUEST, rejected.reason)
        assertNull(fixture.holder.consumeGeneratedRoutine("a"), "late responses stage nothing")
        assertEquals(0, fixture.repository.saveCalls)
    }

    @Test
    fun `profile switch mid-flight invalidates the late response`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(successResponse())
        fixture.client.onCall = {
            fixture.profileId = "profile-b"
        }

        val outcome = fixture.coordinator.generate("prompt", null, includeLoadContext = false)

        val rejected = assertIs<AiRoutineGenerateOutcome.Rejected>(outcome)
        assertEquals(AiRoutineDraftAcceptance.IDENTITY_CHANGED, rejected.reason)
        assertNull(fixture.holder.consumeGeneratedRoutine("a"))
        assertEquals(0, fixture.repository.saveCalls)
    }

    @Test
    fun `logout mid-flight invalidates the late response`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(successResponse())
        fixture.client.onCall = {
            fixture.accountId = null
        }

        val outcome = fixture.coordinator.generate("prompt", null, includeLoadContext = false)

        val rejected = assertIs<AiRoutineGenerateOutcome.Rejected>(outcome)
        assertEquals(AiRoutineDraftAcceptance.IDENTITY_CHANGED, rejected.reason)
        assertNull(fixture.holder.consumeGeneratedRoutine("a"))
        assertEquals(0, fixture.repository.saveCalls)
    }

    @Test
    fun `load context is opt-in, bounded, and never sent when the toggle is off`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(successResponse())
        repeat(45) { index ->
            fixture.baselines.seed("profile-a", "ex-$index", oneRepMaxPerCableKg = 20f + index)
        }
        fixture.baselines.seed("profile-a", "ex-without-baseline", oneRepMaxPerCableKg = null)
        fixture.baselines.seed("profile-other", "ex-other", oneRepMaxPerCableKg = 50f)

        fixture.coordinator.generate("prompt", null, includeLoadContext = false)
        assertTrue(fixture.client.requests.last().loadContext.isEmpty(), "default OFF sends no load data")

        fixture.coordinator.generate("prompt", null, includeLoadContext = true)
        val request = fixture.client.requests.last()
        assertTrue(request.includeLoadContext)
        assertEquals(40, request.loadContext.size, "wire contract caps load context at 40 items")
        assertTrue(request.loadContext.all { it.estimated1RmKg > 0f })
        assertTrue(request.loadContext.none { it.exerciseId == "ex-without-baseline" })
        assertTrue(request.loadContext.none { it.exerciseId == "ex-other" }, "other profiles never leak")
    }

    @Test
    fun `staged drafts are scoped to the generation profile`() = runTest {
        val fixture = Fixture()
        fixture.client.result = Result.success(successResponse())

        val outcome = fixture.coordinator.generate("prompt", null, includeLoadContext = false)

        val draft = assertIs<AiRoutineGenerateOutcome.Draft>(outcome)
        assertEquals("profile-a", draft.routine.profileId)
        assertTrue(fixture.holder.stagedProfileMatches("profile-a"))
        assertIs<Routine>(draft.routine)
    }
}
