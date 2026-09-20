package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.ProfileRecoverySourceSnapshot
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PortalProfileRecoverySourceVerifierTest {
    @Test
    fun `verifier posts canonical proof arrays and maps the authenticated result`() = runTest {
        withContext(Dispatchers.Default) {
            var captured: HttpRequestData? = null
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content =
                        """[{"verified":true,"authenticated_owner_user_id":"owner-a","verified_proof_count":2}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            val storage = PortalTokenStorage(MapSettings()).apply {
                saveGoTrueAuth(
                    GoTrueAuthResponse(
                        accessToken = "access-a",
                        expiresIn = 3600,
                        expiresAt = 4_102_444_800L,
                        refreshToken = "refresh-a",
                        user = GoTrueUser(id = "owner-a"),
                    ),
                )
            }
            val verifier = PortalProfileRecoverySourceVerifier(
                PortalApiClient(
                    SupabaseConfig("https://fake.supabase.co", "anon"),
                    storage,
                    httpClientEngine = engine,
                ),
            )
            val result = verifier.verify(
                ProfileRecoverySourceSnapshot(
                    sourceProfileId = "profile-a",
                    workoutSessionIds = listOf("00000000-0000-4000-8000-000000000001"),
                    routineIds = listOf("00000000-0000-4000-8000-000000000002"),
                    cycleIds = emptyList(),
                    personalRecordIds = emptyList(),
                    proofWorkoutSessionIds = listOf("00000000-0000-4000-8000-000000000001"),
                    proofRoutineIds = listOf("00000000-0000-4000-8000-000000000002"),
                    proofCycleIds = emptyList(),
                    proofPersonalRecordIds = emptyList(),
                ),
            )

            assertTrue(result.verified)
            assertEquals("owner-a", result.authenticatedOwnerUserId)
            assertEquals(2, result.verifiedProofCount)
            val request = checkNotNull(captured)
            assertEquals("/rest/v1/rpc/verify_profile_recovery_source", request.url.encodedPath)
            assertEquals("Bearer access-a", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            ).jsonObject
            assertEquals("profile-a", body.getValue("p_source_profile_id").jsonPrimitive.content)
            assertEquals(
                "00000000-0000-4000-8000-000000000001",
                body.getValue("p_workout_session_ids").jsonArray.single().jsonPrimitive.content,
            )
            assertEquals(
                "00000000-0000-4000-8000-000000000002",
                body.getValue("p_proof_routine_ids").jsonArray.single().jsonPrimitive.content,
            )
        }
    }
}
