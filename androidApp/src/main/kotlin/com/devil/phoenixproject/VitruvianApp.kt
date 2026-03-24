package com.devil.phoenixproject

import android.app.Activity
import android.app.Application
import android.os.Bundle
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.devil.phoenixproject.data.migration.MigrationManager
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.di.initKoin
import com.devil.phoenixproject.util.ActivityHolder
import com.devil.phoenixproject.util.DeviceInfo
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.dsl.module

class VitruvianApp : Application(), SingletonImageLoader.Factory {

    private val migrationManager: MigrationManager by inject()

    override fun onCreate() {
        super.onCreate()

        // Configure Kermit log level for release builds — suppress Debug/Verbose/Info
        if (!BuildConfig.DEBUG) {
            Logger.mutableConfig.minSeverity = Severity.Warn
        }

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

        // H11: Register ActivityHolder via lifecycle callbacks instead of manual
        // calls in each Activity. Ensures the reference is always current across
        // config changes and multi-activity scenarios.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ActivityHolder.registerActivity(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                // Don't clear — paused activity is still valid for context operations
            }
            override fun onActivityDestroyed(activity: Activity) {
                // Only clear if the destroyed activity is the one we're holding
                if (ActivityHolder.getActivity() === activity) {
                    ActivityHolder.clear()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })

        Logger.d("VitruvianApp") { "Application initialized" }
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
            }
            .crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
    }
}