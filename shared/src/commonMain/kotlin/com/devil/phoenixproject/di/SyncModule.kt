package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.util.ConnectivityChecker
import org.koin.dsl.module

private val log = Logger.withTag("SyncModule")

val syncModule = module {
    // Portal Sync (must be before Auth since PortalAuthRepository depends on these)
    single { PortalTokenStorage(get(SecureSettingsQualifier)) }
    single {
        PortalApiClient(
            supabaseConfig = get<SupabaseConfig>(),
            tokenStorage = get<PortalTokenStorage>()
        )
    }
    single<SyncRepository> { SqlDelightSyncRepository(get()) }
    // Diagnostic resolution — previous crash traced to SyncTriggerManager but root cause is deeper.
    // Explicit get() calls with logging identify which SyncManager dependency fails.
    single {
        log.i { "SyncManager: resolving dependencies..." }
        val apiClient = runCatching { get<PortalApiClient>() }.onFailure { log.e(it) { "FAILED: PortalApiClient" } }.getOrThrow()
        val tokenStorage = runCatching { get<PortalTokenStorage>() }.onFailure { log.e(it) { "FAILED: PortalTokenStorage" } }.getOrThrow()
        val syncRepo = runCatching { get<SyncRepository>() }.onFailure { log.e(it) { "FAILED: SyncRepository" } }.getOrThrow()
        val gamificationRepo = runCatching { get<GamificationRepository>() }.onFailure { log.e(it) { "FAILED: GamificationRepository" } }.getOrThrow()
        val repMetricRepo = runCatching { get<RepMetricRepository>() }.onFailure { log.e(it) { "FAILED: RepMetricRepository" } }.getOrThrow()
        val userProfileRepo = runCatching { get<UserProfileRepository>() }.onFailure { log.e(it) { "FAILED: UserProfileRepository" } }.getOrThrow()
        val extActivityRepo = runCatching { get<ExternalActivityRepository>() }.onFailure { log.e(it) { "FAILED: ExternalActivityRepository" } }.getOrThrow()
        log.i { "SyncManager: all dependencies resolved" }
        SyncManager(apiClient, tokenStorage, syncRepo, gamificationRepo, repMetricRepo, userProfileRepo, extActivityRepo)
    }
    single {
        log.i { "SyncTriggerManager: resolving dependencies..." }
        val syncManager = runCatching { get<SyncManager>() }.onFailure { log.e(it) { "FAILED: SyncManager" } }.getOrThrow()
        val connectivity = runCatching { get<ConnectivityChecker>() }.onFailure { log.e(it) { "FAILED: ConnectivityChecker" } }.getOrThrow()
        log.i { "SyncTriggerManager: resolved" }
        SyncTriggerManager(syncManager, connectivity)
    }
    single { IntegrationManager(get(), get()) }

    // Auth (using Supabase GoTrue)
    single<AuthRepository> { PortalAuthRepository(get(), get(), get()) }
}
