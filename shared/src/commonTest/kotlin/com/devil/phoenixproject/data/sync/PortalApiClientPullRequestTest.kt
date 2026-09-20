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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Exercises the REAL PortalApiClient.pullPortalPayload over a MockEngine, so the
 * delta-pull wire contract is pinned at the HTTP boundary (not only at the fake):
 * the request body carries the given lastSync, and the response flag
 * externalActivitiesHasMore decodes from the portal's JSON.
 */
class PortalApiClientPullRequestTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun runPortalHttpTest(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun client(engine: MockEngine) = PortalApiClient(
        SupabaseConfig("https://fake.supabase.co", "anon"),
        PortalTokenStorage(MapSettings()).apply {
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
        },
        httpClientEngine = engine,
    )

    private fun bodyText(request: HttpRequestData): String = when (val body = request.body) {
        is TextContent -> body.text
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> error("Unexpected request body type: ${body::class}")
    }

    private fun sentLastSync(request: HttpRequestData): Long =
        Json.parseToJsonElement(bodyText(request)).jsonObject.getValue("lastSync").jsonPrimitive.long

    @Test
    fun `pull request body carries the given lastSync and decodes externalActivitiesHasMore`() = runPortalHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"syncTime":1,"hasMore":false,"externalActivitiesHasMore":true}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val result = client(engine).pullPortalPayload(
            knownEntityIds = KnownEntityIds(),
            deviceId = "device",
            lastSync = 1_740_000_000_000L,
        )

        assertTrue(result.isSuccess, "pull should succeed: ${result.exceptionOrNull()}")
        val request = requests.single()
        assertTrue(request.url.encodedPath.endsWith("/functions/v1/mobile-sync-pull"))
        assertEquals(1_740_000_000_000L, sentLastSync(request), "request body must carry the stored lastSync")
        assertTrue(result.getOrThrow().externalActivitiesHasMore, "externalActivitiesHasMore must decode from JSON")
    }

    @Test
    fun `pull request defaults to lastSync 0 and externalActivitiesHasMore false`() = runPortalHttpTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("""{"syncTime":1,"hasMore":false}""", HttpStatusCode.OK, jsonHeaders)
        }

        val result = client(engine).pullPortalPayload(knownEntityIds = KnownEntityIds(), deviceId = "device")

        assertTrue(result.isSuccess, "pull should succeed: ${result.exceptionOrNull()}")
        assertEquals(0L, sentLastSync(requests.single()), "default call keeps sending lastSync=0")
        assertFalse(result.getOrThrow().externalActivitiesHasMore)
    }
}
