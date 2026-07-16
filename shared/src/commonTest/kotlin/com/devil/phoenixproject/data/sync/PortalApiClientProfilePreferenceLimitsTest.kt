package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.readProjectFile
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val LEGACY_PUSH_RESPONSE =
    """{"syncTime":"2026-07-11T12:00:00Z"}"""
private val JSON_RESPONSE_HEADERS = headersOf(
    HttpHeaders.ContentType,
    ContentType.Application.Json.toString(),
)

private fun runPortalHttpTest(block: suspend () -> Unit) = runTest {
    withContext(Dispatchers.Default) { block() }
}

class PortalApiClientProfilePreferenceLimitsTest {
    private fun authenticatedStorage(
        accessToken: String = "old-token",
        refreshToken: String = "refresh-token",
    ) = PortalTokenStorage(MapSettings()).apply {
        saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = accessToken,
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = 4_102_444_800L,
                refreshToken = refreshToken,
                user = GoTrueUser(id = "user-a", email = "user@example.com"),
            ),
        )
    }

    private fun client(engine: MockEngine, storage: PortalTokenStorage = authenticatedStorage()) =
        PortalApiClient(
            SupabaseConfig("https://fake.supabase.co", "anon"),
            storage,
            httpClientEngine = engine,
        )

    private fun mutation(index: Int = 0, padding: Int = 0) =
        PortalProfilePreferenceSectionMutationDto(
            localProfileId = "profile-$index",
            section = "RACK",
            documentVersion = 1,
            baseRevision = 0,
            clientModifiedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject { put("padding", "x".repeat(padding)) },
        )

    private fun preferencePayload(
        mutations: List<PortalProfilePreferenceSectionMutationDto>,
    ) = PortalSyncPayload(
        deviceId = "device",
        platform = "android",
        lastSync = 0,
        profilePreferenceSections = mutations,
    )

    private fun payloadWithSectionBytes(targetBytes: Int): PortalSyncPayload {
        val seed = preferencePayload(listOf(mutation()))
        val encodedSeed = encodePortalSyncPayload(seed)
        val fixedBytes = encodedSeed.preferenceElementByteCount(
            encodedSeed.preferenceElementSpans.single(),
        )
        val payload = preferencePayload(
            listOf(mutation(padding = targetBytes - fixedBytes)),
        )
        val encoded = encodePortalSyncPayload(payload)
        check(
            encoded.preferenceElementByteCount(encoded.preferenceElementSpans.single()) ==
                targetBytes,
        )
        return payload
    }

    private fun preferencePayloadWithRequestBytes(targetBytes: Int): PortalSyncPayload {
        val seeds = List(3) { mutation(index = it) }
        val fixedBytes = encodePortalSyncPayload(preferencePayload(seeds)).rawBytes.size
        val paddingBytes = targetBytes - fixedBytes
        require(paddingBytes >= 0)
        val payload = preferencePayload(
            seeds.mapIndexed { index, _ ->
                val padding = paddingBytes / seeds.size +
                    if (index < paddingBytes % seeds.size) 1 else 0
                mutation(index = index, padding = padding)
            },
        )
        check(encodePortalSyncPayload(payload).rawBytes.size == targetBytes)
        return payload
    }

    private fun payloadWithRequestBytes(
        targetBytes: Int,
        sections: List<PortalProfilePreferenceSectionMutationDto>?,
    ): PortalSyncPayload {
        val seed = PortalSyncPayload(
            deviceId = "",
            lastSync = 0,
            profilePreferenceSections = sections,
        )
        val paddingBytes = targetBytes - encodePortalSyncPayload(seed).rawBytes.size
        require(paddingBytes >= 0)
        val payload = seed.copy(deviceId = "x".repeat(paddingBytes))
        check(encodePortalSyncPayload(payload).rawBytes.size == targetBytes)
        return payload
    }

    private fun requestBodyBytes(request: HttpRequestData): ByteArray =
        assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes()

    private fun effectiveContentTypes(request: HttpRequestData): List<ContentType> =
        request.headers.getAll(HttpHeaders.ContentType).orEmpty().map(ContentType::parse) +
            listOfNotNull(request.body.contentType)

    @Test
    fun `transport writes the exact counted preference bytes with one JSON content type`() =
        runPortalHttpTest {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
            }
            val payload = preferencePayload(listOf(mutation(padding = 64)))
            val expectedBytes = encodePortalSyncPayload(payload).rawBytes

            val result = client(engine).pushPortalPayload(payload)

            assertTrue(result.isSuccess)
            val request = requests.single()
            assertContentEquals(expectedBytes, requestBodyBytes(request))
            val contentTypes = effectiveContentTypes(request)
            assertEquals(1, contentTypes.size)
            assertTrue(contentTypes.single().match(ContentType.Application.Json))
            assertEquals("Bearer old-token", request.headers[HttpHeaders.Authorization])
            assertEquals("anon", request.headers["apikey"])
        }

    @Test
    fun `shared ContentNegotiation decodes an ordinary legacy push response`() =
        runPortalHttpTest {
        val engine = MockEngine {
            respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
        }

        val response = client(engine)
            .pushPortalPayload(PortalSyncPayload(deviceId = "device", lastSync = 0))
            .getOrThrow()

        assertNull(response.profilePreferencesAccepted)
        assertTrue(response.canonicalProfilePreferenceSections.isEmpty())
        assertTrue(response.profilePreferenceRejections.isEmpty())
    }

    @Test
    fun `401 refresh retry writes byte-identical push bodies`() =
        runPortalHttpTest {
        val pushRequests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/functions/v1/mobile-sync-push") -> {
                    pushRequests += request
                    if (pushRequests.size == 1) {
                        respond(
                            """{"error":"expired"}""",
                            HttpStatusCode.Unauthorized,
                            JSON_RESPONSE_HEADERS,
                        )
                    } else {
                        respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
                    }
                }
                request.url.encodedPath.endsWith("/auth/v1/token") -> respond(
                    """
                    {
                      "access_token":"new-token",
                      "token_type":"bearer",
                      "expires_in":3600,
                      "expires_at":4102444800,
                      "refresh_token":"new-refresh",
                      "user":{"id":"user-a","email":"user@example.com"}
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    JSON_RESPONSE_HEADERS,
                )
                else -> error("Unexpected MockEngine path")
            }
        }
        val payload = preferencePayload(listOf(mutation(padding = 128)))
        val expectedBytes = encodePortalSyncPayload(payload).rawBytes

        val result = client(engine).pushPortalPayload(payload)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())

        assertEquals(2, pushRequests.size)
        pushRequests.forEach { request ->
            assertContentEquals(expectedBytes, requestBodyBytes(request))
        }
        assertEquals(
            listOf("Bearer old-token", "Bearer new-token"),
            pushRequests.map { it.headers[HttpHeaders.Authorization] },
        )
    }

    @Test
    fun `section cap accepts 262144 and rejects 262145 before HTTP`() =
        runPortalHttpTest {
        var httpCalls = 0
        val engine = MockEngine {
            httpCalls++
            respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
        }
        val client = client(engine)

        assertTrue(
            client.pushPortalPayload(
                payloadWithSectionBytes(MAX_PROFILE_PREFERENCE_SECTION_BYTES),
            ).isSuccess,
        )
        val error = client.pushPortalPayload(
            payloadWithSectionBytes(MAX_PROFILE_PREFERENCE_SECTION_BYTES + 1),
        ).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertEquals("SECTION_TOO_LARGE: cap=262144", error.message)
        assertEquals(1, httpCalls)
    }

    @Test
    fun `request cap accepts 524288 and rejects 524289 before HTTP`() =
        runPortalHttpTest {
        var httpCalls = 0
        val engine = MockEngine {
            httpCalls++
            respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
        }
        val client = client(engine)
        val exact = preferencePayloadWithRequestBytes(MAX_PROFILE_PREFERENCE_REQUEST_BYTES)
        val overflow = preferencePayloadWithRequestBytes(
            MAX_PROFILE_PREFERENCE_REQUEST_BYTES + 1,
        )
        assertTrue(
            encodePortalSyncPayload(exact).preferenceElementSpans.all { span ->
                encodePortalSyncPayload(exact).preferenceElementByteCount(span) <=
                    MAX_PROFILE_PREFERENCE_SECTION_BYTES
            },
        )

        val exactResult = client.pushPortalPayload(exact)
        assertTrue(exactResult.isSuccess, exactResult.exceptionOrNull()?.toString())
        val error = client.pushPortalPayload(overflow).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertEquals("REQUEST_TOO_LARGE: cap=524288", error.message)
        assertEquals(1, httpCalls)
    }

    @Test
    fun `empty preference list uses 512 KiB while null preserves legacy ordinary cap`() =
        runPortalHttpTest {
            var httpCalls = 0
            val engine = MockEngine {
                httpCalls++
                respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
            }
            val client = client(engine)
            val target = MAX_PROFILE_PREFERENCE_REQUEST_BYTES + 1
            val ordinary = payloadWithRequestBytes(target, sections = null)
            val presentEmpty = payloadWithRequestBytes(target, sections = emptyList())

            assertTrue(client.pushPortalPayload(ordinary).isSuccess)
            val error = client.pushPortalPayload(presentEmpty).exceptionOrNull()

            assertIs<PortalApiException>(error)
            assertEquals(413, error.statusCode)
            assertEquals("REQUEST_TOO_LARGE: cap=524288", error.message)
            assertEquals(1, httpCalls)
        }

    @Test
    fun `legacy 9500000 byte cap is inclusive and overflow is 413`() =
        runPortalHttpTest {
        var httpCalls = 0
        val engine = MockEngine {
            httpCalls++
            respond(LEGACY_PUSH_RESPONSE, HttpStatusCode.OK, JSON_RESPONSE_HEADERS)
        }
        val client = client(engine)
        val exact = payloadWithRequestBytes(SyncConfig.MAX_PAYLOAD_BYTES.toInt(), null)
        val overflow = payloadWithRequestBytes(
            SyncConfig.MAX_PAYLOAD_BYTES.toInt() + 1,
            null,
        )

        assertTrue(client.pushPortalPayload(exact).isSuccess)
        val error = client.pushPortalPayload(overflow).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertEquals(1, httpCalls)
    }

    @Test
    fun `transport source encodes once per logical push and sends only raw bytes`() {
        val source = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt",
            ),
        )

        assertTrue("json(PortalWireJson)" in source)
        assertEquals(
            1,
            Regex("""encodePortalSyncPayload[(]payload[)]""").findAll(source).count(),
        )
        assertTrue("setBody(encoded.rawBytes)" in source)
        assertFalse(Regex("""setBody[(]encoded[.]raw[)]""").containsMatchIn(source))
        assertFalse(Regex("""private\s+val\s+json\s*=\s*Json""").containsMatchIn(source))
    }
}
