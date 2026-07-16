package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.integration.HealthBodyWeightReader
import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.data.integration.HealthIntegrationBodyWeightReader
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.repository.*
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.ProfilePreferenceSyncCodec
import com.devil.phoenixproject.data.sync.ProfilePreferenceSyncRepository
import com.devil.phoenixproject.data.sync.SqlDelightProfilePreferenceSyncRepository
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
            tokenStorage = get<PortalTokenStorage>(),
        )
    }
    single<SyncRepository> { SqlDelightSyncRepository(get(), get()) }
    single { ProfilePreferenceSyncCodec() }
    single<ProfilePreferenceSyncRepository> {
        SqlDelightProfilePreferenceSyncRepository(database = get(), codec = get())
    }
    single {
        val migrationManager = get<com.devil.phoenixproject.data.migration.MigrationManager>()
        SyncManager(
            apiClient = get(),
            tokenStorage = get(),
            syncRepository = get(),
            gamificationRepository = get(),
            repMetricRepository = get(),
            userProfileRepository = get(),
            profilePreferenceSyncRepository = get(),
            externalActivityRepository = get(),
            velocityOneRepMaxRepository = get(),
            isProfilePreferenceMigrationReady = {
                migrationManager.requiredMigrationState.value is
                    com.devil.phoenixproject.data.migration.RequiredMigrationState.Ready
            },
        )
    }
    single<HealthBodyWeightReader> { HealthIntegrationBodyWeightReader(get()) }
    single {
        HealthBodyWeightSyncManager(
            bodyWeightReader = get(),
            externalActivityRepository = get(),
            externalMeasurementRepository = get(),
            requiredMigrationGate = get<RequiredMigrationGate>(),
            userProfileRepository = get(),
        )
    }
    single { SyncTriggerManager(get(), get(), get()) }
    single { IntegrationManager(get(), get(), get(), get(), get(), get(), get()) }

    // Auth (using Supabase GoTrue)
    single<AuthRepository> { PortalAuthRepository(get(), get(), get(), get(), get()) }
}
