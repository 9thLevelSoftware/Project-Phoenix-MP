package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Commits one authenticated identity while the caller holds [com.devil.phoenixproject.data.repository.ProfileMutationBarrier].
 * Profile ownership is validated and persisted before the token becomes visible to sync.
 */
internal suspend fun commitPortalIdentityUnderProfileMutationBarrier(
    response: GoTrueAuthResponse,
    tokenStorage: PortalTokenStorage,
    userProfileRepository: UserProfileRepository,
) {
    val activeProfile = requireNotNull(userProfileRepository.activeProfile.value) {
        "An active profile is required before signing in"
    }
    val previousAuth = tokenStorage.snapshotAuthState()
    val receipt = userProfileRepository.linkToSupabaseUnderProfileMutationBarrier(
        profileId = activeProfile.id,
        supabaseUserId = response.user.id,
    )
    try {
        check(tokenStorage.saveGoTrueAuth(response)) { "Authenticated identity commit was rejected" }
    } catch (commitFailure: Throwable) {
        withContext(NonCancellable) {
            try {
                tokenStorage.restoreAuthState(previousAuth)
            } catch (authRollbackFailure: Throwable) {
                commitFailure.addSuppressed(authRollbackFailure)
            }
            try {
                userProfileRepository.rollbackSupabaseLinkUnderProfileMutationBarrier(receipt)
            } catch (profileRollbackFailure: Throwable) {
                commitFailure.addSuppressed(profileRollbackFailure)
            }
        }
        throw commitFailure
    }
}
