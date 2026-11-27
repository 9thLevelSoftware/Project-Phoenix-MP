package com.devil.phoenixproject.data.sync

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * One `GET /rest/v1/subscriptions` returns both the premium flag and the active tier.
 */
class PortalApiClientSubscriptionTest {
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

    @Test
    fun `one subscriptions read returns premium and the highest known tier`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(
                content = """[{"tier":"EMBER","status":"active"},{"tier":"INFERNO","status":"trialing"}]""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val result = client(engine).fetchSubscriptionEntitlement()

        assertEquals(SubscriptionEntitlement(isPremium = true, tier = "INFERNO"), result.getOrThrow())
        val request = engine.requestHistory.single()
        assertSubscriptionQuery(request)
    }

    @Test
    fun `an empty subscriptions read is a confirmed lapse`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(content = "[]", status = HttpStatusCode.OK, headers = jsonHeaders)
        }

        val result = client(engine).fetchSubscriptionEntitlement()

        assertEquals(SubscriptionEntitlement(isPremium = false, tier = null), result.getOrThrow())
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `unknown tiers are not premium`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(
                content = """[{"tier":"CINDER","status":"active"}]""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val result = client(engine).fetchSubscriptionEntitlement()

        assertEquals(SubscriptionEntitlement(isPremium = false, tier = null), result.getOrThrow())
    }

    @Test
    fun `a failed subscriptions read is one failure for both fields`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.ServiceUnavailable)
        }

        val result = client(engine).fetchSubscriptionEntitlement()

        assertTrue(result.isFailure)
        val error = assertIs<PortalApiException>(result.exceptionOrNull())
        assertEquals(503, error.statusCode)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `missing auth does not call subscriptions`() = runPortalHttpTest {
        val engine = MockEngine {
            respond(content = "[]", status = HttpStatusCode.OK, headers = jsonHeaders)
        }

        val result = client(engine, PortalTokenStorage(MapSettings())).fetchSubscriptionEntitlement()

        assertTrue(result.isFailure)
        assertEquals(401, assertIs<PortalApiException>(result.exceptionOrNull()).statusCode)
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `expired access token refreshes via authenticatedRequest and returns INFERNO`() = runPortalHttpTest {
        val storage = PortalTokenStorage(MapSettings()).apply {
            saveGoTrueAuth(
                GoTrueAuthResponse(
                    accessToken = "expired-access",
                    tokenType = "bearer",
                    expiresIn = 3600,
                    expiresAt = 1L,
                    refreshToken = "valid-refresh",
                    user = GoTrueUser(id = "user-a", email = "user@example.com"),
                ),
            )
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(
                    content = """
                        {
                          "access_token": "fresh-access",
                          "token_type": "bearer",
                          "expires_in": 3600,
                          "expires_at": 4102444800,
                          "refresh_token": "fresh-refresh",
                          "user": {"id": "user-a", "email": "user@example.com"}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                "/rest/v1/subscriptions" -> respond(
                    content = """[{"tier":"INFERNO","status":"active"}]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }

        val result = client(engine, storage).fetchSubscriptionEntitlement()

        assertEquals(SubscriptionEntitlement(isPremium = true, tier = "INFERNO"), result.getOrThrow())
        assertEquals(2, engine.requestHistory.size)
        assertEquals("/auth/v1/token", engine.requestHistory[0].url.encodedPath)
        assertEquals("refresh_token", engine.requestHistory[0].url.parameters["grant_type"])
        assertSubscriptionQuery(engine.requestHistory[1])
        assertEquals("Bearer fresh-access", engine.requestHistory[1].headers[HttpHeaders.Authorization])
        assertEquals("fresh-access", storage.getToken())
        assertEquals("fresh-refresh", storage.getRefreshToken())
    }

    @Test
    fun `server 401 forces refresh and retries subscriptions read`() = runPortalHttpTest {
        val storage = authenticatedStorage()
        var subscriptionCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/v1/subscriptions" -> {
                    subscriptionCalls++
                    if (subscriptionCalls == 1) {
                        respond(content = "", status = HttpStatusCode.Unauthorized)
                    } else {
                        respond(
                            content = """[{"tier":"INFERNO","status":"trialing"}]""",
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                }
                "/auth/v1/token" -> respond(
                    content = """
                        {
                          "access_token": "refreshed-after-401",
                          "token_type": "bearer",
                          "expires_in": 3600,
                          "expires_at": 4102444800,
                          "refresh_token": "refresh-2",
                          "user": {"id": "user-a", "email": "user@example.com"}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }

        val result = client(engine, storage).fetchSubscriptionEntitlement()

        assertEquals(SubscriptionEntitlement(isPremium = true, tier = "INFERNO"), result.getOrThrow())
        assertEquals(3, engine.requestHistory.size)
        assertEquals("Bearer token", engine.requestHistory[0].headers[HttpHeaders.Authorization])
        assertEquals("/auth/v1/token", engine.requestHistory[1].url.encodedPath)
        assertEquals("Bearer refreshed-after-401", engine.requestHistory[2].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `signOut rethrows CancellationException instead of swallowing it`() = runPortalHttpTest {
        val engine = MockEngine {
            throw CancellationException("signOut cancelled")
        }

        assertFailsWith<CancellationException> {
            client(engine).signOut()
        }
    }

    private fun assertSubscriptionQuery(request: HttpRequestData) {
        assertEquals("/rest/v1/subscriptions", request.url.encodedPath)
        assertEquals("tier,status", request.url.parameters["select"])
        assertEquals("in.(active,trialing)", request.url.parameters["status"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
    }
}
