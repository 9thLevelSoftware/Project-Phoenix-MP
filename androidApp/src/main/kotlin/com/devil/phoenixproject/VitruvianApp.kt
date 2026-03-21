package com.devil.phoenixproject

import android.app.Application
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.di.initKoin
import com.devil.phoenixproject.util.DeviceInfo
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.dsl.module

class VitruvianApp : Application(), SingletonImageLoader.Factory {

    private val migrationManager: MigrationManager by inject()

    override fun onCreate() {
        super.onCreate()

        // Initialize DeviceInfo with BuildConfig values
        DeviceInfo.initialize(
            versionCode = BuildConfig.VERSION_CODE,
            isDebug = BuildConfig.DEBUG
        )

        initKoin {
            androidLogger()
            androidContext(this@VitruvianApp)
            modules(module {
                single {
                    SupabaseConfig(
                        url = BuildConfig.SUPABASE_URL,
                        anonKey = BuildConfig.SUPABASE_ANON_KEY
                    )
                }
            })
        }

        // Run migrations after Koin is initialized
        migrationManager.checkAndRunMigrations()

        Logger.d("VitruvianApp") { "Application initialized" }
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
            }
            .crossfade(true)
            .logger(DebugLogger())
            .build()
    }
}