package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.subscription.SubscriptionManager
import org.koin.dsl.module

val syncModule = module {
    // Portal Sync (must be before Auth since PortalAuthRepository depends on these)
    single { PortalTokenStorage(get()) }
    single {
        PortalApiClient(
            tokenProvider = { get<PortalTokenStorage>().getToken() }
        )
    }
    single<SyncRepository> { SqlDelightSyncRepository(get()) }
    single { SyncManager(get(), get(), get()) }
    single { SyncTriggerManager(get(), get()) }

    // Auth & Subscription (using Railway Portal backend)
    single<AuthRepository> { PortalAuthRepository(get(), get()) }
    single { SubscriptionManager(get()) }
}
