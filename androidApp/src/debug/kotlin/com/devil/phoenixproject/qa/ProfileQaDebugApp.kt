package com.devil.phoenixproject.qa

import com.devil.phoenixproject.VitruvianApp
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

class ProfileQaDebugApp : VitruvianApp() {
    override fun onCreate() {
        super.onCreate()

        loadKoinModules(
            module {
                single { ProfileQaFixtureGate(this@ProfileQaDebugApp) }
                single<PortalApiClient> {
                    QaBlockingPortalApiClient(
                        supabaseConfig = get<SupabaseConfig>(),
                        tokenStorage = get<PortalTokenStorage>(),
                        fixtureGate = get<ProfileQaFixtureGate>(),
                    )
                }
            },
        )
    }
}
