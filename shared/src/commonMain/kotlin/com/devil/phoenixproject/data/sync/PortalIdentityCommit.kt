package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.ProfileAccountLinkReceipt
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Commits one authenticated identity while the caller holds [com.devil.phoenixproject.data.repository.ProfileMutationBarrier].
 * Profile ownership is validated and persisted before the token becomes visible to sync.
 *
 * PR 11: when the just-signed-in user is a *different* portal account than the one
 * this device's rows already belong to, no profile is relinked. The token is still
 * saved so the user is authenticated and can answer the account-switch dialog, and
 * [PortalIdentityCommitOutcome.AccountMismatchDetected] is returned. A caller that cannot
 * act on the outcome itself passes [pendingAccountMismatch] so [SyncManager] can adopt it;
 * [SyncManager]'s own login/signup pass `null` and apply the outcome directly.
 */
internal suspend fun commitPortalIdentityUnderProfileMutationBarrier(
    response: GoTrueAuthResponse,
    tokenStorage: PortalTokenStorage,
    userProfileRepository: UserProfileRepository,
    pendingAccountMismatch: PendingAccountMismatch?,
): PortalIdentityCommitOutcome {
    val activeProfile = requireNotNull(userProfileRepository.activeProfile.value) {
        "An active profile is required before signing in"
    }
    // A pre-PR-11 device's only record of the account its rows reached is the legacy
    // cursor, which saveGoTrueAuth drops on an account change. Keep it first.
    tokenStorage.adoptLegacySyncOwner(tokenStorage.currentUser.value?.id)
    val mismatch = detectAccountMismatch(
        newUserId = response.user.id,
        newUserLabel = response.user.email?.takeIf { it.isNotBlank() } ?: response.user.id,
        lastSyncedPortalUserId = tokenStorage.getLastSyncedPortalUserId(),
        lastSyncedPortalUserLabel = tokenStorage.getLastSyncedPortalUserLabel(),
        profileOwners = userProfileRepository.allProfiles.value.mapNotNull { profile ->
            profile.supabaseUserId?.takeIf { it.isNotBlank() }?.let { owner -> profile.id to owner }
        },
    )
    if (mismatch != null) {
        // Authenticate without relinking: the relink is the dialog's explicit choice.
        check(tokenStorage.saveGoTrueAuth(response)) { "Authenticated identity commit was rejected" }
        pendingAccountMismatch?.publish(mismatch)
        return PortalIdentityCommitOutcome.AccountMismatchDetected(mismatch)
    }

    val previousAuth = tokenStorage.snapshotAuthState()
    val receipt: ProfileAccountLinkReceipt = userProfileRepository.linkToSupabaseUnderProfileMutationBarrier(
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
    return PortalIdentityCommitOutcome.Committed(receipt)
}
