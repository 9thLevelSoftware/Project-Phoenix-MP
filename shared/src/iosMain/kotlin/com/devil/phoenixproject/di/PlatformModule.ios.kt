package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DatabaseFileNames
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.local.legacyLibraryRootPath
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.di.IosRuntimeBindings
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.resetSecureStorageOnFreshInstall
import com.devil.phoenixproject.domain.voice.IosSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.BackupDestinationResolver
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.IosBackupDestinationResolver
import com.devil.phoenixproject.util.IosCsvExporter
import com.devil.phoenixproject.util.IosCsvImporter
import com.devil.phoenixproject.util.IosDataBackupManager
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults

private const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"

// NSUserDefaults key; its absence (with no local database) marks a fresh install.
private const val INSTALL_MARKER_KEY = "phoenix_install_marker"

private val log = Logger.withTag("PlatformModule")

private val simulatorLaunchFixture = IosRuntimeBindings.resolveSimulatorLaunchFixture()

/**
 * Keys that need to be migrated from NSUserDefaults to Keychain.
 * Mirrors the Android PORTAL_KEYS list for parity.
 */
private val PORTAL_KEYS = listOf(
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

actual val platformModule: Module = module {
    // The device Keychain survives uninstall; simulator bindings are process-local.
    // Only the real device runtime runs the fresh-install wipe.
    if (IosRuntimeBindings.usesRealBlePermissionGate) {
        // Runs before any single can create the database; lazy resolution could mistake
        // a fresh install for an upgrade after this launch creates its database.
        clearKeychainOnFreshInstall()
    }

    single {
        val bundle = NSBundle.mainBundle
        val url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String
        val anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String

        // Fail fast with clear error if Supabase config is missing
        // This usually means Supabase.xcconfig wasn't created or linked in Xcode
        require(!url.isNullOrBlank()) {
            "SUPABASE_URL not found in Info.plist. " +
                "Ensure Supabase.xcconfig exists and is linked in the Xcode project."
        }
        require(!anonKey.isNullOrBlank()) {
            "SUPABASE_ANON_KEY not found in Info.plist. " +
                "Ensure Supabase.xcconfig exists and is linked in the Xcode project."
        }

        SupabaseConfig(url = url, anonKey = anonKey)
    }
    single { DriverFactory() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        val settings = NSUserDefaultsSettings(defaults)
        simulatorLaunchFixture?.let { fixture ->
            fixture.seed(settings)
            NSLog("PHOENIX_IOS_FIXTURE_READY ${fixture.id}")
        }
        settings
    }
    // Secure storage using iOS Keychain for auth tokens (JWT, refresh token, user identity).
    // Keychain data persists across app reinstalls and is protected by iOS Data Protection;
    // clearKeychainOnFreshInstall() above wipes it when the app itself was reinstalled.
    @OptIn(ExperimentalSettingsImplementation::class)
    single<Settings>(SecureSettingsQualifier) {
        if (!IosRuntimeBindings.usesRealBlePermissionGate) {
            IosRuntimeBindings.createSecureSettings(get())
        } else {
            val keychainSettings = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
            val legacySettings: Settings = get() // NSUserDefaults
            migrateTokensToKeychain(legacySettings, keychainSettings)
            keychainSettings
        }
    }
    single { OAuthLauncher() }
    single<BleRepository> { IosRuntimeBindings.createBleRepository() }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter(get()) }
    single<BackupDestinationResolver> { IosBackupDestinationResolver() }
    single<DataBackupManager> { IosDataBackupManager(get(), get(), get(), get(), get(), get(), get()) }
    single { ConnectivityChecker() }
    single<SafeWordListenerFactory> { IosSafeWordListenerFactory() }
    single { HealthIntegration() }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    single {
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
            syncTriggerManager = get(),
            repMetricRepository = get(),
            biomechanicsRepository = get(),
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

/**
 * Keychain items survive uninstall, NSUserDefaults and the database don't. On a
 * fresh install (no marker, no database) wipe the auth Keychain service so a
 * reinstall on a handed-down device isn't signed in as the previous owner. An
 * upgrade from a build without the marker has a database and keeps its session.
 */
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
        if (cleared) log.i { "Fresh install: cleared auth Keychain left by a previous install" }
    } catch (e: Exception) {
        // Never block start-up; worst case is the pre-existing behaviour.
        log.e(e) { "Fresh-install Keychain check failed" }
    }
}

private fun anyLocalDatabaseExists(): Boolean {
    val fileManager = NSFileManager.defaultManager
    val sqliterPaths = listOf(
        DatabaseFileNames.TARGET,
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.STAGING,
        DatabaseFileNames.RECOVERY,
    ).map { DatabaseFileContext.databasePath(it, null) }
    return (sqliterPaths + legacyLibraryRootPath()).any { fileManager.fileExistsAtPath(it) }
}

/**
 * One-time migration: copies all portal keys from the old NSUserDefaults to
 * the Keychain store, then removes them from NSUserDefaults.
 *
 * This is idempotent: if no legacy keys exist, it returns immediately.
 * Migration runs synchronously during Koin module initialization to ensure
 * tokens are available in Keychain before any auth operations.
 */
private fun migrateTokensToKeychain(legacy: Settings, keychain: Settings) {
    // Check if any portal keys exist in legacy storage
    val hasLegacyKeys = PORTAL_KEYS.any { key ->
        legacy.getStringOrNull(key) != null ||
            legacy.getLongOrNull(key) != null ||
            legacy.getBooleanOrNull(key) != null
    }
    if (!hasLegacyKeys) return

    log.i { "Migrating portal keys from NSUserDefaults to Keychain" }

    try {
        for (key in PORTAL_KEYS) {
            // Never overwrite a value that already lives in the Keychain. The app
            // may have authenticated directly into the Keychain while stale legacy
            // NSUserDefaults keys still linger; clobbering the fresh token with an
            // old one (and then deleting the legacy copy) would corrupt the session.
            if (keychain.getStringOrNull(key) != null ||
                keychain.getLongOrNull(key) != null ||
                keychain.getBooleanOrNull(key) != null
            ) {
                continue
            }
            // Try each type - Settings stores typed values
            legacy.getStringOrNull(key)?.let { value ->
                keychain.putString(key, value)
            }
            legacy.getLongOrNull(key)?.let { value ->
                keychain.putLong(key, value)
            }
            legacy.getBooleanOrNull(key)?.let { value ->
                keychain.putBoolean(key, value)
            }
        }

        // Remove migrated keys from legacy storage
        for (key in PORTAL_KEYS) {
            legacy.remove(key)
        }

        log.i { "Portal key migration to Keychain complete" }
    } catch (e: Exception) {
        // Log but don't crash - user can re-authenticate if migration fails
        log.e(e) { "Failed to migrate portal keys to Keychain" }
    }
}
