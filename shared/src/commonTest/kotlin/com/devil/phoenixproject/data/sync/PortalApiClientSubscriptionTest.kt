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
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    private fun assertSubscriptionQuery(request: HttpRequestData) {
        assertEquals("/rest/v1/subscriptions", request.url.encodedPath)
        assertEquals("tier,status", request.url.parameters["select"])
        assertEquals("in.(active,trialing)", request.url.parameters["status"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
    }
}
