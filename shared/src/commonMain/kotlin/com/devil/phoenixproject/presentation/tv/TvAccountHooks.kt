package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.data.sync.AccountSwitchChoice
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import kotlinx.coroutines.Job

/** TV entry points only; account binding, ACKs and persistence remain in the shared sync core. */
internal class TvAccountHooks(
    val link: LinkAccountViewModel,
    syncManager: com.devil.phoenixproject.data.sync.SyncManager,
    isOnline: () -> Boolean,
    private val migrations: RequiredMigrationGate,
    private val sessionOpen: () -> Boolean,
    private val admission: TvActivityAdmission = TvActivityAdmission(),
) {
    // A restored, previously synced identity may refresh. Any login/identity decision
    // on this launch revokes that permission until the user explicitly selects Sync Now.
    private var explicitAccountId = link.currentUser.value?.id?.takeIf { link.lastSyncTime.value > 0 }
    private val migrationsReady get() = migrations.requiredMigrationState.value is RequiredMigrationState.Ready
    private val identityReady get() = link.isAuthenticated.value &&
        link.uiState.value !is com.devil.phoenixproject.ui.sync.LinkAccountUiState.Loading &&
        link.syncState.value !is SyncState.AccountMismatch && link.syncState.value !is SyncState.OwnershipConflict
    private val automaticSyncAllowed get() = migrationsReady && identityReady &&
        explicitAccountId != null && explicitAccountId == link.currentUser.value?.id

    val syncTrigger = SyncTriggerManager(syncManager, { automaticSyncAllowed && isOnline() })
    var error: String? = null
        private set

    fun login(email: String, password: String): Boolean {
        if (!migrationsReady) return fail("Wait for required local migration before changing account identity.")
        if (sessionOpen()) return fail("End training before changing account identity.")
        return accountOperation {
            explicitAccountId = null
            link.loginTracked(email.trim(), password) {
                syncAfterLogin()
            }
        }
    }

    fun resolveAccountMismatch(choice: AccountSwitchChoice): Boolean {
        if (!migrationsReady) return fail("Wait for required local migration before changing account identity.")
        if (sessionOpen()) return fail("End training before changing account identity.")
        return accountOperation {
            explicitAccountId = null
            link.resolveAccountMismatchTracked(choice)
        }
    }

    fun syncNow(): Boolean {
        if (!migrationsReady) return fail("Wait for required local migration before syncing.")
        if (sessionOpen()) return fail("End training before syncing profile and routine changes.")
        if (!identityReady) return fail("Sign in and resolve account identity or ownership conflict before syncing.")
        return accountOperation {
            explicitAccountId = link.currentUser.value?.id
            syncTrigger.clearError()
            link.syncTracked()
        }
    }

    fun signOut(): Boolean {
        if (sessionOpen()) return fail("End training before signing out.")
        return accountOperation {
            explicitAccountId = null
            link.logoutTracked()
        }
    }

    suspend fun onForeground() {
        if (sessionOpen() || !automaticSyncAllowed || link.lastSyncTime.value == 0L) return
        if (!admission.tryAccountMutation()) return
        try {
            // This suspend call awaits entitlement refresh and sync, not a detached launch.
            syncTrigger.onAppForeground()
        } finally {
            admission.releaseAccountMutation()
        }
    }

    private fun accountOperation(start: () -> Job): Boolean {
        if (!admission.tryAccountMutation()) return fail("Wait for the current account operation or training session to finish.")
        try {
            // Reserve BEFORE scheduling. invokeOnCompletion also covers already-completed
            // or pre-cancelled jobs; observing Loading or a wrapper launch would not.
            start().invokeOnCompletion { admission.releaseAccountMutation() }
        } catch (failure: Throwable) {
            admission.releaseAccountMutation()
            throw failure
        }
        error = null
        return true
    }

    private suspend fun syncAfterLogin() {
        if (!migrationsReady || !identityReady) return
        val accountId = link.currentUser.value?.id ?: return

        syncTrigger.clearError()
        link.syncInCurrentOperation()

        // Login's one-shot sync is not permission to keep syncing a different,
        // unresolved, or no-longer-current identity in the background.
        if (migrationsReady && identityReady && link.currentUser.value?.id == accountId &&
            link.syncState.value is SyncState.Success
        ) {
            explicitAccountId = accountId
        }
    }

    private fun fail(message: String): Boolean { error = message; return false }
}
