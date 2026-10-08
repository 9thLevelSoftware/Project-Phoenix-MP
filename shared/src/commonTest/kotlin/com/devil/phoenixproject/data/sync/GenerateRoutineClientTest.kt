package com.devil.phoenixproject.data.sync

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises the REAL [PortalApiClient.generateRoutine] over a MockEngine (same
 * convention as [PortalApiClientPullRequestTest]): the request wire shape is pinned
 * at the HTTP boundary, and every generate-routine failure status maps to its own
 * user-facing copy bucket BEFORE the shared [classifyByStatusCode] (which collapses
 * 402/403). Nothing is persisted anywhere in this path.
 */
class GenerateRoutineClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun runPortalHttpTest(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun authenticatedStorage() = PortalTokenStorage(MapSettings()).apply {
        saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = 4_102_444_800L,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-a", email = "user@example.com"),
            ),
        )
    }

    private fun client(engine: MockEngine, storage: PortalTokenStorage = authenticatedStorage()) = PortalApiClient(
        SupabaseConfig("https://fake.supabase.co", "anon"),
        storage,
        httpClientEngine = engine,
    )

    private fun bodyText(request: HttpRequestData): String = when (val body = request.body) {
        is TextContent -> body.text
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> error("Unexpected request body type: ${body::class}")
    }

    // === Happy path / wire shape ===

    @Test
    fun `200 posts the request shape and decodes a draft with string and numeric superset groups`() = runPortalHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = """
                    {
                      "kind": "routine",
                      "remainingToday": 19,
                      "disclaimer": "This is a training draft, not medical advice. Review and edit before saving.",
                      "draft": {
                        "name": "Upper body, 35 min",
                        "targetMinutes": 35,
                        "avoidedMuscles": ["shoulders"],
                        "unmetConstraints": [],
                        "exercises": [
                          {
                            "exerciseId": "bench-press",
                            "name": "Bench Press",
                            "sets": 3,
                            "reps": 8,
                            "restSeconds": 60,
                            "percentOfOneRm": 70,
                            "mode": "OLD_SCHOOL",
                            "supersetGroup": 1,
                            "echoLevel": null,
                            "eccentricLoad": null
                          },
                          {
                            "exerciseId": "incline-curl",
                            "name": "Incline Curl",
                            "sets": 3,
                            "reps": 12,
                            "restSeconds": 45,
                            "percentOfOneRm": 60,
                            "mode": "ECHO",
                            "echoLevel": "EPIC",
                            "eccentricLoad": "LOAD_120",
                            "supersetGroup": "1"
                          },
                          {
                            "exerciseId": "plank",
                            "sets": 2,
                            "reps": 12,
                            "restSeconds": 30,
                            "percentOfOneRm": 70,
                            "mode": "OLD_SCHOOL"
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val result = client(engine).generateRoutine(
            GenerateRoutineRequest(prompt = "I have 35 minutes", targetMinutes = 35),
        )

        val response = result.getOrThrow()
        assertEquals("routine", response.kind)
        assertEquals(19, response.remainingToday)
        assertEquals(AI_ROUTINE_DRAFT_DISCLAIMER, response.disclaimer)

        val draft = response.draft
        assertEquals("Upper body, 35 min", draft.name)
        assertEquals(35, draft.targetMinutes)
        assertEquals(listOf("shoulders"), draft.avoidedMuscles)
        assertEquals(3, draft.exercises.size)
        // supersetGroup decodes from a JSON number OR a JSON string as the same key.
        assertEquals(listOf("1", "1", null), draft.exercises.map { it.supersetGroup })
        assertEquals("EPIC", draft.exercises[1].echoLevel)
        assertEquals("LOAD_120", draft.exercises[1].eccentricLoad)
        assertNull(draft.exercises[2].echoLevel)
        assertEquals("plank", draft.exercises[2].exerciseId)

        // Wire shape: function URL, user JWT + anon apikey, JSON body.
        val request = requests.single()
        assertEquals("/functions/v1/generate-routine", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        assertEquals("anon", request.headers["apikey"])
        val sent = Json.parseToJsonElement(bodyText(request)).jsonObject
        assertEquals("I have 35 minutes", sent.getValue("prompt").jsonPrimitive.content)
        assertEquals("routine", sent.getValue("kind").jsonPrimitive.content)
        assertEquals("35", sent.getValue("targetMinutes").jsonPrimitive.content)
    }

    // === Error mapping: distinct copy per status, parsed BEFORE classifyByStatusCode ===

    @Test
    fun `402 maps to the upgrade-to-Flame copy with tier context`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"subscription_required","message":"Flame required","requiredTier":"FLAME","currentTier":"EMBER"}""",
                status = HttpStatusCode.PaymentRequired,
                headers = jsonHeaders,
            )
        }

        val failure = assertIs<GenerateRoutineException>(
            client(engine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )

        assertEquals(GenerateRoutineErrorKind.NOT_SUBSCRIBED, failure.kind)
        assertEquals("subscription_required", failure.serverError)
        assertEquals("FLAME", failure.requiredTier)
        assertEquals("EMBER", failure.currentTier)
        assertEquals(402, failure.statusCode)
    }

    @Test
    fun `403 feature_disabled maps to the not-available-right-now copy`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"feature_disabled","message":"AI routine generation is not available right now"}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders,
            )
        }

        val failure = assertIs<GenerateRoutineException>(
            client(engine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )

        assertEquals(GenerateRoutineErrorKind.NOT_AVAILABLE, failure.kind)
        assertEquals("feature_disabled", failure.serverError)
    }

    @Test
    fun `404 function-not-deployed maps to the same copy as feature_disabled`() = runPortalHttpTest {
        val engine404 = MockEngine {
            respond(content = "<html>Not Found</html>", status = HttpStatusCode.NotFound)
        }
        val engine403 = MockEngine {
            respond(
                content = """{"error":"feature_disabled","message":"not available right now"}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders,
            )
        }

        val notFound = assertIs<GenerateRoutineException>(
            client(engine404).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )
        val disabled = assertIs<GenerateRoutineException>(
            client(engine403).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )

        assertEquals(GenerateRoutineErrorKind.NOT_AVAILABLE, notFound.kind)
        assertEquals(disabled.kind, notFound.kind)
    }

    @Test
    fun `422 maps to the invalid-draft copy`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"generation_invalid","message":"could not build a valid workout"}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = jsonHeaders,
            )
        }

        val failure = assertIs<GenerateRoutineException>(
            client(engine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )

        assertEquals(GenerateRoutineErrorKind.INVALID_DRAFT, failure.kind)
        assertEquals("generation_invalid", failure.serverError)
    }

    @Test
    fun `429 carries retryAfterSeconds from the body and falls back to Retry-After`() = runPortalHttpTest {
        val bodyEngine = MockEngine {
            respond(
                content = """{"error":"rate_limit_exceeded","message":"limit reached","retryAfterSeconds":42}""",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders,
            )
        }
        val headerEngine = MockEngine {
            respond(
                content = """{"error":"rate_limit_exceeded","message":"limit reached"}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "Retry-After" to listOf("17"),
                ),
            )
        }

        val fromBody = assertIs<GenerateRoutineException>(
            client(bodyEngine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )
        assertEquals(GenerateRoutineErrorKind.RATE_LIMITED, fromBody.kind)
        assertEquals(42, fromBody.retryAfterSeconds)

        val fromHeader = assertIs<GenerateRoutineException>(
            client(headerEngine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
        )
        assertEquals(GenerateRoutineErrorKind.RATE_LIMITED, fromHeader.kind)
        assertEquals(17, fromHeader.retryAfterSeconds)
    }

    @Test
    fun `503 maps to the temporarily-unavailable copy for every fail-closed code`() = runPortalHttpTest {
        for (code in listOf("model_unavailable", "subscription_unavailable", "rate_limit_unavailable")) {
            val engine = MockEngine {
                respond(
                    content = """{"error":"$code","message":"temporarily unavailable"}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = jsonHeaders,
                )
            }

            val failure = assertIs<GenerateRoutineException>(
                client(engine).generateRoutine(GenerateRoutineRequest(prompt = "x")).exceptionOrNull(),
            )

            assertEquals(GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE, failure.kind, "for $code")
            assertEquals(code, failure.serverError)
        }
    }

    @Test
    fun `missing auth fails without calling the function`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(content = "{}", status = HttpStatusCode.OK, headers = jsonHeaders)
        }

        val failure = assertIs<GenerateRoutineException>(
            client(engine, PortalTokenStorage(MapSettings()))
                .generateRoutine(GenerateRoutineRequest(prompt = "x"))
                .exceptionOrNull(),
        )

        assertEquals(GenerateRoutineErrorKind.UNAUTHORIZED, failure.kind)
        assertTrue(engine.requestHistory.isEmpty())
    }
}
