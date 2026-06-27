---
title: Data Backup And Repair
summary: Phoenix protects local workout history with streamed backup and restore flows in Settings plus startup data-repair passes that reconcile legacy profile scope, routine metadata, and orphaned records.
topics: [systems, data, flows, workouts, sync, android, ios]
sources:
  - id: backup-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
    note: Defines streamed export and import, per-session and per-routine auto-backups, retention, and duplicate-skipping restore behavior.
  - id: backup-settings
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Defines the user-facing backup, share, restore, and progress UI.
  - id: active-session-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    note: Defines when completed sessions trigger auto-backups and why routine sets skip per-set backup.
  - id: workout-session-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    note: Defines routine-completion auto-backup before routine session context is cleared.
  - id: migration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
    note: Defines startup repair passes, profile-scope repair choices, and orphaned-record repair.
  - id: migration-state
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/ProfileScopeRepairState.kt
    note: Defines the explicit startup repair states surfaced when legacy default-profile rows conflict with active-profile rows.
  - id: android-backup
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt
    note: Defines Android backup destinations, MediaStore behavior, and custom-folder fallback policy.
  - id: ios-backup
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt
    note: Defines iOS Documents-folder storage, security-scoped custom-folder access, and file-pruning behavior.
  - id: preferences-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
    note: Defines the persisted auto-backup and backup-destination preferences.
  - id: backup-destination
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestination.kt
    note: Defines the persisted default-versus-custom destination model and iOS bookmark storage.
  - id: app-startup
    type: file
    path: androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt
    note: Shows that startup migration and repair runs immediately after Koin initialization on Android.
  - id: backup-routing-test
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupRoutingTest.kt
    note: Verifies destination serialization and custom-destination resolver behavior.
  - id: migration-tests
    type: file
    path: shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt
    note: Characterizes several startup repair transforms such as fabricated routine-session cleanup and routine-name backfill.
status: active
verified: 2026-06-25
---
Phoenix protects local data with two separate recovery paths. Manual and automatic backups serialize database state to JSON through [[shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt]], while startup repair runs every launch through [[shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt]] to normalize or migrate rows that are already on device [@backup-manager] [@migration-manager] [@app-startup].

## Backup surface

The user-facing backup entry point is the Settings screen, not a hidden developer tool. `SettingsTab` opens a dialog that lets the user save a streamed backup to files, share it through the platform share sheet, or restore from a picked file, and it shows progress plus imported-versus-skipped record counts after restore [@backup-settings].

Backup preferences are persistent shared settings. `PreferencesManager` stores both `autoBackupEnabled` and a serialized `BackupDestination`, and `BackupDestination` distinguishes the platform default folder from a user-picked custom folder while carrying iOS security-scoped bookmark data when needed [@preferences-manager] [@backup-destination]. `BackupRoutingTest` locks in that custom destinations survive preference round-trips and that resolver failures surface as normal write failures rather than corrupting the setting shape [@backup-routing-test].

The common backup path is deliberately streamed for large histories. `BaseDataBackupManager.exportToFile()` writes incrementally through a platform `BackupJsonWriter`, `importFromFile()` switches to streaming import at `50 MB`, and metric imports batch in chunks of `5,000` rows so large workout histories do not need one full in-memory JSON object on mobile [@backup-manager].

## Auto-backup timing

Auto-backup is tied to workout persistence, not to an arbitrary timer. `ActiveSessionEngine` writes a per-session backup only after session rows, metric samples, completed sets, PR flags, and the post-save sync trigger have already been scheduled, and it does that only for non-routine sessions so a routine workout does not generate one backup file per set [@active-session-engine].

Routine workouts back up once at routine completion. `ActiveSessionEngine.autoBackupRoutineIfEnabled()` and the routine-complete path in `DefaultWorkoutSessionManager` both export the aggregate routine while `currentRoutineSessionId` still exists, then clear routine session context afterward [@active-session-engine] [@workout-session-manager]. The backup retention rule is shared between single-session and routine exports: keep only the newest `90` auto-backup files [@backup-manager].

## Platform storage differences

Android and iOS keep the same backup contract but write to different default surfaces. Android uses MediaStore Downloads on Android `10+` and public Downloads on older versions, while iOS writes to the app Documents directory [@android-backup] [@ios-backup]. Both implementations attempt a user-selected custom destination first and fall back to the default location if the custom destination is inaccessible or the write fails [@android-backup] [@ios-backup].

Pruning behavior is also platform-specific. Android queries Downloads entries and deletes the oldest matching `phoenix-*.json` files when retention is exceeded, while iOS sorts by file modification time because routine and workout backups now use different filename prefixes that no longer sort chronologically by name [@android-backup] [@ios-backup].

## Startup repair

Startup repair is part of normal app boot. Android calls `migrationManager.checkAndRunMigrations()` during `VitruvianApp.onCreate()`, and the migration manager then refreshes profiles, strips fabricated `legacy_session_<id>` routine session IDs, normalizes legacy workout-mode names, backfills bad routine names on old workout rows, repairs PRs from workout history, audits profile-scoped data, and checks for orphaned records [@app-startup] [@migration-manager] [@migration-tests].

Profile-scope repair can become interactive when old default-profile rows and current active-profile rows both exist. `ProfileScopeRepairState.NeedsChoice` carries both row counts plus the active profile identity so the app can either move legacy `default`-scoped data into the active profile or switch back to the default profile without moving rows [@migration-state] [@migration-manager]. Read [[profiles]] with this page when the symptom is "data disappeared after I changed profiles" rather than a failed restore or broken file export.

The repair path is broader than schema migration. It exists because Phoenix preserved real device histories across profile introduction, routine-model changes, and legacy naming bugs, so correctness depends on reconciling old local rows as well as applying numbered SQLDelight migrations [@migration-manager] [@migration-tests]. Read [[local-data-model]] alongside this page when the question is about schema shape or reconciliation machinery rather than user-visible recovery flows.

Read [[workouts]] when the symptom starts from session completion, routine exit, or missing auto-backup files after a workout. Read [[sync]] when an import or repair issue later appears as remote sync churn, because sync still consumes the same repaired local tables after startup. Read [[platform-hosts]] when backup failures differ between Android and iOS, because destination access and file-manager behavior diverge there even though the shared backup contract does not.
