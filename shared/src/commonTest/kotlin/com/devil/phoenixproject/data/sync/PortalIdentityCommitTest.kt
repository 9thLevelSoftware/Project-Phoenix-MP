package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.ProfileAccountBindingException
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class PortalIdentityCommitTest {
    @Test
    fun `new identity binds the unowned profile and resets account scoped cursor`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val storage = PortalTokenStorage(MapSettings()).apply {
            setLastSyncTimestamp(42L)
        }

        ProfileMutationBarrier().withExclusive {
            commitPortalIdentityUnderProfileMutationBarrier(
                response = authResponse("owner-b", "token-b"),
                tokenStorage = storage,
                userProfileRepository = profiles,
            )
        }

        assertEquals("owner-b", profiles.activeProfile.value?.supabaseUserId)
        assertEquals("owner-b", storage.currentUser.value?.id)
        assertEquals(0L, storage.getLastSyncTimestamp())
    }

    @Test
    fun `different profile owner rejects identity and preserves prior auth`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = "owner-a")
        }
        val storage = PortalTokenStorage(MapSettings()).apply {
            saveGoTrueAuth(authResponse("owner-a", "token-a"))
            setLastSyncTimestamp(42L)
        }

        assertFailsWith<ProfileAccountBindingException> {
            ProfileMutationBarrier().withExclusive {
                commitPortalIdentityUnderProfileMutationBarrier(
                    response = authResponse("owner-b", "token-b"),
                    tokenStorage = storage,
                    userProfileRepository = profiles,
                )
            }
        }

        assertEquals("owner-a", profiles.activeProfile.value?.supabaseUserId)
        assertEquals("owner-a", storage.currentUser.value?.id)
        assertEquals("token-a", storage.getToken())
        assertEquals(42L, storage.getLastSyncTimestamp())
    }

    @Test
    fun `token persistence failure restores prior auth and unowned profile`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val settings = OneShotBooleanWriteFailureSettings()
        val storage = PortalTokenStorage(settings).apply {
            saveGoTrueAuth(authResponse("owner-a", "token-a"))
            recordCompletedPull(42L, "owner-a:default")
        }
        settings.failNextBooleanWrite = true

        assertFailsWith<InjectedSettingsFailure> {
            ProfileMutationBarrier().withExclusive {
                commitPortalIdentityUnderProfileMutationBarrier(
                    response = authResponse("owner-b", "token-b"),
                    tokenStorage = storage,
                    userProfileRepository = profiles,
                )
            }
        }

        assertNull(profiles.activeProfile.value?.supabaseUserId)
        assertEquals("owner-a", storage.currentUser.value?.id)
        assertEquals("token-a", storage.getToken())
        assertEquals(42L, storage.getLastSyncTimestamp())
        assertEquals("owner-a:default", storage.getDeltaPullKey())
    }

    @Test
    fun `cancellation during token commit still rolls back profile and auth`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default", supabaseUserId = null)
        }
        val settings = OneShotBooleanWriteFailureSettings()
        val storage = PortalTokenStorage(settings).apply {
            saveGoTrueAuth(authResponse("owner-a", "token-a"))
            setLastSyncTimestamp(42L)
        }
        settings.nextFailure = CancellationException("cancel identity commit")

        assertFailsWith<CancellationException> {
            ProfileMutationBarrier().withExclusive {
                commitPortalIdentityUnderProfileMutationBarrier(
                    response = authResponse("owner-b", "token-b"),
                    tokenStorage = storage,
                    userProfileRepository = profiles,
                )
            }
        }

        assertNull(profiles.activeProfile.value?.supabaseUserId)
        assertEquals("owner-a", storage.currentUser.value?.id)
        assertEquals("token-a", storage.getToken())
        assertEquals(42L, storage.getLastSyncTimestamp())
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

    private class InjectedSettingsFailure : IllegalStateException("injected Settings failure")

    private class OneShotBooleanWriteFailureSettings(
        private val delegate: Settings = MapSettings(),
    ) : Settings by delegate {
        var nextFailure: Throwable? = null
        var failNextBooleanWrite: Boolean
            get() = nextFailure != null
            set(value) {
                nextFailure = if (value) InjectedSettingsFailure() else null
            }

        override fun putBoolean(key: String, value: Boolean) {
            nextFailure?.let { failure ->
                nextFailure = null
                throw failure
            }
            delegate.putBoolean(key, value)
        }
    }
}
