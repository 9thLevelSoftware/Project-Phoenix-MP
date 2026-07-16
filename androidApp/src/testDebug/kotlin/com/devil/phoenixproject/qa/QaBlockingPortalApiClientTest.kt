package com.devil.phoenixproject.qa

import android.content.Context
import android.content.SharedPreferences
import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.GoTrueUser
import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalApiException
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QaBlockingPortalApiClientTest {
    @Test
    fun `enable persists the gate synchronously in a private preferences file`() {
        val fixture = gateFixture()

        fixture.gate.enable()

        assertTrue(fixture.gate.isEnabled())
        verify(exactly = 1) {
            fixture.context.getSharedPreferences("profile_qa_fixture_gate", Context.MODE_PRIVATE)
            fixture.editor.commit()
        }
    }

    @Test
    fun `enabled gate blocks push locally before a Ktor request`() = runTest {
        val requestCount = AtomicInteger(0)
        val client = blockingClient(requestCount)

        val result = client.pushPortalPayload(
            PortalSyncPayload(deviceId = "qa-device", lastSync = 0),
        )

        assertLocalOnlyFailure(result.exceptionOrNull())
        assertEquals("No HTTP request may escape the local fixture gate", 0, requestCount.get())
    }

    @Test
    fun `enabled gate blocks pull locally before a Ktor request`() = runTest {
        val requestCount = AtomicInteger(0)
        val client = blockingClient(requestCount)

        val result = client.pullPortalPayload(
            knownEntityIds = KnownEntityIds(),
            deviceId = "qa-device",
        )

        assertLocalOnlyFailure(result.exceptionOrNull())
        assertEquals("No HTTP request may escape the local fixture gate", 0, requestCount.get())
    }

    private fun blockingClient(requestCount: AtomicInteger): QaBlockingPortalApiClient {
        val gate = gateFixture().gate.apply { enable() }
        val engine = MockEngine {
            requestCount.incrementAndGet()
            respond(
                content = "{}",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return QaBlockingPortalApiClient(
            supabaseConfig = SupabaseConfig("https://fake.supabase.co", "anon-key"),
            tokenStorage = authenticatedStorage(),
            fixtureGate = gate,
            httpClientEngine = engine,
        )
    }

    private fun authenticatedStorage() = PortalTokenStorage(MapSettings()).apply {
        saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access-token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = 4_102_444_800L,
                refreshToken = "refresh-token",
                user = GoTrueUser(id = "user-id", email = "qa@example.com"),
            ),
        )
    }

    private fun gateFixture(): GateFixture {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        var enabled = false

        every {
            context.getSharedPreferences("profile_qa_fixture_gate", Context.MODE_PRIVATE)
        } returns preferences
        every { preferences.getBoolean("enabled", false) } answers { enabled }
        every { preferences.edit() } returns editor
        every { editor.putBoolean("enabled", true) } returns editor
        every { editor.commit() } answers {
            enabled = true
            true
        }

        return GateFixture(
            context = context,
            editor = editor,
            gate = ProfileQaFixtureGate(context),
        )
    }

    private fun assertLocalOnlyFailure(error: Throwable?) {
        assertTrue("Expected PortalApiException but was $error", error is PortalApiException)
        assertEquals("QA fixture is local-only", error?.message)
    }

    private data class GateFixture(
        val context: Context,
        val editor: SharedPreferences.Editor,
        val gate: ProfileQaFixtureGate,
    )
}
