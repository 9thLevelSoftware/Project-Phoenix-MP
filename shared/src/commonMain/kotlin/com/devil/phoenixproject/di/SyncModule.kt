package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import org.koin.dsl.module

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
    single {
        SyncManager(
            apiClient = get(),
            tokenStorage = get(),
            syncRepository = get(),
            gamificationRepository = get(),
            repMetricRepository = get(),
            userProfileRepository = get()
        )
    }
    single { SyncTriggerManager(get(), get()) }

    // Auth (using Supabase GoTrue)
    single<AuthRepository> { PortalAuthRepository(get(), get(), get()) }
}
