---
status: complete
agent: engineering-senior-developer
wave: 2
---

# Plan 42-02 Summary: UI Integration & Backup Path Routing

## Status: Complete

## What Changed
- **BackupDestinationResolver.kt** (NEW): Interface with `isAccessible()`, `writeFile()`, `listFiles()`. Interface (not expect/actual) for testability.
- **BackupDestinationResolver.android.kt** (NEW): DocumentFile + persisted URI permissions. Checks canWrite(), copies temp file to custom destination.
- **BackupDestinationResolver.ios.kt** (NEW): Resolves Base64 bookmark → NSURL via URLByResolvingBookmarkData. Security-scoped resource access wrapper.
- **DataBackupManager.android.kt** (MODIFIED): Added PreferencesManager + BackupDestinationResolver constructor params. tryCustomDestination() with fallback.
- **DataBackupManager.ios.kt** (MODIFIED): Same pattern — custom destination routing with fallback to default.
- **PlatformModule.android.kt** (MODIFIED): Registered AndroidBackupDestinationResolver, updated DataBackupManager binding.
- **PlatformModule.ios.kt** (MODIFIED): Registered IosBackupDestinationResolver, updated DataBackupManager binding.
- **SettingsTab.kt** (MODIFIED): Backup Location row with display name, Change Location button, Reset to Default button, picker integration.
- **SettingsManager.kt** (MODIFIED): Added setBackupDestination() method.
- **MainViewModel.kt** (MODIFIED): Added setBackupDestination() forwarding to SettingsManager.
- **NavGraph.kt** (MODIFIED): Wired backupDestination + onBackupDestinationChange to SettingsTab.

## Decisions
1. BackupDestinationResolver as interface (not expect/actual) — enables FakeBackupDestinationResolver in tests
2. BaseDataBackupManager constructor UNCHANGED — resolver + prefs injected at platform level only
3. tryCustomDestination() returns null on failure → caller falls through to default behavior
4. Settings UI follows existing param-from-NavGraph pattern through SettingsManager → MainViewModel → NavGraph → SettingsTab
5. Both finalizeExport() and writeSessionBackupFile() route through custom destination

## Verification
All 10 verification commands passed. `./gradlew :androidApp:assembleDebug` BUILD SUCCESSFUL.

## Files Created
- shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.kt
- shared/src/androidMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.android.kt
- shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt

## Files Modified
- shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt
- shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt
- shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt
- shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
