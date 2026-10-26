package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DatabaseFileNames
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.local.legacyLibraryRootPath
import com.devil.phoenixproject.data.repository.BleRepository

import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.data.sync.SyncTriggerTarget
import com.devil.phoenixproject.data.sync.resetSecureStorageOnFreshInstall
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.TvosUnavailableDataBackupManager
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults

private const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"
private const val INSTALL_MARKER_KEY = "phoenix_install_marker"

private val portalKeys = listOf(
    "portal_auth_token",
    "portal_refresh_token",
    "portal_token_expires_at",
    "portal_user_id",
    "portal_user_email",
    "portal_user_display_name",
    "portal_user_is_premium",
    "portal_device_id",
    "portal_last_sync_timestamp",
)

/** Avoid constructing Keychain-backed cloud services in a build with cloud sync disabled. */
private object CloudUnavailableSyncTarget : SyncTriggerTarget {
    override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false)
    override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.NotAuthenticated)
    override val currentUser: StateFlow<PortalUser?> = MutableStateFlow(null)
    override val lastSyncTime: StateFlow<Long> = MutableStateFlow(0L)

    override suspend fun sync(): Result<Long> = Result.failure(
        IllegalStateException("Cloud Sync is unavailable because this build has no server configuration."),
    )

    override suspend fun refreshPremiumStatusFromServer() = Unit
    override fun markPausedNotPremium() = Unit
}

actual val platformModule: Module = module {
    clearKeychainOnFreshInstall()

    single {
        val bundle = NSBundle.mainBundle
        val url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String
        val anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String
        if (url.isNullOrBlank() || anonKey.isNullOrBlank()) {
            Logger.w { "tvOS Cloud Sync is unavailable because Supabase configuration is not present in this build." }
            SupabaseConfig(url = url.orEmpty(), anonKey = anonKey.orEmpty())
        } else {
            SupabaseConfig(url = url, anonKey = anonKey)
        }
    }
    single { DriverFactory() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    @OptIn(ExperimentalSettingsImplementation::class)
    single<Settings>(SecureSettingsQualifier) {
        val secure = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
        migrateTokensToKeychain(get(), secure)
        secure
    }
    single { OAuthLauncher() }
    single<BleRepository> { createTvosBleRepository() }
    single { ConnectivityChecker() }
    single { HealthIntegration() }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<DataBackupManager> { TvosUnavailableDataBackupManager() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    single {
        val cloud = get<SupabaseConfig>()
        val cloudConfigured = cloud.url.isNotBlank() && cloud.anonKey.isNotBlank()
        MainViewModel(
            bleRepository = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            personalRecordRepository = get(),
            profileExerciseBaselineRepository = get(),
            repCounter = get(),
            preferencesManager = get(),
            gamificationRepository = get(),
            trainingCycleRepository = get(),
            completedSetRepository = get(),
            activeWorkoutRuntimeRepository = get(),
            dropSetEligibilityPolicy = get(),
            syncTriggerManager = if (cloudConfigured) {
                get<SyncTriggerManager>()
            } else {
                SyncTriggerManager(syncManager = CloudUnavailableSyncTarget, isOnline = { false })
            },
            resolveWeightsUseCase = get(),
            applyRoutineModifierUseCase = get(),
            recommendWeightAdjustmentUseCase = get(),
            equipmentRackRepository = get(),
            applyEquipmentRackLoadUseCase = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
            healthIntegration = get(),
            externalActivityRepository = get(),
            workoutServiceController = get(),
            healthExportCursorRepository = get(),
            computeVelocityOneRepMaxUseCase = get(),
            recordPersonalMvtSampleUseCase = get(),
            velocityOneRepMaxRepository = get(),
            countVelocityOneRepMaxImprovementsUseCase = get(),
            backfillVelocityOneRepMaxUseCase = get(),
            machineSafetyCoordinator = get(),
            profileRecoveryActivityTracker = get(),
            recentJustLiftExerciseStore = get(),
        )
    }
}

@OptIn(ExperimentalSettingsImplementation::class)
private fun clearKeychainOnFreshInstall() {
    val defaults = NSUserDefaults.standardUserDefaults
    try {
        val cleared = resetSecureStorageOnFreshInstall(
            hasInstallMarker = { defaults.boolForKey(INSTALL_MARKER_KEY) },
            localDatabaseExists = ::anyLocalDatabaseExists,
            clearSecureStorage = { KeychainSettings(service = KEYCHAIN_SERVICE_NAME).clear() },
            setInstallMarker = { defaults.setBool(true, forKey = INSTALL_MARKER_KEY) },
        )
        if (cleared) Logger.i { "Fresh install: cleared auth Keychain left by a previous install" }
    } catch (failure: Exception) {
        // A Keychain capability can be absent in unsigned simulator builds. Never let that
        // prevent startup; signed distribution builds still exercise the secure reset path.
        Logger.e(failure) { "Fresh-install Keychain check failed" }
    }
}

private fun anyLocalDatabaseExists(): Boolean {
    val fileManager = NSFileManager.defaultManager
    val databasePaths = listOf(
        DatabaseFileNames.TARGET,
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.STAGING,
        DatabaseFileNames.RECOVERY,
    ).map { DatabaseFileContext.databasePath(it, null) }
    return (databasePaths + legacyLibraryRootPath()).any(fileManager::fileExistsAtPath)
}

@OptIn(ExperimentalSettingsImplementation::class)
private fun migrateTokensToKeychain(legacy: Settings, secure: Settings) {
    if (portalKeys.none { key ->
            legacy.getStringOrNull(key) != null ||
                legacy.getLongOrNull(key) != null ||
                legacy.getBooleanOrNull(key) != null
        }
    ) return

    for (key in portalKeys) {
        if (secure.getStringOrNull(key) != null || secure.getLongOrNull(key) != null ||
            secure.getBooleanOrNull(key) != null
        ) continue
        legacy.getStringOrNull(key)?.let { secure.putString(key, it) }
        legacy.getLongOrNull(key)?.let { secure.putLong(key, it) }
        legacy.getBooleanOrNull(key)?.let { secure.putBoolean(key, it) }
    }
    portalKeys.forEach(legacy::remove)
}
