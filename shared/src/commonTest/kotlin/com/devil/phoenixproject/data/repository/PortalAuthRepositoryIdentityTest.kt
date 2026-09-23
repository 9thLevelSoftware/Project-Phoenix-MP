package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.GoTrueUser
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PortalAuthRepositoryIdentityTest {
    @Test
    fun `email sign in binds an unowned active profile`() = runTest {
        val response = authResponse("owner-a", "token-a")
        val api = FakePortalApiClient().apply { signInResult = Result.success(response) }
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val storage = PortalTokenStorage(MapSettings())
        val repository = repository(api, storage, profiles)

        try {
            val result = repository.signInWithEmail("owner-a@example.com", "password")

            assertTrue(result.isSuccess)
            assertEquals("owner-a", profiles.activeProfile.value?.supabaseUserId)
            assertEquals("owner-a", storage.currentUser.value?.id)
        } finally {
            repository.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `oauth sign in uses the same guarded identity commit`() = runTest {
        val response = authResponse("owner-a", "token-a")
        val api = object : FakePortalApiClient() {
            override suspend fun exchangeOAuthCode(
                authCode: String,
                codeVerifier: String,
            ): Result<GoTrueAuthResponse> {
                assertEquals("oauth-code", authCode)
                return Result.success(response)
            }
        }
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val storage = PortalTokenStorage(MapSettings())
        val repository = repository(api, storage, profiles) { _, _ ->
            Result.success("com.devil.phoenixproject://auth-callback?code=oauth-code")
        }

        try {
            val result = repository.signInWithGoogle()

            assertTrue(result.isSuccess)
            assertEquals("owner-a", profiles.activeProfile.value?.supabaseUserId)
            assertEquals("owner-a", storage.currentUser.value?.id)
        } finally {
            repository.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `email account switch rejects and preserves prior profile and token`() = runTest {
        val api = FakePortalApiClient().apply {
            signInResult = Result.success(authResponse("owner-b", "token-b"))
        }
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = "owner-a")
        }
        val storage = PortalTokenStorage(MapSettings()).apply {
            saveGoTrueAuth(authResponse("owner-a", "token-a"))
            setPullCursor("owner-a", "default", 42L)
        }
        val repository = repository(api, storage, profiles)

        try {
            val result = repository.signInWithEmail("owner-b@example.com", "password")

            assertTrue(result.isFailure)
            assertIs<ProfileAccountBindingException>(result.exceptionOrNull())
            assertEquals("owner-a", profiles.activeProfile.value?.supabaseUserId)
            assertEquals("owner-a", storage.currentUser.value?.id)
            assertEquals("token-a", storage.getToken())
            assertEquals(42L, storage.getPullCursor("owner-a", "default"))
        } finally {
            repository.close()
            Dispatchers.resetMain()
        }
    }

    private fun repository(
        api: FakePortalApiClient,
        storage: PortalTokenStorage,
        profiles: FakeUserProfileRepository,
        launchOAuth: suspend (String, String) -> Result<String> = { _, _ ->
            Result.failure(IllegalStateException("OAuth was not expected"))
        },
    ): PortalAuthRepository {
        Dispatchers.setMain(StandardTestDispatcher())
        return PortalAuthRepository(
            apiClient = api,
            tokenStorage = storage,
            userProfileRepository = profiles,
            supabaseConfig = SupabaseConfig("https://fake.supabase.co", "anon"),
            profileMutationBarrier = ProfileMutationBarrier(),
            launchOAuth = launchOAuth,
        )
    }

    private fun authResponse(ownerUserId: String, token: String) = GoTrueAuthResponse(
        accessToken = token,
        expiresIn = 3_600,
        expiresAt = 4_102_444_800L,
        refreshToken = "refresh-$ownerUserId",
        user = GoTrueUser(
            id = ownerUserId,
            email = "$ownerUserId@example.com",
        ),
    )
}
