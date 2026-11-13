package com.devil.phoenixproject.data.integration.intervals

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.encodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * MockEngine transport tests for [IntervalsApiClient] (issue #1384 packet P4):
 * allowlist/Basic-auth origin assertions, refused redirects, sanitized errors, no hidden
 * POST retry, nonmutating GET validation, and independent HR omission. No real key,
 * account or Intervals.icu request is ever used.
 */
class IntervalsApiClientTest {

    private val wireJson = Json { ignoreUnknownKeys = true }

    private fun expectedBasicAuth(apiKey: String): String =
        "Basic " + ("API_KEY:$apiKey").encodeBase64()

    private fun headersOfPairs(vararg pairs: Pair<String, String>): Headers {
        val builder = HeadersBuilder()
        for ((name, value) in pairs) builder.append(name, value)
        return builder.build()
    }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun sampleActivity(
        averageHeartrate: Int? = null,
        maxHeartrate: Int? = null,
    ) = IntervalsManualActivity(
        externalId = "routine-session-1",
        name = "Push Day",
        description = "Bench: 3x5 @ 60 kg (configured kg per cable)",
        startDate = "2026-11-08T14:03:21Z",
        startDateLocal = "2026-11-08T09:03:21",
        elapsedTimeSeconds = 3_600,
        averageHeartrate = averageHeartrate,
        maxHeartrate = maxHeartrate,
    )

    // ------------------------------------------------------------ origin + auth

    @Test
    fun createPostsAllowlistedOriginWithPerRequestBasicAuth() = runHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            jsonResponse("""{"id":"99","external_id":"routine-session-1"}""")
        }
        val client = IntervalsApiClient(engine)
        val outcome = client.createManualActivity("i42", "test-key", sampleActivity())

        val accepted = assertIs<IntervalsCreateOutcome.Accepted>(outcome)
        assertEquals("99", accepted.remoteActivityId)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("https", request.url.protocol.name.lowercase())
        assertEquals("intervals.icu", request.url.host)
        assertEquals("/api/v1/athlete/i42/activities/manual", request.url.encodedPath)
        assertEquals(expectedBasicAuth("test-key"), request.headers[HttpHeaders.Authorization])
        client.close()
    }

    @Test
    fun hostileIdSegmentCannotEscapeItsPathSegment() = runHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            jsonResponse("""{"id":"1"}""")
        }
        val client = IntervalsApiClient(engine)
        // The literal id is preserved as data but cannot inject extra path segments.
        client.createManualActivity("i42/../../admin", "k", sampleActivity())
        val request = requests.single()
        assertEquals("intervals.icu", request.url.host)
        assertEquals("/api/v1/athlete/i42%2F..%2F..%2Fadmin/activities/manual", request.url.encodedPath)
        client.close()
    }

    // ------------------------------------------------------------ redirects

    @Test
    fun redirectResponsesAreRefusedAndNeverFollowed() = runHttpTest {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://evil.example/steal"),
            )
        }
        val client = IntervalsApiClient(engine)

        val create = client.createManualActivity("i42", "k", sampleActivity())
        // Possible delivery + refused redirect: ambiguity, never a blind replay.
        assertEquals(IntervalsCreateOutcome.Ambiguous, create)
        assertEquals(1, requestCount)

        val validation = client.validateAthlete("i42", "k")
        val rejected = assertIs<IntervalsValidationResult.Rejected>(validation)
        assertEquals("redirect refused", rejected.detail)
        assertEquals(2, requestCount)
        client.close()
    }

    // ------------------------------------------------------------ sanitization

    @Test
    fun errorDetailsNeverContainResponseBodiesOrIdentifiers() = runHttpTest {
        val secretBody =
            """{"error":"athlete John Doe (i42) rejected","ssn":"000-00-0000","key":"sk-live-secret"}"""
        val engine = MockEngine { jsonResponse(secretBody, HttpStatusCode.BadRequest) }
        val client = IntervalsApiClient(engine)

        val validation = client.validateAthlete("i42", "sk-live-secret")
        val rejected = assertIs<IntervalsValidationResult.Rejected>(validation)
        val detail = assertNotNull(rejected.detail)
        assertTrue(detail.contains("400"), "detail should carry the status code: $detail")
        assertFalse(detail.contains("John Doe"))
        assertFalse(detail.contains("i42"))
        assertFalse(detail.contains("sk-live-secret"))
        assertFalse(detail.contains("000-00-0000"))

        val create = client.createManualActivity("i42", "sk-live-secret", sampleActivity())
        val createRejected = assertIs<IntervalsCreateOutcome.ValidationRejected>(create)
        val createDetail = assertNotNull(createRejected.detail)
        assertTrue(createDetail.contains("400"))
        assertFalse(createDetail.contains("John Doe"))
        assertFalse(createDetail.contains("sk-live-secret"))
        client.close()
    }

    @Test
    fun transportFailuresProduceSanitizedDescriptions() = runHttpTest {
        val engine = MockEngine { throw RuntimeException("connection to i42 leaked body: John Doe") }
        val client = IntervalsApiClient(engine)

        val validation = client.validateAthlete("i42", "k")
        val failure = assertIs<IntervalsValidationResult.NetworkFailure>(validation)
        val detail = assertNotNull(failure.detail)
        assertFalse(detail.contains("John Doe"))
        assertFalse(detail.contains("i42"))

        val create = client.createManualActivity("i42", "k", sampleActivity())
        assertEquals(IntervalsCreateOutcome.Ambiguous, create)
        client.close()
    }

    // ------------------------------------------------------------ no hidden retry

    @Test
    fun postIsNeverAutomaticallyRetried() = runHttpTest {
        var postCount = 0
        var getCount = 0
        val engine = MockEngine { request ->
            when (request.method.value) {
                "POST" -> {
                    postCount++
                    jsonResponse("""{"oops":"500 with partial acceptance"}""", HttpStatusCode.InternalServerError)
                }

                else -> {
                    getCount++
                    jsonResponse("""{"id":"i42"}""")
                }
            }
        }
        val client = IntervalsApiClient(engine)

        val outcome = client.createManualActivity("i42", "k", sampleActivity())
        val serverFailure = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(outcome)
        assertFalse(serverFailure.provablyNotAccepted)
        assertEquals(1, postCount, "exactly one POST attempt, no hidden retry")

        client.validateAthlete("i42", "k")
        assertEquals(1, getCount)
        assertEquals(1, postCount, "GET validation must not trigger POSTs")
        client.close()
    }

    // ------------------------------------------------------------ validation is nonmutating

    @Test
    fun validationIsANonmutatingGetAndNeverEnables() = runHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            jsonResponse("""{"id":"i42","name":"Chris","premium":true,"ignored":"x"}""")
        }
        val client = IntervalsApiClient(engine)

        val result = client.validateAthlete("i42", "k")
        val valid = assertIs<IntervalsValidationResult.Valid>(result)
        assertEquals("i42", valid.canonicalAthleteId)
        assertEquals("Chris", valid.displayName)

        val request = requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/api/v1/athlete/i42", request.url.encodedPath)
        assertEquals(expectedBasicAuth("k"), request.headers[HttpHeaders.Authorization])
        // A GET can never establish eligibility: the API surface has no enable operation.
        client.close()
    }

    @Test
    fun invalidCredentialsAreReportedWithoutDetailLeak() = runHttpTest {
        val engine = MockEngine { jsonResponse("""{"message":"bad key for John"}""", HttpStatusCode.Unauthorized) }
        val client = IntervalsApiClient(engine)
        val result = client.validateAthlete("i42", "bad-key")
        val invalid = assertIs<IntervalsValidationResult.InvalidCredentials>(result)
        assertNull(invalid.detail)
        client.close()
    }

    // ------------------------------------------------------------ frozen classification

    @Test
    fun createClassificationMatchesFrozenStateMachine() = runHttpTest {
        suspend fun outcomeFor(
            status: HttpStatusCode,
            body: String = "{}",
            retryAfter: String? = null,
        ): IntervalsCreateOutcome {
            val headers = if (retryAfter != null) {
                headersOfPairs(
                    HttpHeaders.ContentType to ContentType.Application.Json.toString(),
                    HttpHeaders.RetryAfter to retryAfter,
                )
            } else {
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            val engine = MockEngine { respond(body, status, headers) }
            val client = IntervalsApiClient(engine)
            val outcome = client.createManualActivity("i42", "k", sampleActivity())
            client.close()
            return outcome
        }

        assertIs<IntervalsCreateOutcome.AuthRejected>(outcomeFor(HttpStatusCode.Unauthorized))
        assertIs<IntervalsCreateOutcome.AuthRejected>(outcomeFor(HttpStatusCode.Forbidden))

        val badRequest = assertIs<IntervalsCreateOutcome.ValidationRejected>(outcomeFor(HttpStatusCode.BadRequest))
        assertEquals(400, badRequest.statusCode)
        val tooLarge = assertIs<IntervalsCreateOutcome.ValidationRejected>(
            outcomeFor(HttpStatusCode.PayloadTooLarge),
        )
        assertEquals(413, tooLarge.statusCode)

        val rateLimited = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(
            outcomeFor(HttpStatusCode.TooManyRequests, retryAfter = "7"),
        )
        assertEquals(429, rateLimited.statusCode)
        assertFalse(rateLimited.provablyNotAccepted)
        assertEquals(7_000L, rateLimited.retryAfterMillis)

        val badRetryAfter = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(
            outcomeFor(HttpStatusCode.TooManyRequests, retryAfter = "soonish"),
        )
        assertNull(badRetryAfter.retryAfterMillis)

        val serverFailure = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(
            outcomeFor(HttpStatusCode.ServiceUnavailable),
        )
        assertEquals(503, serverFailure.statusCode)
        assertFalse(serverFailure.provablyNotAccepted)
    }

    @Test
    fun retryAfterDateFormIsConvertedToDelay() = runHttpTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "Sun, 08 Nov 2026 15:00:00 GMT"),
            )
        }
        // Deterministic clock: 2026-11-08T14:00:00Z.
        val now = 1_794_146_400_000L
        val client = IntervalsApiClient(engine) { now }
        val outcome = client.createManualActivity("i42", "k", sampleActivity())
        val rateLimited = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(outcome)
        assertEquals(3_600_000L, rateLimited.retryAfterMillis)
        client.close()
    }

    @Test
    fun connectPhaseFailureIsProvablyNotAcceptedAndPostDeliveryLossIsAmbiguous() = runHttpTest {
        /** Class name matches the pre-delivery failure vocabulary (connect refused). */
        class ConnectException : Exception("connection refused")

        val connectEngine = MockEngine { throw ConnectException() }
        val connectClient = IntervalsApiClient(connectEngine)
        val safeRetry = connectClient.createManualActivity("i42", "k", sampleActivity())
        val safe = assertIs<IntervalsCreateOutcome.RateLimitedOrServerFailure>(safeRetry)
        assertTrue(safe.provablyNotAccepted)
        connectClient.close()

        class BoomException : Exception("socket reset mid-response")

        val lostEngine = MockEngine { throw BoomException() }
        val lostClient = IntervalsApiClient(lostEngine)
        val lost = lostClient.createManualActivity("i42", "k", sampleActivity())
        assertEquals(IntervalsCreateOutcome.Ambiguous, lost)
        lostClient.close()

        // Cancellation is NEVER classified: it propagates so shutdown()/structured
        // cancellation stops the drain. The claimed job stays IN_FLIGHT under its lease
        // and `expireIntervalsLeases` turns it UNCERTAIN — ambiguity preserved without
        // running past cancellation (review fix: swallowing it here let a cancelled
        // coroutine keep running inside NonCancellable writes).
        val cancelledEngine = MockEngine { throw CancellationException("cancelled after delivery") }
        val cancelledClient = IntervalsApiClient(cancelledEngine)
        assertFailsWith<CancellationException> {
            cancelledClient.createManualActivity("i42", "k", sampleActivity())
        }
        cancelledClient.close()
    }

    // ------------------------------------------------------------ HR allowlist

    @Test
    fun heartRateFieldsAreOmittedIndependentlyAndForbiddenKeysAbsent() = runHttpTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as TextContent).text
            jsonResponse("""{"id":"1"}""")
        }
        val client = IntervalsApiClient(engine)

        client.createManualActivity("i42", "k", sampleActivity(averageHeartrate = null, maxHeartrate = 140))
        client.createManualActivity("i42", "k", sampleActivity(averageHeartrate = 121, maxHeartrate = null))
        client.createManualActivity("i42", "k", sampleActivity(averageHeartrate = null, maxHeartrate = null))
        client.createManualActivity("i42", "k", sampleActivity(averageHeartrate = 121, maxHeartrate = 140))

        val keySets = bodies.map { Json.parseToJsonElement(it).jsonObject.keys }
        val (maxOnly, avgOnly, neither, both) = keySets
        assertTrue("max_heartrate" in maxOnly)
        assertFalse("average_heartrate" in maxOnly)
        assertTrue("average_heartrate" in avgOnly)
        assertFalse("max_heartrate" in avgOnly)
        assertFalse("average_heartrate" in neither)
        assertFalse("max_heartrate" in neither)
        assertTrue("average_heartrate" in both)
        assertTrue("max_heartrate" in both)

        // Wire allowlist only (integration-model §5).
        val allowed = setOf(
            "type", "external_id", "name", "description", "start_date", "start_date_local",
            "elapsed_time", "average_heartrate", "max_heartrate",
        )
        bodies.forEach { body ->
            val keys = Json.parseToJsonElement(body).jsonObject.keys
            assertTrue(keys.all { it in allowed }, "forbidden wire keys in $body")
            assertFalse(body.contains("moving_time"))
            assertFalse(body.contains("kg_lifted"))
        }

        val decoded = wireJson.decodeFromString(IntervalsManualActivity.serializer(), bodies[3])
        assertEquals("WeightTraining", decoded.type)
        client.close()
    }

    // ------------------------------------------------------------ listing + readback

    @Test
    fun listingReturnsPagesWithCompletenessFlagAndQueryBounds() = runHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val fullPage =
            (1..100).joinToString(",", prefix = "[", postfix = "]") { """{"id":"$it","external_id":"e$it"}""" }
        val engine = MockEngine { request ->
            requests += request
            if (request.url.parameters["page"] == "1") {
                jsonResponse(fullPage)
            } else {
                jsonResponse("""[{"id":"101","external_id":"e101"}]""")
            }
        }
        val client = IntervalsApiClient(engine)

        val first = client.listActivities("i42", "k", "2026-11-07", "2026-11-09", page = 1)
        val firstPage = assertIs<IntervalsListResult.Page>(first)
        assertEquals(100, firstPage.items.size)
        assertFalse(firstPage.complete, "a full page is an incomplete search")

        val second = client.listActivities("i42", "k", "2026-11-07", "2026-11-09", page = 2)
        val secondPage = assertIs<IntervalsListResult.Page>(second)
        assertEquals(1, secondPage.items.size)
        assertTrue(secondPage.complete, "a partial page completes the enumeration")

        val request = requests.first()
        assertEquals("2026-11-07", request.url.parameters["oldest"])
        assertEquals("2026-11-09", request.url.parameters["newest"])
        assertEquals("id,external_id", request.url.parameters["fields"])
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("/api/v1/athlete/i42/activities", request.url.encodedPath)
        client.close()
    }

    @Test
    fun readbackClassifiesFoundNotFoundAndAuth() = runHttpTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/404") ->
                    jsonResponse("""{"error":"missing"}""", HttpStatusCode.NotFound)

                request.url.encodedPath.endsWith("/401") ->
                    jsonResponse("""{}""", HttpStatusCode.Unauthorized)

                else -> jsonResponse("""{"id":"77","external_id":"routine-session-1","name":"Push"}""")
            }
        }
        val client = IntervalsApiClient(engine)

        val found = assertIs<IntervalsReadbackResult.Found>(client.readbackActivity("i42", "k", "77"))
        assertEquals("77", found.summary.id)
        assertEquals("routine-session-1", found.summary.resolvedExternalId)

        assertEquals(IntervalsReadbackResult.NotFound, client.readbackActivity("i42", "k", "404"))
        assertIs<IntervalsReadbackResult.AuthRejected>(client.readbackActivity("i42", "k", "401"))
        client.close()
    }

    @Test
    fun externalIdAlternateShapeResolves() = runHttpTest {
        val engine = MockEngine {
            jsonResponse("""{"id":"5","externalId":"legacy-shape"}""")
        }
        val client = IntervalsApiClient(engine)
        val found = assertIs<IntervalsReadbackResult.Found>(client.readbackActivity("i42", "k", "5"))
        assertEquals("legacy-shape", found.summary.resolvedExternalId)
        client.close()
    }

    @Test
    fun emptyIdsAreRejectedLocallyWithoutAnyRequest() = runHttpTest {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            jsonResponse("{}")
        }
        val client = IntervalsApiClient(engine)

        assertIs<IntervalsValidationResult.Rejected>(client.validateAthlete("", "k"))
        assertEquals(
            IntervalsCreateOutcome.ValidationRejected(0, "empty athlete id rejected locally"),
            client.createManualActivity("", "k", sampleActivity()),
        )
        assertEquals(0, requestCount, "no request for a locally rejected id")
        client.close()
    }

    private fun runHttpTest(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }
}
