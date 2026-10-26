package com.devil.phoenixproject.presentation.tv

import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.data.sync.AccountSwitchChoice
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel

/** TV entry points only; account binding, ACKs and persistence remain in the shared sync core. */
internal class TvAccountHooks(
    val link: LinkAccountViewModel,
    syncManager: com.devil.phoenixproject.data.sync.SyncManager,
    isOnline: () -> Boolean,
    private val migrations: RequiredMigrationGate,
    private val sessionOpen: () -> Boolean,
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
        explicitAccountId = null
        link.login(email.trim(), password)
        error = null
        return true
    }

    fun resolveAccountMismatch(choice: AccountSwitchChoice): Boolean {
        if (!migrationsReady) return fail("Wait for required local migration before changing account identity.")
        if (sessionOpen()) return fail("End training before changing account identity.")
        explicitAccountId = null
        link.resolveAccountMismatch(choice)
        error = null
        return true
    }

    fun syncNow(): Boolean {
        if (!migrationsReady) return fail("Wait for required local migration before syncing.")
        if (sessionOpen()) return fail("End training before syncing profile and routine changes.")
        if (!identityReady) return fail("Sign in and resolve account identity or ownership conflict before syncing.")
        explicitAccountId = link.currentUser.value?.id
        syncTrigger.clearError()
        link.sync()
        error = null
        return true
    }

    fun signOut(): Boolean {
        if (sessionOpen()) return fail("End training before signing out.")
        explicitAccountId = null
        link.logout()
        error = null
        return true
    }

    suspend fun onForeground() {
        if (sessionOpen() || !automaticSyncAllowed || link.lastSyncTime.value == 0L) return
        syncTrigger.onAppForeground()
    }

    private fun fail(message: String): Boolean { error = message; return false }
}
