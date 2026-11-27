package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.database.PhoenixDatabase
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module

expect val platformModule: Module

val appModule = module {
    includes(dataModule, syncModule, domainModule, presentationModule)

    // Resolving this singleton is the persisted-file startup boundary. The
    // values are intentionally resolved in this order so no feature dependency
    // can observe a partially prepared database or preference store.
    // Offline/fixture tvOS builds leave SupabaseConfig blank and never touch Keychain.
    single {
        get<PhoenixDatabase>()
        get<Settings>()
        val supabase = getOrNull<SupabaseConfig>()
        if (supabase == null || (supabase.url.isNotBlank() && supabase.anonKey.isNotBlank())) {
            get<Settings>(SecureSettingsQualifier)
        }
        PersistedFileStartupPrerequisite
    }
}

internal data object PersistedFileStartupPrerequisite
